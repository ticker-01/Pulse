//! Десктопный клиент МГКАП для Windows, Linux и macOS.
//!
//! Ядро (разбор расписания, звонки, аналитика) живёт в крейте `pulse-core`
//! и покрыто тестами; здесь — только интерфейс на `egui`/`eframe`.

// В релизной сборке под Windows консольное окно не нужно; в debug оно полезно.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod notifier;
mod store;
mod theme;
mod views;

use app::PulseApp;

/// Рендерер под текущую ОС.
///
/// Windows — wgpu (DirectX 12): OpenGL там бывает 1.1 без вендорных драйверов,
/// а `egui_glow` требует 2.0+ и падает. На Linux и macOS glow работает везде,
/// и тянет за собой меньше зависимостей, поэтому wgpu там не включается вовсе.
fn renderer() -> eframe::Renderer {
    #[cfg(target_os = "windows")]
    {
        eframe::Renderer::Wgpu
    }
    #[cfg(not(target_os = "windows"))]
    {
        eframe::Renderer::Glow
    }
}

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1200.0, 800.0])
            .with_min_inner_size([960.0, 620.0])
            .with_title(theme::WINDOW_TITLE),
        renderer: renderer(),
        ..Default::default()
    };

    eframe::run_native(
        theme::WINDOW_TITLE,
        options,
        Box::new(|cc| Ok(Box::new(PulseApp::new(cc)))),
    )
}