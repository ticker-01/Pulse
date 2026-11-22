//! Вкладка «Преподаватели»: справочник и расписание выбранного преподавателя.

use chrono::{Days, NaiveDate};
use eframe::egui;
use pulse_core::datetime;
use pulse_core::model::Teacher;

use crate::app::PulseApp;
use crate::theme;
use crate::views::{avatar, badge, card, dark, empty_state, muted, muted_color, subject_visual};

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
        let mut date_shift: i64 = 0;
        let mut drop_selection = false;

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

            ui.vertical(|ui| {
                let Some(name) = self.selected_teacher.clone() else {
                    empty_state(
                        ui,
                        "👤",
                        "Выберите преподавателя",
                        "Слева — список; справа появится его расписание по дням.",
                    );
                    return;
                };

                let teacher = self.teachers.iter().find(|teacher| teacher.name == name).cloned();
                let (avatar_color, subject, room, email) = match &teacher {
                    Some(teacher) => (
                        teacher.avatar_color,
                        teacher.subject.clone(),
                        teacher.room.clone(),
                        teacher.email.clone(),
                    ),
                    None => (self.settings.accent, String::new(), String::new(), String::new()),
                };

                card(ui, |ui| {
                    ui.horizontal(|ui| {
                        avatar(ui, &name, avatar_color);
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new(&name).size(16.0).strong());
                            if !subject.is_empty() {
                                muted(ui, &subject);
                            }
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.small_button("×").on_hover_text("Сбросить выбор").clicked() {
                                drop_selection = true;
                            }
                        });
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
                    if ui.button("◀").clicked() {
                        date_shift = -1;
                    }
                    ui.label(
                        egui::RichText::new(datetime::format_day_month(self.teacher_date))
                            .strong(),
                    );
                    if ui.button("▶").clicked() {
                        date_shift = 1;
                    }
                    if ui.small_button("Сегодня").clicked() {
                        self.teacher_date = self.today();
                    }
                });
                ui.add_space(6.0);

                let date: NaiveDate = self.teacher_date;
                let lessons = self
                    .teacher_schedule
                    .get(&date)
                    .cloned()
                    .unwrap_or_default();

                if lessons.is_empty() {
                    muted(ui, "В этот день пар нет.");
                    let upcoming: Vec<(NaiveDate, usize)> = self
                        .teacher_schedule
                        .iter()
                        .filter(|(day, items)| **day >= date && !items.is_empty())
                        .map(|(day, items)| (*day, items.len()))
                        .take(3)
                        .collect();
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
                        let (emoji, color) = subject_visual(lesson.clean_subject());
                        card(ui, |ui| {
                            ui.horizontal_top(|ui| {
                                ui.vertical(|ui| {
                                    ui.label(
                                        egui::RichText::new(lesson.time_range())
                                            .monospace()
                                            .strong(),
                                    );
                                    muted(ui, format!("{} пара", lesson.lessons));
                                });
                                ui.add_space(10.0);
                                ui.vertical(|ui| {
                                    ui.horizontal_wrapped(|ui| {
                                        ui.label(egui::RichText::new(emoji).size(18.0));
                                        ui.label(
                                            egui::RichText::new(lesson.clean_subject())
                                                .strong()
                                                .color(color),
                                        );
                                    });
                                    let mut info = format!("ауд. {}", lesson.room);
                                    if !lesson.group.is_empty() {
                                        info.push_str(&format!(" · гр. {}", lesson.group));
                                    }
                                    muted(ui, info);
                                });
                            });
                        });
                    }
                }
            });
        });

        if let Some(name) = chosen {
            self.select_teacher(Some(name));
        }
        if drop_selection {
            self.select_teacher(None);
        }
        if date_shift != 0 {
            self.teacher_date = if date_shift > 0 {
                self.teacher_date
                    .checked_add_days(Days::new(date_shift as u64))
                    .unwrap_or(self.teacher_date)
            } else {
                self.teacher_date
                    .checked_sub_days(Days::new((-date_shift) as u64))
                    .unwrap_or(self.teacher_date)
            };
        }
    }
}