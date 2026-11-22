//! Домашние задания — порт `data/HomeworkManager.kt` и хелперов из `ScheduleViewModel`.
//!
//! Модель и имена полей в JSON совпадают с Android-версией, поэтому файл
//! `homework.json` можно перенести с телефона на десктоп как есть.

use chrono::{NaiveDate, NaiveTime};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::model::{LessonPair, ScheduleCache};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct HomeworkItem {
    pub id: String,
    pub subject: String,
    pub task: String,
    pub due_date: NaiveDate,
    #[serde(default = "default_true")]
    pub notify_day_before: bool,
    pub is_completed: bool,
    pub assign_date: NaiveDate,
    /// Сколько пар по предмету осталось до сдачи (для «осталось N пар»).
    pub target_pairs: Option<u32>,
}

fn default_true() -> bool {
    true
}

impl Default for HomeworkItem {
    fn default() -> Self {
        let today = chrono::Local::now().date_naive();
        Self {
            id: Uuid::new_v4().to_string(),
            subject: String::new(),
            task: String::new(),
            due_date: today,
            notify_day_before: true,
            is_completed: false,
            assign_date: today,
            target_pairs: None,
        }
    }
}

impl HomeworkItem {
    pub fn new(subject: impl Into<String>, task: impl Into<String>, due_date: NaiveDate) -> Self {
        Self {
            subject: subject.into(),
            task: task.into(),
            ..Self::default()
        }
        .with_due(due_date)
    }

    pub fn with_due(mut self, due_date: NaiveDate) -> Self {
        self.due_date = due_date;
        self
    }

    /// `subject.substringBefore("(").trim().lowercase()` — ключ сравнения с парами.
    pub fn subject_key(&self) -> String {
        self.subject
            .split('(')
            .next()
            .unwrap_or("")
            .trim()
            .to_lowercase()
    }

    pub fn is_overdue(&self, today: NaiveDate) -> bool {
        !self.is_completed && self.due_date < today
    }

    /// Сортировка как в `loadHomeworks`: невыполненные раньше, внутри — по сроку.
    pub fn sort_items(items: &mut [HomeworkItem]) {
        items.sort_by(|a, b| a.is_completed.cmp(&b.is_completed).then(a.due_date.cmp(&b.due_date)));
    }
}

/// Добавляет или обновляет задание по `id`.
pub fn upsert(items: &mut Vec<HomeworkItem>, item: HomeworkItem) {
    match items.iter_mut().find(|existing| existing.id == item.id) {
        Some(existing) => *existing = item,
        None => items.push(item),
    }
    HomeworkItem::sort_items(items);
}

pub fn delete(items: &mut Vec<HomeworkItem>, id: &str) -> bool {
    let before = items.len();
    items.retain(|item| item.id != id);
    items.len() != before
}

/// Переключает «выполнено»; возвращает новое состояние.
pub fn toggle_completed(items: &mut [HomeworkItem], id: &str) -> Option<bool> {
    let item = items.iter_mut().find(|item| item.id == id)?;
    item.is_completed = !item.is_completed;
    let completed = item.is_completed;
    HomeworkItem::sort_items(items);
    Some(completed)
}

/// Удаляет все выполненные; возвращает количество удалённых.
pub fn delete_completed(items: &mut Vec<HomeworkItem>) -> usize {
    let before = items.len();
    items.retain(|item| !item.is_completed);
    before - items.len()
}

/// Сколько пар по предмету ДЗ уже прошло и сколько осталось (`getRemainingPairs`).
pub fn remaining_pairs(
    homework: &HomeworkItem,
    cache: &ScheduleCache,
    today: NaiveDate,
    now: NaiveTime,
) -> Option<u32> {
    let target = homework.target_pairs?;
    let subject_key = homework.subject_key();
    let mut passed: u32 = 0;

    for (date, lessons) in cache {
        if *date < homework.assign_date || *date > today {
            continue;
        }
        let relevant: Vec<&LessonPair> = if *date == today {
            lessons
                .iter()
                .filter(|lesson| lesson.start_local_time().unwrap_or(NaiveTime::MIN) < now)
                .collect()
        } else {
            lessons.iter().collect()
        };
        passed += relevant
            .iter()
            .filter(|lesson| lesson.clean_subject().to_lowercase() == subject_key)
            .count() as u32;
    }

    Some(target.saturating_sub(passed))
}

/// Сколько пар по предмету пройдёт до срока сдачи (`countLessonsUntil`).
pub fn count_lessons_until(
    subject: &str,
    due_date: NaiveDate,
    cache: &ScheduleCache,
    today: NaiveDate,
    now: NaiveTime,
) -> u32 {
    if due_date < today {
        return 0;
    }
    let subject_key = subject
        .split('(')
        .next()
        .unwrap_or("")
        .trim()
        .to_lowercase();

    let mut count = 0;
    for (date, lessons) in cache {
        if *date >= today && *date < due_date {
            let relevant: Vec<&LessonPair> = if *date == today {
                lessons
                    .iter()
                    .filter(|lesson| lesson.start_local_time().unwrap_or(NaiveTime::MIN) > now)
                    .collect()
            } else {
                lessons.iter().collect()
            };
            count += relevant
                .iter()
                .filter(|lesson| lesson.clean_subject().to_lowercase() == subject_key)
                .count() as u32;
        }
    }
    count
}

/// Дата следующего занятия по предмету (`findDateForNextSubject`).
pub fn find_date_for_next_subject(
    subject: &str,
    skip_lessons: u32,
    cache: &ScheduleCache,
    today: NaiveDate,
    now: NaiveTime,
) -> Option<NaiveDate> {
    let subject_key = subject
        .split('(')
        .next()
        .unwrap_or("")
        .trim()
        .to_lowercase();
    let mut matched: u32 = 0;

    for (date, lessons) in cache.iter().filter(|(date, _)| **date >= today) {
        let relevant: Vec<&LessonPair> = if *date == today {
            lessons
                .iter()
                .filter(|lesson| lesson.start_local_time().unwrap_or(NaiveTime::MIN) > now)
                .collect()
        } else {
            lessons.iter().collect()
        };
        if relevant
            .iter()
            .any(|lesson| lesson.clean_subject().to_lowercase() == subject_key)
        {
            if matched == skip_lessons {
                return Some(*date);
            }
            matched += 1;
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::LessonPair;
    use std::collections::BTreeMap;

    fn date(y: i32, m: u32, d: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, d).unwrap()
    }

    fn pair(lesson: u32, subject: &str) -> LessonPair {
        let start = match lesson {
            1 => "08:30",
            2 => "09:25",
            3 => "10:20",
            _ => "11:15",
        };
        LessonPair {
            pair_number: lesson,
            lessons: lesson.to_string(),
            start_time: start.into(),
            end_time: "09:15".into(),
            subject: subject.into(),
            room: "101".into(),
            teacher: "Иванов И.И.".into(),
            group: "21э".into(),
        }
    }

    #[test]
    fn upsert_updates_existing_and_sorts() {
        let mut items = Vec::new();
        let mut first = HomeworkItem::new("Математика", "№ 5", date(2026, 11, 25));
        let second = HomeworkItem::new("Физика", "Лаба", date(2026, 11, 23));
        upsert(&mut items, first.clone());
        upsert(&mut items, second.clone());
        assert_eq!(items[0].subject, "Физика", "срок раньше — выше");

        first.task = "№ 5, 6".into();
        upsert(&mut items, first.clone());
        assert_eq!(items.len(), 2);
        assert_eq!(
            items.iter().find(|i| i.id == first.id).unwrap().task,
            "№ 5, 6"
        );
    }

    #[test]
    fn toggling_moves_item_after_uncompleted() {
        let mut items = vec![
            HomeworkItem::new("Физика", "Лаба", date(2026, 11, 23)),
            HomeworkItem::new("Математика", "№ 5", date(2026, 11, 25)),
        ];
        let first_id = items[0].id.clone();
        assert_eq!(toggle_completed(&mut items, &first_id), Some(true));
        assert_eq!(items[1].subject, "Физика");
        assert_eq!(toggle_completed(&mut items, "нет-такого"), None);
    }

    #[test]
    fn counts_remaining_pairs_for_today() {
        let today = date(2026, 11, 23);
        let mut cache: ScheduleCache = BTreeMap::new();
        cache.insert(today, vec![pair(1, "Математика"), pair(2, "Физика"), pair(3, "Математика")]);

        let mut homework = HomeworkItem::new("Математика (алгебра)", "№ 5", date(2026, 11, 25));
        homework.assign_date = today;
        homework.target_pairs = Some(3);

        // До первой пары ещё не дошло — прошло 0 пар, осталось 3.
        let early = crate::datetime::parse_time("08:00").unwrap();
        assert_eq!(remaining_pairs(&homework, &cache, today, early), Some(3));

        // После второй пары прошло две пары «Математика»? Нет: одна (08:30) и одна позже.
        let mid = crate::datetime::parse_time("09:30").unwrap();
        assert_eq!(remaining_pairs(&homework, &cache, today, mid), Some(2));

        let late = crate::datetime::parse_time("23:00").unwrap();
        assert_eq!(remaining_pairs(&homework, &cache, today, late), Some(1));

        homework.target_pairs = None;
        assert_eq!(remaining_pairs(&homework, &cache, today, late), None);
    }

    #[test]
    fn counts_lessons_until_deadline() {
        let today = date(2026, 11, 23);
        let mut cache: ScheduleCache = BTreeMap::new();
        cache.insert(today, vec![pair(1, "Электротехника")]);
        cache.insert(date(2026, 11, 24), vec![pair(1, "Электротехника")]);
        cache.insert(date(2026, 11, 26), vec![pair(1, "Электротехника")]);

        let now = crate::datetime::parse_time("07:00").unwrap();
        assert_eq!(
            count_lessons_until("Электротехника", date(2026, 11, 26), &cache, today, now),
            2
        );
        assert_eq!(
            count_lessons_until("Электротехника", today, &cache, today, now),
            0
        );
        assert_eq!(
            find_date_for_next_subject("Электротехника", 1, &cache, today, now),
            Some(date(2026, 11, 24))
        );
    }

    #[test]
    fn json_matches_android_schema() {
        let item = HomeworkItem::new("Физика", "Лаба № 3", date(2026, 11, 25));
        let json = serde_json::to_string(&item).unwrap();
        for key in [
            "\"id\"",
            "\"subject\"",
            "\"task\"",
            "\"dueDate\":\"2026-11-25\"",
            "\"notifyDayBefore\"",
            "\"isCompleted\"",
            "\"assignDate\"",
            "\"targetPairs\"",
        ] {
            assert!(json.contains(key), "нет ключа {key} в {json}");
        }
        let back: HomeworkItem = serde_json::from_str(&json).unwrap();
        assert_eq!(back, item);
    }
}