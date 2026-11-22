//! Сравнение двух версий расписания: замены, переносы, снятые и добавленные пары.
//!
//! Зачем: страница «Замены в расписании» на сайте колледжа отдаёт не отдельный
//! файл замен, а новый PDF расписания — замены уже внесены в него, пометок в
//! тексте нет. Поэтому «что изменилось» считается сравнением прошлой и новой
//! версии: то, что колледж поменял, видно как замена/перенос/снятие пары.

use chrono::NaiveDate;
use serde::{Deserialize, Serialize};

use crate::model::{LessonPair, ScheduleCache};

/// Что случилось с парой.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ChangeKind {
    /// Пара была и осталась, но предмет/преподаватель/кабинет другие.
    Replaced,
    /// Пара та же, но переехала на другое время или в другой кабинет.
    Moved,
    /// Пары больше нет в расписании.
    Removed,
    /// Пара появилась — её не было в прошлой версии.
    Added,
}

impl ChangeKind {
    pub fn label(self) -> &'static str {
        match self {
            ChangeKind::Replaced => "замена",
            ChangeKind::Moved => "перенос",
            ChangeKind::Removed => "снята",
            ChangeKind::Added => "добавлена",
        }
    }
}

/// Одна пара в компактном виде — ею описываем «было» и «стало».
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LessonBrief {
    pub subject: String,
    pub teacher: String,
    pub room: String,
    pub time: String,
}

impl LessonBrief {
    fn from_lesson(lesson: &LessonPair) -> Self {
        Self {
            subject: lesson.clean_subject().to_string(),
            teacher: lesson.teacher.trim().to_string(),
            room: lesson.room.trim().to_string(),
            time: lesson.time_range(),
        }
    }

    /// `Математика · Ляховец Е.В. · 204 · 08:30–09:15`.
    pub fn describe(&self) -> String {
        let mut parts: Vec<&str> = Vec::new();
        if !self.subject.is_empty() {
            parts.push(&self.subject);
        }
        if !self.teacher.is_empty() {
            parts.push(&self.teacher);
        }
        if !self.room.is_empty() {
            parts.push(&self.room);
        }
        let mut text = parts.join(" · ");
        if !self.time.is_empty() {
            if !text.is_empty() {
                text.push_str(" · ");
            }
            text.push_str(&self.time);
        }
        text
    }
}

/// Изменение в расписании для конкретной пары конкретного дня.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ScheduleChange {
    pub date: NaiveDate,
    pub pair_number: u32,
    /// Группа из строки пары (у преподавателя это чужая группа, у студента — своя).
    pub group: String,
    pub kind: ChangeKind,
    pub before: Option<LessonBrief>,
    pub after: Option<LessonBrief>,
}

impl Default for ScheduleChange {
    fn default() -> Self {
        Self {
            date: NaiveDate::from_ymd_opt(1970, 1, 1).expect("дата всегда корректна"),
            pair_number: 0,
            group: String::new(),
            kind: ChangeKind::Added,
            before: None,
            after: None,
        }
    }
}

impl ScheduleChange {
    /// `Было → стало` одной строкой.
    ///
    /// Для переноса не повторяем то, что не менялось: предмет пишем один раз,
    /// а сравниваем время и кабинет.
    pub fn describe(&self) -> String {
        if let (ChangeKind::Moved, Some(before), Some(after)) =
            (self.kind, &self.before, &self.after)
        {
            let room_changed = before.room != after.room;
            let from = if room_changed && !before.room.is_empty() {
                format!("{} · {}", before.room, before.time)
            } else {
                before.time.clone()
            };
            let to = if room_changed && !after.room.is_empty() {
                format!("{} · {}", after.room, after.time)
            } else {
                after.time.clone()
            };
            let subject = if after.subject.is_empty() {
                before.subject.clone()
            } else {
                after.subject.clone()
            };
            return if subject.is_empty() {
                format!("{from} → {to}")
            } else {
                format!("{subject} · {from} → {to}")
            };
        }

        match (&self.before, &self.after) {
            (Some(before), Some(after)) => format!("{} → {}", before.describe(), after.describe()),
            (None, Some(after)) => after.describe(),
            (Some(before), None) => before.describe(),
            (None, None) => String::new(),
        }
    }
}

/// Собирает изменения между двумя версиями расписания.
///
/// Сравниваются только дни, которые есть в обеих версиях: если колледж
/// выложил расписание на следующую неделю, все её дни иначе выглядели бы как
/// «всё снято и всё добавлено». Появившиеся и ушедшие дни — не замены.
pub fn diff_schedules(before: &ScheduleCache, after: &ScheduleCache) -> Vec<ScheduleChange> {
    let mut changes: Vec<ScheduleChange> = Vec::new();

    for (date, previous) in before {
        let Some(current) = after.get(date) else {
            continue;
        };

        let mut old: Vec<&LessonPair> = previous.iter().collect();
        let new: Vec<&LessonPair> = current.iter().collect();
        let mut taken = vec![false; new.len()];

        // Пары сопоставляем по смыслу, от самого точного совпадения к общему:
        // 1) тот же преподаватель на той же паре, 2) тот же номер и предмет,
        // 3) та же пара с другим номером (перенос), 4) тот же номер пары.
        let matchers: [fn(&LessonPair, &LessonPair) -> bool; 4] = [
            |old, new| old.pair_number == new.pair_number && same_text(&old.teacher, &new.teacher),
            |old, new| {
                old.pair_number == new.pair_number && old.clean_subject() == new.clean_subject()
            },
            |old, new| {
                old.pair_number != new.pair_number
                    && old.clean_subject() == new.clean_subject()
                    && same_text(&old.teacher, &new.teacher)
            },
            |old, new| old.pair_number == new.pair_number,
        ];

        for (round, matches) in matchers.into_iter().enumerate() {
            let kind = if round == 2 {
                ChangeKind::Moved
            } else {
                ChangeKind::Replaced
            };
            for index in (0..old.len()).rev() {
                let Some(found) = (0..new.len())
                    .find(|candidate| !taken[*candidate] && matches(old[index], new[*candidate]))
                else {
                    continue;
                };
                taken[found] = true;
                let before = old.remove(index);
                let after = new[found];
                // Совпало полностью — рассказывать не о чем.
                if LessonBrief::from_lesson(before) != LessonBrief::from_lesson(after) {
                    changes.push(change(*date, before, kind, Some(before), Some(after)));
                }
            }
        }

        for lesson in old {
            changes.push(change(
                *date,
                lesson,
                ChangeKind::Removed,
                Some(lesson),
                None,
            ));
        }
        for (index, lesson) in new.into_iter().enumerate() {
            if !taken[index] {
                changes.push(change(*date, lesson, ChangeKind::Added, None, Some(lesson)));
            }
        }
    }

    changes.sort_by(|left, right| {
        left.date
            .cmp(&right.date)
            .then(left.pair_number.cmp(&right.pair_number))
            .then(left.kind.label().cmp(right.kind.label()))
    });
    changes
}

fn same_text(left: &str, right: &str) -> bool {
    left.trim().to_lowercase() == right.trim().to_lowercase()
}

fn change(
    date: NaiveDate,
    lesson: &LessonPair,
    kind: ChangeKind,
    before: Option<&LessonPair>,
    after: Option<&LessonPair>,
) -> ScheduleChange {
    ScheduleChange {
        date,
        pair_number: lesson.pair_number,
        group: lesson.group.clone(),
        kind,
        before: before.map(LessonBrief::from_lesson),
        after: after.map(LessonBrief::from_lesson),
    }
}

/// Оставляет изменения не старше `days` дней — чтобы список не копился вечно.
pub fn keep_recent(
    changes: Vec<ScheduleChange>,
    today: NaiveDate,
    days: i64,
) -> Vec<ScheduleChange> {
    let cutoff = today - chrono::Duration::days(days.max(0));
    changes
        .into_iter()
        .filter(|change| change.date >= cutoff)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn date() -> NaiveDate {
        NaiveDate::from_ymd_opt(2026, 11, 23).expect("дата теста")
    }

    fn lesson(
        pair: u32,
        subject: &str,
        teacher: &str,
        room: &str,
        start: &str,
        end: &str,
    ) -> LessonPair {
        LessonPair {
            pair_number: pair,
            lessons: pair.to_string(),
            start_time: start.to_string(),
            end_time: end.to_string(),
            subject: subject.to_string(),
            room: room.to_string(),
            teacher: teacher.to_string(),
            group: "511".to_string(),
        }
    }

    fn cache(lessons: Vec<LessonPair>) -> ScheduleCache {
        let mut cache = ScheduleCache::new();
        cache.insert(date(), lessons);
        cache
    }

    #[test]
    fn unchanged_schedule_has_no_changes() {
        let before = cache(vec![lesson(
            1,
            "Математика",
            "Ляховец Е.В.",
            "204",
            "08:30",
            "09:15",
        )]);
        let changes = diff_schedules(&before, &before.clone());
        assert!(changes.is_empty(), "не должно быть изменений: {changes:?}");
    }

    #[test]
    fn detects_subject_replacement() {
        let before = cache(vec![lesson(
            1,
            "Математика",
            "Ляховец Е.В.",
            "204",
            "08:30",
            "09:15",
        )]);
        let after = cache(vec![lesson(
            1,
            "Физика",
            "Петрова А.С.",
            "305",
            "08:30",
            "09:15",
        )]);

        let changes = diff_schedules(&before, &after);
        assert_eq!(changes.len(), 1, "получено: {changes:?}");
        let change = &changes[0];
        assert_eq!(change.kind, ChangeKind::Replaced);
        assert_eq!(change.pair_number, 1);
        assert_eq!(
            change.before.as_ref().map(|b| b.subject.as_str()),
            Some("Математика")
        );
        assert_eq!(
            change.after.as_ref().map(|a| a.subject.as_str()),
            Some("Физика")
        );
        assert!(
            change.describe().contains("Математика"),
            "{}",
            change.describe()
        );
    }

    #[test]
    fn detects_move_to_another_pair() {
        let before = cache(vec![
            lesson(1, "Математика", "Ляховец Е.В.", "204", "08:30", "09:15"),
            lesson(2, "Физика", "Петрова А.С.", "305", "09:25", "10:10"),
        ]);
        let after = cache(vec![
            lesson(3, "Математика", "Ляховец Е.В.", "204", "11:00", "11:45"),
            lesson(2, "Физика", "Петрова А.С.", "305", "09:25", "10:10"),
        ]);

        let changes = diff_schedules(&before, &after);
        assert_eq!(changes.len(), 1, "получено: {changes:?}");
        assert_eq!(changes[0].kind, ChangeKind::Moved);
        assert_eq!(changes[0].pair_number, 1, "указываем пару, с которой ушла");
        assert!(
            changes[0].after.as_ref().unwrap().time.contains("11:00"),
            "перенос должен указывать новое время: {:?}",
            changes[0].after
        );
    }

    #[test]
    fn detects_removed_and_added_pairs() {
        let before = cache(vec![
            lesson(1, "Математика", "Ляховец Е.В.", "204", "08:30", "09:15"),
            lesson(2, "Физика", "Петрова А.С.", "305", "09:25", "10:10"),
        ]);
        let after = cache(vec![
            lesson(1, "Математика", "Ляховец Е.В.", "204", "08:30", "09:15"),
            lesson(2, "Химия", "Козлова И.П.", "301", "09:25", "10:10"),
            lesson(3, "История", "Сидоров С.С.", "203", "11:00", "11:45"),
        ]);

        let changes = diff_schedules(&before, &after);
        let kinds: Vec<ChangeKind> = changes.iter().map(|change| change.kind).collect();
        assert_eq!(
            kinds,
            vec![ChangeKind::Replaced, ChangeKind::Added],
            "получено: {changes:?}"
        );
    }

    #[test]
    fn ignores_days_that_only_exist_in_one_version() {
        let mut before = ScheduleCache::new();
        before.insert(
            date(),
            vec![lesson(
                1,
                "Математика",
                "Ляховец Е.В.",
                "204",
                "08:30",
                "09:15",
            )],
        );
        let mut after = ScheduleCache::new();
        let next_day = date().succ_opt().expect("следующий день");
        after.insert(
            next_day,
            vec![lesson(
                1,
                "Математика",
                "Ляховец Е.В.",
                "204",
                "08:30",
                "09:15",
            )],
        );

        // Новая неделя не должна выглядеть как «всё снято и всё добавлено».
        assert!(diff_schedules(&before, &after).is_empty());
    }

    #[test]
    fn keeps_both_rows_of_the_same_pair() {
        // ФКиЗ ведут двое: две строки одной пары не должны считаться заменой.
        let before = cache(vec![
            lesson(3, "ФКиЗ", "Нежуга С.В.", "", "10:20", "11:05"),
            lesson(3, "ФКиЗ", "Авраменко Н.Н.", "", "10:20", "11:05"),
        ]);
        assert!(diff_schedules(&before, &before.clone()).is_empty());
    }

    #[test]
    fn move_description_skips_repeated_parts() {
        let change = ScheduleChange {
            date: date(),
            pair_number: 1,
            group: "511".to_string(),
            kind: ChangeKind::Moved,
            before: Some(LessonBrief {
                subject: "История".to_string(),
                teacher: "Петрова А.С.".to_string(),
                room: "305".to_string(),
                time: "08:30 – 10:00".to_string(),
            }),
            after: Some(LessonBrief {
                subject: "История".to_string(),
                teacher: "Петрова А.С.".to_string(),
                room: "305".to_string(),
                time: "12:30 – 14:00".to_string(),
            }),
        };
        // Предмет и кабинет не менялись — не повторяем их дважды.
        assert_eq!(change.describe(), "История · 08:30 – 10:00 → 12:30 – 14:00");
    }

    #[test]
    fn filters_out_old_changes() {
        let changes = vec![
            ScheduleChange {
                date: date(),
                pair_number: 1,
                ..ScheduleChange::default()
            },
            ScheduleChange {
                date: date() - chrono::Duration::days(30),
                pair_number: 2,
                ..ScheduleChange::default()
            },
        ];
        let recent = keep_recent(changes, date(), 14);
        assert_eq!(recent.len(), 1);
        assert_eq!(recent[0].pair_number, 1);
    }
}
