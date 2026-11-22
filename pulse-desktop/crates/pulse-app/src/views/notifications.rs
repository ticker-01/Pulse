//! Вкладка «Уведомления»: сводка того, что требует внимания.
//!
//! Системных уведомлений ОС здесь нет намеренно: приложение не ставит задачи в
//! планировщик, поэтому напоминания живут внутри окна и собираются из данных
//! при открытии вкладки. Это честнее, чем «уведомление», которое появится,
//! только когда приложение уже запущено.

use chrono::Datelike;
use eframe::egui;
use pulse_core::analytics::{self, RiskType};
use pulse_core::datetime;
use pulse_core::model::now_lessons;
use pulse_core::performance::{COLOR_EXCELLENT, COLOR_FAIL, COLOR_GOOD, COLOR_NEUTRAL, COLOR_SATISFACTORY};

use crate::app::{PulseApp, Tab};
use crate::theme;
use crate::views::{badge, card_with, dark, empty_state, muted, muted_color};

/// Одна карточка ленты.
pub(crate) struct Notice {
    icon: &'static str,
    group: &'static str,
    title: String,
    detail: String,
    color: u32,
    tab: Tab,
}

impl PulseApp {
    pub(crate) fn ui_notifications(&mut self, ui: &mut egui::Ui) {
        let today = self.today();
        let active = self.homework.iter().filter(|item| !item.is_completed).count();
        let overdue = self.homework.iter().filter(|item| item.is_overdue(today)).count();

        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Уведомления").size(22.0).strong());
            badge(ui, &format!("активных заданий: {active}"), theme::color(self.settings.accent));
            if overdue > 0 {
                badge(ui, &format!("просрочено: {overdue}"), theme::color(COLOR_FAIL));
            }
        });
        muted(
            ui,
            "Сводка собирается из ваших данных при открытии вкладки: сроки, пары и оценки.",
        );
        ui.add_space(10.0);

        let notices = self.collect_notices();
        if notices.is_empty() {
            empty_state(
                ui,
                "✅",
                "Всё под контролем",
                "Просроченных заданий нет, долгов нет, расписание загружено.",
            );
            let mut goto: Option<Tab> = None;
            ui.horizontal(|ui| {
                if ui.button("К расписанию").clicked() {
                    goto = Some(Tab::Schedule);
                }
                if ui.button("К заданиям").clicked() {
                    goto = Some(Tab::Homework);
                }
            });
            if let Some(tab) = goto {
                self.tab = tab;
            }
            return;
        }

        let mut goto: Option<Tab> = None;
        let mut current_group = "";
        for notice in &notices {
            if notice.group != current_group {
                current_group = notice.group;
                ui.add_space(2.0);
                ui.label(egui::RichText::new(current_group).size(13.0).strong());
                ui.add_space(2.0);
            }
            if notice_card(ui, notice) {
                goto = Some(notice.tab);
            }
        }

        ui.add_space(6.0);
        muted(
            ui,
            "Напоминания видны, пока приложение открыто. Для системы это обычное окно — задач в планировщик ОС оно не ставит.",
        );

        if let Some(tab) = goto {
            self.tab = tab;
        }
    }

    /// Собирает ленту в порядке важности. Пустой результат — «всё хорошо».
    pub(crate) fn collect_notices(&self) -> Vec<Notice> {
        let today = self.today();
        let now = self.now();
        let tomorrow = today.succ_opt().unwrap_or(today);
        let accent = self.settings.accent;
        let mut notices: Vec<Notice> = Vec::new();

        // ── требует внимания ────────────────────────────────────────────────
        if self.raw_text.trim().is_empty() {
            notices.push(Notice {
                icon: "📡",
                group: "Требует внимания",
                title: "Расписание не загружено".to_string(),
                detail: "Нажмите «Обновить»: приложение скачает PDF с сайта колледжа и разберёт его."
                    .to_string(),
                color: COLOR_FAIL,
                tab: Tab::Schedule,
            });
        } else if self.active_group.trim().is_empty() {
            notices.push(Notice {
                icon: "👥",
                group: "Требует внимания",
                title: "Группа не выбрана".to_string(),
                detail: "Без группы нечего показать: выберите её в верхней панели или в настройках."
                    .to_string(),
                color: COLOR_SATISFACTORY,
                tab: Tab::Settings,
            });
        }

        for item in self.homework.iter().filter(|item| item.is_overdue(today)) {
            notices.push(Notice {
                icon: "⏰",
                group: "Требует внимания",
                title: format!("Просрочено: {}", item.subject),
                detail: format!(
                    "{} · срок {}",
                    first_line(&item.task),
                    datetime::format_short_date(item.due_date)
                ),
                color: COLOR_FAIL,
                tab: Tab::Homework,
            });
        }

        if let Some(semester) = self.semester() {
            for risk in analytics::detect_risks(&semester.grades) {
                if risk.risk_type != RiskType::FailDebt {
                    continue;
                }
                notices.push(Notice {
                    icon: "⚠",
                    group: "Требует внимания",
                    title: format!("Долг по предмету «{}»", risk.subject_name),
                    detail: risk.title.clone(),
                    color: COLOR_FAIL,
                    tab: Tab::Performance,
                });
            }
        }

        // ── замены и переносы из последнего обновления ──────────────────────
        if !self.changes.is_empty() {
            let count = self.changes.len();
            let first = &self.changes[0];
            notices.push(Notice {
                icon: "📅",
                group: "Расписание",
                title: format!(
                    "Изменения в расписании: {count} ({})",
                    datetime::format_short_date(first.date)
                ),
                detail: first.describe(),
                color: COLOR_SATISFACTORY,
                tab: Tab::Schedule,
            });
        }

        // ── на сегодня и завтра ─────────────────────────────────────────────
        for item in self
            .homework
            .iter()
            .filter(|item| !item.is_completed && item.due_date == today)
        {
            notices.push(Notice {
                icon: "📝",
                group: "На сегодня и завтра",
                title: format!("Сдать сегодня: {}", item.subject),
                detail: first_line(&item.task),
                color: COLOR_SATISFACTORY,
                tab: Tab::Homework,
            });
        }

        for item in self
            .homework
            .iter()
            .filter(|item| !item.is_completed && item.due_date == tomorrow)
        {
            notices.push(Notice {
                icon: "🔔",
                group: "На сегодня и завтра",
                title: format!("Завтра срок: {}", item.subject),
                detail: if item.notify_day_before {
                    format!("{} · напоминание включено", first_line(&item.task))
                } else {
                    first_line(&item.task)
                },
                color: COLOR_GOOD,
                tab: Tab::Homework,
            });
        }

        let today_lessons = self.lessons_on(today);
        if !today_lessons.is_empty() {
            let found = now_lessons(today_lessons, now);
            let detail = match (found.current, found.next) {
                (Some(current), _) => format!("Сейчас идёт «{}» до {}", current.clean_subject(), current.end_time),
                (None, Some(next)) => format!("Далее «{}» в {}", next.clean_subject(), next.start_time),
                (None, None) => "Пары на сегодня закончились.".to_string(),
            };
            notices.push(Notice {
                icon: "📅",
                group: "На сегодня и завтра",
                title: format!("Сегодня пар: {}", today_lessons.len()),
                detail,
                color: accent,
                tab: Tab::Schedule,
            });
        }

        let tomorrow_lessons = self.lessons_on(tomorrow);
        if !tomorrow_lessons.is_empty() {
            let first = tomorrow_lessons
                .first()
                .map(|lesson| format!("первая в {}", lesson.start_time))
                .unwrap_or_default();
            notices.push(Notice {
                icon: "➡",
                group: "На сегодня и завтра",
                title: format!("Завтра пар: {}", tomorrow_lessons.len()),
                detail: format!(
                    "{} · {}",
                    datetime::weekday_name_ru(tomorrow.weekday()).to_lowercase(),
                    first
                ),
                color: COLOR_NEUTRAL,
                tab: Tab::Schedule,
            });
        }

        // ── мелочи, но полезные ─────────────────────────────────────────────
        if let Some(semester) = self.semester() {
            let without_scores: Vec<&str> = semester
                .grades
                .iter()
                .filter(|grade| grade.scores.is_empty() && grade.kr_scores.is_empty())
                .map(|grade| grade.subject_name.as_str())
                .collect();
            if !without_scores.is_empty() {
                notices.push(Notice {
                    icon: "📊",
                    group: "Мелочи, но полезные",
                    title: format!("Без оценок: {}", without_scores.len()),
                    detail: format!(
                        "{} — добавьте оценки, иначе они не попадут в средний балл.",
                        without_scores.join(", ")
                    ),
                    color: COLOR_NEUTRAL,
                    tab: Tab::Performance,
                });
            }

            let low: Vec<&str> = semester
                .grades
                .iter()
                .filter(|grade| grade.effective_average() > 0.0 && grade.effective_average() < 4.0)
                .map(|grade| grade.subject_name.as_str())
                .collect();
            if !low.is_empty() {
                notices.push(Notice {
                    icon: "📉",
                    group: "Мелочи, но полезные",
                    title: format!("Ниже 4.0: {}", low.len()),
                    detail: low.join(", "),
                    color: COLOR_FAIL,
                    tab: Tab::Performance,
                });
            }

            let target_reached: Vec<&str> = semester
                .grades
                .iter()
                .filter(|grade| {
                    grade
                        .target_score
                        .map(|target| grade.effective_average() >= target)
                        .unwrap_or(false)
                })
                .map(|grade| grade.subject_name.as_str())
                .collect();
            if !target_reached.is_empty() {
                notices.push(Notice {
                    icon: "🎯",
                    group: "Мелочи, но полезные",
                    title: format!("Цель достигнута: {}", target_reached.len()),
                    detail: target_reached.join(", "),
                    color: COLOR_EXCELLENT,
                    tab: Tab::Performance,
                });
            }
        }

        if self.teachers.is_empty() && !self.raw_text.trim().is_empty() {
            notices.push(Notice {
                icon: "👤",
                group: "Мелочи, но полезные",
                title: "Преподаватели ещё не разобраны".to_string(),
                detail: "Обновите расписание — справочник собирается из его текста.".to_string(),
                color: COLOR_NEUTRAL,
                tab: Tab::Teachers,
            });
        }

        notices
    }
}

/// Карточка ленты; `true`, если нажали «перейти».
fn notice_card(ui: &mut egui::Ui, notice: &Notice) -> bool {
    let color = theme::color(notice.color);
    let mut clicked = false;

    card_with(ui, Some(theme::surface(dark(ui))), |ui| {
        ui.horizontal_top(|ui| {
            ui.label(egui::RichText::new(notice.icon).size(20.0));
            ui.vertical(|ui| {
                ui.label(
                    egui::RichText::new(&notice.title)
                        .size(14.0)
                        .strong()
                        .color(color),
                );
                if !notice.detail.is_empty() {
                    ui.label(
                        egui::RichText::new(&notice.detail)
                            .size(12.0)
                            .color(muted_color(ui)),
                    );
                }
            });
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Min), |ui| {
                if ui.button("перейти").clicked() {
                    clicked = true;
                }
                badge(ui, notice.tab.label(), muted_color(ui));
            });
        });
    });

    clicked
}

/// Первая строка задания: в ленте длинный текст не нужен.
fn first_line(task: &str) -> String {
    let line = task.lines().next().unwrap_or("").trim();
    if line.is_empty() {
        "описание не заполнено".to_string()
    } else if line.chars().count() > 90 {
        let cut: String = line.chars().take(90).collect();
        format!("{cut}…")
    } else {
        line.to_string()
    }
}

