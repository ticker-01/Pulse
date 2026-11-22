//! Локальное хранилище — замена `SharedPreferences` + JSON-файлов.
//!
//! Всё лежит в каталоге данных приложения (`%APPDATA%\Pulse\pulse\data` на Windows,
//! `~/.local/share/pulse` на Linux, `~/Library/Application Support/by.Pulse.pulse`
//! на macOS). Запись атомарная: сначала `.tmp`, потом rename, чтобы не потерять
//! данные при выключении питания.

use anyhow::{Context, Result};
use directories::ProjectDirs;
use serde::{de::DeserializeOwned, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

/// Имена файлов — совпадают по смыслу с ключами `SharedPreferences`.
pub const FILE_SETTINGS: &str = "settings.json";
pub const FILE_SCHEDULE: &str = "schedule.json";
pub const FILE_TEACHERS: &str = "teachers.json";
pub const FILE_HOMEWORK: &str = "homework.json";
pub const FILE_PERFORMANCE: &str = "performance.json";
pub const FILE_BELLS: &str = "bells.json";
pub const FILE_RAW_TEXT: &str = "last_raw_text.txt";
/// Изменения расписания, найденные при последнем обновлении.
pub const FILE_CHANGES: &str = "schedule_changes.json";

#[derive(Debug, Clone)]
pub struct Storage {
    dir: PathBuf,
}

impl Storage {
    /// Каталог данных по умолчанию для текущей ОС.
    pub fn open() -> Result<Self> {
        let dirs = ProjectDirs::from("by", "Pulse", "pulse")
            .context("не удалось определить каталог данных приложения")?;
        Self::open_at(dirs.data_dir().to_path_buf())
    }

    /// Произвольный каталог: так тесты и переменная окружения `PULSE_DATA_DIR`
/// (переносимый запуск с флешки) не зависят от каталога ОС.
    pub fn open_at(dir: impl Into<PathBuf>) -> Result<Self> {
        let dir = dir.into();
        fs::create_dir_all(&dir)
            .with_context(|| format!("не удалось создать каталог {}", dir.display()))?;
        Ok(Self { dir })
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    pub fn path_of(&self, file: &str) -> PathBuf {
        self.dir.join(file)
    }

    /// Чтение JSON; при отсутствии файла или ошибке разбора возвращается
    /// значение по умолчанию — приложение не должно падать из-за кэша.
    pub fn load<T: DeserializeOwned + Default>(&self, file: &str) -> T {
        let path = self.path_of(file);
        let Ok(text) = fs::read_to_string(&path) else {
            return T::default();
        };
        match serde_json::from_str::<T>(&text) {
            Ok(value) => value,
            Err(error) => {
                eprintln!("pulse: не удалось разобрать {}: {error}", path.display());
                T::default()
            }
        }
    }

    /// Атомарная запись JSON.
    pub fn save<T: Serialize>(&self, file: &str, value: &T) -> Result<()> {
        let json = serde_json::to_string_pretty(value).context("не удалось сериализовать данные")?;
        self.write_atomic(file, json.as_bytes())
    }

    pub fn load_text(&self, file: &str) -> Option<String> {
        fs::read_to_string(self.path_of(file)).ok()
    }

    pub fn save_text(&self, file: &str, text: &str) -> Result<()> {
        self.write_atomic(file, text.as_bytes())
    }

    pub fn remove(&self, file: &str) -> Result<()> {
        let path = self.path_of(file);
        if path.exists() {
            fs::remove_file(&path)
                .with_context(|| format!("не удалось удалить {}", path.display()))?;
        }
        Ok(())
    }

    fn write_atomic(&self, file: &str, bytes: &[u8]) -> Result<()> {
        let path = self.path_of(file);
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, bytes)
            .with_context(|| format!("не удалось записать {}", tmp.display()))?;
        fs::rename(&tmp, &path)
            .with_context(|| format!("не удалось заменить {}", path.display()))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("pulse-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn json_roundtrip() {
        let dir = temp_dir("roundtrip");
        let storage = Storage::open_at(&dir).unwrap();

        let mut map: BTreeMap<String, Vec<u32>> = BTreeMap::new();
        map.insert("21э".into(), vec![1, 2, 3]);
        storage.save("test.json", &map).unwrap();
        let back: BTreeMap<String, Vec<u32>> = storage.load("test.json");
        assert_eq!(map, back);

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn missing_file_gives_default() {
        let dir = temp_dir("missing");
        let storage = Storage::open_at(&dir).unwrap();
        let value: BTreeMap<String, u32> = storage.load("nope.json");
        assert!(value.is_empty());
        assert_eq!(storage.load_text("nope.txt"), None);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn broken_json_does_not_panic() {
        let dir = temp_dir("broken");
        let storage = Storage::open_at(&dir).unwrap();
        fs::write(storage.path_of("broken.json"), "{ not json").unwrap();
        let value: BTreeMap<String, u32> = storage.load("broken.json");
        assert!(value.is_empty());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn text_files_are_saved_and_removed() {
        let dir = temp_dir("text");
        let storage = Storage::open_at(&dir).unwrap();
        storage.save_text(FILE_RAW_TEXT, "РАСПИСАНИЕ НА 23.11.2026").unwrap();
        assert_eq!(
            storage.load_text(FILE_RAW_TEXT).as_deref(),
            Some("РАСПИСАНИЕ НА 23.11.2026")
        );
        storage.remove(FILE_RAW_TEXT).unwrap();
        assert_eq!(storage.load_text(FILE_RAW_TEXT), None);
        fs::remove_dir_all(&dir).ok();
    }
}