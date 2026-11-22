//! Вкладка «Расписание»: день/неделя, «сейчас идёт», звонки дня.

use chrono::{Datelike, Days, NaiveDate};
use eframe::egui;
use pulse_core::datetime;
use pulse_core::model::{now_lessons, LessonPair};

use crate::app::PulseApp;
use crate::theme;
use crate::views::{
    badge, card, dark, empty_state, muted, muted_color, subject_visual,
};

impl PulseApp {
    pub(crate) fn ui_schedule(&mut self, ui: &mut egui::Ui) {
        let dark = dark(ui);
        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Расписание").size(22.0).strong());
            if !self.active_group.is_empty() {
                badge(ui, &format!("гр. {}", self.active_group), theme::color(self.settings.accent));
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.checkbox(&mut self.week_mode, "Вся неделя");
            });
        });
        muted(ui, format!("Источник: {}", pulse_core::PAGE_URL));
        ui.add_space(8.0);

        // Навигация по датам.
        ui.horizontal(|ui| {
            let step = if self.week_mode { 7 } else { 1 };
            if ui.button("◀").on_hover_text("Назад").clicked() {
                self.selected_date = shift_days(self.selected_date, -step);
            }
            let title = if self.week_mode {
                match (self.week_of(self.selected_date).first(), self.week_of(self.selected_date).last()) {
                    (Some(start), Some(end)) => {
                        format!("{} — {}", datetime::format_short_date(*start), datetime::format_short_date(*end))
                    }
                    _ => datetime::format_day_month(self.selected_date),
                }
            } else {
                datetime::format_day_month(self.selected_date)
            };
            ui.label(egui::RichText::new(title).size(15.0).strong().color(theme::text(dark)));
            if ui.button("▶").on_hover_text("Вперёд").clicked() {
                self.selected_date = shift_days(self.selected_date, step);
            }
            if ui.button("Сегодня").clicked() {
                self.selected_date = self.today();
            }
            if self.selected_date != self.today()
                && ui.small_button("к сегодняшнему дню").clicked()
            {
                self.selected_date = self.today();
            }
        });
        ui.add_space(8.0);

        if self.raw_text.trim().is_empty() {
            empty_state(
                ui,
                "📡",
                "Расписание ещё не загружено",
                "Нажмите «Обновить» — приложение скачает PDF с сайта колледжа и разберёт его.",
            );
            if self.fetch.is_none() && ui.button("⟳ Загрузить расписание").clicked() {
                self.start_fetch();
            }
            return;
        }

        if self.active_group.trim().is_empty() {
            empty_state(
                ui,
                "👥",
                "Группа не выбрана",
                "Выберите группу в верхней панели или в настройках — тогда появится ваше расписание.",
            );
            return;
        }

        if self.week_mode {
            self.ui_schedule_week(ui);
        } else {
            let date = self.selected_date;
            self.ui_schedule_day(ui, date);
        }
    }

    fn ui_schedule_day(&mut self, ui: &mut egui::Ui, date: NaiveDate) {
        let today = self.today();
        let now = self.now();
        let lessons = self.lessons_on(date).to_vec();

        if date == today && !lessons.is_empty() {
            let found = now_lessons(&lessons, now);
            card(ui, |ui| {
                ui.label(
                    egui::RichText::new("Сейчас и далее")
                        .size(13.0)
                        .strong()
                        .color(theme::color(self.settings.accent)),
                );
                ui.add_space(4.0);
                match found.current {
                    Some(lesson) => {
                        let (emoji, color) = subject_visual(lesson.clean_subject());
                        ui.horizontal(|ui| {
                            ui.label(egui::RichText::new(emoji).size(20.0));
                            ui.vertical(|ui| {
                                ui.label(
                                    egui::RichText::new(lesson.clean_subject())
                                        .size(15.0)
                                        .strong()
                                        .color(color),
                                );
                                muted(
                                    ui,
                                    format!(
                                        "идёт сейчас · {} · ауд. {} · {}",
                                        lesson.time_range(),
                                        lesson.room,
                                        lesson.teacher
                                    ),
                                );
                            });
                        });
                    }
                    None => {
                        muted(ui, "Сейчас пары нет.");
                    }
                }
                if let Some(next) = found.next {
                    ui.add_space(6.0);
                    ui.label(
                        egui::RichText::new(format!(
                            "Далее: {} в {} (ауд. {})",
                            next.clean_subject(),
                            next.start_time,
                            next.room
                        ))
                        .size(12.0)
                        .color(muted_color(ui)),
                    );
                }
            });
        }

        if lessons.is_empty() {
            empty_state(
                ui,
                "⛅",
                format!("На {} пар нет", datetime::format_short_date(date)),
                "Возможно, в этот день занятий не было или расписание ещё не обновилось.",
            );
        } else {
            for lesson in &lessons {
                lesson_card(ui, lesson, date == today, now);
            }
        }

        // Звонки дня — полезная шпаргалка под расписанием.
        let slots = self.bells.slots_for(date);
        let custom = self.bells.is_shortened(date);
        let label = if custom {
            "Звонки (сокращённый день)".to_string()
        } else {
            format!("Звонки ({})", self.bells.day_for(date).label_ru())
        };
        egui::CollapsingHeader::new(label)
            .default_open(false)
            .show(ui, |ui| {
                for (index, slot) in slots.iter().enumerate() {
                    ui.horizontal(|ui| {
                        ui.label(
                            egui::RichText::new(format!("{:>2}.", index + 1))
                                .monospace()
                                .color(muted_color(ui)),
                        );
                        ui.label(egui::RichText::new(format!("{} – {}", slot.start, slot.end)).monospace());
                    });
                }
            });
    }

    fn ui_schedule_week(&mut self, ui: &mut egui::Ui) {
        let today = self.today();
        let now = self.now();
        for date in self.week_of(self.selected_date) {
            let lessons = self.lessons_on(date).to_vec();
            let is_today = date == today;
            let title = format!(
                "{}, {}",
                datetime::weekday_short_ru(date.weekday()),
                datetime::format_short_date(date)
            );
            let day_kind = self.bells.day_for(date);

            // Пустой день показываем одной строкой: иначе неделя не влезает на экран.
            if lessons.is_empty() {
                ui.horizontal(|ui| {
                    let color = if is_today {
                        theme::color(self.settings.accent)
                    } else {
                        muted_color(ui)
                    };
                    ui.label(egui::RichText::new(title).size(13.0).strong().color(color));
                    if is_today {
                        badge(ui, "сегодня", theme::color(self.settings.accent));
                    }
                    badge(ui, day_kind.label_ru(), muted_color(ui));
                    muted(ui, "пар нет");
                });
                ui.add_space(6.0);
                continue;
            }

            card(ui, |ui| {
                ui.horizontal(|ui| {
                    let color = if is_today {
                        theme::color(self.settings.accent)
                    } else {
                        theme::text(dark(ui))
                    };
                    ui.label(egui::RichText::new(title).size(14.0).strong().color(color));
                    if is_today {
                        badge(ui, "сегодня", theme::color(self.settings.accent));
                    }
                    badge(ui, day_kind.label_ru(), muted_color(ui));
                });
                ui.add_space(4.0);
                for lesson in &lessons {
                    ui.horizontal(|ui| {
                        ui.label(
                            egui::RichText::new(format!("{:>5}", lesson.start_time))
                                .monospace()
                                .color(muted_color(ui)),
                        );
                        let (emoji, color) = subject_visual(lesson.clean_subject());
                        ui.label(egui::RichText::new(emoji).size(14.0));
                        ui.label(
                            egui::RichText::new(lesson.clean_subject())
                                .color(color)
                                .strong(),
                        );
                        muted(ui, format!("ауд. {} · {}", lesson.room, lesson.teacher));
                    });
                }
                if is_today {
                    let found = now_lessons(&lessons, now);
                    if let Some(current) = found.current {
                        ui.add_space(2.0);
                        muted(ui, format!("сейчас: {}", current.clean_subject()));
                    }
                }
            });
        }
    }
}

fn lesson_card(ui: &mut egui::Ui, lesson: &LessonPair, is_today: bool, now: chrono::NaiveTime) {
    let active = is_today
        && lesson
            .start_local_time()
            .zip(lesson.end_local_time())
            .map(|(start, end)| now >= start && now <= end)
            .unwrap_or(false);

    let (emoji, color) = subject_visual(lesson.clean_subject());
    let stroke_color = if active {
        color
    } else {
        crate::views::border_color(ui)
    };

    egui::Frame::new()
        .fill(theme::surface(dark(ui)))
        .stroke(egui::Stroke::new(if active { 1.5 } else { 1.0 }, stroke_color))
        .inner_margin(egui::Margin::same(14))
        .corner_radius(egui::CornerRadius::same(12))
        .show(ui, |ui| {
            ui.horizontal_top(|ui| {
                ui.vertical(|ui| {
                    ui.label(
                        egui::RichText::new(lesson.time_range())
                            .size(14.0)
                            .strong()
                            .monospace(),
                    );
                    muted(ui, format!("{} пара", lesson.lessons));
                });
                ui.add_space(10.0);
                ui.vertical(|ui| {
                    ui.horizontal_wrapped(|ui| {
                        ui.label(egui::RichText::new(emoji).size(18.0));
                        ui.label(
                            egui::RichText::new(lesson.clean_subject())
                                .size(15.0)
                                .strong()
                                .color(color),
                        );
                    });
                    muted(
                        ui,
                        format!("{} · ауд. {}", lesson.teacher, lesson.room),
                    );
                });
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if active {
                        badge(ui, "сейчас", color);
                    }
                    if !lesson.group.is_empty() {
                        badge(ui, &lesson.group, muted_color(ui));
                    }
                });
            });
        });
    ui.add_space(8.0);
}

fn shift_days(date: NaiveDate, days: i64) -> NaiveDate {
    if days >= 0 {
        date.checked_add_days(Days::new(days as u64))
            .unwrap_or(date)
    } else {
        date.checked_sub_days(Days::new((-days) as u64))
            .unwrap_or(date)
    }
}