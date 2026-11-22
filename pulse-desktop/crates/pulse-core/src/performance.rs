//! Успеваемость — порт `PerformanceViewModel.kt` (модели и средние)
//! и `formatScoreByMode` / `getNeededGradesText` из `PerformanceScreen.kt`.

use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Цвета оценок — те же, что в `PerformanceAnalytics.kt`.
pub const COLOR_EXCELLENT: u32 = 0xFF4CAF50;
pub const COLOR_GOOD: u32 = 0xFF2196F3;
pub const COLOR_SATISFACTORY: u32 = 0xFFFFC107;
pub const COLOR_FAIL: u32 = 0xFFF44336;
pub const COLOR_NEUTRAL: u32 = 0xFF9090A0;
pub const COLOR_ORANGE: u32 = 0xFFFF9800;

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct SubjectGrade {
    pub id: String,
    pub subject_name: String,
    pub teacher: String,
    pub scores: Vec<i32>,
    /// Целевой балл пользователя (может быть `null`).
    pub target_score: Option<f64>,
    /// Оценки за контрольные работы.
    pub kr_scores: Vec<i32>,
}

impl SubjectGrade {
    pub fn new(subject_name: impl Into<String>, teacher: impl Into<String>) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            subject_name: subject_name.into(),
            teacher: teacher.into(),
            ..Default::default()
        }
    }

    pub fn average(&self) -> f64 {
        average(&self.scores)
    }

    pub fn kr_average(&self) -> f64 {
        average(&self.kr_scores)
    }

    /// Как в Kotlin: пустой список даёт 0.0, а не `NaN`.
    pub fn effective_average(&self) -> f64 {
        let base = self.average();
        let kr = self.kr_average();
        match (base == 0.0, kr == 0.0) {
            (true, true) => 0.0,
            (true, false) => kr,
            (false, true) => base,
            (false, false) => (base + kr) / 2.0,
        }
    }
}

fn average(values: &[i32]) -> f64 {
    if values.is_empty() {
        0.0
    } else {
        values.iter().map(|v| *v as f64).sum::<f64>() / values.len() as f64
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Semester {
    pub id: String,
    pub name: String,
    pub grades: Vec<SubjectGrade>,
}

impl Semester {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            name: name.into(),
            grades: Vec::new(),
        }
    }

    /// Средний балл семестра по предметам, у которых есть оценки.
    pub fn global_average(&self, rounding_mode: i32) -> f64 {
        let active: Vec<f64> = self
            .grades
            .iter()
            .map(|grade| grade.effective_average())
            .filter(|value| *value > 0.0)
            .map(|value| format_score_by_mode(value, rounding_mode))
            .collect();
        if active.is_empty() {
            0.0
        } else {
            active.iter().sum::<f64>() / active.len() as f64
        }
    }

    /// Индекс предмета по `id`.
    pub fn index_of(&self, id: &str) -> Option<usize> {
        self.grades.iter().position(|grade| grade.id == id)
    }
}

/// Режим отображения среднего балла: 0 — целое, 1 — десятые, 2 — сотые.
pub const ROUNDING_WHOLE: i32 = 0;
pub const ROUNDING_TENTHS: i32 = 1;
pub const ROUNDING_HUNDREDTHS: i32 = 2;

/// `formatScoreByMode` из `PerformanceScreen.kt`.
pub fn format_score_by_mode(score: f64, mode: i32) -> f64 {
    if score.is_nan() || score == 0.0 {
        return 0.0;
    }
    match mode {
        ROUNDING_WHOLE => custom_round(score),
        ROUNDING_HUNDREDTHS => (score * 100.0).round() / 100.0,
        _ => (score * 10.0).round() / 10.0,
    }
}

/// `customRound`: 4.49 → 4, 4.5 → 5 (округление вверх по дробной части).
pub fn custom_round(value: f64) -> f64 {
    let integer_part = value.trunc();
    let fractional_part = value - integer_part;
    if fractional_part >= 0.5 {
        integer_part + 1.0
    } else {
        integer_part
    }
}

/// Сколько и каких оценок нужно для целевого балла (`getNeededGradesText`).
pub fn needed_grades_text(grade: &SubjectGrade, target: f64) -> String {
    if target > 10.0 {
        return "Невозможно".to_string();
    }
    if grade.effective_average() >= target {
        return "Достигнуто!".to_string();
    }

    let kr_avg = grade.kr_average();
    let required_base_avg = if grade.kr_scores.is_empty() {
        target
    } else {
        2.0 * target - kr_avg
    };

    if required_base_avg > 10.0 {
        return "Невозможно".to_string();
    }
    if required_base_avg <= 0.0 {
        return "Уже достигнуто!".to_string();
    }

    let sum: i64 = grade.scores.iter().map(|v| *v as i64).sum();
    let count = grade.scores.len() as f64;

    if count == 0.0 {
        let required_int = required_base_avg.ceil() as i64;
        if required_int > 10 {
            return "Невозможно".to_string();
        }
        return format!("Нужно: «{required_int}»x1");
    }

    let calc = |value: f64| -> Option<u32> {
        if value <= required_base_avg {
            return None;
        }
        let needed = (required_base_avg * count - sum as f64) / (value - required_base_avg);
        if !needed.is_finite() || needed <= 0.0 {
            return None;
        }
        Some(needed.ceil() as u32)
    };

    let mut options: Vec<String> = Vec::new();
    for value in [10.0, 9.0, 8.0] {
        if let Some(need) = calc(value) {
            if need < 25 {
                options.push(format!("«{}»x{}", value as i64, need));
            }
        }
    }

    if options.is_empty() {
        if let Some(need) = calc(10.0) {
            return format!("Нужно ОЧЕНЬ много 10-к ({need} шт)");
        }
        return "Недостижимо".to_string();
    }

    options.join(" или ")
}

/// Визуальная оценка балла — как `getGradeVisual` в `PerformanceScreen.kt`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GradeVisual {
    pub color: u32,
    pub label: &'static str,
}

pub fn grade_visual(score: f64) -> GradeVisual {
    if score >= 8.5 {
        GradeVisual {
            color: COLOR_EXCELLENT,
            label: "Отлично",
        }
    } else if score >= 6.5 {
        GradeVisual {
            color: COLOR_GOOD,
            label: "Хорошо",
        }
    } else if score >= 4.0 {
        GradeVisual {
            color: COLOR_SATISFACTORY,
            label: "Удовл.",
        }
    } else if score > 0.0 {
        GradeVisual {
            color: COLOR_FAIL,
            label: "Неуд.",
        }
    } else {
        GradeVisual {
            color: COLOR_NEUTRAL,
            label: "Нет",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn grade(scores: &[i32], kr: &[i32]) -> SubjectGrade {
        SubjectGrade {
            id: "id".into(),
            subject_name: "Математика".into(),
            teacher: "Иванов И.И.".into(),
            scores: scores.to_vec(),
            target_score: None,
            kr_scores: kr.to_vec(),
        }
    }

    #[test]
    fn averages_follow_kotlin_rules() {
        assert_eq!(grade(&[], &[]).effective_average(), 0.0);
        assert_eq!(grade(&[8, 9], &[]).effective_average(), 8.5);
        assert_eq!(grade(&[], &[10]).effective_average(), 10.0);
        // (8 + 6) / 2 = 7
        assert_eq!(grade(&[8], &[6]).effective_average(), 7.0);
    }

    #[test]
    fn rounding_modes_match_android() {
        assert_eq!(format_score_by_mode(7.44, ROUNDING_WHOLE), 7.0);
        assert_eq!(format_score_by_mode(7.5, ROUNDING_WHOLE), 8.0);
        assert_eq!(format_score_by_mode(7.456, ROUNDING_TENTHS), 7.5);
        assert_eq!(format_score_by_mode(7.456, ROUNDING_HUNDREDTHS), 7.46);
        assert_eq!(format_score_by_mode(0.0, ROUNDING_TENTHS), 0.0);
    }

    #[test]
    fn needed_grades_text_cases() {
        assert_eq!(needed_grades_text(&grade(&[], &[]), 11.0), "Невозможно");
        assert_eq!(needed_grades_text(&grade(&[9, 9], &[]), 8.0), "Достигнуто!");
        assert_eq!(needed_grades_text(&grade(&[], &[]), 8.0), "Нужно: «8»x1");
        let text = needed_grades_text(&grade(&[7, 7, 7], &[]), 8.0);
        assert!(text.contains("«10»") || text.contains("«9»"), "получено: {text}");
    }

    #[test]
    fn grade_visual_thresholds() {
        assert_eq!(grade_visual(9.0).label, "Отлично");
        assert_eq!(grade_visual(7.0).label, "Хорошо");
        assert_eq!(grade_visual(4.5).label, "Удовл.");
        assert_eq!(grade_visual(2.0).label, "Неуд.");
        assert_eq!(grade_visual(0.0).label, "Нет");
    }

    #[test]
    fn semester_json_matches_android_schema() {
        let semester = Semester {
            id: "s1".into(),
            name: "1 семестр".into(),
            grades: vec![SubjectGrade {
                id: "g1".into(),
                subject_name: "Физика".into(),
                teacher: "Петров П.П.".into(),
                scores: vec![8, 9],
                target_score: Some(9.0),
                kr_scores: vec![7],
            }],
        };
        let json = serde_json::to_string(&semester).unwrap();
        for key in ["\"subjectName\"", "\"krScores\"", "\"targetScore\":9.0"] {
            assert!(json.contains(key), "нет ключа {key} в {json}");
        }
        let back: Semester = serde_json::from_str(&json).unwrap();
        assert_eq!(back, semester);
    }

    #[test]
    fn legacy_single_kr_score_is_tolerated() {
        // Android умел читать старое поле "krScore" — оно просто игнорируется.
        let json = r#"{"id":"g1","subjectName":"Физика","teacher":"-","scores":[8],"krScore":7}"#;
        let parsed: SubjectGrade = serde_json::from_str(json).unwrap();
        assert_eq!(parsed.scores, vec![8]);
        assert!(parsed.kr_scores.is_empty());
    }
}