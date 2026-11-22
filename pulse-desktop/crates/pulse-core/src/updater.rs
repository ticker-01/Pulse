//! Автообновление приложения через GitHub Releases.
//!
//! Репозиторий публичный — никаких токенов не нужно.
//! CI создаёт GitHub Release с бинарниками и файлом `checksums.txt`.
//! Приложение раз в сутки проверяет последний релиз и предлагает обновление.

use serde::{Deserialize, Serialize};
use std::cmp::Ordering;
use std::io::Read;
use std::time::Duration;
use sha2::{Digest, Sha256};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const READ_TIMEOUT: Duration = Duration::from_secs(30);
const USER_AGENT: &str = "Mozilla/5.0 (MGKAP-updater)";

/// API последнего релиза в публичном репозитории.
pub const RELEASES_API_URL: &str =
    "https://api.github.com/repos/ticker-01/Pulse/releases/latest";

/// Текущая версия приложения (из Cargo.toml).
pub fn current_version() -> &'static str {
    crate::VERSION
}

/// Название ОС для поиска ассета.
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

// ── парсинг ответа GitHub API ──────────────────────────────────────────────

/// Ассет из GitHub Release.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReleaseAsset {
    pub name: String,
    #[serde(rename = "browser_download_url")]
    pub download_url: String,
}

/// Ответ GitHub API `/releases/latest`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitHubRelease {
    #[serde(rename = "tag_name")]
    pub tag_name: String,
    #[serde(rename = "published_at")]
    pub published_at: String,
    pub assets: Vec<ReleaseAsset>,
}

/// Всё, что нужно для установки обновления.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateInfo {
    pub version: String,
    pub download_url: String,
    pub sha256: String,
    pub filename: String,
    /// Куда скачать файл (временный путь).
    pub temp_path: String,
}

// ── проверка ───────────────────────────────────────────────────────────────

/// Проверяет последний релиз на GitHub и возвращает обновление для текущей ОС.
pub fn fetch_and_check(latest_url: &str, current: &str) -> Result<Option<UpdateInfo>, String> {
    let body = fetch_url(latest_url)
        .map_err(|e| format!("не удалось загрузить информацию о релизе: {e}"))?;
    check_from_body(&body, current)
}

/// Проверяет ответ API (уже скачанный) и возвращает обновление.
pub fn check_from_body(body: &str, current: &str) -> Result<Option<UpdateInfo>, String> {
    let release: GitHubRelease =
        serde_json::from_str(body).map_err(|e| format!("битый ответ API: {e}"))?;

    let tag_version = release.tag_name.strip_prefix('v').unwrap_or(&release.tag_name);

    if compare_versions(tag_version, current) != Ordering::Greater {
        return Ok(None);
    }

    let asset = resolve_asset(&release.assets)?;
    let sha256 = fetch_checksum(&release.assets, &asset.name)?;
    let filename = asset.name.clone();
    let temp_path = format!("/tmp/mgkap-update-{filename}");

    Ok(Some(UpdateInfo {
        version: tag_version.to_string(),
        download_url: asset.download_url,
        sha256,
        filename,
        temp_path,
    }))
}

fn resolve_asset(assets: &[ReleaseAsset]) -> Result<ReleaseAsset, String> {
    let pattern = match os_label() {
        "windows" => "windows-x86_64",
        "macos" => "macos-universal",
        "linux" => "linux-x86_64",
        _ => return Err("неизвестная ОС".into()),
    };

    assets
        .iter()
        .find(|a| a.name.contains(pattern) && !a.name.ends_with(".sha256") && a.name != "checksums.txt")
        .cloned()
        .ok_or_else(|| format!("в релизе нет ассета для {pattern}"))
}

fn fetch_checksum(assets: &[ReleaseAsset], binary_name: &str) -> Result<String, String> {
    // Сначала ищем checksums.txt
    let checksum_asset = assets
        .iter()
        .find(|a| a.name == "checksums.txt")
        .ok_or_else(|| "в релизе нет checksums.txt".to_string())?;

    let body = fetch_url(&checksum_asset.download_url)
        .map_err(|e| format!("не удалось загрузить checksums.txt: {e}"))?;

    // Парсим: "<sha256>  <filename>"
    for line in body.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() >= 2 && parts[1] == binary_name {
            return Ok(parts[0].to_string());
        }
    }

    Err(format!("в checksums.txt нет записи для {binary_name}"))
}

/// Сравнение семвер-строк (без v-префикса): `"0.2.0" > "0.1.0"`.
fn compare_versions(a: &str, b: &str) -> Ordering {
    let a_parts: Vec<u32> = a.split('.').filter_map(|s| s.parse().ok()).collect();
    let b_parts: Vec<u32> = b.split('.').filter_map(|s| s.parse().ok()).collect();

    for i in 0..a_parts.len().max(b_parts.len()) {
        let av = a_parts.get(i).copied().unwrap_or(0);
        let bv = b_parts.get(i).copied().unwrap_or(0);
        if av != bv {
            return if av > bv { Ordering::Greater } else { Ordering::Less };
        }
    }
    Ordering::Equal
}

// ── скачивание ─────────────────────────────────────────────────────────────

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

/// SHA-256 файла по пути.
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

    fn sample_release() -> GitHubRelease {
        GitHubRelease {
            tag_name: "v0.2.0".into(),
            published_at: "2026-11-22T12:00:00Z".into(),
            assets: vec![
                ReleaseAsset {
                    name: "mgkap-windows-x86_64.exe".into(),
                    download_url: "https://example.com/mgkap.exe".into(),
                },
                ReleaseAsset {
                    name: "mgkap-macos-universal.zip".into(),
                    download_url: "https://example.com/mgkap-universal.zip".into(),
                },
                ReleaseAsset {
                    name: "mgkap-linux-x86_64.tar.gz".into(),
                    download_url: "https://example.com/mgkap".into(),
                },
                ReleaseAsset {
                    name: "checksums.txt".into(),
                    download_url: "https://example.com/checksums.txt".into(),
                },
            ],
        }
    }

    #[test]
    fn picks_platform_asset() {
        let asset = resolve_asset(&sample_release().assets).unwrap();
        let label = os_label();
        match label {
            "windows" => assert!(asset.name.contains("windows")),
            "macos" => assert!(asset.name.contains("macos-universal")),
            "linux" => assert!(asset.name.contains("linux")),
            _ => unreachable!(),
        }
    }

    #[test]
    fn returns_none_when_already_latest() {
        let json = serde_json::to_string(&sample_release()).unwrap();
        let result = check_from_body(&json, "999.0.0").unwrap();
        assert!(result.is_none(), "не должна предлагать старую версию");
    }

    #[test]
    fn returns_info_when_newer() {
        let json = serde_json::to_string(&sample_release()).unwrap();
        // Должна найти обновление, но упадёт на скачивании checksums.txt
        let result = check_from_body(&json, "0.0.1");
        assert!(
            result.is_err(),
            "ожидалась ошибка скачивания checksums.txt"
        );
    }

    #[test]
    fn compare_versions_works() {
        assert_eq!(compare_versions("0.2.0", "0.1.0"), Ordering::Greater);
        assert_eq!(compare_versions("0.1.0", "0.2.0"), Ordering::Less);
        assert_eq!(compare_versions("1.0.0", "0.9.9"), Ordering::Greater);
        assert_eq!(compare_versions("0.1.0", "0.1.0"), Ordering::Equal);
        assert_eq!(compare_versions("0.10.0", "0.9.0"), Ordering::Greater);
    }

    #[test]
    fn version_without_v_prefix() {
        assert_eq!(compare_versions("1.0", "0.9.9"), Ordering::Greater);
        assert_eq!(compare_versions("0.1", "0.1.0"), Ordering::Equal);
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
        let mut release = sample_release();
        release.assets.retain(|a| a.name == "checksums.txt");
        let result = resolve_asset(&release.assets);
        assert!(result.is_err());
    }

    #[test]
    fn release_parse_roundtrip() {
        let release = sample_release();
        let json = serde_json::to_string_pretty(&release).unwrap();
        let parsed: GitHubRelease = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.tag_name, "v0.2.0");
        assert_eq!(parsed.assets.len(), 4);
    }
}
