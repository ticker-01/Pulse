//! Разбор дат и времени — порт `utils/DateTimeUtils.kt`.
//!
//! Kotlin-версия пробовала сначала строгий ISO-формат, затем «мягкий» разбор
//! (`8:30`, `08.30`, `8-30`, `8:30:00`). Логика сохранена 1:1, включая то, что
//! при невалидном часе/минуте возвращается `None`, а не «пробуем дальше».

use chrono::{Datelike, NaiveDate, NaiveTime, Timelike, Weekday};

/// `parseLocalTimeOrNull`: "08:30", "8:30", "08.30", "8-30", "8:30:00".
pub fn parse_time(raw: &str) -> Option<NaiveTime> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }

    for fmt in ["%H:%M:%S", "%H:%M"] {
        if let Ok(time) = NaiveTime::parse_from_str(trimmed, fmt) {
            return Some(time);
        }
    }

    let normalized = trimmed.replace(['.', '-'], ":");
    let parts: Vec<&str> = normalized.split(':').collect();
    if parts.len() >= 2 {
        let hour = parts[0].trim().parse::<u32>().ok()?;
        let minute = parts[1].trim().parse::<u32>().ok()?;
        if hour <= 23 && minute <= 59 {
            let second = if parts.len() >= 3 {
                parts[2].trim().parse::<u32>().unwrap_or(0).min(59)
            } else {
                0
            };
            return NaiveTime::from_hms_opt(hour, minute, second);
        }
    }

    None
}

/// `parseLocalDateOrNull`: "2026-11-22" и "22.11.2026" / "22.11.26".
pub fn parse_date(raw: &str) -> Option<NaiveDate> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }

    if let Ok(date) = NaiveDate::parse_from_str(trimmed, "%Y-%m-%d") {
        return Some(date);
    }

    let parts: Vec<&str> = trimmed.split('.').collect();
    if parts.len() == 3 {
        let day = parts[0].trim().parse::<u32>().ok()?;
        let month = parts[1].trim().parse::<u32>().ok()?;
        let raw_year = parts[2].trim().parse::<i32>().ok()?;
        let year = if raw_year < 100 { 2000 + raw_year } else { raw_year };
        return NaiveDate::from_ymd_opt(year, month, day);
    }

    None
}

/// "25.11.2026" — формат для полей ввода даты (`dd.MM.yyyy`).
pub fn format_date_input(date: NaiveDate) -> String {
    format!("{:02}.{:02}.{}", date.day(), date.month(), date.year())
}

/// ISO-представление даты — так она хранится в файлах настроек.
pub fn format_date_iso(date: NaiveDate) -> String {
    date.format("%Y-%m-%d").to_string()
}

/// "08:30"
pub fn format_time(time: NaiveTime) -> String {
    format!("{:02}:{:02}", time.hour(), time.minute())
}

/// "Понедельник"
pub fn weekday_name_ru(weekday: Weekday) -> &'static str {
    match weekday {
        Weekday::Mon => "Понедельник",
        Weekday::Tue => "Вторник",
        Weekday::Wed => "Среда",
        Weekday::Thu => "Четверг",
        Weekday::Fri => "Пятница",
        Weekday::Sat => "Суббота",
        Weekday::Sun => "Воскресенье",
    }
}

/// "Пн"
pub fn weekday_short_ru(weekday: Weekday) -> &'static str {
    match weekday {
        Weekday::Mon => "Пн",
        Weekday::Tue => "Вт",
        Weekday::Wed => "Ср",
        Weekday::Thu => "Чт",
        Weekday::Fri => "Пт",
        Weekday::Sat => "Сб",
        Weekday::Sun => "Вс",
    }
}

/// Название месяца в родительном падеже — как нужно для «5 мая».
pub fn month_name_ru(month: u32) -> &'static str {
    match month {
        1 => "января",
        2 => "февраля",
        3 => "марта",
        4 => "апреля",
        5 => "мая",
        6 => "июня",
        7 => "июля",
        8 => "августа",
        9 => "сентября",
        10 => "октября",
        11 => "ноября",
        12 => "декабря",
        _ => "—",
    }
}

/// "5 мая" (`DATE_FORMATTER_SHORT`)
pub fn format_short_date(date: NaiveDate) -> String {
    format!("{} {}", date.day(), month_name_ru(date.month()))
}

/// "Понедельник, 5 мая" (`DATE_FORMATTER_DAY_MONTH`)
pub fn format_day_month(date: NaiveDate) -> String {
    format!(
        "{}, {}",
        weekday_name_ru(date.weekday()),
        format_short_date(date)
    )
}

/// "5 мая 2026"
pub fn format_full_date(date: NaiveDate) -> String {
    format!("{} {}", format_short_date(date), date.year())
}

/// По-человечески про срок сдачи: «Сегодня», «Завтра», «Просрочено на 2 дн.».
pub fn humanize_days(due: NaiveDate, today: NaiveDate) -> String {
    let diff = (due - today).num_days();
    match diff {
        0 => "Сегодня".to_string(),
        1 => "Завтра".to_string(),
        2..=4 => format!("Через {diff} дня"),
        d if d > 4 => format!("Через {d} дней"),
        -1 => "Просрочено на 1 день".to_string(),
        d => format!("Просрочено на {} дн.", -d),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_times_in_all_known_forms() {
        assert_eq!(parse_time("08:30").unwrap().to_string(), "08:30:00");
        assert_eq!(parse_time(" 8:30 ").unwrap().to_string(), "08:30:00");
        assert_eq!(parse_time("8.30").unwrap().to_string(), "08:30:00");
        assert_eq!(parse_time("8-30").unwrap().to_string(), "08:30:00");
        assert_eq!(parse_time("08:30:15").unwrap().to_string(), "08:30:15");
        assert_eq!(parse_time("9:05").unwrap().to_string(), "09:05:00");
    }

    #[test]
    fn rejects_broken_times() {
        assert!(parse_time("").is_none());
        assert!(parse_time("??:??").is_none());
        assert!(parse_time("25:00").is_none());
        assert!(parse_time("10:75").is_none());
        assert!(parse_time("перемена").is_none());
    }

    #[test]
    fn parses_dates() {
        let expected = NaiveDate::from_ymd_opt(2026, 11, 22).unwrap();
        assert_eq!(parse_date("2026-11-22"), Some(expected));
        assert_eq!(parse_date("22.11.2026"), Some(expected));
        assert_eq!(parse_date("22.11.26"), Some(expected));
        assert_eq!(parse_date("32.11.2026"), None);
        assert_eq!(parse_date(""), None);
    }

    #[test]
    fn formats_russian_dates() {
        let date = NaiveDate::from_ymd_opt(2026, 5, 5).unwrap();
        assert_eq!(format_day_month(date), "Вторник, 5 мая");
        assert_eq!(format_short_date(date), "5 мая");
        assert_eq!(format_full_date(date), "5 мая 2026");
        assert_eq!(format_date_input(date), "05.05.2026");
    }

    #[test]
    fn parses_input_dates_in_both_formats() {
        let date = NaiveDate::from_ymd_opt(2026, 11, 25).unwrap();
        assert_eq!(parse_date("25.11.2026"), Some(date));
        assert_eq!(parse_date("2026-11-25"), Some(date));
        assert_eq!(parse_date(&format_date_input(date)), Some(date));
        assert_eq!(parse_date(""), None);
    }

    #[test]
    fn humanizes_deadlines() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 22).unwrap();
        assert_eq!(humanize_days(today, today), "Сегодня");
        assert_eq!(
            humanize_days(today.succ_opt().unwrap(), today),
            "Завтра"
        );
        assert_eq!(
            humanize_days(today.pred_opt().unwrap(), today),
            "Просрочено на 1 день"
        );
    }
}