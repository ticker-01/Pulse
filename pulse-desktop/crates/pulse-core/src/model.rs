//! Модели предметной области — порт `LessonPair` / `Teacher` из `ScheduleViewModel.kt`.
//!
//! Имена полей в JSON совпадают с Android-версией (`pairNumber`, `startTime`, …),
//! поэтому файлы кэша и экспорт из мобильного приложения читаются как есть.

use chrono::{NaiveDate, NaiveTime};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

use crate::datetime;

/// Кэш расписания: дата → пары этого дня.
pub type ScheduleCache = BTreeMap<NaiveDate, Vec<LessonPair>>;

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LessonPair {
    pub pair_number: u32,
    /// Номера пар внутри блока, строкой: `"1"`, `"2, 3"`.
    pub lessons: String,
    pub start_time: String,
    pub end_time: String,
    pub subject: String,
    pub room: String,
    pub teacher: String,
    pub group: String,
}

impl LessonPair {
    pub fn start_local_time(&self) -> Option<NaiveTime> {
        datetime::parse_time(&self.start_time)
    }

    pub fn end_local_time(&self) -> Option<NaiveTime> {
        datetime::parse_time(&self.end_time)
    }

    /// `subject.substringBefore("(").substringBefore(",").trim()`.
    pub fn clean_subject(&self) -> &str {
        self.subject
            .split('(')
            .next()
            .unwrap_or("")
            .split(',')
            .next()
            .unwrap_or("")
            .trim()
    }

    /// Номера пар из поля `lessons`.
    pub fn lesson_numbers(&self) -> Vec<u32> {
        self.lessons
            .split(|c: char| c == ',' || c.is_whitespace())
            .filter_map(|part| part.trim().parse::<u32>().ok())
            .collect()
    }

    /// "08:30 – 09:15"; если время неизвестно — "??:??".
    pub fn time_range(&self) -> String {
        let start = if self.start_time.is_empty() {
            "??:??"
        } else {
            self.start_time.as_str()
        };
        let end = if self.end_time.is_empty() {
            "??:??"
        } else {
            self.end_time.as_str()
        };
        format!("{start} – {end}")
    }

    /// Пары, которые ещё не закончились (для блока «сейчас / дальше»).
    pub fn is_finished(&self, now: NaiveTime) -> bool {
        match self.end_local_time() {
            Some(end) => now > end,
            None => false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Teacher {
    pub name: String,
    pub subject: String,
    pub room: String,
    pub email: String,
    /// Цвет аватара в формате `0xRRGGBB` — как `Long` в Android-версии.
    pub avatar_color: u32,
}

impl Default for Teacher {
    fn default() -> Self {
        Self {
            name: String::new(),
            subject: String::new(),
            room: String::new(),
            email: "-".to_string(),
            avatar_color: 0xFF6C48E1,
        }
    }
}

/// Идущая и следующая пары на день.
#[derive(Debug, Clone, Default)]
pub struct NowLessons<'a> {
    pub current: Option<&'a LessonPair>,
    pub next: Option<&'a LessonPair>,
}

/// Как `ScheduleScreen`: ищем текущую пару и ближайшую следующую.
///
/// Пары без распознанного времени считаются «не начавшимися» и могут попасть
/// только в `next` — так же ведёт себя Android-версия (там `LocalTime.MIN`).
pub fn now_lessons<'a>(lessons: &'a [LessonPair], now: NaiveTime) -> NowLessons<'a> {
    let mut current = None;
    let mut next = None;

    for lesson in lessons {
        let (Some(start), Some(end)) = (lesson.start_local_time(), lesson.end_local_time()) else {
            if next.is_none() {
                next = Some(lesson);
            }
            continue;
        };

        if now >= start && now <= end {
            current = Some(lesson);
        } else if now < start && next.is_none() {
            next = Some(lesson);
        }
    }

    NowLessons { current, next }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair(start: &str, end: &str, subject: &str) -> LessonPair {
        LessonPair {
            pair_number: 1,
            lessons: "1".into(),
            start_time: start.into(),
            end_time: end.into(),
            subject: subject.into(),
            room: "101".into(),
            teacher: "Иванов И.И.".into(),
            group: "21э".into(),
        }
    }

    #[test]
    fn clean_subject_cuts_parenthesis_and_comma() {
        let mut lesson = pair("08:30", "09:15", "Математика (алгебра)");
        assert_eq!(lesson.clean_subject(), "Математика");
        lesson.subject = "Физика, астрономия".into();
        assert_eq!(lesson.clean_subject(), "Физика");
    }

    #[test]
    fn lesson_numbers_parses_blocks() {
        let mut lesson = pair("08:30", "09:15", "Математика");
        lesson.lessons = "2, 3".into();
        assert_eq!(lesson.lesson_numbers(), vec![2, 3]);
        lesson.lessons = "4".into();
        assert_eq!(lesson.lesson_numbers(), vec![4]);
        lesson.lessons = String::new();
        assert!(lesson.lesson_numbers().is_empty());
    }

    #[test]
    fn now_lessons_finds_current_and_next() {
        let lessons = vec![
            pair("08:30", "09:15", "Математика"),
            pair("09:25", "10:10", "Физика"),
        ];
        let now = datetime::parse_time("09:00").unwrap();
        let found = now_lessons(&lessons, now);
        assert_eq!(found.current.map(|l| l.subject.as_str()), Some("Математика"));
        assert_eq!(found.next.map(|l| l.subject.as_str()), Some("Физика"));

        let between = datetime::parse_time("09:20").unwrap();
        let found = now_lessons(&lessons, between);
        assert!(found.current.is_none());
        assert_eq!(found.next.map(|l| l.subject.as_str()), Some("Физика"));
    }
}