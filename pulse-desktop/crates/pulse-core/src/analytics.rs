//! Аналитика успеваемости — порт `utils/PerformanceAnalytics.kt`.
//!
//! Отличие от Android-версии: в оригинале `buildDeepReport` возвращал пустые
//! `risks` и `recommendations`, хотя `detectRisks` и `generateRecommendations`
//! были реализованы. Здесь они подключены — это единственное намеренное
//! поведенческое изменение (баг оригинала, помеченный в docs/CODE_REVIEW.md).
//!
//! Цвета передаются числом `0xRRGGBB`, чтобы ядро не зависело от GUI-фреймворка.

use crate::performance::{
    format_score_by_mode, SubjectGrade, COLOR_EXCELLENT, COLOR_FAIL, COLOR_GOOD, COLOR_NEUTRAL,
    COLOR_ORANGE, COLOR_SATISFACTORY,
};
use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq)]
pub struct ScholarshipStatus {
    /// "9+", "8+", "7+", "6+", "< 6", "Долг", "—"
    pub tier: String,
    pub title: String,
    pub badge_text: String,
    pub description: String,
    pub current_gpa: f64,
    pub target_gpa: Option<f64>,
    pub gpa_needed: Option<f64>,
    pub progress: f32,
    pub color: u32,
    pub has_debts: bool,
    pub is_five_point_scale: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct AcademicStatus {
    pub title: String,
    pub badge_text: String,
    pub description: String,
    pub color: u32,
    pub advice: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct GradeDistribution {
    /// Количество оценок по баллам 1..=10 (`counts_by_score[0]` — единицы).
    pub counts_by_score: [u32; 10],
    pub excellent_count: u32,
    pub good_count: u32,
    pub satisfactory_count: u32,
    pub fail_count: u32,
    pub total_count: u32,
    pub regular_count: u32,
    pub kr_count: u32,
    /// Качество знаний: (9–10 + 6–8) / всего * 100.
    pub quality_of_knowledge_percent: f64,
    /// Успеваемость: (всего − неуд.) / всего * 100.
    pub absolute_pass_percent: f64,
}

impl GradeDistribution {
    pub fn count(&self, score: u32) -> u32 {
        if (1..=10).contains(&score) {
            self.counts_by_score[score as usize - 1]
        } else {
            0
        }
    }
}

impl Default for GradeDistribution {
    fn default() -> Self {
        Self {
            counts_by_score: [0; 10],
            excellent_count: 0,
            good_count: 0,
            satisfactory_count: 0,
            fail_count: 0,
            total_count: 0,
            regular_count: 0,
            kr_count: 0,
            quality_of_knowledge_percent: 0.0,
            absolute_pass_percent: 0.0,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct SubjectImpact {
    pub subject_name: String,
    pub effective_average: f64,
    pub delta_from_global_avg: f64,
    pub estimated_gpa_loss_or_gain: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RiskType {
    /// Средний балл < 4.0 — академическая задолженность.
    FailDebt,
    /// 4.0..4.3 — на грани срыва.
    BorderlineFail,
    /// 6.0..6.4 — чуть-чуть до хорошиста.
    BorderlineGood,
    /// 8.0..8.4 — чуть-чуть до отличника.
    BorderlineExcellent,
    /// Нет оценок вообще.
    NoGrades,
    /// КР значительно ниже текущих оценок.
    LowKr,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SubjectRisk {
    pub subject_name: String,
    pub risk_type: RiskType,
    /// 1 — критично, 2 — внимание, 3 — возможность.
    pub severity: u8,
    pub title: String,
    pub description: String,
    pub color: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrendDirection {
    Up,
    Down,
    Stable,
    NotEnoughData,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SubjectTrend {
    pub subject_name: String,
    pub direction: TrendDirection,
    pub first_half_avg: f64,
    pub second_half_avg: f64,
    pub description: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DiagnosticsSummary {
    pub overall_kr_avg: f64,
    pub overall_regular_avg: f64,
    pub kr_impact_description: String,
    pub kr_color: u32,
    pub stability_title: String,
    pub stability_description: String,
    pub stability_color: u32,
    pub outlier_note: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ActionRecommendation {
    pub priority: u32,
    pub badge: String,
    pub title: String,
    pub subject_name: String,
    pub action_text: String,
    pub potential_gpa_delta: Option<f64>,
    pub color: u32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SimulationResult {
    pub original_subject_avg: f64,
    pub simulated_subject_avg: f64,
    pub subject_delta: f64,
    pub original_global_avg: f64,
    pub simulated_global_avg: f64,
    pub global_delta: f64,
}

impl Default for SimulationResult {
    fn default() -> Self {
        Self {
            original_subject_avg: 0.0,
            simulated_subject_avg: 0.0,
            subject_delta: 0.0,
            original_global_avg: 0.0,
            simulated_global_avg: 0.0,
            global_delta: 0.0,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct DeepPerformanceReport {
    pub scholarship_status: ScholarshipStatus,
    pub academic_status: AcademicStatus,
    pub distribution: GradeDistribution,
    pub drivers: Vec<SubjectImpact>,
    pub anchors: Vec<SubjectImpact>,
    pub risks: Vec<SubjectRisk>,
    pub trends: Vec<SubjectTrend>,
    pub diagnostics: DiagnosticsSummary,
    pub recommendations: Vec<ActionRecommendation>,
}

fn round1(value: f64) -> f64 {
    (value * 10.0).round() / 10.0
}

fn round2(value: f64) -> f64 {
    (value * 100.0).round() / 100.0
}

fn active_grades(grades: &[SubjectGrade]) -> Vec<&SubjectGrade> {
    grades
        .iter()
        .filter(|grade| grade.effective_average() > 0.0)
        .collect()
}

fn global_average(grades: &[SubjectGrade], rounding_mode: i32) -> f64 {
    let active = active_grades(grades);
    if active.is_empty() {
        return 0.0;
    }
    let values: Vec<f64> = active
        .iter()
        .map(|grade| format_score_by_mode(grade.effective_average(), rounding_mode))
        .collect();
    values.iter().sum::<f64>() / values.len() as f64
}

pub fn calculate_distribution(grades: &[SubjectGrade]) -> GradeDistribution {
    let mut counts = [0u32; 10];
    let mut regular_count = 0u32;
    let mut kr_count = 0u32;

    for grade in grades {
        for score in &grade.scores {
            if (1..=10).contains(score) {
                counts[*score as usize - 1] += 1;
                regular_count += 1;
            }
        }
        for score in &grade.kr_scores {
            if (1..=10).contains(score) {
                counts[*score as usize - 1] += 1;
                kr_count += 1;
            }
        }
    }

    let total = regular_count + kr_count;
    let excellent = counts[8] + counts[9];
    let good = counts[5] + counts[6] + counts[7];
    let satisfactory = counts[3] + counts[4];
    let fail = counts[0] + counts[1] + counts[2];

    let quality = if total > 0 {
        (excellent + good) as f64 / total as f64 * 100.0
    } else {
        0.0
    };
    let pass = if total > 0 {
        (total - fail) as f64 / total as f64 * 100.0
    } else {
        0.0
    };

    GradeDistribution {
        counts_by_score: counts,
        excellent_count: excellent,
        good_count: good,
        satisfactory_count: satisfactory,
        fail_count: fail,
        total_count: total,
        regular_count,
        kr_count,
        quality_of_knowledge_percent: round1(quality),
        absolute_pass_percent: round1(pass),
    }
}

/// Категория стипендии по среднему баллу (10- и 5-балльные шкалы).
pub fn calculate_scholarship_status(global_avg: f64, grades: &[SubjectGrade]) -> ScholarshipStatus {
    let active = active_grades(grades);
    if grades.is_empty() || active.is_empty() {
        return ScholarshipStatus {
            tier: "—".into(),
            title: "Стипендия".into(),
            badge_text: "Нет оценок".into(),
            description: "Добавьте первые оценки по предметам для расчёта категории стипендии."
                .into(),
            current_gpa: 0.0,
            target_gpa: Some(6.0),
            gpa_needed: None,
            progress: 0.0,
            color: COLOR_NEUTRAL,
            has_debts: false,
            is_five_point_scale: false,
        };
    }

    let mut max_score = 0;
    for grade in grades {
        for score in grade.scores.iter().chain(grade.kr_scores.iter()) {
            max_score = max_score.max(*score);
        }
    }
    let is_five_point_scale = max_score <= 5 && global_avg <= 5.0;

    let min_pass_threshold = if is_five_point_scale { 3.0 } else { 4.0 };
    let debt_count = active
        .iter()
        .filter(|grade| grade.effective_average() < min_pass_threshold)
        .count();

    if debt_count > 0 {
        return ScholarshipStatus {
            tier: "Долг".into(),
            title: "Без стипендии".into(),
            badge_text: "Долг".into(),
            description: format!(
                "Есть академическая задолженность ({debt_count} предм.). Закройте долги для начисления стипендии."
            ),
            current_gpa: round2(global_avg),
            target_gpa: Some(if is_five_point_scale { 3.5 } else { 6.0 }),
            gpa_needed: None,
            progress: 0.0,
            color: COLOR_FAIL,
            has_debts: true,
            is_five_point_scale,
        };
    }

    let rounded_gpa = round2(global_avg);

    if !is_five_point_scale {
        // 10-балльная шкала.
        let (tier, title, badge, target, color, needed, progress) = if rounded_gpa >= 9.0 {
            (
                "9+",
                "Самая высокая стипендия",
                "9+",
                None,
                COLOR_EXCELLENT,
                Some(0.0),
                1.0,
            )
        } else if rounded_gpa >= 8.0 {
            (
                "8+",
                "Повышенная стипендия",
                "8+",
                Some(9.0),
                COLOR_GOOD,
                Some(round2(9.0 - rounded_gpa)),
                ((rounded_gpa - 8.0) as f32).clamp(0.0, 1.0),
            )
        } else if rounded_gpa >= 7.0 {
            (
                "7+",
                "Базовая стипендия",
                "7+",
                Some(8.0),
                COLOR_SATISFACTORY,
                Some(round2(8.0 - rounded_gpa)),
                ((rounded_gpa - 7.0) as f32).clamp(0.0, 1.0),
            )
        } else if rounded_gpa >= 6.0 {
            (
                "6+",
                "Минимальная стипендия",
                "6+",
                Some(7.0),
                COLOR_ORANGE,
                Some(round2(7.0 - rounded_gpa)),
                ((rounded_gpa - 6.0) as f32).clamp(0.0, 1.0),
            )
        } else {
            (
                "< 6",
                "Без стипендии",
                "< 6",
                Some(6.0),
                COLOR_FAIL,
                Some(round2(6.0 - rounded_gpa)),
                (rounded_gpa / 6.0) as f32,
            )
        };

        let description = if let Some(needed) = needed {
            if needed <= 0.0 {
                "Максимальный коэффициент стипендии! Отличная успеваемость.".to_string()
            } else {
                format!("До следующей категории осталось поднять GPA на {needed}.")
            }
        } else {
            "Максимальный коэффициент стипендии! Отличная успеваемость.".to_string()
        };

        return ScholarshipStatus {
            tier: tier.into(),
            title: title.into(),
            badge_text: badge.into(),
            description,
            current_gpa: rounded_gpa,
            target_gpa: target,
            gpa_needed: needed,
            progress: progress.clamp(0.0, 1.0),
            color,
            has_debts: false,
            is_five_point_scale: false,
        };
    }

    // 5-балльная шкала.
    let (tier, title, badge, target, color, needed, progress) = if rounded_gpa >= 4.8 {
        (
            "9+",
            "Самая высокая стипендия",
            "Отличник",
            None,
            COLOR_EXCELLENT,
            Some(0.0),
            1.0,
        )
    } else if rounded_gpa >= 4.5 {
        (
            "8+",
            "Повышенная стипендия",
            "4.5+",
            Some(4.8),
            COLOR_GOOD,
            Some(round2(4.8 - rounded_gpa)),
            ((rounded_gpa - 4.5) / 0.3) as f32,
        )
    } else if rounded_gpa >= 4.0 {
        (
            "7+",
            "Базовая стипендия",
            "4.0+",
            Some(4.5),
            COLOR_SATISFACTORY,
            Some(round2(4.5 - rounded_gpa)),
            ((rounded_gpa - 4.0) / 0.5) as f32,
        )
    } else if rounded_gpa >= 3.5 {
        (
            "6+",
            "Минимальная стипендия",
            "3.5+",
            Some(4.0),
            COLOR_ORANGE,
            Some(round2(4.0 - rounded_gpa)),
            ((rounded_gpa - 3.5) / 0.5) as f32,
        )
    } else {
        (
            "< 6",
            "Без стипендии",
            "< 3.5",
            Some(3.5),
            COLOR_FAIL,
            Some(round2(3.5 - rounded_gpa)),
            (rounded_gpa / 3.5) as f32,
        )
    };

    let description = match needed {
        Some(needed) if needed > 0.0 => {
            format!("До следующей категории осталось поднять GPA на {needed}.")
        }
        _ => "Максимальный коэффициент стипендии! Отличная успеваемость.".to_string(),
    };

    ScholarshipStatus {
        tier: tier.into(),
        title: title.into(),
        badge_text: badge.into(),
        description,
        current_gpa: rounded_gpa,
        target_gpa: target,
        gpa_needed: needed,
        progress: progress.clamp(0.0, 1.0),
        color,
        has_debts: false,
        is_five_point_scale: true,
    }
}

pub fn calculate_academic_status(scholarship: &ScholarshipStatus) -> AcademicStatus {
    AcademicStatus {
        title: scholarship.title.clone(),
        badge_text: scholarship.badge_text.clone(),
        description: scholarship.description.clone(),
        color: scholarship.color,
        advice: match scholarship.gpa_needed {
            Some(needed) if needed > 0.0 => {
                format!("Поднимите GPA на {needed} для перехода на следующий уровень.")
            }
            _ => "У вас наивысшая категория стипендии!".to_string(),
        },
    }
}

pub fn calculate_drivers_and_anchors(
    grades: &[SubjectGrade],
    global_avg: f64,
) -> (Vec<SubjectImpact>, Vec<SubjectImpact>) {
    let active = active_grades(grades);
    if active.is_empty() {
        return (Vec::new(), Vec::new());
    }
    let count = active.len() as f64;

    let mut impacts: Vec<SubjectImpact> = active
        .iter()
        .map(|grade| {
            let average = grade.effective_average();
            let delta = average - global_avg;
            SubjectImpact {
                subject_name: grade.subject_name.clone(),
                effective_average: round2(average),
                delta_from_global_avg: round2(delta),
                estimated_gpa_loss_or_gain: round2((delta / count).abs()),
            }
        })
        .collect();

    impacts.sort_by(|a, b| {
        b.delta_from_global_avg
            .partial_cmp(&a.delta_from_global_avg)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let drivers: Vec<SubjectImpact> = impacts
        .iter()
        .filter(|impact| impact.delta_from_global_avg > 0.0)
        .take(3)
        .cloned()
        .collect();

    let mut anchors: Vec<SubjectImpact> =
        impacts.into_iter().filter(|impact| impact.delta_from_global_avg < 0.0).collect();
    anchors.sort_by(|a, b| {
        a.delta_from_global_avg
            .partial_cmp(&b.delta_from_global_avg)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    anchors.truncate(3);

    (drivers, anchors)
}

/// Риски по предметам (в Android-версии функция была написана, но не вызывалась).
pub fn detect_risks(grades: &[SubjectGrade]) -> Vec<SubjectRisk> {
    let mut risks: Vec<SubjectRisk> = Vec::new();

    for grade in grades {
        let average = grade.effective_average();

        if average > 0.0 && average < 4.0 {
            risks.push(SubjectRisk {
                subject_name: grade.subject_name.clone(),
                risk_type: RiskType::FailDebt,
                severity: 1,
                title: format!("Угроза неаттестации ({})", round1(average)),
                description: "Средний балл ниже порога 4.0. Предмет считается несданным.".into(),
                color: COLOR_FAIL,
            });
        } else if (4.0..=4.3).contains(&average) {
            risks.push(SubjectRisk {
                subject_name: grade.subject_name.clone(),
                risk_type: RiskType::BorderlineFail,
                severity: 2,
                title: format!("На грани срыва ({})", round1(average)),
                description: "Любая новая тройка или двойка опустит предмет в долги.".into(),
                color: COLOR_SATISFACTORY,
            });
        } else if (6.0..=6.4).contains(&average) {
            risks.push(SubjectRisk {
                subject_name: grade.subject_name.clone(),
                risk_type: RiskType::BorderlineGood,
                severity: 3,
                title: format!("Близко к «Хорошо» ({})", round1(average)),
                description: "Не хватает всего 0.1–0.4 балла для перехода на 7+ баллов.".into(),
                color: COLOR_GOOD,
            });
        } else if average == 0.0 {
            risks.push(SubjectRisk {
                subject_name: grade.subject_name.clone(),
                risk_type: RiskType::NoGrades,
                severity: 2,
                title: "Нет оценок".into(),
                description:
                    "По предмету нет текущих отметок — риск накопления долгов к концу семестра."
                        .into(),
                color: COLOR_NEUTRAL,
            });
        }

        if grade.kr_average() > 0.0
            && grade.average() > 0.0
            && (grade.average() - grade.kr_average()) >= 1.5
        {
            risks.push(SubjectRisk {
                subject_name: grade.subject_name.clone(),
                risk_type: RiskType::LowKr,
                severity: 2,
                title: format!("Провал КР ({})", round1(grade.kr_average())),
                description: format!(
                    "Балл за контрольные существенно ниже текущих ({}). КР тянет вниз.",
                    round1(grade.average())
                ),
                color: 0xFFFF9100,
            });
        }
    }

    risks.sort_by_key(|risk| risk.severity);
    risks
}

pub fn calculate_trends(grades: &[SubjectGrade]) -> Vec<SubjectTrend> {
    let mut trends: Vec<SubjectTrend> = Vec::new();

    for grade in grades {
        if grade.scores.len() < 4 {
            continue;
        }
        let half = grade.scores.len() / 2;
        let first_half = grade.scores[..half].iter().map(|v| *v as f64).sum::<f64>() / half as f64;
        let second_len = grade.scores.len() - half;
        let second_half = grade.scores[half..]
            .iter()
            .map(|v| *v as f64)
            .sum::<f64>()
            / second_len as f64;
        let diff = second_half - first_half;

        let direction = if diff >= 0.5 {
            TrendDirection::Up
        } else if diff <= -0.5 {
            TrendDirection::Down
        } else {
            TrendDirection::Stable
        };

        let description = match direction {
            TrendDirection::Up => format!(
                "Оценки растут: с {} до {} 📈",
                round1(first_half),
                round1(second_half)
            ),
            TrendDirection::Down => format!(
                "Спад результатов: с {} до {} 📉",
                round1(first_half),
                round1(second_half)
            ),
            TrendDirection::Stable => {
                format!("Стабильный уровень ({}) ➡", round1(second_half))
            }
            TrendDirection::NotEnoughData => "Мало данных".to_string(),
        };

        trends.push(SubjectTrend {
            subject_name: grade.subject_name.clone(),
            direction,
            first_half_avg: round1(first_half),
            second_half_avg: round1(second_half),
            description,
        });
    }

    trends.sort_by_key(|trend| match trend.direction {
        TrendDirection::Down => 0,
        TrendDirection::Up => 1,
        TrendDirection::Stable => 2,
        TrendDirection::NotEnoughData => 3,
    });
    trends
}

pub fn calculate_diagnostics(grades: &[SubjectGrade]) -> DiagnosticsSummary {
    let all_kr: Vec<i32> = grades
        .iter()
        .filter(|grade| !grade.kr_scores.is_empty())
        .flat_map(|grade| grade.kr_scores.iter().copied())
        .collect();
    let all_regular: Vec<i32> = grades
        .iter()
        .flat_map(|grade| grade.scores.iter().copied())
        .collect();

    let kr_avg = if all_kr.is_empty() {
        0.0
    } else {
        all_kr.iter().map(|v| *v as f64).sum::<f64>() / all_kr.len() as f64
    };
    let reg_avg = if all_regular.is_empty() {
        0.0
    } else {
        all_regular.iter().map(|v| *v as f64).sum::<f64>() / all_regular.len() as f64
    };

    let (kr_desc, kr_color) = if all_kr.is_empty() {
        (
            "Контрольные работы пока не выставлялись".to_string(),
            COLOR_NEUTRAL,
        )
    } else if kr_avg < reg_avg - 0.7 {
        (
            format!(
                "КР заметно снижают общий балл ({} против {} за устные ответы). Уделите внимание подготовке к письменным срезам!",
                round1(kr_avg),
                round1(reg_avg)
            ),
            COLOR_FAIL,
        )
    } else if kr_avg > reg_avg + 0.5 {
        (
            format!(
                "КР вытягивают средний балл ({} против {}). Письменные работы даются лучше ответов на парах!",
                round1(kr_avg),
                round1(reg_avg)
            ),
            COLOR_EXCELLENT,
        )
    } else {
        (
            format!(
                "Оценки за КР ({}) и текущие ({}) сбалансированы.",
                round1(kr_avg),
                round1(reg_avg)
            ),
            COLOR_GOOD,
        )
    };

    let mut combined: Vec<f64> = all_regular
        .iter()
        .chain(all_kr.iter())
        .map(|v| *v as f64)
        .collect();

    let (stability_title, stability_description, stability_color) = if combined.len() >= 4 {
        let mean = combined.iter().sum::<f64>() / combined.len() as f64;
        let variance = combined
            .iter()
            .map(|value| (value - mean) * (value - mean))
            .sum::<f64>()
            / combined.len() as f64;
        let std_dev = variance.sqrt();

        if std_dev <= 1.1 {
            (
                "Высокая стабильность",
                format!(
                    "Разброс оценок минимален (±{}). Вы учитесь предсказуемо и ровно.",
                    round1(std_dev)
                ),
                COLOR_EXCELLENT,
            )
        } else if std_dev <= 1.8 {
            (
                "Умеренный разброс",
                format!(
                    "Оценки немного колеблются (±{}), что естественно для учебного процесса.",
                    round1(std_dev)
                ),
                COLOR_GOOD,
            )
        } else {
            (
                "Высокая волатильность",
                format!(
                    "Оценки сильно скачут (±{}): от двоек до девяток. Нет предсказуемости.",
                    round1(std_dev)
                ),
                COLOR_SATISFACTORY,
            )
        }
    } else {
        (
            "Недостаточно данных",
            "Для оценки стабильности нужно больше оценок.".to_string(),
            COLOR_NEUTRAL,
        )
    };
    combined.clear();

    let outliers: Vec<&SubjectGrade> = grades
        .iter()
        .filter(|grade| grade.effective_average() >= 6.5 && grade.scores.iter().any(|s| *s <= 3))
        .collect();

    let outlier_note = if outliers.is_empty() {
        None
    } else {
        let names = outliers
            .iter()
            .take(2)
            .map(|grade| grade.subject_name.as_str())
            .collect::<Vec<_>>()
            .join(", ");
        Some(format!(
            "Разовые двойки тянут вниз сильные предметы ({names}). Исправление одной оценки заметно поднимет средний балл."
        ))
    };

    DiagnosticsSummary {
        overall_kr_avg: round2(kr_avg),
        overall_regular_avg: round2(reg_avg),
        kr_impact_description: kr_desc,
        kr_color,
        stability_title: stability_title.to_string(),
        stability_description,
        stability_color,
        outlier_note,
    }
}

/// Рекомендации по исправлению ситуации (в Android-версии не вызывались).
pub fn generate_recommendations(
    grades: &[SubjectGrade],
    risks: &[SubjectRisk],
) -> Vec<ActionRecommendation> {
    let mut list: Vec<ActionRecommendation> = Vec::new();
    let mut priority = 1u32;

    for risk in risks.iter().filter(|r| r.risk_type == RiskType::FailDebt) {
        list.push(ActionRecommendation {
            priority,
            badge: "Срочно".into(),
            title: format!("Закрыть долг по {}", risk.subject_name),
            subject_name: risk.subject_name.clone(),
            action_text:
                "Получите оценку не ниже 6, чтобы выйти из зоны риска неаттестации.".into(),
            potential_gpa_delta: Some(0.35),
            color: COLOR_FAIL,
        });
        priority += 1;
    }

    for risk in risks
        .iter()
        .filter(|r| r.risk_type == RiskType::LowKr)
        .take(2)
    {
        let kr_avg = grades
            .iter()
            .find(|grade| grade.subject_name == risk.subject_name)
            .map(|grade| round1(grade.kr_average()))
            .unwrap_or(0.0);
        list.push(ActionRecommendation {
            priority,
            badge: "Контрольная".into(),
            title: format!("Пересдать КР: {}", risk.subject_name),
            subject_name: risk.subject_name.clone(),
            action_text: format!(
                "Балл за КР составляет {kr_avg}. Пересдача на 8 даст мощный буст баллу предмета."
            ),
            potential_gpa_delta: Some(0.25),
            color: 0xFFFF9100,
        });
        priority += 1;
    }

    for risk in risks
        .iter()
        .filter(|r| r.risk_type == RiskType::BorderlineGood)
        .take(2)
    {
        let Some(grade) = grades
            .iter()
            .find(|grade| grade.subject_name == risk.subject_name)
        else {
            continue;
        };
        let sum: f64 = grade.scores.iter().map(|v| *v as f64).sum();
        let needed = (6.5 * (grade.scores.len() as f64 + 1.0) - sum).ceil() as i64;
        let needed = needed.clamp(7, 10);
        list.push(ActionRecommendation {
            priority,
            badge: "Быстрый рост".into(),
            title: format!("Дожать до «Хорошо»: {}", risk.subject_name),
            subject_name: risk.subject_name.clone(),
            action_text: format!(
                "Текущий балл {}. Достаточно получить одну «{needed}», чтобы зафиксировать статус.",
                round1(grade.effective_average())
            ),
            potential_gpa_delta: Some(0.15),
            color: COLOR_GOOD,
        });
        priority += 1;
    }

    let empty: Vec<&SubjectRisk> = risks
        .iter()
        .filter(|r| r.risk_type == RiskType::NoGrades)
        .collect();
    if !empty.is_empty() {
        let names = empty
            .iter()
            .take(2)
            .map(|risk| risk.subject_name.as_str())
            .collect::<Vec<_>>()
            .join(", ");
        list.push(ActionRecommendation {
            priority,
            badge: "Профилактика".into(),
            title: format!("Получить первые оценки: {names}"),
            subject_name: names,
            action_text: "Не откладывайте на конец семестра, чтобы не образовался завал.".into(),
            potential_gpa_delta: None,
            color: COLOR_NEUTRAL,
        });
    }

    list.truncate(4);
    list
}

/// Полный отчёт для экрана успеваемости.
pub fn build_deep_report(grades: &[SubjectGrade], rounding_mode: i32) -> DeepPerformanceReport {
    let average = global_average(grades, rounding_mode);
    let distribution = calculate_distribution(grades);
    let scholarship = calculate_scholarship_status(average, grades);
    let status = calculate_academic_status(&scholarship);
    let (drivers, anchors) = calculate_drivers_and_anchors(grades, average);
    let risks = detect_risks(grades);
    let trends = calculate_trends(grades);
    let diagnostics = calculate_diagnostics(grades);
    let recommendations = generate_recommendations(grades, &risks);

    DeepPerformanceReport {
        scholarship_status: scholarship,
        academic_status: status,
        distribution,
        drivers,
        anchors,
        risks,
        trends,
        diagnostics,
        recommendations,
    }
}

/// Симуляция: что будет с баллами, если добавить такие оценки.
pub fn simulate_grades(
    grades: &[SubjectGrade],
    target_subject_id: &str,
    added_regular: &[i32],
    added_kr: &[i32],
    rounding_mode: i32,
) -> SimulationResult {
    let Some(target) = grades.iter().find(|grade| grade.id == target_subject_id) else {
        return SimulationResult::default();
    };

    let original_subject_avg = target.effective_average();
    let mut new_scores = target.scores.clone();
    new_scores.extend_from_slice(added_regular);
    let mut new_kr = target.kr_scores.clone();
    new_kr.extend_from_slice(added_kr);

    let new_subject = SubjectGrade {
        scores: new_scores,
        kr_scores: new_kr,
        ..target.clone()
    };
    let new_subject_avg = new_subject.effective_average();

    let original_global = global_average(grades, rounding_mode);
    let simulated: Vec<SubjectGrade> = grades
        .iter()
        .map(|grade| {
            if grade.id == target_subject_id {
                new_subject.clone()
            } else {
                grade.clone()
            }
        })
        .collect();
    let simulated_global = global_average(&simulated, rounding_mode);

    SimulationResult {
        original_subject_avg: round2(original_subject_avg),
        simulated_subject_avg: round2(new_subject_avg),
        subject_delta: round2(new_subject_avg - original_subject_avg),
        original_global_avg: round2(original_global),
        simulated_global_avg: round2(simulated_global),
        global_delta: round2(simulated_global - original_global),
    }
}

/// Распределение оценок семестра — удобная обёртка для графиков в UI.
pub fn grade_histogram(grades: &[SubjectGrade]) -> BTreeMap<u32, u32> {
    let mut histogram = BTreeMap::new();
    for score in 1..=10u32 {
        histogram.insert(score, 0);
    }
    for grade in grades {
        for score in grade.scores.iter().chain(grade.kr_scores.iter()) {
            if (1..=10).contains(score) {
                *histogram.entry(*score as u32).or_insert(0) += 1;
            }
        }
    }
    histogram
}

#[cfg(test)]
mod tests {
    use super::*;

    fn grade(name: &str, scores: &[i32], kr: &[i32]) -> SubjectGrade {
        SubjectGrade {
            id: name.to_string(),
            subject_name: name.to_string(),
            teacher: "-".into(),
            scores: scores.to_vec(),
            target_score: None,
            kr_scores: kr.to_vec(),
        }
    }

    #[test]
    fn empty_grades_give_neutral_scholarship() {
        let report = build_deep_report(&[], 1);
        assert_eq!(report.scholarship_status.tier, "—");
        assert!(report.drivers.is_empty());
        assert!(report.trends.is_empty());
        assert_eq!(report.distribution.total_count, 0);
    }

    #[test]
    fn scholarship_tiers_for_ten_point_scale() {
        let nine = vec![grade("A", &[9, 9], &[])];
        assert_eq!(calculate_scholarship_status(9.0, &nine).tier, "9+");

        let eight = vec![grade("A", &[8, 8], &[])];
        assert_eq!(calculate_scholarship_status(8.0, &eight).tier, "8+");

        let seven = vec![grade("A", &[7, 7], &[])];
        assert_eq!(calculate_scholarship_status(7.0, &seven).tier, "7+");

        let six = vec![grade("A", &[6, 6], &[])];
        assert_eq!(calculate_scholarship_status(6.0, &six).tier, "6+");

        // Чтобы шкала осталась 10-балльной (есть оценки выше 5), но GPA < 6.
        let low = vec![grade("A", &[6, 6], &[]), grade("B", &[5, 5], &[])];
        assert_eq!(calculate_scholarship_status(5.5, &low).tier, "< 6");
    }

    #[test]
    fn debts_block_scholarship() {
        let grades = vec![grade("A", &[9, 9], &[]), grade("B", &[3, 3], &[])];
        let status = calculate_scholarship_status(6.0, &grades);
        assert_eq!(status.tier, "Долг");
        assert!(status.has_debts);
        assert_eq!(status.color, COLOR_FAIL);
    }

    #[test]
    fn five_point_scale_is_detected() {
        let grades = vec![grade("A", &[5, 5], &[]), grade("B", &[4, 5], &[])];
        let status = calculate_scholarship_status(4.75, &grades);
        assert!(status.is_five_point_scale);
        assert_eq!(status.tier, "8+");
    }

    #[test]
    fn distribution_counts_regular_and_kr() {
        let grades = vec![grade("A", &[9, 5, 3], &[10]), grade("B", &[7], &[])];
        let distribution = calculate_distribution(&grades);
        assert_eq!(distribution.total_count, 5);
        assert_eq!(distribution.regular_count, 4);
        assert_eq!(distribution.kr_count, 1);
        assert_eq!(distribution.excellent_count, 2);
        assert_eq!(distribution.good_count, 1);
        assert_eq!(distribution.satisfactory_count, 1);
        assert_eq!(distribution.fail_count, 1);
        assert_eq!(distribution.count(9), 1);
        assert_eq!(distribution.absolute_pass_percent, 80.0);
    }

    #[test]
    fn risks_are_detected_and_sorted() {
        let grades = vec![
            grade("Долг", &[3, 3], &[]),
            grade("Грань", &[4, 4], &[]),
            grade("Пусто", &[], &[]),
            grade("КР", &[8, 8], &[4, 4]),
        ];
        let risks = detect_risks(&grades);
        assert!(risks.iter().any(|r| r.risk_type == RiskType::FailDebt));
        assert!(risks.iter().any(|r| r.risk_type == RiskType::BorderlineFail));
        assert!(risks.iter().any(|r| r.risk_type == RiskType::NoGrades));
        assert!(risks.iter().any(|r| r.risk_type == RiskType::LowKr));
        assert_eq!(risks.first().unwrap().severity, 1, "критичное — выше");
    }

    #[test]
    fn recommendations_prioritize_debts() {
        let grades = vec![grade("Долг", &[3, 3], &[])];
        let risks = detect_risks(&grades);
        let recommendations = generate_recommendations(&grades, &risks);
        assert_eq!(recommendations[0].badge, "Срочно");
        assert_eq!(recommendations[0].priority, 1);
        assert!(recommendations.len() <= 4);
    }

    #[test]
    fn drivers_and_anchors_split_by_global_average() {
        let grades = vec![
            grade("Сильный", &[10, 10], &[]),
            grade("Слабый", &[5, 5], &[]),
            grade("Средний", &[7, 7], &[]),
        ];
        let average = global_average(&grades, 1);
        let (drivers, anchors) = calculate_drivers_and_anchors(&grades, average);
        assert_eq!(drivers.first().unwrap().subject_name, "Сильный");
        assert_eq!(anchors.first().unwrap().subject_name, "Слабый");
        assert!(drivers.iter().all(|d| d.delta_from_global_avg > 0.0));
        assert!(anchors.iter().all(|a| a.delta_from_global_avg < 0.0));
    }

    #[test]
    fn trends_need_four_scores() {
        let grades = vec![grade("A", &[5, 5, 9, 9], &[]), grade("B", &[7], &[])];
        let trends = calculate_trends(&grades);
        assert_eq!(trends.len(), 1);
        assert_eq!(trends[0].direction, TrendDirection::Up);
    }

    #[test]
    fn diagnostics_reports_volatility() {
        let grades = vec![grade("A", &[2, 3, 9, 10], &[])];
        let diagnostics = calculate_diagnostics(&grades);
        assert_eq!(diagnostics.stability_title, "Высокая волатильность");
        assert!(diagnostics.outlier_note.is_none(), "средний балл ещё не 6.5");

        let strong = vec![grade("A", &[2, 8, 8, 8], &[])];
        assert!(calculate_diagnostics(&strong).outlier_note.is_some());
    }

    #[test]
    fn simulation_raises_global_average() {
        let grades = vec![grade("A", &[6, 6], &[]), grade("B", &[8, 8], &[])];
        let target = grades[0].id.clone();
        let result = simulate_grades(&grades, &target, &[10], &[], 1);
        assert!(result.simulated_subject_avg > result.original_subject_avg);
        assert!(result.global_delta > 0.0);
    }

    #[test]
    fn simulation_with_unknown_id_is_zeroed() {
        let grades = vec![grade("A", &[6], &[])];
        let result = simulate_grades(&grades, "нет", &[10], &[], 1);
        assert_eq!(result, SimulationResult::default());
    }

    #[test]
    fn histogram_has_all_ten_buckets() {
        let histogram = grade_histogram(&[grade("A", &[5, 5, 10], &[])]);
        assert_eq!(histogram.len(), 10);
        assert_eq!(histogram.get(&5), Some(&2));
        assert_eq!(histogram.get(&10), Some(&1));
        assert_eq!(histogram.get(&1), Some(&0));
    }
}