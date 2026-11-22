//! Вкладка «Группы»: расписание любой группы колледжа.
//!
//! Студенту она не нужна (у него своя группа в шапке), а преподавателю —
//! первое дело: посмотреть, что и когда идёт у группы.

use chrono::Datelike;
use eframe::egui;
use pulse_core::datetime;

use crate::app::PulseApp;
use crate::theme;
use crate::views::schedule::shift_days;
use crate::views::{
    badge, card, dark, empty_state, muted, muted_color, pulse_badge, subject_visual,
};

impl PulseApp {
    pub(crate) fn ui_groups(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Группы").size(22.0).strong());
            badge(
                ui,
                &format!("{} в списке", self.groups.len()),
                theme::color(self.settings.accent),
            );
        });
        muted(
            ui,
            "Все группы из последнего PDF. Выберите группу — покажу её пары по дням.",
        );
        ui.add_space(8.0);

        if self.groups.is_empty() {
            empty_state(
                ui,
                "📡",
                "Групп пока нет",
                "Обновите расписание — список групп берётся из текста PDF.",
            );
            if self.fetch.is_none() && ui.button("⟳ Загрузить расписание").clicked() {
                self.start_fetch();
            }
            return;
        }

        // Первый показ: подставляем группу студента, иначе первую из списка.
        if self.group_view_group.trim().is_empty() {
            let fallback = if self
                .groups
                .iter()
                .any(|group| group.eq_ignore_ascii_case(&self.active_group))
            {
                self.active_group.clone()
            } else {
                self.groups.first().cloned().unwrap_or_default()
            };
            self.set_group_view(fallback);
        }

        let mut chosen: Option<String> = None;
        let mut date_shift: i64 = 0;

        ui.horizontal(|ui| {
            let mut picked: Option<String> = None;
            let label = ui.label("Группа:");
            let current = self.group_view_group.clone();
            let combo = egui::ComboBox::from_id_salt("groups-picker")
                .selected_text(current)
                .width(150.0)
                .show_ui(ui, |ui| {
                    for group in &self.groups {
                        let selected = group.eq_ignore_ascii_case(&self.group_view_group);
                        if ui.selectable_label(selected, group).clicked() {
                            picked = Some(group.clone());
                        }
                    }
                });
            // Имя из подписи — иначе список нечитаем для скринридера.
            combo.response.labelled_by(label.id);
            chosen = picked;

            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.checkbox(&mut self.week_mode, "Вся неделя");
            });
        });

        if let Some(group) = chosen {
            self.set_group_view(group);
        }

        ui.add_space(6.0);
        let step = if self.week_mode { 7 } else { 1 };
        ui.horizontal(|ui| {
            if ui.button("◀").on_hover_text("Назад").clicked() {
                date_shift = -step;
            }
            let title = if self.week_mode {
                let days = self.week_of(self.group_view_date);
                match (days.first(), days.last()) {
                    (Some(first), Some(last)) => format!(
                        "{} — {}",
                        datetime::format_short_date(*first),
                        datetime::format_short_date(*last)
                    ),
                    _ => datetime::format_short_date(self.group_view_date),
                }
            } else {
                datetime::format_day_month(self.group_view_date)
            };
            ui.label(egui::RichText::new(title).strong());
            if ui.button("▶").on_hover_text("Вперёд").clicked() {
                date_shift = step;
            }
            if ui.small_button("Сегодня").clicked() {
                self.group_view_date = self.today();
            }
        });
        ui.add_space(6.0);

        if self.week_mode {
            self.ui_group_week(ui);
        } else {
            self.ui_group_day(ui);
        }

        if date_shift != 0 {
            self.group_view_date = shift_days(self.group_view_date, date_shift);
        }
    }

    fn ui_group_day(&mut self, ui: &mut egui::Ui) {
        let date = self.group_view_date;
        let lessons = self
            .group_view_schedule
            .get(&date)
            .cloned()
            .unwrap_or_default();

        if lessons.is_empty() {
            empty_state(
                ui,
                "⛅",
                format!("На {} пар нет", datetime::format_short_date(date)),
                "Возможно, в этот день занятий не было или расписание ещё не обновилось.",
            );
            return;
        }

        // Расписание группы читается лучше одним блоком, чем стопкой карточек.
        let today = self.today();
        let now = self.now();
        let view_group = self.group_view_group.clone();
        card(ui, |ui| {
            for (index, lesson) in lessons.iter().enumerate() {
                if index > 0 {
                    ui.add_space(6.0);
                    ui.separator();
                    ui.add_space(6.0);
                }
                group_lesson_row(ui, lesson, date == today, now, &view_group);
            }
        });
    }

    fn ui_group_week(&mut self, ui: &mut egui::Ui) {
        let today = self.today();
        let now = self.now();
        let view_group = self.group_view_group.clone();
        for date in self.week_of(self.group_view_date) {
            let lessons = self
                .group_view_schedule
                .get(&date)
                .cloned()
                .unwrap_or_default();
            let is_today = date == today;
            let title = format!(
                "{}, {}",
                datetime::weekday_short_ru(date.weekday()),
                datetime::format_short_date(date)
            );

            // Пустой день — одна строка, иначе неделя не влезает на экран.
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
                });
                ui.add_space(4.0);
                for lesson in &lessons {
                    group_lesson_row(ui, lesson, is_today, now, &view_group);
                }
            });
        }
    }
}

/// Одна пара: время, предмет, преподаватель и кабинет.
fn group_lesson_row(
    ui: &mut egui::Ui,
    lesson: &pulse_core::model::LessonPair,
    is_today: bool,
    now: chrono::NaiveTime,
    view_group: &str,
) {
    let active = is_today
        && lesson
            .start_local_time()
            .zip(lesson.end_local_time())
            .map(|(start, end)| now >= start && now <= end)
            .unwrap_or(false);
    let (emoji, color) = subject_visual(lesson.clean_subject());

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
                if active {
                    pulse_badge(ui, "сейчас", color);
                }
            });
            let mut info = format!("{} · ауд. {}", lesson.teacher, lesson.room);
            // Группу пишем только если пара не той группы, что выбрана в фильтре.
            if !lesson.group.is_empty() && !lesson.group.eq_ignore_ascii_case(view_group) {
                info.push_str(&format!(" · гр. {}", lesson.group));
            }
            muted(ui, info);
        });
    });
}