//! Расписание звонков — порт `ScheduleViewModel.getBellSchedule`.
//!
//! Списки дословно повторяют Android-версию (включая странную 5-ю пару
//! понедельника 13:00–13:25): это данные колледжа, а не наши предположения.
//! Пользователь может переопределить любой день через `BellConfig::custom`.

use chrono::{Datelike, NaiveDate, Weekday};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashSet};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BellSlot {
    pub start: String,
    pub end: String,
}

impl BellSlot {
    pub fn new(start: impl Into<String>, end: impl Into<String>) -> Self {
        Self {
            start: start.into(),
            end: end.into(),
        }
    }
}

/// Тип дня, для которого берутся звонки.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum BellDay {
    Monday,
    Thursday,
    Saturday,
    Regular,
    Shortened,
}

impl BellDay {
    pub fn key(self) -> &'static str {
        match self {
            Self::Monday => "MONDAY",
            Self::Thursday => "THURSDAY",
            Self::Saturday => "SATURDAY",
            Self::Regular => "REGULAR",
            Self::Shortened => "SHORTENED",
        }
    }

    pub fn from_key(key: &str) -> Option<Self> {
        Some(match key.trim().to_uppercase().as_str() {
            "MONDAY" => Self::Monday,
            "THURSDAY" => Self::Thursday,
            "SATURDAY" => Self::Saturday,
            "REGULAR" => Self::Regular,
            "SHORTENED" => Self::Shortened,
            _ => return None,
        })
    }

    pub fn label_ru(self) -> &'static str {
        match self {
            Self::Monday => "Понедельник",
            Self::Thursday => "Четверг",
            Self::Saturday => "Суббота",
            Self::Regular => "Обычный день",
            Self::Shortened => "Сокращённый день",
        }
    }

    /// Порядок в настройках: от общего к частному.
    pub const ALL: [BellDay; 5] = [
        BellDay::Regular,
        BellDay::Monday,
        BellDay::Thursday,
        BellDay::Saturday,
        BellDay::Shortened,
    ];

    /// Сокращённый день важнее дня недели, дальше — Пн/Чт/Сб, иначе обычный.
    pub fn for_date(date: NaiveDate, shortened: bool) -> Self {
        if shortened {
            return Self::Shortened;
        }
        match date.weekday() {
            Weekday::Mon => Self::Monday,
            Weekday::Thu => Self::Thursday,
            Weekday::Sat => Self::Saturday,
            _ => Self::Regular,
        }
    }
}

const MONDAY: [(&str, &str); 13] = [
    ("08:30", "09:15"),
    ("09:25", "10:10"),
    ("10:20", "11:05"),
    ("11:15", "12:00"),
    ("13:00", "13:25"),
    ("13:35", "14:20"),
    ("14:30", "15:15"),
    ("15:25", "16:10"),
    ("16:20", "17:05"),
    ("17:15", "18:00"),
    ("18:10", "18:55"),
    ("19:05", "19:50"),
    ("20:00", "20:45"),
];

const REGULAR: [(&str, &str); 13] = [
    ("08:30", "09:15"),
    ("09:25", "10:10"),
    ("10:20", "11:05"),
    ("11:15", "12:00"),
    ("13:00", "13:45"),
    ("13:55", "14:40"),
    ("14:50", "15:35"),
    ("15:45", "16:30"),
    ("16:40", "17:25"),
    ("17:35", "18:20"),
    ("18:30", "19:15"),
    ("19:25", "20:10"),
    ("20:20", "21:05"),
];

const SATURDAY: [(&str, &str); 13] = [
    ("08:30", "09:15"),
    ("09:15", "10:00"),
    ("10:15", "11:05"),
    ("11:05", "11:45"),
    ("12:00", "13:10"),
    ("13:20", "13:30"),
    ("13:55", "15:00"),
    ("15:00", "15:15"),
    ("15:30", "16:50"),
    ("17:00", "17:00"),
    ("17:55", "18:40"),
    ("18:50", "19:35"),
    ("19:45", "20:30"),
];

const SHORTENED: [(&str, &str); 14] = [
    ("08:30", "09:10"),
    ("09:10", "09:50"),
    ("10:00", "10:40"),
    ("10:40", "11:20"),
    ("11:40", "12:20"),
    ("12:20", "13:00"),
    ("13:10", "13:50"),
    ("13:50", "14:30"),
    ("14:40", "15:20"),
    ("15:20", "16:00"),
    ("16:10", "16:50"),
    ("16:50", "17:30"),
    ("17:40", "18:20"),
    ("18:20", "19:00"),
];

pub fn default_schedule(day: BellDay) -> Vec<BellSlot> {
    let table: &[(&str, &str)] = match day {
        BellDay::Monday => &MONDAY,
        BellDay::Thursday | BellDay::Regular => &REGULAR,
        BellDay::Saturday => &SATURDAY,
        BellDay::Shortened => &SHORTENED,
    };
    table
        .iter()
        .map(|(start, end)| BellSlot::new(*start, *end))
        .collect()
}

/// Настройки звонков: кастомные списки по типу дня + отмеченные сокращённые даты.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct BellConfig {
    /// `"MONDAY" -> [ … ]` — если день есть в карте, используется он.
    pub custom: BTreeMap<String, Vec<BellSlot>>,
    /// Даты, которые считаются сокращёнными (в Android это `StringSet` ISO-дат).
    pub shortened_days: HashSet<NaiveDate>,
}

impl BellConfig {
    pub fn is_shortened(&self, date: NaiveDate) -> bool {
        self.shortened_days.contains(&date)
    }

    pub fn day_for(&self, date: NaiveDate) -> BellDay {
        BellDay::for_date(date, self.is_shortened(date))
    }

    /// Звонки на конкретную дату: кастомный список дня, если он непустой.
    pub fn slots_for(&self, date: NaiveDate) -> Vec<BellSlot> {
        let day = self.day_for(date);
        if let Some(list) = self.custom.get(day.key()) {
            if !list.is_empty() {
                return list.clone();
            }
        }
        default_schedule(day)
    }

    pub fn set_custom(&mut self, day: BellDay, slots: Vec<BellSlot>) {
        if slots.is_empty() {
            self.custom.remove(day.key());
        } else {
            self.custom.insert(day.key().to_string(), slots);
        }
    }

    /// Время начала и конца пары по её номеру (1-based), как `timings[hour - 1]`.
    pub fn timing_for_pair(&self, date: NaiveDate, pair_number: u32) -> Option<(String, String)> {
        if pair_number == 0 {
            return None;
        }
        let slots = self.slots_for(date);
        slots
            .get(pair_number as usize - 1)
            .map(|slot| (slot.start.clone(), slot.end.clone()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn date(y: i32, m: u32, d: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, d).unwrap()
    }

    #[test]
    fn day_keys_follow_android_rules() {
        let config = BellConfig::default();
        assert_eq!(config.day_for(date(2026, 11, 23)), BellDay::Monday);
        assert_eq!(config.day_for(date(2026, 11, 26)), BellDay::Thursday);
        assert_eq!(config.day_for(date(2026, 11, 28)), BellDay::Saturday);
        assert_eq!(config.day_for(date(2026, 11, 24)), BellDay::Regular);
    }

    #[test]
    fn shortened_day_wins_over_weekday() {
        let mut config = BellConfig::default();
        let target = date(2026, 11, 23); // понедельник
        config.shortened_days.insert(target);
        assert_eq!(config.day_for(target), BellDay::Shortened);
        assert_eq!(config.slots_for(target), default_schedule(BellDay::Shortened));
    }

    #[test]
    fn default_tables_match_android_lengths() {
        assert_eq!(default_schedule(BellDay::Monday).len(), 13);
        assert_eq!(default_schedule(BellDay::Regular).len(), 13);
        assert_eq!(default_schedule(BellDay::Thursday).len(), 13);
        assert_eq!(default_schedule(BellDay::Saturday).len(), 13);
        assert_eq!(default_schedule(BellDay::Shortened).len(), 14);
        assert_eq!(
            default_schedule(BellDay::Regular)[12],
            BellSlot::new("20:20", "21:05")
        );
    }

    #[test]
    fn custom_schedule_overrides_default() {
        let mut config = BellConfig::default();
        let monday = date(2026, 11, 23);
        config.set_custom(BellDay::Monday, vec![BellSlot::new("09:00", "09:45")]);
        let slots = config.slots_for(monday);
        assert_eq!(slots.len(), 1);
        assert_eq!(slots[0].start, "09:00");
        assert_eq!(config.timing_for_pair(monday, 1), Some(("09:00".into(), "09:45".into())));
        assert_eq!(config.timing_for_pair(monday, 2), None);
    }

    #[test]
    fn bell_config_survives_json_roundtrip() {
        let mut config = BellConfig::default();
        config.shortened_days.insert(date(2026, 11, 23));
        config.set_custom(BellDay::Saturday, vec![BellSlot::new("10:00", "10:45")]);
        let json = serde_json::to_string(&config).unwrap();
        let back: BellConfig = serde_json::from_str(&json).unwrap();
        assert!(back.is_shortened(date(2026, 11, 23)));
        assert_eq!(back.custom.get("SATURDAY").unwrap().len(), 1);
    }
}