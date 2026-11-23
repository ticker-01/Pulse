//! Фоновые уведомления ОС: начало и конец пар, обновления расписания.
//!
//! Работает в отдельном потоке, проверяет расписание каждые 60 секунд
//! и показывает системный тост, если пара началась или скоро закончится.

use std::sync::mpsc;
use std::sync::Arc;
use std::time::Duration;

use chrono::{Local, NaiveDate};
use notify_rust::Notification;
use pulse_core::model::{LessonPair, ScheduleCache};

const CHECK_INTERVAL: Duration = Duration::from_secs(60);
const NOTIFY_BEFORE_END_MINS: i64 = 5;

/// Команды фоновому потоку уведомлений.
pub(crate) enum NotifyCommand {
    /// Обновить расписание для проверки.
    UpdateSchedule(Arc<ScheduleCache>),
}

/// Запускает фоновый поток уведомлений.
/// Возвращает отправитель для команд.
pub(crate) fn spawn() -> mpsc::Sender<NotifyCommand> {
    let (tx, rx) = mpsc::channel();

    std::thread::spawn(move || {
        let mut cache: Option<Arc<ScheduleCache>> = None;
        let mut notified_starts: Vec<(NaiveDate, u32)> = Vec::new();
        let mut notified_ends: Vec<(NaiveDate, u32)> = Vec::new();

        loop {
            // Обрабатываем команды с таймаутом 60 сек
            match rx.recv_timeout(CHECK_INTERVAL) {
                Ok(NotifyCommand::UpdateSchedule(new_cache)) => {
                    cache = Some(new_cache);
                    // При новом расписании сбрасываем отправленные уведомления
                    notified_starts.clear();
                    notified_ends.clear();
                    continue;
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    // Нормально — просто проверяем расписание
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => return,
            }

            let Some(ref cache) = cache else {
                continue;
            };

            let today = Local::now().date_naive();
            let now = Local::now().time();

            let Some(lessons) = cache.get(&today) else {
                continue;
            };

            for lesson in lessons {
                let Some(start) = lesson.start_local_time() else { continue };
                let Some(end) = lesson.end_local_time() else { continue };

                // Начало пары (в пределах 60 секунд от старта)
                if now >= start && now <= start + chrono::Duration::seconds(60) {
                    let key = (today, lesson.pair_number);
                    if !notified_starts.contains(&key) {
                        notify_start(lesson);
                        notified_starts.push(key);
                    }
                }

                // Скоро конец пары (за 5 минут до, не чаще раза в час)
                let remind_at = end - chrono::Duration::minutes(NOTIFY_BEFORE_END_MINS);
                if now >= remind_at && now < end {
                    let key = (today, lesson.pair_number);
                    if !notified_ends.contains(&key) {
                        notify_ending(lesson);
                        notified_ends.push(key);
                    }
                }

                // Очистка старых уведомлений (на следующий день)
                notified_starts.retain(|(d, _)| *d == today);
                notified_ends.retain(|(d, _)| *d == today);
            }
        }
    });

    tx
}

fn notify_start(lesson: &LessonPair) {
    let body = format!(
        "ауд. {} · {}\n{} – {}",
        lesson.room,
        lesson.group,
        lesson.start_time,
        lesson.end_time
    );

    if let Err(e) = Notification::new()
        .summary(&format!("📚 {}", lesson.clean_subject()))
        .body(&body)
        .icon("dialog-information")
        .appname("МГКАП")
        .timeout(5000)
        .show()
    {
        eprintln!("не удалось показать уведомление: {e}");
    }
}

fn notify_ending(lesson: &LessonPair) {
    let body = format!(
        "Через 5 минут конец {} пары.\nауд. {} · {}",
        lesson.lessons, lesson.room, lesson.group
    );

    if let Err(e) = Notification::new()
        .summary(&format!("⏳ {}", lesson.clean_subject()))
        .body(&body)
        .icon("dialog-information")
        .appname("МГКАП")
        .timeout(5000)
        .show()
    {
        eprintln!("не удалось показать уведомление: {e}");
    }
}
