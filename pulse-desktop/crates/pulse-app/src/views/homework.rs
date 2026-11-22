//! Вкладка «Домашние задания»: дедлайны, «осталось пар», отметки о выполнении.

use chrono::NaiveDate;
use eframe::egui;
use pulse_core::datetime;
use pulse_core::homework::{self, HomeworkItem};

use crate::app::{PulseApp, StatusKind};
use crate::theme;
use crate::views::{badge, card, empty_state, muted, muted_color, subject_visual};

enum HomeworkAction {
    Toggle(String),
    Edit(String),
    Delete(String),
    DeleteCompleted,
}

impl PulseApp {
    pub(crate) fn ui_homework(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Домашние задания").size(22.0).strong());
            if ui.button("+ Задание").clicked() {
                self.open_homework_dialog(None);
            }
        });
        muted(
            ui,
            "Срок можно указать вручную — приложение напомнит и посчитает, сколько пар осталось.",
        );
        ui.add_space(8.0);

        let today = self.today();
        let now = self.now();

        let active = self.homework.iter().filter(|item| !item.is_completed).count();
        let overdue = self
            .homework
            .iter()
            .filter(|item| item.is_overdue(today))
            .count();
        let done = self.homework.len() - active;
        let accent = self.settings.accent;
        let mut actions: Vec<HomeworkAction> = Vec::new();

        card(ui, |ui| {
            ui.horizontal_wrapped(|ui| {
                badge(ui, &format!("активных: {active}"), theme::color(accent));
                badge(
                    ui,
                    &format!("просрочено: {overdue}"),
                    theme::color(if overdue > 0 {
                        pulse_core::performance::COLOR_FAIL
                    } else {
                        pulse_core::performance::COLOR_EXCELLENT
                    }),
                );
                badge(
                    ui,
                    &format!("выполнено: {done}"),
                    theme::color(pulse_core::performance::COLOR_GOOD),
                );
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if done > 0 && ui.button("Очистить выполненные").clicked() {
                        // Действие применяется после отрисовки: список нельзя менять во время обхода.
                        actions.push(HomeworkAction::DeleteCompleted);
                    }
                });
            });
        });

        if self.homework.is_empty() {
            empty_state(
                ui,
                "📝",
                "Заданий нет",
                "Нажмите «+ Задание», чтобы записать домашку и не забыть про срок.",
            );
            return;
        }

        let groups: [(&str, Vec<&HomeworkItem>); 3] = [
            (
                "Просроченные",
                self.homework
                    .iter()
                    .filter(|item| item.is_overdue(today))
                    .collect(),
            ),
            (
                "На сегодня",
                self.homework
                    .iter()
                    .filter(|item| !item.is_completed && item.due_date == today)
                    .collect(),
            ),
            (
                "Далее",
                self.homework
                    .iter()
                    .filter(|item| !item.is_completed && item.due_date > today)
                    .collect(),
            ),
        ];

        for (title, items) in groups {
            if items.is_empty() {
                continue;
            }
            ui.add_space(2.0);
            ui.label(egui::RichText::new(format!("{title} · {}", items.len())).strong());
            ui.add_space(4.0);
            for item in items {
                homework_card(ui, item, today, self, &mut actions);
            }
        }

        let completed: Vec<&HomeworkItem> = self
            .homework
            .iter()
            .filter(|item| item.is_completed)
            .collect();
        if !completed.is_empty() {
            egui::CollapsingHeader::new(format!("Выполненные · {}", completed.len()))
                .default_open(false)
                .show(ui, |ui| {
                    for item in completed {
                        homework_card(ui, item, today, self, &mut actions);
                    }
                });
        }

        let _ = now;

        for action in actions {
            match action {
                HomeworkAction::Toggle(id) => {
                    if let Some(completed) = homework::toggle_completed(&mut self.homework, &id) {
                        self.save_homework();
                        let text = if completed {
                            "Задание выполнено."
                        } else {
                            "Задание возвращено в работу."
                        };
                        self.set_status(StatusKind::Success, text);
                    }
                }
                HomeworkAction::Edit(id) => self.open_homework_dialog(Some(&id)),
                HomeworkAction::Delete(id) => {
                    if homework::delete(&mut self.homework, &id) {
                        self.save_homework();
                        self.set_status(StatusKind::Success, "Задание удалено.");
                    }
                }
                HomeworkAction::DeleteCompleted => {
                    let removed = homework::delete_completed(&mut self.homework);
                    self.save_homework();
                    self.set_status(
                        StatusKind::Success,
                        format!("Удалено выполненных заданий: {removed}."),
                    );
                }
            }
        }
    }
}

fn homework_card(
    ui: &mut egui::Ui,
    item: &HomeworkItem,
    today: NaiveDate,
    app: &PulseApp,
    actions: &mut Vec<HomeworkAction>,
) {
    let overdue = item.is_overdue(today);
    let (emoji, color) = subject_visual(&item.subject);
    let remaining = homework::remaining_pairs(item, &app.schedule, today, app.now());

    card(ui, |ui| {
        ui.horizontal_top(|ui| {
            let mut completed = item.is_completed;
            let check = ui.checkbox(&mut completed, "");
            let mut subject_label = None;

            ui.vertical(|ui| {
                ui.horizontal_wrapped(|ui| {
                    ui.label(egui::RichText::new(emoji).size(18.0));
                    subject_label = Some(ui.label(
                        egui::RichText::new(if item.subject.trim().is_empty() {
                            "Без предмета"
                        } else {
                            &item.subject
                        })
                        .size(15.0)
                        .strong()
                        .color(color),
                    ));
                    let due_color = if overdue {
                        theme::color(pulse_core::performance::COLOR_FAIL)
                    } else if item.due_date == today {
                        theme::color(pulse_core::performance::COLOR_SATISFACTORY)
                    } else {
                        muted_color(ui)
                    };
                    badge(
                        ui,
                        &format!(
                            "{} · {}",
                            datetime::format_short_date(item.due_date),
                            datetime::humanize_days(item.due_date, today)
                        ),
                        due_color,
                    );
                    if let Some(left) = remaining {
                        badge(
                            ui,
                            &format!("осталось пар: {left}"),
                            theme::color(if left == 0 {
                                pulse_core::performance::COLOR_FAIL
                            } else {
                                pulse_core::performance::COLOR_GOOD
                            }),
                        );
                    }
                    if item.notify_day_before {
                        badge(ui, "напомнить", muted_color(ui));
                    }
                });

                if !item.task.trim().is_empty() {
                    ui.label({
                        // Зачёркиваем только выполненное задание.
                        let text = egui::RichText::new(&item.task).size(13.0);
                        if item.is_completed {
                            text.strikethrough().color(muted_color(ui))
                        } else {
                            text
                        }
                    });
                } else {
                    muted(ui, "описание не заполнено");
                }
            });

            // Имя флажка берём у предмета: пустая подпись нечитаема для скринридера.
            let check = match &subject_label {
                Some(label) => check.labelled_by(label.id),
                None => check,
            };
            if check.changed() {
                actions.push(HomeworkAction::Toggle(item.id.clone()));
            }

            ui.with_layout(egui::Layout::right_to_left(egui::Align::Min), |ui| {
                if ui.small_button("удалить").clicked() {
                    actions.push(HomeworkAction::Delete(item.id.clone()));
                }
                if ui.small_button("изменить").clicked() {
                    actions.push(HomeworkAction::Edit(item.id.clone()));
                }
            });
        });

        if item.assign_date != today {
            muted(ui, format!("выдано {}", datetime::format_short_date(item.assign_date)));
        }
    });
}