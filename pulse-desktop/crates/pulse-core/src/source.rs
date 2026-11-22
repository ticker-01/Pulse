//! Загрузка расписания с сайта колледжа — порт `fetchAllPdfUrls` + `downloadAndExtractPdf`.
//!
//! Сеть синхронная (`ureq`): вызывающий код (GUI) обязан запускать это в
//! отдельном потоке. Лимит размера PDF — те же 20 МБ, что и в Android-версии.

use anyhow::{bail, Context, Result};
use regex::Regex;
use std::io::Read;
use std::sync::LazyLock;
use std::time::Duration;

use crate::datetime;

/// Страница, на которой лежат ссылки на PDF с заменами.
pub const PAGE_URL: &str = "https://college-ripo.by/uchashhimsya/zameny-v-raspisanii/";

const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

/// Максимальный размер одного PDF (как `maxBytes` в Kotlin).
pub const MAX_PDF_BYTES: u64 = 20 * 1024 * 1024;

/// Ограничение на размер HTML-страницы, чтобы не съесть память на «мусоре».
const MAX_PAGE_BYTES: u64 = 8 * 1024 * 1024;

const BASE_URL: &str = "https://college-ripo.by";

fn re(pattern: &str) -> Regex {
    Regex::new(pattern).expect("регулярное выражение из порта должно компилироваться")
}

static BUTTON_HREF_FIRST: LazyLock<Regex> = LazyLock::new(|| {
    re(r#"(?i)<a\b[^>]*?href=["']([^"']+\.pdf)["'][^>]*?class=["'][^"']*wp-block-file__button"#)
});
static BUTTON_HREF_SECOND: LazyLock<Regex> = LazyLock::new(|| {
    re(r#"(?i)<a\b[^>]*?class=["'][^"']*wp-block-file__button[^"']*["'][^>]*?href=["']([^"']+\.pdf)["']"#)
});
static OBJECT_DATA: LazyLock<Regex> =
    LazyLock::new(|| re(r#"(?i)<object\b[^>]*?data=["']([^"']+\.pdf)["']"#));
static GENERAL_PDF: LazyLock<Regex> = LazyLock::new(|| {
    re(r#"(?i)href=["'](https?://[^"']*/wp-content/uploads/[^"']*\.pdf)["']"#)
});
static URL_DATE: LazyLock<Regex> = LazyLock::new(|| re(r"([0-9]{2})\.([0-9]{2})\.([0-9]{2,4})"));
static URL_PATH: LazyLock<Regex> = LazyLock::new(|| re(r"/uploads/([0-9]{4})/([0-9]{2})/"));

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(15))
        .timeout_read(Duration::from_secs(20))
        .user_agent(USER_AGENT)
        .build()
}

/// HTML страницы с заменами.
pub fn fetch_page(url: &str) -> Result<String> {
    let agent = agent();
    let response = agent
        .get(url)
        .call()
        .with_context(|| format!("не удалось загрузить страницу {url}"))?;
    let mut html = String::new();
    response
        .into_reader()
        .take(MAX_PAGE_BYTES)
        .read_to_string(&mut html)
        .with_context(|| format!("не удалось прочитать ответ от {url}"))?;
    Ok(html)
}

fn push_unique(list: &mut Vec<String>, value: &str) {
    let value = value.trim();
    if !value.is_empty() && !list.iter().any(|existing| existing == value) {
        list.push(value.to_string());
    }
}

fn resolve_url(raw: &str) -> String {
    let mut url = raw.trim().to_string();
    if let Some(rest) = url.strip_prefix("//") {
        url = format!("https://{rest}");
    } else if url.starts_with('/') {
        url = format!("{BASE_URL}{url}");
    } else if !url.starts_with("http://") && !url.starts_with("https://") {
        url = format!("{BASE_URL}/{url}");
    }
    if url.starts_with("http://") {
        url = url.replacen("http://", "https://", 1);
    }
    url
}

fn sort_key(link: &str) -> i64 {
    if let Some(caps) = URL_DATE.captures(link) {
        let day = caps[1].parse::<i64>().unwrap_or(0);
        let month = caps[2].parse::<i64>().unwrap_or(0);
        let mut year = caps[3].parse::<i64>().unwrap_or(0);
        if year < 100 {
            year += 2000;
        }
        return year * 10_000 + month * 100 + day;
    }
    if let Some(caps) = URL_PATH.captures(link) {
        let year = caps[1].parse::<i64>().unwrap_or(0);
        let month = caps[2].parse::<i64>().unwrap_or(0);
        return year * 100 + month;
    }
    0
}

/// Ссылки на PDF из HTML — те же четыре регулярки, что и в Kotlin.
pub fn extract_pdf_links(html: &str) -> Vec<String> {
    let mut candidates: Vec<String> = Vec::new();

    for regex in [&*BUTTON_HREF_FIRST, &*BUTTON_HREF_SECOND] {
        for caps in regex.captures_iter(html) {
            push_unique(&mut candidates, &caps[1]);
        }
    }
    for caps in OBJECT_DATA.captures_iter(html) {
        push_unique(&mut candidates, &caps[1]);
    }
    if candidates.is_empty() {
        for caps in GENERAL_PDF.captures_iter(html) {
            push_unique(&mut candidates, &caps[1]);
        }
    }

    let mut resolved: Vec<String> = candidates
        .into_iter()
        .map(|raw| resolve_url(&raw))
        .filter(|link| {
            let lower = link.to_lowercase();
            lower.contains("deti")
                || lower.contains("дети")
                || lower.contains("raspis")
                || lower.contains("zamen")
                || URL_DATE.is_match(&lower)
        })
        .collect();

    resolved.sort_by_key(|link| sort_key(link));
    resolved.dedup();
    resolved
}

/// Все ссылки на актуальные PDF замен.
pub fn fetch_pdf_urls() -> Result<Vec<String>> {
    let html = fetch_page(PAGE_URL)?;
    let urls = extract_pdf_links(&html);
    if urls.is_empty() {
        bail!("на странице замен не найдено ни одной ссылки на PDF");
    }
    Ok(urls)
}

/// Скачивание PDF с контролем размера.
pub fn download_pdf(url: &str) -> Result<Vec<u8>> {
    let agent = agent();
    let response = agent
        .get(url)
        .call()
        .with_context(|| format!("не удалось скачать PDF {url}"))?;

    let mut bytes: Vec<u8> = Vec::new();
    response
        .into_reader()
        .take(MAX_PDF_BYTES + 1)
        .read_to_end(&mut bytes)
        .with_context(|| format!("не удалось прочитать PDF {url}"))?;

    if bytes.len() as u64 > MAX_PDF_BYTES {
        bail!("размер PDF превышает лимит 20 МБ: {url}");
    }
    if bytes.is_empty() {
        bail!("сервер вернул пустой файл: {url}");
    }
    Ok(bytes)
}

/// Текст из PDF. `pdf-extract` иногда паникует на битых файлах — ловим это.
pub fn pdf_text(bytes: &[u8]) -> Result<String> {
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        pdf_extract::extract_text_from_mem(bytes)
    }));

    match outcome {
        Ok(Ok(text)) => Ok(text),
        Ok(Err(error)) => bail!("не удалось извлечь текст из PDF: {error:?}"),
        Err(_) => bail!("парсер PDF аварийно завершился на этом файле"),
    }
}

/// Результат полного цикла «страница → PDF → текст».
#[derive(Debug, Clone, Default)]
pub struct FetchOutcome {
    /// Найденные ссылки на PDF (от старых к новым).
    pub urls: Vec<String>,
    /// Склеенный текст всех успешно разобранных PDF.
    pub text: String,
    /// Сколько PDF удалось прочитать.
    pub parsed_files: usize,
    /// Ошибки по отдельным файлам — показываются в интерфейсе.
    pub errors: Vec<String>,
}

impl FetchOutcome {
    /// Имя последнего файла — удобно показать в статусе.
    pub fn last_file_name(&self) -> Option<&str> {
        self.urls.last().map(|url| {
            url.rsplit('/').next().unwrap_or(url)
        })
    }
}

/// Полный цикл загрузки расписания.
pub fn fetch_schedule_text() -> Result<FetchOutcome> {
    let urls = fetch_pdf_urls()?;
    let mut outcome = FetchOutcome {
        urls: urls.clone(),
        ..FetchOutcome::default()
    };

    for url in &urls {
        match download_pdf(url).and_then(|bytes| pdf_text(&bytes)) {
            Ok(text) if !text.trim().is_empty() => {
                outcome.text.push_str(&text);
                outcome.text.push_str("\n\n");
                outcome.parsed_files += 1;
            }
            Ok(_) => outcome.errors.push(format!("{url}: пустой текст")),
            Err(error) => outcome.errors.push(format!("{url}: {error:#}")),
        }
    }

    if outcome.text.trim().is_empty() {
        bail!(
            "не удалось получить текст расписания ({} файл(ов) не прочитано)",
            urls.len()
        );
    }
    Ok(outcome)
}

/// Текущая дата в формате, который понимает парсер (`22.11.2026`).
pub fn today_header() -> String {
    datetime::format_date_iso(chrono::Local::now().date_naive())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_relative_links() {
        assert_eq!(
            resolve_url("//college-ripo.by/a.pdf"),
            "https://college-ripo.by/a.pdf"
        );
        assert_eq!(
            resolve_url("/wp-content/uploads/a.pdf"),
            "https://college-ripo.by/wp-content/uploads/a.pdf"
        );
        assert_eq!(
            resolve_url("wp-content/uploads/a.pdf"),
            "https://college-ripo.by/wp-content/uploads/a.pdf"
        );
        assert_eq!(
            resolve_url("http://college-ripo.by/a.pdf"),
            "https://college-ripo.by/a.pdf"
        );
    }

    #[test]
    fn extracts_and_sorts_pdf_links() {
        // Даты в именах файлов в формате dd.MM.yy — по ним и сортируем.
        let html = r#"
            <a class="wp-block-file__button" href="/wp-content/uploads/2026/11/raspisanie-19.11.26.pdf">Скачать</a>
            <a href="/wp-content/uploads/2026/11/raspisanie-23.11.26.pdf" class="wp-block-file__button">Скачать</a>
            <object data="/wp-content/uploads/2026/10/raspisanie.pdf"></object>
            <a href="/wp-content/uploads/2026/11/other-file.pdf">не расписание</a>
        "#;
        let links = extract_pdf_links(html);
        assert!(links.iter().all(|link| link.starts_with("https://")));
        assert_eq!(links.len(), 3, "получено: {links:?}");
        // Самый старый — по пути /uploads/2026/10/, остальные по дате в имени.
        assert!(links[0].ends_with("raspisanie.pdf"), "получено: {links:?}");
        assert!(links[2].contains("23.11.26"), "получено: {links:?}");
    }

    #[test]
    fn tenant_filter_drops_unrelated_files() {
        let html = r#"<object data="/wp-content/uploads/2026/11/hello.pdf"></object>"#;
        assert!(extract_pdf_links(html).is_empty());
    }
}