//! Платформенно-независимое ядро приложения МГКАП (Pulse).
//!
//! Здесь живёт вся логика, которая в Android-версии была размазана по
//! `ScheduleViewModel`, `PerformanceViewModel`, `HomeworkManager`,
//! `PerformanceAnalytics`, `SubjectThemeHelper` и `DateTimeUtils`.
//! Модули не знают ничего про GUI и про конкретную ОС, поэтому покрыты тестами.

pub mod analytics;
pub mod bells;
pub mod changes;
pub mod datetime;
pub mod homework;
pub mod model;
pub mod parser;
pub mod performance;
pub mod source;
pub mod storage;
pub mod subject_theme;
pub mod updater;

pub use bells::{BellConfig, BellDay, BellSlot};
pub use changes::{diff_schedules, ChangeKind, LessonBrief, ScheduleChange};
pub use homework::HomeworkItem;
pub use model::{now_lessons, LessonPair, ScheduleCache, Teacher};
pub use performance::{Semester, SubjectGrade};
pub use updater::{
    check_from_body, current_version, download_and_verify, fetch_and_check, os_label,
    MacosAssets, PlatformAsset, UpdateInfo, UpdateManifest,
};

/// Адрес страницы с заменами в расписании колледжа.
pub const PAGE_URL: &str = source::PAGE_URL;

/// Версия приложения (для окна «О программе»).
pub const VERSION: &str = env!("CARGO_PKG_VERSION");