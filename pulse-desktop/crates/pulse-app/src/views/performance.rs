//! Вкладка «Успеваемость»: предметы, стипендия, распределение и аналитика.

use eframe::egui;
use pulse_core::analytics::{self, RiskType, TrendDirection};
use pulse_core::performance::{
    grade_visual, needed_grades_text, Semester, SubjectGrade, ROUNDING_HUNDREDTHS, ROUNDING_WHOLE,
};

use crate::app::{parse_scores, PulseApp, StatusKind};
use crate::theme;
use crate::views::{
    bar, bar_width, badge, card, dark, empty_state, metric, muted, muted_color, score_chip,
    section_title, subject_visual,
};

/// Действия над предметами, собранные во время отрисовки.
enum GradeAction {
    Edit(String),
    Delete(String),
    AddScore(usize, i32),
    AddKr(usize, i32),
    RemoveScore(usize, usize),
    RemoveKr(usize, usize),
    SetTarget(usize, f64),
}

impl PulseApp {
    pub(crate) fn ui_performance(&mut self, ui: &mut egui::Ui) {
        if self.semesters.is_empty() {
            self.semesters.push(Semester::new("1 семестр"));
        }
        self.active_semester = self.active_semester.min(self.semesters.len() - 1);

        ui.horizontal(|ui| {
            ui.label(egui::RichText::new("Успеваемость").size(22.0).strong());

            let current = self
                .semesters
                .get(self.active_semester)
                .map(|semester| semester.name.clone())
                .unwrap_or_default();
            let mut chosen: Option<usize> = None;
            let semester_label = ui.label(
                egui::RichText::new("Семестр")
                    .size(12.0)
                    .color(muted_color(ui)),
            );
            let combo = egui::ComboBox::from_id_salt("semester-picker")
                .selected_text(current)
                .width(160.0)
                .show_ui(ui, |ui| {
                    for (index, semester) in self.semesters.iter().enumerate() {
                        if ui
                            .selectable_label(index == self.active_semester, &semester.name)
                            .clicked()
                        {
                            chosen = Some(index);
                        }
                    }
                });
            combo.response.labelled_by(semester_label.id);
            if let Some(index) = chosen {
                self.active_semester = index;
            }

            if ui.button("+ семестр").clicked() {
                let name = format!("{} семестр", self.semesters.len() + 1);
                self.semesters.push(Semester::new(name));
                self.active_semester = self.semesters.len() - 1;
                self.save_semesters();
            }
        });

        // Переименование семестра.
        let mut name = self
            .semesters
            .get(self.active_semester)
            .map(|semester| semester.name.clone())
            .unwrap_or_default();
        ui.horizontal(|ui| {
            let name_label = muted(ui, "Название:");
            if ui
                .add(egui::TextEdit::singleline(&mut name).desired_width(180.0))
                .labelled_by(name_label.id)
                .changed()
            {
                if let Some(semester) = self.semester_mut() {
                    semester.name = name.clone();
                }
                self.save_semesters();
            }
        });
        ui.add_space(8.0);

        let mut actions: Vec<GradeAction> = Vec::new();
        let grades: Vec<SubjectGrade> = self
            .semester()
            .map(|semester| semester.grades.clone())
            .unwrap_or_default();

        if grades.is_empty() {
            empty_state(
                ui,
                "📊",
                "Оценок пока нет",
                "Добавьте предметы и оценки — приложение посчитает средний балл, стипендию и риски.",
            );
            if ui.button("+ Добавить предмет").clicked() {
                self.open_subject_dialog(None);
            }
            return;
        }

        let mode = self.settings.rounding_mode;
        let report = analytics::build_deep_report(&grades, mode);

        self.ui_performance_summary(ui, &grades, &report, mode);

        section_title(ui, "Предметы");
        ui.horizontal(|ui| {
            if ui.button("+ Предмет").clicked() {
                self.open_subject_dialog(None);
            }
            ui.label(
                egui::RichText::new(format!(
                    "Средний балл показан в режиме: {}",
                    rounding_label(mode)
                ))
                .size(12.0)
                .color(muted_color(ui)),
            );
        });
        ui.add_space(4.0);

        for (index, grade) in grades.iter().enumerate() {
            subject_card(ui, index, grade, mode, &mut actions);
        }

        self.ui_performance_analytics(ui, &grades, &report, mode, &mut actions);

        for action in actions {
            match action {
                GradeAction::Edit(id) => self.open_subject_dialog(Some(&id)),
                GradeAction::Delete(id) => {
                    if let Some(semester) = self.semester_mut() {
                        semester.grades.retain(|grade| grade.id != id);
                    }
                    self.save_semesters();
                    self.set_status(StatusKind::Success, "Предмет удалён.");
                }
                GradeAction::AddScore(index, value) => {
                    if let Some(grade) = self.grade_mut(index) {
                        grade.scores.push(value);
                    }
                    self.save_semesters();
                }
                GradeAction::AddKr(index, value) => {
                    if let Some(grade) = self.grade_mut(index) {
                        grade.kr_scores.push(value);
                    }
                    self.save_semesters();
                }
                GradeAction::RemoveScore(index, position) => {
                    if let Some(grade) = self.grade_mut(index) {
                        if position < grade.scores.len() {
                            grade.scores.remove(position);
                        }
                    }
                    self.save_semesters();
                }
                GradeAction::RemoveKr(index, position) => {
                    if let Some(grade) = self.grade_mut(index) {
                        if position < grade.kr_scores.len() {
                            grade.kr_scores.remove(position);
                        }
                    }
                    self.save_semesters();
                }
                GradeAction::SetTarget(index, target) => {
                    if let Some(grade) = self.grade_mut(index) {
                        grade.target_score = if grade.target_score == Some(target) {
                            None
                        } else {
                            Some(target)
                        };
                    }
                    self.save_semesters();
                }
            }
        }
    }

    fn grade_mut(&mut self, index: usize) -> Option<&mut SubjectGrade> {
        self.semester_mut()
            .and_then(|semester| semester.grades.get_mut(index))
    }

    fn ui_performance_summary(
        &mut self,
        ui: &mut egui::Ui,
        grades: &[SubjectGrade],
        report: &analytics::DeepPerformanceReport,
        mode: i32,
    ) {
        let semester_avg = self
            .semester()
            .map(|semester| semester.global_average(mode))
            .unwrap_or(0.0);
        let visual = grade_visual(semester_avg);
        let accent = theme::color(self.settings.accent);

        card(ui, |ui| {
            ui.horizontal(|ui| {
                metric(
                    ui,
                    "Средний балл",
                    format!("{:.2}", semester_avg),
                    theme::color(visual.color),
                );
                ui.add_space(24.0);
                let scored = grades
                    .iter()
                    .filter(|grade| grade.effective_average() > 0.0)
                    .count();
                metric(
                    ui,
                    "Предметов с оценками",
                    format!("{scored} из {}", grades.len()),
                    theme::text(dark(ui)),
                );
                ui.add_space(24.0);
                metric(
                    ui,
                    "Оценок всего",
                    report.distribution.total_count.to_string(),
                    theme::text(dark(ui)),
                );
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    badge(ui, &report.scholarship_status.badge_text, theme::color(report.scholarship_status.color));
                });
            });

            ui.add_space(8.0);
            ui.label(
                egui::RichText::new(&report.scholarship_status.title)
                    .size(15.0)
                    .strong()
                    .color(theme::color(report.scholarship_status.color)),
            );
            muted(ui, &report.scholarship_status.description);
            ui.add_space(6.0);
            bar(
                ui,
                report.scholarship_status.progress,
                theme::color(report.scholarship_status.color),
                10.0,
            );
            ui.add_space(4.0);
            if let Some(target) = report.scholarship_status.target_gpa {
                muted(
                    ui,
                    format!(
                        "Текущий GPA {:.2} · следующая планка {:.2}",
                        report.scholarship_status.current_gpa, target
                    ),
                );
            }
        });

        // Распределение оценок.
        let distribution = &report.distribution;
        let max_count = distribution
            .counts_by_score
            .iter()
            .copied()
            .max()
            .unwrap_or(0)
            .max(1);
        card(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(egui::RichText::new("Распределение оценок").strong());
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    muted(
                        ui,
                        format!(
                            "качество знаний {:.1}% · успеваемость {:.1}%",
                            distribution.quality_of_knowledge_percent,
                            distribution.absolute_pass_percent
                        ),
                    );
                });
            });
            ui.add_space(6.0);
            for score in (1..=10u32).rev() {
                let count = distribution.count(score);
                ui.horizontal(|ui| {
                    ui.label(
                        egui::RichText::new(format!("{score:>2}"))
                            .monospace()
                            .color(muted_color(ui)),
                    );
                    // Оставляем место справа под число оценок.
                    let width = (ui.available_width() - 34.0).max(60.0);
                    bar_width(
                        ui,
                        width,
                        count as f32 / max_count as f32,
                        theme::color(crate::views::score_color(score as i32)),
                        9.0,
                    );
                    ui.label(
                        egui::RichText::new(count.to_string())
                            .monospace()
                            .size(12.0)
                            .color(muted_color(ui)),
                    );
                });
            }
            ui.add_space(4.0);
            // Легенда по уровням, как в Android-версии (`GradeDistributionCard`).
            muted(
                ui,
                format!(
                    "9–10: {} · 6–8: {} · 4–5: {} · 1–3: {}",
                    distribution.excellent_count,
                    distribution.good_count,
                    distribution.satisfactory_count,
                    distribution.fail_count
                ),
            );
            let _ = accent;
        });
    }

    fn ui_performance_analytics(
        &mut self,
        ui: &mut egui::Ui,
        grades: &[SubjectGrade],
        report: &analytics::DeepPerformanceReport,
        mode: i32,
        actions: &mut Vec<GradeAction>,
    ) {
        section_title(ui, "Аналитика");

        // Риски.
        card(ui, |ui| {
            ui.label(egui::RichText::new("Риски").strong());
            ui.add_space(4.0);
            if report.risks.is_empty() {
                muted(ui, "Рисков не обнаружено — так держать.");
            } else {
                for risk in &report.risks {
                    ui.horizontal_wrapped(|ui| {
                        badge(
                            ui,
                            &format!("{} · {}", risk_label(risk.risk_type), risk.subject_name),
                            theme::color(risk.color),
                        );
                        ui.label(egui::RichText::new(&risk.title).size(12.0));
                    });
                    ui.label(
                        egui::RichText::new(&risk.description)
                            .size(11.0)
                            .color(muted_color(ui)),
                    );
                    ui.add_space(4.0);
                }
            }
        });

        // Какие предметы тянут вверх и вниз.
        if !report.drivers.is_empty() || !report.anchors.is_empty() {
            card(ui, |ui| {
                ui.label(egui::RichText::new("Что влияет на средний балл").strong());
                ui.add_space(4.0);
                for impact in &report.drivers {
                    ui.horizontal_wrapped(|ui| {
                        badge(
                            ui,
                            "тянет вверх",
                            theme::color(pulse_core::performance::COLOR_EXCELLENT),
                        );
                        ui.label(
                            egui::RichText::new(format!(
                                "{}: {:.2} (+{:.2} к GPA)",
                                impact.subject_name,
                                impact.effective_average,
                                impact.estimated_gpa_loss_or_gain
                            ))
                            .size(12.0),
                        );
                    });
                }
                for impact in &report.anchors {
                    ui.horizontal_wrapped(|ui| {
                        badge(ui, "тянет вниз", theme::color(pulse_core::performance::COLOR_FAIL));
                        ui.label(
                            egui::RichText::new(format!(
                                "{}: {:.2} (−{:.2} к GPA)",
                                impact.subject_name,
                                impact.effective_average,
                                impact.estimated_gpa_loss_or_gain
                            ))
                            .size(12.0),
                        );
                    });
                }
            });
        }

        // Тренды.
        if !report.trends.is_empty() {
            card(ui, |ui| {
                ui.label(egui::RichText::new("Тренды").strong());
                ui.add_space(4.0);
                for trend in &report.trends {
                    let (arrow, color) = match trend.direction {
                        TrendDirection::Up => {
                            ("📈", pulse_core::performance::COLOR_EXCELLENT)
                        }
                        TrendDirection::Down => ("📉", pulse_core::performance::COLOR_FAIL),
                        TrendDirection::Stable => ("➡", pulse_core::performance::COLOR_GOOD),
                        TrendDirection::NotEnoughData => {
                            ("…", pulse_core::performance::COLOR_NEUTRAL)
                        }
                    };
                    ui.horizontal_wrapped(|ui| {
                        ui.label(egui::RichText::new(arrow).size(14.0));
                        ui.label(egui::RichText::new(&trend.subject_name).strong());
                        ui.label(
                            egui::RichText::new(&trend.description)
                                .size(12.0)
                                .color(theme::color(color)),
                        );
                    });
                }
            });
        }

        // Диагностика.
        let diagnostics = &report.diagnostics;
        card(ui, |ui| {
            ui.label(egui::RichText::new("Диагностика").strong());
            ui.add_space(4.0);
            ui.label(
                egui::RichText::new(&diagnostics.kr_impact_description)
                    .size(12.0)
                    .color(theme::color(diagnostics.kr_color)),
            );
            ui.label(
                egui::RichText::new(&diagnostics.stability_description)
                    .size(12.0)
                    .color(theme::color(diagnostics.stability_color)),
            );
            ui.horizontal_wrapped(|ui| {
                badge(
                    ui,
                    &diagnostics.stability_title,
                    theme::color(diagnostics.stability_color),
                );
                if diagnostics.overall_kr_avg > 0.0 {
                    badge(
                        ui,
                        &format!("КР: {:.2}", diagnostics.overall_kr_avg),
                        muted_color(ui),
                    );
                }
                if diagnostics.overall_regular_avg > 0.0 {
                    badge(
                        ui,
                        &format!("текущие: {:.2}", diagnostics.overall_regular_avg),
                        muted_color(ui),
                    );
                }
            });
            if let Some(note) = &diagnostics.outlier_note {
                ui.add_space(4.0);
                muted(ui, note);
            }
        });

        // Рекомендации.
        if !report.recommendations.is_empty() {
            card(ui, |ui| {
                ui.label(egui::RichText::new("Что сделать").strong());
                ui.add_space(4.0);
                for recommendation in &report.recommendations {
                    ui.horizontal_wrapped(|ui| {
                        badge(ui, &recommendation.badge, theme::color(recommendation.color));
                        ui.label(egui::RichText::new(&recommendation.title).strong().size(13.0));
                    });
                    ui.label(
                        egui::RichText::new(&recommendation.action_text)
                            .size(12.0)
                            .color(muted_color(ui)),
                    );
                    ui.add_space(6.0);
                }
            });
        }

        // Симуляция: что будет, если получить ещё одну оценку.
        card(ui, |ui| {
            ui.label(egui::RichText::new("Симуляция").strong());
            muted(
                ui,
                "Выберите предмет и введите оценки, которые планируете получить — покажем, как изменится балл.",
            );
            ui.add_space(6.0);

            let selected_name = self
                .simulation_subject
                .as_ref()
                .filter(|id| grades.iter().any(|grade| &grade.id == *id))
                .and_then(|id| grades.iter().find(|grade| &grade.id == id))
                .map(|grade| grade.subject_name.clone())
                .unwrap_or_else(|| "выбрать предмет".to_string());

            let mut chosen: Option<String> = None;
            let subject_label = ui.label(
                egui::RichText::new("Предмет")
                    .size(12.0)
                    .color(muted_color(ui)),
            );
            let combo = egui::ComboBox::from_id_salt("sim-subject")
                .selected_text(selected_name)
                .width(220.0)
                .show_ui(ui, |ui| {
                    for grade in grades {
                        let selected = self
                            .simulation_subject
                            .as_deref()
                            .map(|id| id == grade.id)
                            .unwrap_or(false);
                        if ui.selectable_label(selected, &grade.subject_name).clicked() {
                            chosen = Some(grade.id.clone());
                        }
                    }
                });
            combo.response.labelled_by(subject_label.id);
            if let Some(id) = chosen {
                self.simulation_subject = Some(id);
            }

            ui.horizontal(|ui| {
                ui.add(
                    egui::TextEdit::singleline(&mut self.simulate_input)
                        .hint_text("например 10 или 9 10")
                        .desired_width(200.0),
                );
                muted(ui, "оценки, которые вы планируете получить");
            });

            let planned = parse_scores(&self.simulate_input);
            if let Some(id) = self.simulation_subject.clone() {
                if !planned.is_empty() {
                    let result = analytics::simulate_grades(grades, &id, &planned, &[], mode);
                    ui.add_space(6.0);
                    ui.horizontal_wrapped(|ui| {
                        badge(
                            ui,
                            &format!(
                                "предмет {:.2} → {:.2}",
                                result.original_subject_avg, result.simulated_subject_avg
                            ),
                            theme::color(if result.subject_delta >= 0.0 {
                                pulse_core::performance::COLOR_EXCELLENT
                            } else {
                                pulse_core::performance::COLOR_FAIL
                            }),
                        );
                        badge(
                            ui,
                            &format!(
                                "средний {:.2} → {:.2} ({:+.2})",
                                result.original_global_avg,
                                result.simulated_global_avg,
                                result.global_delta
                            ),
                            theme::color(pulse_core::performance::COLOR_GOOD),
                        );
                    });

                    // Кнопка «применить» — сразу добавить оценки в предмет.
                    let target_index = grades.iter().position(|grade| grade.id == id);
                    if let Some(index) = target_index {
                        if ui.button("Добавить эти оценки").clicked() {
                            for value in &planned {
                                actions.push(GradeAction::AddScore(index, *value));
                            }
                            self.simulate_input.clear();
                        }
                    }
                }
            }
        });
    }
}

fn subject_card(
    ui: &mut egui::Ui,
    index: usize,
    grade: &SubjectGrade,
    mode: i32,
    actions: &mut Vec<GradeAction>,
) {
    let (emoji, color) = subject_visual(&grade.subject_name);
    let visual = grade_visual(grade.effective_average());

    card(ui, |ui| {
        ui.horizontal(|ui| {
            ui.label(egui::RichText::new(emoji).size(20.0));
            ui.vertical(|ui| {
                ui.label(
                    egui::RichText::new(&grade.subject_name)
                        .size(15.0)
                        .strong()
                        .color(color),
                );
                let teacher = if grade.teacher.trim().is_empty() || grade.teacher == "-" {
                    "преподаватель не указан".to_string()
                } else {
                    grade.teacher.clone()
                };
                muted(ui, teacher);
            });
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                // Текстовые кнопки: монохромные эмодзи-иконки в мелком кегле нечитаемы.
                if ui.small_button("удалить").clicked() {
                    actions.push(GradeAction::Delete(grade.id.clone()));
                }
                if ui.small_button("изменить").clicked() {
                    actions.push(GradeAction::Edit(grade.id.clone()));
                }
                badge(
                    ui,
                    &format!("{} · {}", visual.label, format_average(grade.effective_average(), mode)),
                    theme::color(visual.color),
                );
                badge(ui, &format_average(grade.effective_average(), mode), theme::color(visual.color));
            });
        });

        ui.add_space(6.0);

        ui.horizontal_wrapped(|ui| {
            muted(ui, "Оценки:");
            if grade.scores.is_empty() {
                muted(ui, "нет");
            }
            let scores = grade.scores.clone();
            for (position, score) in scores.iter().enumerate() {
                if score_chip(ui, *score, true) {
                    actions.push(GradeAction::RemoveScore(index, position));
                }
            }
        });

        ui.horizontal_wrapped(|ui| {
            muted(ui, "добавить:");
            for value in 1..=10 {
                if ui.small_button(value.to_string()).clicked() {
                    actions.push(GradeAction::AddScore(index, value));
                }
            }
        });

        ui.add_space(2.0);
        egui::CollapsingHeader::new(format!("Контрольные работы ({})", grade.kr_scores.len()))
            // Без соли одинаковые заголовки соседних предметов получают один и тот же ID.
            .id_salt(("kr", &grade.id))
            .default_open(false)
            .show(ui, |ui| {
                ui.horizontal_wrapped(|ui| {
                    if grade.kr_scores.is_empty() {
                        muted(ui, "оценок за КР нет");
                    }
                    let kr = grade.kr_scores.clone();
                    for (position, score) in kr.iter().enumerate() {
                        if score_chip(ui, *score, true) {
                            actions.push(GradeAction::RemoveKr(index, position));
                        }
                    }
                });
                ui.horizontal_wrapped(|ui| {
                    muted(ui, "добавить:");
                    for value in 1..=10 {
                        if ui.small_button(value.to_string()).clicked() {
                            actions.push(GradeAction::AddKr(index, value));
                        }
                    }
                });
            });

        ui.add_space(2.0);
        ui.horizontal_wrapped(|ui| {
            muted(ui, "цель:");
            for target in [7.0, 8.0, 9.0, 10.0] {
                let selected = grade.target_score.map(|value| (value - target).abs() < 0.01).unwrap_or(false);
                if ui.selectable_label(selected, format!("{target:.0}")).clicked() {
                    actions.push(GradeAction::SetTarget(index, target));
                }
            }
            if let Some(target) = grade.target_score {
                let hint = needed_grades_text(grade, target);
                let color = if hint.contains("Достигнуто") || hint.contains("достигнуто") {
                    theme::color(pulse_core::performance::COLOR_EXCELLENT)
                } else if hint.contains("Невозможно") || hint.contains("Недостижимо") {
                    theme::color(pulse_core::performance::COLOR_FAIL)
                } else {
                    theme::color(pulse_core::performance::COLOR_SATISFACTORY)
                };
                ui.label(egui::RichText::new(hint).size(12.0).color(color));
            }
        });
    });
}

fn rounding_label(mode: i32) -> &'static str {
    match mode {
        ROUNDING_WHOLE => "целые числа",
        ROUNDING_HUNDREDTHS => "сотые",
        _ => "десятые",
    }
}

fn risk_label(kind: RiskType) -> &'static str {
    match kind {
        RiskType::FailDebt => "долг",
        RiskType::BorderlineFail => "на грани",
        RiskType::BorderlineGood => "до хорошиста",
        RiskType::BorderlineExcellent => "до отличника",
        RiskType::NoGrades => "нет оценок",
        RiskType::LowKr => "низкие КР",
    }
}

/// Средний балл с точностью, которую выбрал пользователь.
fn format_average(value: f64, mode: i32) -> String {
    match mode {
        ROUNDING_WHOLE => format!("{:.0}", pulse_core::performance::custom_round(value)),
        ROUNDING_HUNDREDTHS => format!("{:.2}", value),
        _ => format!("{:.1}", value),
    }
}