//! Вкладка «Настройки»: профиль, оформление, звонки, сокращённые дни, данные.

use chrono::Datelike;
use eframe::egui;
use pulse_core::bells::{BellConfig, BellDay};
use pulse_core::datetime;
use pulse_core::model::ScheduleCache;
use pulse_core::performance::{Semester, ROUNDING_HUNDREDTHS, ROUNDING_TENTHS, ROUNDING_WHOLE};

use crate::app::{BellsEditor, PulseApp, StatusKind};
use crate::store::Settings;
use crate::theme;
use crate::views::{badge, card, dark, muted, muted_color, section_title};

/// Акцентные цвета — тот же набор, что в Android (`AccentColorPicker`).
const ACCENTS: [u32; 10] = [
    0xFF6C48E1, 0xFF00B0FF, 0xFF00E5FF, 0xFFFF6B35, 0xFF00C853, 0xFFAA00FF, 0xFFFF3D00,
    0xFF2979FF, 0xFF651FFF, 0xFFD500F9,
];

const ROUNDING_MODES: [(i32, &str, &str); 3] = [
    (ROUNDING_WHOLE, "Целые", "средний балл округляется до целого"),
    (ROUNDING_TENTHS, "Десятые", "как в зачётке: 8.4 из 8.36"),
    (ROUNDING_HUNDREDTHS, "Сотые", "без округления, два знака"),
];

/// Действия, собранные во время отрисовки.
enum SettingAction {
    SaveSettings,
    OpenBells(BellDay),
    ResetBells(BellDay),
    AddShortened,
    RemoveShortened(chrono::NaiveDate),
    ResetAll,
}

impl PulseApp {
    pub(crate) fn ui_settings(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Настройки").size(22.0).strong());
            badge(ui, &format!("версия {}", pulse_core::VERSION), muted_color(ui));
        });
        muted(
            ui,
            "Всё хранится локально в одном каталоге — без аккаунта, сервера и интернета, кроме загрузки расписания.",
        );
        ui.add_space(10.0);

        let mut actions: Vec<SettingAction> = Vec::new();
        let mut dirty = false;

        // ── профиль ─────────────────────────────────────────────────────────
        section_title(ui, "Профиль");
        card(ui, |ui| {
            egui::Grid::new("settings-profile")
                .num_columns(2)
                .spacing([12.0, 8.0])
                .show(ui, |ui| {
                    ui.label("Имя");
                    dirty |= ui
                        .add(
                            egui::TextEdit::singleline(&mut self.settings.user_name)
                                .hint_text("Как обращаться")
                                .desired_width(240.0),
                        )
                        .changed();
                    ui.end_row();

                    let group_label = ui.label("Группа");
                    let current = if self.active_group.is_empty() {
                        "не выбрана".to_string()
                    } else {
                        self.active_group.clone()
                    };
                    let mut chosen: Option<String> = None;
                    ui.horizontal(|ui| {
                        let combo = egui::ComboBox::from_id_salt("settings-group")
                            .selected_text(current)
                            .width(180.0)
                            .show_ui(ui, |ui| {
                                if self.groups.is_empty() {
                                    ui.label(egui::RichText::new("список пуст").color(muted_color(ui)));
                                }
                                for group in &self.groups {
                                    let selected = group.eq_ignore_ascii_case(&self.active_group);
                                    if ui.selectable_label(selected, group).clicked() {
                                        chosen = Some(group.clone());
                                    }
                                }
                            });
                        combo.response.labelled_by(group_label.id);
                        if !self.groups.is_empty() && ui.small_button("сбросить").clicked() {
                            chosen = Some(String::new());
                        }
                    });
                    if let Some(group) = chosen {
                        if group.is_empty() {
                            self.clear_group();
                        } else {
                            self.set_active_group(group);
                        }
                    }
                    ui.end_row();
                });
            muted(
                ui,
                if self.groups.is_empty() {
                    "Список групп появится после первой загрузки расписания."
                } else {
                    "Доступные группы взяты из последнего загруженного расписания."
                },
            );
        });

        // ── оформление ──────────────────────────────────────────────────────
        section_title(ui, "Оформление");
        card(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label("Тема");
                let mut is_dark = self.settings.dark_theme;
                if ui.selectable_label(is_dark, "🌙 Тёмная").clicked() {
                    is_dark = true;
                }
                if ui.selectable_label(!is_dark, "☀ Светлая").clicked() {
                    is_dark = false;
                }
                if is_dark != self.settings.dark_theme {
                    self.settings.dark_theme = is_dark;
                    dirty = true;
                }
            });

            ui.add_space(8.0);
            ui.label("Акцентный цвет");
            ui.horizontal_wrapped(|ui| {
                for accent in ACCENTS {
                    let selected = self.settings.accent == accent;
                    let color = theme::color(accent);
                    let (rect, response) =
                        ui.allocate_exact_size(egui::vec2(28.0, 28.0), egui::Sense::click());
                    ui.painter().circle_filled(rect.center(), 12.0, color);
                    if selected {
                        ui.painter().circle_stroke(
                            rect.center(),
                            14.0,
                            egui::Stroke::new(2.0, theme::text(dark(ui))),
                        );
                    }
                    if response.clicked() && !selected {
                        self.settings.accent = accent;
                        dirty = true;
                    }
                    response.on_hover_text(format!("{accent:08X}"));
                }
            });
        });

        // ── расчёты ─────────────────────────────────────────────────────────
        section_title(ui, "Расчёты");
        card(ui, |ui| {
            ui.label("Округление среднего балла");
            ui.add_space(4.0);
            for (mode, title, hint) in ROUNDING_MODES {
                let selected = self.settings.rounding_mode == mode;
                if ui
                    .selectable_label(selected, format!("{title} — {hint}"))
                    .clicked()
                    && !selected
                {
                    self.settings.rounding_mode = mode;
                    dirty = true;
                }
            }
            muted(
                ui,
                "Влияет только на показ: сами оценки не меняются, поэтому переключение безопасно.",
            );
        });

        // ── обновление ──────────────────────────────────────────────────────
        section_title(ui, "Обновление расписания");
        card(ui, |ui| {
            dirty |= ui
                .checkbox(
                    &mut self.settings.auto_refresh,
                    "Загружать расписание при запуске, если данных ещё нет",
                )
                .changed();

            ui.horizontal(|ui| {
                let busy = self.fetch.is_some();
                if ui
                    .add_enabled(!busy, egui::Button::new("⟳ Обновить сейчас"))
                    .clicked()
                {
                    self.start_fetch();
                }
                if busy {
                    ui.spinner();
                    muted(ui, "идёт загрузка…");
                }
            });

            if !self.settings.last_pdf_file.is_empty() {
                ui.add_space(4.0);
                muted(
                    ui,
                    format!("Последний файл: {}", self.settings.last_pdf_file),
                );
            }
            if !self.settings.last_pdf_url.is_empty() {
                ui.label(
                    egui::RichText::new(&self.settings.last_pdf_url)
                        .size(11.0)
                        .color(muted_color(ui)),
                );
            }
            ui.add_space(2.0);
            ui.hyperlink_to("Страница расписания колледжа", pulse_core::PAGE_URL);
        });

        // ── звонки ──────────────────────────────────────────────────────────
        section_title(ui, "Звонки");
        card(ui, |ui| {
            muted(
                ui,
                "Время пар берётся из расписания, но если его нет — из этой таблицы. Тип дня определяется по дате.",
            );
            ui.add_space(6.0);
            for day in BellDay::ALL {
                let custom = self.bells.custom.contains_key(day.key());
                let slots = self.bells.custom.get(day.key());
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new(day.label_ru()).strong());
                    badge(
                        ui,
                        if custom { "свой вариант" } else { "по умолчанию" },
                        if custom {
                            theme::color(self.settings.accent)
                        } else {
                            muted_color(ui)
                        },
                    );
                    if let Some(slots) = slots {
                        muted(ui, format!("{} пар", slots.len()));
                    }
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if custom && ui.small_button("сбросить").clicked() {
                            actions.push(SettingAction::ResetBells(day));
                        }
                        if ui.button("изменить").clicked() {
                            actions.push(SettingAction::OpenBells(day));
                        }
                    });
                });
            }
            ui.add_space(4.0);
            muted(ui, "Активные звонки на сегодня:");
            let today = self.today();
            let slots = self.bells.slots_for(today);
            ui.horizontal_wrapped(|ui| {
                for (index, slot) in slots.iter().enumerate() {
                    badge(
                        ui,
                        &format!("{}. {}–{}", index + 1, slot.start, slot.end),
                        muted_color(ui),
                    );
                }
            });
        });

        // ── сокращённые дни ─────────────────────────────────────────────────
        section_title(ui, "Сокращённые дни");
        card(ui, |ui| {
            muted(
                ui,
                "В отмеченные даты пары идут по субботнему расписанию звонков.",
            );
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                ui.add(
                    egui::TextEdit::singleline(&mut self.shortened_input)
                        .hint_text("25.12.2026")
                        .desired_width(140.0),
                );
                if ui.button("+ добавить").clicked() {
                    actions.push(SettingAction::AddShortened);
                }
                let parsed = datetime::parse_date(&self.shortened_input).is_some();
                if !self.shortened_input.trim().is_empty() && !parsed {
                    muted(ui, "формат: 25.12.2026");
                }
            });

            let mut days: Vec<chrono::NaiveDate> =
                self.bells.shortened_days.iter().copied().collect();
            days.sort();
            if days.is_empty() {
                ui.add_space(4.0);
                muted(ui, "Сокращённых дней нет.");
            } else {
                ui.add_space(6.0);
                let mut remove: Option<chrono::NaiveDate> = None;
                for day in days {
                    ui.horizontal(|ui| {
                        let text = format!(
                            "{} · {}",
                            datetime::format_short_date(day),
                            datetime::weekday_name_ru(day.weekday())
                        );
                        ui.label(egui::RichText::new(text).size(13.0));
                        if ui.small_button("×").on_hover_text("Убрать").clicked() {
                            remove = Some(day);
                        }
                    });
                }
                if let Some(day) = remove {
                    actions.push(SettingAction::RemoveShortened(day));
                }
            }
        });

        // ── данные ──────────────────────────────────────────────────────────
        section_title(ui, "Данные");
        card(ui, |ui| {
            ui.label("Каталог данных");
            ui.label(
                egui::RichText::new(self.store.data_dir())
                    .monospace()
                    .size(12.0),
            );
            ui.horizontal(|ui| {
                if ui
                    .button("Скопировать путь")
                    .on_hover_text("Пригодится для бэкапа")
                    .clicked()
                {
                    ui.ctx().copy_text(self.store.data_dir());
                    self.set_status(StatusKind::Success, "Путь скопирован в буфер обмена.");
                }
            });
            ui.add_space(6.0);
            muted(
                ui,
                "Файлы: settings.json, schedule.json, teachers.json, homework.json, performance.json, bells.json, last_raw_text.txt.",
            );
            ui.add_space(8.0);

            if self.confirm_reset {
                ui.horizontal_wrapped(|ui| {
                    ui.label(
                        egui::RichText::new("Удалить все данные безвозвратно?")
                            .strong()
                            .color(theme::color(pulse_core::performance::COLOR_FAIL)),
                    );
                    if ui.button("Да, удалить").clicked() {
                        actions.push(SettingAction::ResetAll);
                    }
                    if ui.button("Отмена").clicked() {
                        self.confirm_reset = false;
                    }
                });
            } else if ui.button("Сбросить все данные").clicked() {
                self.confirm_reset = true;
            }
        });

        // ── о программе ─────────────────────────────────────────────────────
        section_title(ui, "О программе");
        card(ui, |ui| {
            ui.label(
                egui::RichText::new("МГКАП · Pulse")
                    .size(16.0)
                    .strong()
                    .color(theme::color(self.settings.accent)),
            );
            muted(
                ui,
                format!(
                    "Десктопная версия {}. Расписание, звонки, домашние задания и аналитика успеваемости.",
                    pulse_core::VERSION
                ),
            );
            ui.add_space(4.0);
            ui.hyperlink_to("Открыть страницу расписания", pulse_core::PAGE_URL);
            ui.add_space(4.0);
            muted(
                ui,
                "Данные Android-версии лежат в JSON с теми же именами полей — файлы можно перенести вручную.",
            );
        });

        // ── применение собранных действий ───────────────────────────────────
        if dirty {
            actions.push(SettingAction::SaveSettings);
        }

        for action in actions {
            match action {
                SettingAction::SaveSettings => self.save_settings(),
                SettingAction::OpenBells(day) => {
                    self.bells_editor = Some(BellsEditor::new(day, &self.bells));
                }
                SettingAction::ResetBells(day) => {
                    self.bells.custom.remove(day.key());
                    self.apply_bells();
                    self.set_status(
                        StatusKind::Success,
                        format!("Звонки для «{}» возвращены к стандартным.", day.label_ru()),
                    );
                }
                SettingAction::AddShortened => match datetime::parse_date(&self.shortened_input) {
                    Some(date) => {
                        if self.bells.shortened_days.insert(date) {
                            self.shortened_input.clear();
                            self.apply_bells();
                            self.set_status(
                                StatusKind::Success,
                                format!("{} — сокращённый день.", datetime::format_short_date(date)),
                            );
                        } else {
                            self.set_status(
                                StatusKind::Info,
                                format!("{} уже отмечен.", datetime::format_short_date(date)),
                            );
                        }
                    }
                    None => self.set_status(
                        StatusKind::Error,
                        "Дата должна быть в формате 25.12.2026.",
                    ),
                },
                SettingAction::RemoveShortened(date) => {
                    if self.bells.shortened_days.remove(&date) {
                        self.apply_bells();
                        self.set_status(
                            StatusKind::Success,
                            format!("{} снова обычный день.", datetime::format_short_date(date)),
                        );
                    }
                }
                SettingAction::ResetAll => self.reset_all_data(),
            }
        }

        // Редактор звонков живёт под настройками — рисуется после списка дней.
        ui.add_space(10.0);
        self.ui_bells_editor(ui);
    }

    /// Полный сброс: файлы удалены, состояние вернулось к значениям по умолчанию.
    pub(crate) fn reset_all_data(&mut self) {
        if let Some(error) = self.store.reset_all() {
            self.set_status(StatusKind::Error, error);
            return;
        }

        self.settings = Settings::default();
        self.bells = BellConfig::default();
        self.schedule = ScheduleCache::new();
        self.teachers.clear();
        self.homework.clear();
        self.semesters = vec![Semester::new("1 семестр")];
        self.active_semester = 0;
        self.raw_text.clear();
        self.groups.clear();
        self.active_group.clear();
        self.selected_teacher = None;
        self.teacher_schedule = ScheduleCache::new();
        self.teacher_schedule_for = None;
        self.teacher_query.clear();
        self.bells_editor = None;
        self.subject_dialog = None;
        self.homework_dialog = None;
        self.shortened_input.clear();
        self.simulate_input.clear();
        self.simulation_subject = None;
        self.confirm_reset = false;
        self.selected_date = self.today();
        self.teacher_date = self.today();
        self.tab = crate::app::Tab::Settings;

        self.save_settings();
        self.set_status(
            StatusKind::Success,
            "Все данные удалены. Настройки сброшены к значениям по умолчанию.",
        );
    }
}