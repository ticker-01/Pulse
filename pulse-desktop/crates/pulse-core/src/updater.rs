//! Автообновление приложения: проверка манифеста, сравнение версий, скачивание.
//!
//! Манифест лежит в публичном репозитории `ticker-01/mgkap-dist` (raw.githubusercontent.com).
//! CI заливает туда новые версии автоматически; приложение раз в сутки проверяет,
//! не вышла ли новая, и предлагает установить её одним кликом.

use serde::{Deserialize, Serialize};
use std::io::Read;
use std::time::Duration;
use sha2::{Digest, Sha256};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const READ_TIMEOUT: Duration = Duration::from_secs(30);
const USER_AGENT: &str = "Mozilla/5.0 (MGKAP-updater)";

/// Публичный URL манифеста.
pub const MANIFEST_URL: &str =
    "https://raw.githubusercontent.com/ticker-01/mgkap-dist/main/latest.json";

/// Текущая версия приложения (из Cargo.toml).
pub fn current_version() -> &'static str {
    crate::VERSION
}

/// Название ОС для манифеста.
pub fn os_label() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else if cfg!(target_os = "linux") {
        "linux"
    } else {
        "unknown"
    }
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(CONNECT_TIMEOUT)
        .timeout_read(READ_TIMEOUT)
        .user_agent(USER_AGENT)
        .build()
}

// ── данные манифеста ───────────────────────────────────────────────────────

/// Файловый ресурс: ссылка + контрольная сумма.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlatformAsset {
    pub url: String,
    pub sha256: String,
}

/// Ресурсы macOS: универсальная сборка, плюс отдельные (для обратной совместимости).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MacosAssets {
    pub universal: Option<PlatformAsset>,
    #[serde(rename = "aarch64")]
    pub aarch64: Option<PlatformAsset>,
    #[serde(rename = "x86_64")]
    pub x86_64: Option<PlatformAsset>,
}

/// Корневой манифест.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateManifest {
    pub version: String,
    pub date: String,
    pub windows: Option<PlatformAsset>,
    pub macos: Option<MacosAssets>,
    pub linux: Option<PlatformAsset>,
}

/// Всё, что нужно для установки обновления.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UpdateInfo {
    pub version: String,
    pub download_url: String,
    pub sha256: String,
    pub filename: String,
    /// Куда скачать файл (временный путь).
    pub temp_path: String,
}

// ── проверка и скачивание ───────────────────────────────────────────────────

/// Получает манифест через HTTP и возвращает обновление для текущей ОС.
pub fn fetch_and_check(manifest_url: &str, current: &str) -> Result<Option<UpdateInfo>, String> {
    let body = fetch_url(manifest_url).map_err(|e| format!("не удалось загрузить манифест: {e}"))?;
    check_from_body(&body, current)
}

/// Проверяет манифест (уже скачанный) и возвращает обновление для текущей ОС.
pub fn check_from_body(body: &str, current: &str) -> Result<Option<UpdateInfo>, String> {
    let manifest: UpdateManifest =
        serde_json::from_str(body).map_err(|e| format!("битый манифест: {e}"))?;

    if manifest.version.as_str() <= current {
        return Ok(None);
    }

    let asset = resolve_asset(&manifest)?;
    let filename = asset
        .url
        .rsplit('/')
        .next()
        .unwrap_or("update")
        .to_string();

    Ok(Some(UpdateInfo {
        version: manifest.version,
        download_url: asset.url,
        sha256: asset.sha256,
        temp_path: format!("/tmp/mgkap-update-{filename}"),
        filename,
    }))
}

fn resolve_asset(manifest: &UpdateManifest) -> Result<PlatformAsset, String> {
    match os_label() {
        "windows" => manifest
            .windows
            .clone()
            .ok_or_else(|| "в манифесте нет сборки для Windows".into()),
        "macos" => manifest
            .macos
            .as_ref()
            .and_then(|m| m.universal.clone())
            .ok_or_else(|| "в манифесте нет универсальной сборки для macOS".into()),
        "linux" => manifest
            .linux
            .clone()
            .ok_or_else(|| "в манифесте нет сборки для Linux".into()),
        _ => Err("неизвестная ОС".into()),
    }
}

/// Скачивает файл в temp_path и проверяет SHA-256.
pub fn download_and_verify(info: &UpdateInfo) -> Result<(), String> {
    let data = download_bytes(&info.download_url)?;
    let actual = sha256_hex(&data);
    if actual != info.sha256 {
        return Err(format!(
            "SHA-256 не совпадает: ожидался {}, получен {}",
            info.sha256, actual
        ));
    }
    std::fs::write(&info.temp_path, &data)
        .map_err(|e| format!("не удалось записать {}: {e}", info.temp_path))?;
    Ok(())
}

/// SHA-256 файла по пути (альтернатива для проверки уже скачанного).
pub fn sha256_path(path: &str) -> Result<String, String> {
    let data = std::fs::read(path).map_err(|e| format!("не удалось прочитать {path}: {e}"))?;
    Ok(sha256_hex(&data))
}

// ── хелперы ─────────────────────────────────────────────────────────────────

fn fetch_url(url: &str) -> Result<String, String> {
    let mut body = String::new();
    agent()
        .get(url)
        .call()
        .map_err(|e| format!("{e}"))?
        .into_reader()
        .read_to_string(&mut body)
        .map_err(|e| format!("{e}"))?;
    Ok(body)
}

fn download_bytes(url: &str) -> Result<Vec<u8>, String> {
    let mut data = Vec::new();
    agent()
        .get(url)
        .call()
        .map_err(|e| format!("{e}"))?
        .into_reader()
        .read_to_end(&mut data)
        .map_err(|e| format!("{e}"))?;
    Ok(data)
}

fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex_encode(hasher.finalize().as_slice())
}

fn hex_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        use std::fmt::Write;
        write!(out, "{b:02x}").unwrap();
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_manifest() -> UpdateManifest {
        UpdateManifest {
            version: "0.2.0".into(),
            date: "2026-11-22".into(),
            windows: Some(PlatformAsset {
                url: "https://example.com/mgkap.exe".into(),
                sha256: "a".repeat(64),
            }),
            macos: Some(MacosAssets {
                universal: Some(PlatformAsset {
                    url: "https://example.com/mgkap-universal.zip".into(),
                    sha256: "b".repeat(64),
                }),
                aarch64: None,
                x86_64: None,
            }),
            linux: Some(PlatformAsset {
                url: "https://example.com/mgkap".into(),
                sha256: "c".repeat(64),
            }),
        }
    }

    #[test]
    fn picks_platform_asset() {
        let manifest = sample_manifest();
        let asset = resolve_asset(&manifest).unwrap();
        let label = os_label();
        match label {
            "windows" => assert!(asset.url.contains("mgkap.exe")),
            "macos" => assert!(asset.url.contains("mgkap-universal")),
            "linux" => assert!(asset.url.contains("mgkap")),
            _ => unreachable!(),
        }
    }

    #[test]
    fn returns_none_when_already_latest() {
        let body = serde_json::to_string(&sample_manifest()).unwrap();
        let result = check_from_body(&body, "999.0.0").unwrap();
        assert!(result.is_none(), "не должна предлагать старую версию");
    }

    #[test]
    fn returns_info_when_newer_version() {
        let body = serde_json::to_string(&sample_manifest()).unwrap();
        let result = check_from_body(&body, "0.0.1").unwrap();
        let info = result.expect("должна найти обновление 0.2.0");
        assert_eq!(info.version, "0.2.0");
    }

    #[test]
    fn sha256_known_value() {
        let hex = sha256_hex(b"hello");
        assert_eq!(
            hex,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        );
    }

    #[test]
    fn error_on_missing_platform() {
        // Remove current platform
        let mut m = sample_manifest();
        m.linux = None;
        m.windows = None;
        let body = serde_json::to_string(&m).unwrap();
        let result = check_from_body(&body, "0.0.1");
        assert!(result.is_err(), "должна вернуть ошибку: нет сборки под платформу");
    }

    #[test]
    fn manifest_parse_roundtrip() {
        let manifest = sample_manifest();
        let json = serde_json::to_string_pretty(&manifest).unwrap();
        let parsed: UpdateManifest = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.version, "0.2.0");
        assert!(parsed.macos.as_ref().unwrap().universal.is_some());
    }
}
