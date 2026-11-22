//! Интерфейс обновления приложения: автообновление, кнопка «Проверить», баннер.

use eframe::egui;
use pulse_core::updater;

use crate::app::{PulseApp, StatusKind};
use crate::theme;
use crate::views::muted;

/// Как часто проверять обновление (24 часа).
const CHECK_INTERVAL_SECS: i64 = 24 * 60 * 60;

impl PulseApp {
    /// Запускает фоновую проверку обновления.
    ///
    /// Вызывается при старте приложения, если `auto_update_app` включён и
    /// прошло больше `CHECK_INTERVAL_SECS` с последней проверки.
    pub(crate) fn schedule_update_check(&mut self) {
        if !self.settings.auto_update_app {
            return;
        }
        let since_last = self
            .now_unix()
            .saturating_sub(self.settings.last_update_check_ts);
        if since_last < CHECK_INTERVAL_SECS && self.update_available.is_none() {
            return;
        }
        self.trigger_update_check();
    }

    /// Немедленная проверка обновления (по кнопке или при старте).
    pub(crate) fn trigger_update_check(&mut self) {
        let manifest_url = updater::MANIFEST_URL.to_string();
        let current = updater::current_version().to_string();

        // Блокировка на время проверки неприемлема; запускаем в потоке.
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let result = updater::fetch_and_check(&manifest_url, &current);
            let _ = tx.send(result);
        });
        self.update_rx = Some(rx);
        self.update_check_active = true;
        self.set_status(StatusKind::Info, "Проверка обновлений…");
    }

    /// Вызывается каждый кадр: получает результат фоновой проверки.
    pub(crate) fn poll_update_check(&mut self) {
        let Some(rx) = &self.update_rx else {
            return;
        };
        let Ok(result) = rx.try_recv() else {
            return;
        };
        self.update_rx = None;
        self.update_check_active = false;

        // Запоминаем время проверки, даже если ошибка (чтобы не дёргать снова).
        self.settings.last_update_check_ts = self.now_unix();
        self.save_settings();

        match result {
            Ok(Some(info)) => {
                self.update_available = Some(info);
                let ver = self.update_available.as_ref().unwrap().version.clone();
                self.set_status(
                    StatusKind::Success,
                    format!("Доступна версия {ver} — см. «Обновление»"),
                );
            }
            Ok(None) => {
                self.set_status(StatusKind::Success, "Установлена последняя версия.");
            }
            Err(error) => {
                self.set_status(StatusKind::Error, error);
            }
        }
    }

    /// Показывает баннер обновления внизу sidebar или поверх контента.
    pub(crate) fn ui_update_banner(&mut self, ui: &mut egui::Ui) {
        let version = match &self.update_available {
            Some(info) => info.version.clone(),
            None => return,
        };
        let accent = theme::color(self.settings.accent);

        egui::Frame::new()
            .fill(theme::color(if self.dark(ui) {
                0xFF1A1A2E
            } else {
                0xFFF0F0FF
            }))
            .stroke(egui::Stroke::new(1.0, accent))
            .corner_radius(8.0)
            .inner_margin(egui::Margin::symmetric(12, 8))
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new("📦 Обновление").size(13.0).strong().color(accent));
                    if !self.update_check_active {
                        if ui
                            .small_button("Обновить")
                            .on_hover_text("Скачать и установить новую версию")
                            .clicked()
                        {
                            self.upgrade_to(&version);
                        }
                    } else {
                        muted(ui, "проверка…");
                    }
                });
                muted(ui, format!("Версия {version} доступна"));
            });
    }

    /// Скачать и применить обновление.
    pub(crate) fn upgrade_to(&mut self, version: &str) {
        let info = match &self.update_available {
            Some(i) if i.version == version => i.clone(),
            _ => {
                self.set_status(StatusKind::Error, "Обновление не найдено.");
                return;
            }
        };

        // Скачать + проверить SHA-256.
        self.set_status(StatusKind::Info, "Скачивание обновления…");
        match updater::download_and_verify(&info) {
            Ok(()) => {}
            Err(e) => {
                self.set_status(StatusKind::Error, e);
                return;
            }
        }

        // Применить (замена + перезапуск).
        let temp_path = info.temp_path.clone();
        let exe = match std::env::current_exe() {
            Ok(p) => p,
            Err(e) => {
                self.set_status(StatusKind::Error, format!("Не удалось определить путь: {e}"));
                return;
            }
        };

        let result = apply_update(&temp_path, &exe, &info.filename);
        match result {
            Ok(()) => {
                // Приложение сейчас выйдет; процесс обновления продолжит скрипт.
                self.set_status(StatusKind::Success, "Обновление будет установлено после перезапуска.");
                // Немедленный выход — чтобы замена прошла без «файл занят».
                self.running = false;
            }
            Err(e) => {
                self.set_status(StatusKind::Error, e);
            }
        }
    }

    fn dark(&self, ui: &egui::Ui) -> bool {
        ui.visuals().dark_mode
    }
}

/// Замена + перезапуск по ОС.
fn apply_update(temp_path: &str, exe_path: &std::path::Path, _filename: &str) -> Result<(), String> {
    let exe_str = exe_path.to_string_lossy().into_owned();
    let tmp = temp_path.to_string();

    #[cfg(target_os = "windows")]
    {
        use std::process::Command;
        let script = format!(
            r#"timeout /t 2 /nobreak >nul & move /y "{}" "{}" & start "" "{}""#,
            tmp, exe_str, exe_str
        );
        Command::new("cmd")
            .args(["/c", &script])
            .spawn()
            .map_err(|e| format!("не удалось запустить сценарий обновления: {e}"))?;
        Ok(())
    }

    #[cfg(target_os = "linux")]
    {
        use std::process::Command;
        let script = format!(
            "sleep 2; mv -f '{}' '{}'; chmod +x '{}'; exec '{}'",
            tmp, exe_str, exe_str, exe_str
        );
        Command::new("sh")
            .args(["-c", &script])
            .spawn()
            .map_err(|e| format!("не удалось запустить сценарий обновления: {e}"))?;
        Ok(())
    }

    #[cfg(target_os = "macos")]
    {
        // macOS: .app целиком — заменяем бандл.
        use std::process::Command;

        // Из бинарника внутри .app получаем путь к бандлу.
        let bundle = exe_path
            .parent()
            .and_then(std::path::Path::parent)
            .and_then(std::path::Path::parent)
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or(exe_str.clone());

        // В .zip внутри лежит МГКАП.app. Распаковываем во временную папку,
        // затем заменяем.
        let script = format!(
            r#"sleep 2
TMPDIR="/tmp/mgkap-install"
rm -rf "$TMPDIR"
mkdir -p "$TMPDIR"
cd "$TMPDIR"
unzip -o '{}' 2>/dev/null || echo "не архив"
if [ -d "МГКАП.app" ]; then
  rm -rf '{}'
  mv "МГКАП.app" '{}'
fi
rm -rf "$TMPDIR"
open '{}'"#,
            tmp, bundle, bundle, bundle
        );
        Command::new("sh")
            .args(["-c", &script])
            .spawn()
            .map_err(|e| format!("не удалось запустить сценарий обновления: {e}"))?;
        Ok(())
    }

    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    {
        Err("автообновление не поддерживается на этой ОС".into())
    }
}
