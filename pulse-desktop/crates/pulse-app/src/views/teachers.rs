//! Вкладка «Преподаватели»: справочник и расписание выбранного преподавателя.
//!
//! В режиме преподавателя та же панель показывается как «Моё расписание»,
//! только без списка слева и без кнопки сброса выбора.

use chrono::{Datelike, NaiveDate, NaiveTime};
use eframe::egui;
use pulse_core::datetime;
use pulse_core::model::{LessonPair, ScheduleCache, Teacher};

use crate::app::PulseApp;
use crate::theme;
use crate::views::schedule::shift_days;
use crate::views::{
    avatar, badge, card, dark, empty_state, muted, muted_color, pulse_badge, subject_visual,
};

/// Чей кэш показывает панель преподавателя.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Source {
    /// «Моё расписание»: кэш построен по имени из настроек.
    Mine,
    /// Справочник: кэш построен по выбранному в списке преподавателю.
    Picked,
}

impl PulseApp {
    pub(crate) fn ui_teachers(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Преподаватели").size(22.0).strong());
            badge(
                ui,
                &format!("{} чел.", self.teachers.len()),
                theme::color(self.settings.accent),
            );
        });
        muted(
            ui,
            "Список собирается автоматически из текста расписания: имя, предметы и кабинеты.",
        );
        ui.add_space(8.0);

        if self.teachers.is_empty() {
            empty_state(
                ui,
                "👥",
                "Список пока пуст",
                "Обновите расписание — преподаватели появятся автоматически.",
            );
            if self.fetch.is_none() && ui.button("⟳ Обновить расписание").clicked() {
                self.start_fetch();
            }
            return;
        }

        ui.add(
            egui::TextEdit::singleline(&mut self.teacher_query)
                .hint_text("Поиск: фамилия, предмет или кабинет")
                .desired_width(340.0),
        );
        ui.add_space(8.0);

        let query = self.teacher_query.trim().to_lowercase();
        let teachers: Vec<Teacher> = self
            .teachers
            .iter()
            .filter(|teacher| {
                query.is_empty()
                    || teacher.name.to_lowercase().contains(&query)
                    || teacher.subject.to_lowercase().contains(&query)
                    || teacher.room.to_lowercase().contains(&query)
            })
            .cloned()
            .collect();

        if teachers.is_empty() {
            empty_state(
                ui,
                "🔍",
                "Ничего не найдено",
                "Попробуйте другую часть фамилии или название предмета.",
            );
            return;
        }

        let mut chosen: Option<String> = None;

        ui.horizontal_top(|ui| {
            ui.allocate_ui_with_layout(
                egui::vec2(330.0, ui.available_height().max(200.0)),
                egui::Layout::top_down(egui::Align::Min),
                |ui| {
                    for teacher in &teachers {
                        let selected = self
                            .selected_teacher
                            .as_deref()
                            .map(|name| name == teacher.name)
                            .unwrap_or(false);

                        let response = egui::Frame::new()
                            .fill(if selected {
                                theme::color(self.settings.accent).gamma_multiply(0.16)
                            } else {
                                theme::surface(dark(ui))
                            })
                            .stroke(egui::Stroke::new(
                                1.0,
                                if selected {
                                    theme::color(self.settings.accent)
                                } else {
                                    crate::views::border_color(ui)
                                },
                            ))
                            .inner_margin(egui::Margin::same(10))
                            .corner_radius(egui::CornerRadius::same(10))
                            .show(ui, |ui| {
                                ui.horizontal(|ui| {
                                    avatar(ui, &teacher.name, teacher.avatar_color);
                                    ui.vertical(|ui| {
                                        ui.label(egui::RichText::new(&teacher.name).strong());
                                        if teacher.subject.is_empty() {
                                            muted(ui, "предметы уточняются");
                                        } else {
                                            muted(ui, &teacher.subject);
                                        }
                                    });
                                });
                            })
                            .response;

                        if response.interact(egui::Sense::click()).clicked() {
                            chosen = Some(teacher.name.clone());
                        }
                        ui.add_space(6.0);
                    }
                },
            );

            ui.separator();

            ui.vertical(|ui| match self.selected_teacher.clone() {
                Some(name) => self.teacher_panel(ui, &name, true, Source::Picked),
                None => {
                    empty_state(
                        ui,
                        "👤",
                        "Выберите преподавателя",
                        "Слева — список; справа появится его расписание по дням.",
                    );
                }
            });
        });

        if let Some(name) = chosen {
            self.select_teacher(Some(name));
        }
    }

    /// «Моё расписание» — точка входа для преподавателя.
    pub(crate) fn ui_my_schedule(&mut self, ui: &mut egui::Ui) {
        let name = self.settings.teacher_name.trim().to_string();

        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Моё расписание").size(22.0).strong());
            if !name.is_empty() {
                badge(ui, &name, theme::color(self.settings.accent));
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.checkbox(&mut self.teacher_week_mode, "Вся неделя");
            });
        });
        muted(
            ui,
            "Здесь только ваши пары. Расписание любой группы — во вкладке «Группы».",
        );
        ui.add_space(8.0);

        if self.raw_text.trim().is_empty() {
            empty_state(
                ui,
                "📡",
                "Расписание ещё не загружено",
                "Нажмите «Обновить» в шапке — приложение скачает PDF с сайта колледжа.",
            );
            if self.fetch.is_none() && ui.button("⟳ Загрузить расписание").clicked() {
                self.start_fetch();
            }
            return;
        }

        if name.is_empty() {
            empty_state(
                ui,
                "👤",
                "Не выбрано ваше имя",
                "Выберите себя в шапке — в списке рядом с кнопкой «Обновить».",
            );
            return;
        }

        if self.teacher_week_mode {
            self.teacher_week(ui);
        } else {
            self.teacher_panel(ui, &name, false, Source::Mine);
        }
    }

    /// Неделя преподавателя: пустые дни — компактной строкой.
    fn teacher_week(&mut self, ui: &mut egui::Ui) {
        let today = self.today();
        let now = self.now();
        let days = self.week_of(self.teacher_date);
        let mut date_shift: i64 = 0;

        ui.horizontal(|ui| {
            if ui.button("◀").on_hover_text("Предыдущая неделя").clicked() {
                date_shift = -7;
            }
            let title = match (days.first(), days.last()) {
                (Some(first), Some(last)) => format!(
                    "{} — {}",
                    datetime::format_short_date(*first),
                    datetime::format_short_date(*last)
                ),
                _ => String::new(),
            };
            ui.label(egui::RichText::new(title).strong());
            if ui.button("▶").on_hover_text("Следующая неделя").clicked() {
                date_shift = 7;
            }
            if ui.small_button("Сегодня").clicked() {
                self.teacher_date = today;
            }
        });
        ui.add_space(6.0);

        for date in days {
            let lessons = self.my_schedule.get(&date).cloned().unwrap_or_default();
            let is_today = date == today;
            let title = format!(
                "{}, {}",
                datetime::weekday_short_ru(date.weekday()),
                datetime::format_short_date(date)
            );

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
                    teacher_lesson_row(ui, lesson, is_today, now);
                }
            });
        }

        if date_shift != 0 {
            self.teacher_date = shift_days(self.teacher_date, date_shift);
        }
    }

    /// Карточка преподавателя и его расписание на день.
    ///
    /// `allow_reset` включает кнопку «сбросить выбор» — она нужна только
    /// в справочнике, где выбор делает пользователь. `source` говорит, чей
    /// кэш читать: в справочнике — чужой, на вкладке «Расписание» — свой.
    fn teacher_panel(&mut self, ui: &mut egui::Ui, name: &str, allow_reset: bool, source: Source) {
        let today = self.today();
        let now = self.now();
        let mut date_shift: i64 = 0;
        let mut drop_selection = false;

        let teacher = self
            .teachers
            .iter()
            .find(|teacher| teacher.name == name)
            .cloned();
        let (avatar_color, subject, room, email) = match &teacher {
            Some(teacher) => (
                teacher.avatar_color,
                teacher.subject.clone(),
                teacher.room.clone(),
                teacher.email.clone(),
            ),
            None => (
                self.settings.accent,
                String::new(),
                String::new(),
                String::new(),
            ),
        };

        card(ui, |ui| {
            ui.horizontal(|ui| {
                avatar(ui, name, avatar_color);
                ui.vertical(|ui| {
                    ui.label(egui::RichText::new(name).size(16.0).strong());
                    if !subject.is_empty() {
                        muted(ui, &subject);
                    }
                });
                if allow_reset {
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.small_button("×").on_hover_text("Сбросить выбор").clicked() {
                            drop_selection = true;
                        }
                    });
                }
            });
            if !room.is_empty() || email != "-" {
                ui.add_space(4.0);
                ui.horizontal_wrapped(|ui| {
                    if !room.is_empty() {
                        badge(ui, &format!("ауд. {room}"), muted_color(ui));
                    }
                    if email != "-" && !email.is_empty() {
                        badge(ui, &email, theme::color(self.settings.accent));
                    }
                });
            }
        });

        ui.horizontal(|ui| {
            if ui.button("◀").on_hover_text("Предыдущий день").clicked() {
                date_shift = -1;
            }
            ui.label(egui::RichText::new(datetime::format_day_month(self.teacher_date)).strong());
            if ui.button("▶").on_hover_text("Следующий день").clicked() {
                date_shift = 1;
            }
            if ui.small_button("Сегодня").clicked() {
                self.teacher_date = today;
            }
        });
        ui.add_space(6.0);

        let date: NaiveDate = self.teacher_date;
        // Кэш выбираем один раз: дальше `self` меняется, держать ссылку нельзя.
        let cache: &ScheduleCache = match source {
            Source::Mine => &self.my_schedule,
            Source::Picked => &self.teacher_schedule,
        };
        let lessons = cache.get(&date).cloned().unwrap_or_default();
        let upcoming: Vec<(NaiveDate, usize)> = if lessons.is_empty() {
            cache
                .iter()
                .filter(|(day, items)| **day >= date && !items.is_empty())
                .map(|(day, items)| (*day, items.len()))
                .take(3)
                .collect()
        } else {
            Vec::new()
        };

        if lessons.is_empty() {
            muted(ui, "В этот день пар нет.");
            if !upcoming.is_empty() {
                ui.add_space(4.0);
                muted(ui, "Ближайшие занятия:");
                for (day, count) in upcoming {
                    ui.horizontal(|ui| {
                        ui.label(
                            egui::RichText::new(format!(
                                "{} · {}",
                                datetime::format_day_month(day),
                                count
                            ))
                            .size(12.0),
                        );
                        if ui.small_button("показать").clicked() {
                            self.teacher_date = day;
                        }
                    });
                }
            }
        } else {
            for lesson in &lessons {
                card(ui, |ui| {
                    teacher_lesson_row(ui, lesson, date == today, now);
                });
            }
        }

        if drop_selection {
            self.select_teacher(None);
        }
        if date_shift != 0 {
            self.teacher_date = shift_days(self.teacher_date, date_shift);
        }
    }
}

/// Одна пара преподавателя: время, предмет, кабинет и группа.
fn teacher_lesson_row(ui: &mut egui::Ui, lesson: &LessonPair, is_today: bool, now: NaiveTime) {
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
            let mut info = format!("ауд. {}", lesson.room);
            if !lesson.group.is_empty() {
                info.push_str(&format!(" · гр. {}", lesson.group));
            }
            muted(ui, info);
        });
    });
}