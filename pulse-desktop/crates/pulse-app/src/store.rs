//! Хранение состояния приложения: настройки и все пользовательские данные.
//!
//! Данные лежат в каталоге `Pulse` (см. `pulse_core::storage`) отдельными
//! JSON-файлами — так же, как в Android-версии это делали разные `SharedPreferences`.

use pulse_core::bells::BellConfig;
use pulse_core::changes::ScheduleChange;
use pulse_core::homework::HomeworkItem;
use pulse_core::model::{ScheduleCache, Teacher};
use pulse_core::performance::{Semester, ROUNDING_TENTHS};
use pulse_core::storage::{
    Storage, FILE_BELLS, FILE_CHANGES, FILE_HOMEWORK, FILE_PERFORMANCE, FILE_RAW_TEXT,
    FILE_SCHEDULE, FILE_SETTINGS, FILE_TEACHERS,
};
use serde::{Deserialize, Serialize};

/// Акцентный цвет по умолчанию — как `PrimaryPurple` в Android-версии.
pub const DEFAULT_ACCENT: u32 = 0xFF6C48E1;

/// Кто пользуется приложением: от роли зависит набор вкладок.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    #[default]
    Student,
    Teacher,
}

impl Role {
    pub fn label(self) -> &'static str {
        match self {
            Role::Student => "Студент",
            Role::Teacher => "Преподаватель",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub user_name: String,
    pub user_group: String,
    pub rounding_mode: i32,
    pub dark_theme: bool,
    pub accent: u32,
    pub auto_refresh: bool,
    pub last_pdf_url: String,
    pub last_pdf_file: String,
    /// Роль пользователя; старые файлы без поля читаются как «студент».
    pub role: Role,
    /// Имя преподавателя для режима преподавателя (как в справочнике).
    pub teacher_name: String,
    /// Когда расписание последний раз скачивалось с сайта (Unix-время, 0 — ни разу).
    pub last_fetch_at: i64,
    /// Автоматически проверять обновления приложения.
    pub auto_update_app: bool,
    /// Когда последний раз проверяли обновление приложения (Unix-время).
    pub last_update_check_ts: i64,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            user_name: "Студент".to_string(),
            user_group: String::new(),
            rounding_mode: ROUNDING_TENTHS,
            dark_theme: true,
            accent: DEFAULT_ACCENT,
            auto_refresh: true,
            last_pdf_url: String::new(),
            last_pdf_file: String::new(),
            role: Role::Student,
            teacher_name: String::new(),
            last_fetch_at: 0,
            auto_update_app: true,
            last_update_check_ts: 0,
        }
    }
}

/// Фасад над файловым хранилищем: один метод на файл.
pub struct Store {
    storage: Storage,
}

impl Store {
    /// Открывает каталог данных ОС; при ошибке — временный каталог,
    /// чтобы приложение всё равно запустилось (и сообщило о проблеме).
    pub fn open() -> (Self, Option<String>) {
        // Переносимый режим: каталог данных можно задать переменной окружения.
        if let Some(dir) = std::env::var_os("PULSE_DATA_DIR") {
            if !dir.is_empty() {
                return Self::open_at(dir);
            }
        }
        match Storage::open() {
            Ok(storage) => (Self { storage }, None),
            Err(error) => {
                let fallback = std::env::temp_dir().join("pulse-data");
                let (store, fallback_warning) = Self::open_at(&fallback);
                let dir = store.data_dir();
                let message = match fallback_warning {
                    Some(nested) => format!("Не удалось открыть каталог данных ({error}). {nested}"),
                    None => {
                        format!("Не удалось открыть каталог данных ({error}). Используется {dir}")
                    }
                };
                (store, Some(message))
            }
        }
    }

    /// Открывает конкретный каталог — так тесты и перенос данных не зависят
    /// от каталога ОС.
    pub fn open_at(dir: impl Into<std::path::PathBuf>) -> (Self, Option<String>) {
        match Storage::open_at(dir) {
            Ok(storage) => (Self { storage }, None),
            Err(error) => {
                let storage = Storage::open_at(".").expect("текущий каталог доступен");
                (
                    Self { storage },
                    Some(format!("Не удалось открыть каталог данных: {error}")),
                )
            }
        }
    }

    pub fn data_dir(&self) -> String {
        self.storage.dir().display().to_string()
    }

    pub fn load_settings(&self) -> Settings {
        self.storage.load(FILE_SETTINGS)
    }

    pub fn save_settings(&self, settings: &Settings) -> Option<String> {
        self.error_of(self.storage.save(FILE_SETTINGS, settings))
    }

    pub fn load_schedule(&self) -> ScheduleCache {
        self.storage.load(FILE_SCHEDULE)
    }

    pub fn save_schedule(&self, schedule: &ScheduleCache) -> Option<String> {
        self.error_of(self.storage.save(FILE_SCHEDULE, schedule))
    }

    pub fn load_teachers(&self) -> Vec<Teacher> {
        self.storage.load(FILE_TEACHERS)
    }

    pub fn save_teachers(&self, teachers: &[Teacher]) -> Option<String> {
        self.error_of(self.storage.save(FILE_TEACHERS, &teachers.to_vec()))
    }

    pub fn load_homework(&self) -> Vec<HomeworkItem> {
        let mut items: Vec<HomeworkItem> = self.storage.load(FILE_HOMEWORK);
        HomeworkItem::sort_items(&mut items);
        items
    }

    pub fn save_homework(&self, items: &[HomeworkItem]) -> Option<String> {
        self.error_of(self.storage.save(FILE_HOMEWORK, &items.to_vec()))
    }

    pub fn load_semesters(&self) -> Vec<Semester> {
        let mut semesters: Vec<Semester> = self.storage.load(FILE_PERFORMANCE);
        if semesters.is_empty() {
            semesters.push(Semester::new("1 семестр"));
        }
        semesters
    }

    pub fn save_semesters(&self, semesters: &[Semester]) -> Option<String> {
        self.error_of(self.storage.save(FILE_PERFORMANCE, &semesters.to_vec()))
    }

    pub fn load_bells(&self) -> BellConfig {
        self.storage.load(FILE_BELLS)
    }

    pub fn save_bells(&self, bells: &BellConfig) -> Option<String> {
        self.error_of(self.storage.save(FILE_BELLS, bells))
    }

    pub fn load_raw_text(&self) -> String {
        self.storage.load_text(FILE_RAW_TEXT).unwrap_or_default()
    }

    /// Изменения расписания, найденные при последнем обновлении.
    pub fn load_changes(&self) -> Vec<ScheduleChange> {
        let mut changes: Vec<ScheduleChange> = self.storage.load(FILE_CHANGES);
        changes.sort_by(|left, right| {
            left.date
                .cmp(&right.date)
                .then(left.pair_number.cmp(&right.pair_number))
        });
        changes
    }

    pub fn save_changes(&self, changes: &[ScheduleChange]) -> Option<String> {
        self.error_of(self.storage.save(FILE_CHANGES, &changes.to_vec()))
    }

    pub fn save_raw_text(&self, text: &str) -> Option<String> {
        self.error_of(self.storage.save_text(FILE_RAW_TEXT, text))
    }

    /// Полный сброс пользовательских данных (каталог остаётся).
    pub fn reset_all(&self) -> Option<String> {
        let mut error = None;
        for file in [
            FILE_SETTINGS,
            FILE_SCHEDULE,
            FILE_TEACHERS,
            FILE_HOMEWORK,
            FILE_PERFORMANCE,
            FILE_BELLS,
            FILE_RAW_TEXT,
            FILE_CHANGES,
        ] {
            if let Some(message) = self.error_of(self.storage.remove(file)) {
                error = Some(message);
            }
        }
        error
    }

    fn error_of(&self, result: anyhow::Result<()>) -> Option<String> {
        result.err().map(|error| format!("{error:#}"))
    }
}