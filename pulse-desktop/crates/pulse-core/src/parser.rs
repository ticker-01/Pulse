//! Парсер текста расписания — построчный порт `ScheduleViewModel.kt`.
//!
//! Отличия от оригинала только технические:
//! * `(?=РАСПИСАНИЕ\s+НА)` (lookahead) в крейде `regex` не поддерживается,
//!   поэтому [`split_sections`] делает то же самое руками;
//! * все регулярки собраны в `LazyLock`, чтобы компилироваться один раз;
//! * вместо `SharedPreferences` расписание звонков передаётся параметром
//!   [`BellConfig`] — ядро остаётся без глобального состояния.
//!
//! Алгоритм тот же, что в Android-версии: строки → `ParsedRow` → блоки
//! (`GroupChunk`) → пары с временем из звонков, со склейкой соседних часов
//! в одну пару, если предмет и преподаватель совпадают.

use chrono::NaiveDate;
use regex::Regex;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::LazyLock;

use crate::bells::{BellConfig, BellSlot};
use crate::datetime;
use crate::model::{LessonPair, ScheduleCache, Teacher};

fn re(pattern: &str) -> Regex {
    Regex::new(pattern).expect("регулярное выражение из порта должно компилироваться")
}

static GROUP_PREFIX: LazyLock<Regex> = LazyLock::new(|| re(r"(?i)^(?:гр\.?|группа)\s*"));
static VALID_GROUP: LazyLock<Regex> = LazyLock::new(|| re(r"^[0-9]{2,4}[а-яА-Яa-zA-Z]?$"));
static CLEAN_SUBJECT: LazyLock<Regex> = LazyLock::new(|| re(r"^[0-9\s.\-│|]+"));
static PIPE: LazyLock<Regex> = LazyLock::new(|| re(r"[│|]"));
static MULTI_SPACES: LazyLock<Regex> = LazyLock::new(|| re(r"\s+"));
static SPACES_TABS: LazyLock<Regex> = LazyLock::new(|| re(r"[ \t]+"));
static TEACHER_NAME: LazyLock<Regex> =
    LazyLock::new(|| re(r"([А-ЯЁ][а-яё\-]+)\s+([А-ЯЁ])\.?\s*([А-ЯЁ])?\.?"));
static TEACHER_INITIALS: LazyLock<Regex> =
    LazyLock::new(|| re(r"[А-ЯЁ][а-яё\-]+\s+[А-ЯЁ]\s*\.?\s*[А-ЯЁ]?\s*\.?"));
static JUNK_CHARS: LazyLock<Regex> = LazyLock::new(|| re(r"[0-9├─│|No]+"));
static LESSON_TOKENS: LazyLock<Regex> = LazyLock::new(|| re(r"[\s,\-]+"));

static GROUP_AT_START: LazyLock<Regex> =
    LazyLock::new(|| re(r"^((?:гр\.?\s*)?[0-9]{2,4}[а-яА-Яa-zA-Z]?)\s*[│|]"));
static SCHEDULE_HEADER: LazyLock<Regex> = LazyLock::new(|| re(r"(?i)РАСПИСАНИЕ\s+НА"));
static HEADER_DATE: LazyLock<Regex> = LazyLock::new(|| re(r"([0-9]{2}\.[0-9]{2}\.[0-9]{2,4})"));

static BROKEN_LINE_P1: LazyLock<Regex> = LazyLock::new(|| {
    re(r"^((?:гр\.?\s*)?[0-9]{2,4}[а-яА-Яa-zA-Z]?|-)?\s*([0-9]{1,4}(?:\s+[0-9]{1,2})?)\s+([А-Яа-яЁёA-Za-z0-9.\-/ ]+?)\s+([0-9]{2,4}[а-яА-Я]?|[МM][0-9]+[а-яА-Я]?|—|-)\s+([А-ЯЁ][а-яё\-]+\s+[А-ЯЁ]\.?\s*[А-ЯЁ]?\.?.*)$")
});
static BROKEN_LINE_P2: LazyLock<Regex> = LazyLock::new(|| {
    re(r"^([0-9]{1,4}(?:\s+[0-9]{1,2})?)\s+([А-Яа-яЁёA-Za-z0-9.\-/ ]+?)\s+([0-9]{2,4}[а-яА-Я]?|[МM][0-9]+[а-яА-Я]?|—|-)\s+([А-ЯЁ][а-яё\-]+\s+[А-ЯЁ]\.?\s*[А-ЯЁ]?\.?.*)$")
});
static BROKEN_LINE_P3: LazyLock<Regex> = LazyLock::new(|| {
    re(r"^([0-9]{1,4}(?:\s+[0-9]{1,2})?)\s+([А-Яа-яЁёA-Za-z0-9.\-/ ]+?)\s+([А-ЯЁ][а-яё\-]+\s+[А-ЯЁ]\.?\s*[А-ЯЁ]?\.?.*)$")
});

/// Одна распознанная строка таблицы.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedRow {
    pub group: String,
    pub lesson_nums: Vec<u32>,
    pub subject: String,
    pub room: String,
    pub teacher: String,
}

/// Непрерывный кусок таблицы, относящийся к одной группе.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct GroupChunk {
    pub detected_group: Option<String>,
    pub rows: Vec<ParsedRow>,
    pub max_lesson: u32,
}

/// `key` для «схлопывания» преподавателя: без пробелов и точек, в нижнем регистре.
pub fn normalize_teacher_key(name: &str) -> String {
    name.chars()
        .filter(|c| !c.is_whitespace() && *c != '.')
        .collect::<String>()
        .to_lowercase()
}

pub fn is_valid_group(raw: &str) -> bool {
    let without_prefix = GROUP_PREFIX.replace_all(raw.trim(), "");
    VALID_GROUP.is_match(without_prefix.trim())
}

pub fn normalize_group(raw: &str) -> String {
    let without_prefix = GROUP_PREFIX.replace_all(raw.trim(), "");
    without_prefix.trim().to_lowercase()
}

/// Сопоставление групп: точное совпадение после нормализации либо совпадение
/// цифр (≥2) при пустой или совпадающей букве.
pub fn is_group_match(detected: Option<&str>, target: &str) -> bool {
    let detected = match detected {
        Some(value) if !value.trim().is_empty() => value,
        _ => return false,
    };
    if target.trim().is_empty() {
        return false;
    }

    let detected_norm = normalize_group(detected);
    let target_norm = normalize_group(target);
    if detected_norm == target_norm {
        return true;
    }

    let digits = |value: &str| -> String { value.chars().filter(|c| c.is_numeric()).collect() };
    let letters = |value: &str| -> String { value.chars().filter(|c| c.is_alphabetic()).collect() };

    let detected_digits = digits(&detected_norm);
    let target_digits = digits(&target_norm);
    if detected_digits.chars().count() >= 2
        && target_digits.chars().count() >= 2
        && detected_digits == target_digits
    {
        let target_letters = letters(&target_norm);
        let detected_letters = letters(&detected_norm);
        return target_letters.is_empty() || target_letters == detected_letters;
    }

    false
}

pub fn clean_subject_name(raw: &str) -> String {
    let step = CLEAN_SUBJECT.replace(raw, "");
    let step = PIPE.replace_all(&step, "");
    let step = MULTI_SPACES.replace_all(&step, " ");
    let cleaned = step.trim();
    if cleaned.is_empty() || cleaned == "√" {
        String::new()
    } else {
        cleaned.to_string()
    }
}

/// "иванов и.и." → "Иванов И.И."
pub fn normalize_teacher_name(raw_name: &str) -> String {
    let trimmed = raw_name.trim();
    if let Some(caps) = TEACHER_NAME.captures(trimmed) {
        let surname = caps.get(1).map(|m| m.as_str()).unwrap_or("");
        let initial1 = caps.get(2).map(|m| m.as_str()).unwrap_or("");
        let initial2 = caps.get(3).map(|m| m.as_str()).unwrap_or("");
        return if initial2.is_empty() {
            format!("{surname} {initial1}.")
        } else {
            format!("{surname} {initial1}.{initial2}.")
        };
    }
    MULTI_SPACES.replace_all(trimmed, " ").trim().to_string()
}

/// Список преподавателей из ячейки: ФИО с инициалами, если удалось их найти.
pub fn extract_teacher_names(raw: &str) -> Vec<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed == "—" || trimmed == "√" {
        return Vec::new();
    }

    let found: Vec<String> = TEACHER_INITIALS
        .find_iter(raw)
        .map(|m| normalize_teacher_name(m.as_str()))
        .collect();
    if !found.is_empty() {
        return found;
    }

    let without_junk = JUNK_CHARS.replace_all(raw, "");
    let cleaned = MULTI_SPACES
        .replace_all(&without_junk, " ")
        .trim()
        .to_string();
    let lower = cleaned.to_lowercase();
    // В этих ячейках вместо преподавателя стоит название предмета.
    if lower.contains("математ")
        || lower.contains("рус.лит")
        || lower.contains("физкульт")
        || lower.contains("нормир")
    {
        return Vec::new();
    }

    if cleaned.chars().count() >= 4 {
        vec![normalize_teacher_name(&cleaned)]
    } else {
        Vec::new()
    }
}

/// Номера пар из ячейки: "1", "2,3", "1 2" → [1, 2], "15" → [1, 5].
pub fn parse_lesson_numbers(raw: &str) -> Vec<u32> {
    let clean = raw.trim();
    if clean.is_empty() {
        return Vec::new();
    }

    let mut result: Vec<u32> = Vec::new();
    for token in LESSON_TOKENS.split(clean).filter(|t| !t.is_empty()) {
        let digits: String = token.chars().filter(|c| c.is_numeric()).collect();
        let Ok(number) = digits.parse::<u32>() else {
            continue;
        };
        if (1..=14).contains(&number) {
            if !result.contains(&number) {
                result.push(number);
            }
        } else if number > 14 {
            for ch in token.chars().filter(|c| c.is_numeric()) {
                if let Some(digit) = ch.to_digit(10) {
                    if (1..=14).contains(&digit) && !result.contains(&digit) {
                        result.push(digit);
                    }
                }
            }
        }
    }
    result
}

/// Разбиение текста перед каждым «РАСПИСАНИЕ НА» (ручная замена lookahead).
pub fn split_sections(raw: &str) -> Vec<&str> {
    let starts: Vec<usize> = SCHEDULE_HEADER.find_iter(raw).map(|m| m.start()).collect();
    if starts.is_empty() {
        return vec![raw];
    }

    let mut sections = Vec::with_capacity(starts.len() + 1);
    if starts[0] > 0 {
        sections.push(&raw[..starts[0]]);
    }
    for (index, start) in starts.iter().enumerate() {
        let end = starts.get(index + 1).copied().unwrap_or(raw.len());
        sections.push(&raw[*start..end]);
    }
    sections
}

fn strip_pipes(value: &str) -> String {
    let value = value.strip_prefix('│').unwrap_or(value);
    let value = value.strip_prefix('|').unwrap_or(value);
    let value = value.trim();
    let value = value.strip_suffix('│').unwrap_or(value);
    let value = value.strip_suffix('|').unwrap_or(value);
    value.trim().to_string()
}

fn unique_join(items: impl Iterator<Item = String>) -> String {
    let mut seen: Vec<String> = Vec::new();
    for item in items {
        if !seen.contains(&item) {
            seen.push(item);
        }
    }
    seen.join(" / ")
}

fn or_dash(value: String) -> String {
    if value.is_empty() {
        "—".to_string()
    } else {
        value
    }
}

fn try_parse_broken_line(line: &str) -> Option<[String; 5]> {
    let without_pipes = PIPE.replace_all(line, " ");
    let clean_line = MULTI_SPACES
        .replace_all(&without_pipes, " ")
        .trim()
        .to_string();

    let pick = |caps: &regex::Captures<'_>, index: usize| -> String {
        caps.get(index)
            .map(|m| m.as_str().trim().to_string())
            .unwrap_or_default()
    };

    if let Some(caps) = BROKEN_LINE_P1.captures(&clean_line) {
        return Some([pick(&caps, 1), pick(&caps, 2), pick(&caps, 3), pick(&caps, 4), pick(&caps, 5)]);
    }
    if let Some(caps) = BROKEN_LINE_P2.captures(&clean_line) {
        return Some([
            String::new(),
            pick(&caps, 1),
            pick(&caps, 2),
            pick(&caps, 3),
            pick(&caps, 4),
        ]);
    }
    if let Some(caps) = BROKEN_LINE_P3.captures(&clean_line) {
        return Some([
            String::new(),
            pick(&caps, 1),
            pick(&caps, 2),
            "—".to_string(),
            pick(&caps, 3),
        ]);
    }
    None
}

/// Строки таблицы из одного раздела PDF-текста.
pub fn extract_parsed_rows(section_text: &str) -> Vec<ParsedRow> {
    let normalized = section_text.replace("\r\n", "\n").replace('\r', "\n");
    let normalized = SPACES_TABS.replace_all(&normalized, " ");

    let mut result: Vec<ParsedRow> = Vec::new();
    let mut last_lesson_nums: Vec<u32> = vec![1];

    for line in normalized.split('\n') {
        let mut clean = line.trim().to_string();
        if clean.is_empty() {
            continue;
        }

        let lower = clean.to_lowercase();
        if lower.contains("no") && lower.contains("гр") {
            continue;
        }
        if clean.contains('№') && lower.contains("гр") {
            continue;
        }
        if lower.contains("предмет") && lower.contains("преподаватель") {
            continue;
        }
        if lower.contains("расписание") || lower.contains("утверждаю") {
            continue;
        }

        let mut group = String::new();
        let mut num_str = String::new();
        let mut subject = String::new();
        let mut room = String::new();
        let mut teacher = String::new();

        if let Some(caps) = GROUP_AT_START.captures(&clean) {
            let candidate = caps.get(1).map(|m| m.as_str()).unwrap_or("").trim().to_string();
            if is_valid_group(&candidate) {
                group = candidate;
                if let Some(full) = caps.get(0) {
                    clean = clean[full.end()..].trim().to_string();
                }
            }
        }

        clean = strip_pipes(&clean);
        let cells: Vec<String> = PIPE.split(&clean).map(|c| c.trim().to_string()).collect();

        if cells.len() == 1 && is_valid_group(&clean) {
            result.push(ParsedRow {
                group: clean.clone(),
                lesson_nums: last_lesson_nums.clone(),
                subject: String::new(),
                room: String::new(),
                teacher: String::new(),
            });
            continue;
        }

        if cells.len() >= 5 {
            if is_valid_group(&cells[0]) {
                if group.is_empty() {
                    group = cells[0].clone();
                }
                num_str = cells[1].clone();
                subject = cells[2].clone();
                room = cells[3].clone();
                teacher = cells[4].clone();
            } else if !parse_lesson_numbers(&cells[0]).is_empty() {
                num_str = cells[0].clone();
                subject = cells[1].clone();
                room = cells[2].clone();
                teacher = cells[3].clone();
            } else {
                num_str = cells[1].clone();
                subject = cells[2].clone();
                room = cells[3].clone();
                teacher = cells[4].clone();
            }
        } else if cells.len() == 4 {
            if !parse_lesson_numbers(&cells[0]).is_empty() {
                num_str = cells[0].clone();
                subject = cells[1].clone();
                room = cells[2].clone();
                teacher = cells[3].clone();
            } else if is_valid_group(&cells[0]) {
                if group.is_empty() {
                    group = cells[0].clone();
                }
                num_str = cells[1].clone();
                subject = cells[2].clone();
                room = "—".to_string();
                teacher = cells[3].clone();
            } else {
                num_str = cells[1].clone();
                subject = cells[2].clone();
                room = cells[3].clone();
                teacher = String::new();
            }
        } else if cells.len() == 3 {
            if !parse_lesson_numbers(&cells[0]).is_empty() {
                num_str = cells[0].clone();
                subject = cells[1].clone();
                room = "—".to_string();
                teacher = cells[2].clone();
            }
        } else if let Some(broken) = try_parse_broken_line(&clean) {
            if group.is_empty() && is_valid_group(&broken[0]) {
                group = broken[0].clone();
            }
            num_str = broken[1].clone();
            subject = broken[2].clone();
            room = broken[3].clone();
            teacher = broken[4].clone();
        } else if is_valid_group(&clean) {
            group = clean.clone();
        }

        let parsed_nums = parse_lesson_numbers(&num_str);
        let final_nums = if parsed_nums.is_empty() {
            last_lesson_nums.clone()
        } else {
            last_lesson_nums = parsed_nums.clone();
            parsed_nums
        };

        let clean_subject = clean_subject_name(&subject);
        if !clean_subject.is_empty() || !group.is_empty() {
            result.push(ParsedRow {
                group,
                lesson_nums: final_nums,
                subject: clean_subject,
                room,
                teacher,
            });
        }
    }

    result
}

/// Непрерывные блоки строк, относящиеся к одной группе.
pub fn extract_table_chunks(section_text: &str) -> Vec<GroupChunk> {
    let rows = extract_parsed_rows(section_text);
    let mut chunks: Vec<GroupChunk> = Vec::new();
    let mut current = GroupChunk::default();

    for row in rows {
        let row_group = if is_valid_group(&row.group) {
            row.group.clone()
        } else {
            String::new()
        };
        let first_lesson = row.lesson_nums.first().copied().unwrap_or(1);

        let is_new_chunk = if current.rows.is_empty() {
            false
        } else if !row_group.is_empty()
            && current.detected_group.is_some()
            && !is_group_match(current.detected_group.as_deref(), &row_group)
        {
            true
        } else {
            first_lesson < current.max_lesson
        };

        if is_new_chunk {
            chunks.push(std::mem::take(&mut current));
        }

        if !row_group.is_empty() {
            current.detected_group = Some(row_group);
        }
        let row_max = row.lesson_nums.iter().copied().max().unwrap_or(first_lesson);
        if row_max > current.max_lesson {
            current.max_lesson = row_max;
        }
        current.rows.push(row);
    }

    if !current.rows.is_empty() {
        chunks.push(current);
    }
    chunks
}

fn header_date_of(section: &str) -> Option<NaiveDate> {
    let head: String = section.chars().take(300).collect();
    let caps = HEADER_DATE.captures(&head)?;
    datetime::parse_date(caps.get(1)?.as_str())
}

fn clean_room(raw: &str) -> String {
    let without_pipes = PIPE.replace_all(raw, "");
    let trimmed = without_pipes.trim();
    if trimmed.is_empty() {
        "—".to_string()
    } else {
        trimmed.to_string()
    }
}

/// Итоговое имя преподавателя для пары, включая «запасной» вариант как в Kotlin.
fn display_teacher(raw: &str) -> String {
    let names = extract_teacher_names(raw);
    if !names.is_empty() {
        return names.join(" / ");
    }
    let junk = JUNK_CHARS.replace_all(raw, "");
    let cleaned = junk.trim();
    if cleaned.chars().count() > 2 {
        cleaned.to_string()
    } else {
        "—".to_string()
    }
}

/// Время пары берётся из звонков дня; если звонков меньше, чем пар — `??:??`.
struct BellTimings {
    slots: Vec<BellSlot>,
}

impl BellTimings {
    fn new(bells: &BellConfig, date: NaiveDate) -> Self {
        Self {
            slots: bells.slots_for(date),
        }
    }

    fn timing(&self, hour: u32) -> (String, String) {
        match hour
            .checked_sub(1)
            .and_then(|index| self.slots.get(index as usize))
        {
            Some(slot) => (slot.start.clone(), slot.end.clone()),
            None => ("??:??".to_string(), "??:??".to_string()),
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn make_pair(
    hours: &[u32],
    timings: &BellTimings,
    number: u32,
    subject: &str,
    room: &str,
    teacher: &str,
    group: &str,
) -> LessonPair {
    let (start, _) = timings.timing(hours[0]);
    let (_, end) = timings.timing(*hours.last().unwrap());
    LessonPair {
        pair_number: number,
        lessons: hours
            .iter()
            .map(|h| h.to_string())
            .collect::<Vec<_>>()
            .join(", "),
        start_time: start,
        end_time: end,
        subject: subject.to_string(),
        room: room.to_string(),
        teacher: teacher.to_string(),
        group: group.to_string(),
    }
}

/// Склейка часов в пары для студента: соседние часы с тем же предметом и
/// преподавателем объединяются в один блок.
fn build_pairs(
    lessons_by_hour: &BTreeMap<u32, Vec<(String, String, String)>>,
    timings: &BellTimings,
) -> Vec<LessonPair> {
    let mut block_hours: Vec<u32> = Vec::new();
    let mut current_subject = String::new();
    let mut current_room = String::new();
    let mut current_teacher = String::new();
    let mut pair_counter: u32 = 1;
    let mut daily: Vec<LessonPair> = Vec::new();

    for (hour, entries) in lessons_by_hour {
        let subjects = unique_join(entries.iter().map(|e| e.0.clone()));
        let rooms = or_dash(unique_join(entries.iter().map(|e| e.1.clone())));
        let teachers = or_dash(unique_join(entries.iter().map(|e| e.2.clone())));

        if block_hours.is_empty() {
            block_hours.push(*hour);
            current_subject = subjects;
            current_room = rooms;
            current_teacher = teachers;
            continue;
        }

        let last_hour = *block_hours.last().unwrap();
        if *hour == last_hour + 1 && subjects == current_subject && teachers == current_teacher {
            block_hours.push(*hour);
        } else {
            daily.push(make_pair(
                &block_hours,
                timings,
                pair_counter,
                &current_subject,
                &current_room,
                &current_teacher,
                "",
            ));
            pair_counter += 1;

            block_hours = vec![*hour];
            current_subject = subjects;
            current_room = rooms;
            current_teacher = teachers;
        }
    }

    if !block_hours.is_empty() {
        daily.push(make_pair(
            &block_hours,
            timings,
            pair_counter,
            &current_subject,
            &current_room,
            &current_teacher,
            "",
        ));
    }

    daily
}

/// Склейка часов в пары для преподавателя: сравниваются предмет и группа.
fn build_pairs_for_teacher(
    lessons_by_hour: &BTreeMap<u32, Vec<(String, String, String)>>,
    timings: &BellTimings,
    teacher: &str,
) -> Vec<LessonPair> {
    let mut block_hours: Vec<u32> = Vec::new();
    let mut current_subject = String::new();
    let mut current_room = String::new();
    let mut current_group = String::new();
    let mut pair_counter: u32 = 1;
    let mut daily: Vec<LessonPair> = Vec::new();

    for (hour, entries) in lessons_by_hour {
        let subjects = unique_join(entries.iter().map(|e| e.0.clone()));
        let rooms = or_dash(unique_join(entries.iter().map(|e| e.1.clone())));
        let groups = or_dash(unique_join(entries.iter().map(|e| e.2.clone())));

        if block_hours.is_empty() {
            block_hours.push(*hour);
            current_subject = subjects;
            current_room = rooms;
            current_group = groups;
            continue;
        }

        let last_hour = *block_hours.last().unwrap();
        if *hour == last_hour + 1 && subjects == current_subject && groups == current_group {
            block_hours.push(*hour);
        } else {
            daily.push(make_pair(
                &block_hours,
                timings,
                pair_counter,
                &current_subject,
                &current_room,
                teacher,
                &current_group,
            ));
            pair_counter += 1;

            block_hours = vec![*hour];
            current_subject = subjects;
            current_room = rooms;
            current_group = groups;
        }
    }

    if !block_hours.is_empty() {
        daily.push(make_pair(
            &block_hours,
            timings,
            pair_counter,
            &current_subject,
            &current_room,
            teacher,
            &current_group,
        ));
    }

    daily
}

fn sort_and_renumber(lessons: &mut [LessonPair]) {
    lessons.sort_by_key(|pair| pair.start_local_time());
    for (index, pair) in lessons.iter_mut().enumerate() {
        pair.pair_number = index as u32 + 1;
    }
}

/// Разбор всего текста: дата (из шапки) → пары выбранной группы.
pub fn parse_raw_text(raw: &str, target_group: &str, bells: &BellConfig) -> ScheduleCache {
    let mut result: ScheduleCache = BTreeMap::new();
    if raw.trim().is_empty() || target_group.trim().is_empty() {
        return result;
    }

    for section in split_sections(raw) {
        let Some(date) = header_date_of(section) else {
            continue;
        };

        let chunks = extract_table_chunks(section);
        let matching: Vec<&GroupChunk> = chunks
            .iter()
            .filter(|chunk| is_group_match(chunk.detected_group.as_deref(), target_group))
            .collect();
        if matching.is_empty() {
            continue;
        }

        let mut lessons_by_hour: BTreeMap<u32, Vec<(String, String, String)>> = BTreeMap::new();
        for chunk in matching {
            for row in &chunk.rows {
                if row.subject.trim().is_empty() {
                    continue;
                }
                let room = clean_room(&row.room);
                let teacher = display_teacher(&row.teacher);
                for number in &row.lesson_nums {
                    if (1..=14).contains(number) {
                        lessons_by_hour
                            .entry(*number)
                            .or_default()
                            .push((row.subject.clone(), room.clone(), teacher.clone()));
                    }
                }
            }
        }

        let timings = BellTimings::new(bells, date);
        let daily = build_pairs(&lessons_by_hour, &timings);
        if !daily.is_empty() {
            result.entry(date).or_default().extend(daily);
        }
    }

    for lessons in result.values_mut() {
        sort_and_renumber(lessons);
    }
    result
}

/// Все группы, найденные в тексте (для выпадающего списка).
pub fn extract_all_groups(raw: &str) -> Vec<String> {
    let mut found: Vec<String> = Vec::new();
    if raw.trim().is_empty() {
        return found;
    }

    for section in split_sections(raw) {
        for chunk in extract_table_chunks(section) {
            if let Some(group) = &chunk.detected_group {
                let trimmed = group.trim();
                if !trimmed.is_empty() && is_valid_group(trimmed) {
                    let upper = trimmed.to_uppercase();
                    if !found.contains(&upper) {
                        found.push(upper);
                    }
                }
            }
        }
    }

    found.sort_by(|a, b| {
        let number = |value: &str| -> u64 {
            value
                .chars()
                .filter(|c| c.is_numeric())
                .collect::<String>()
                .parse::<u64>()
                .unwrap_or(0)
        };
        number(a).cmp(&number(b)).then_with(|| a.cmp(b))
    });
    found
}

/// Расписание конкретного преподавателя (порт `getTeacherSchedule`).
pub fn get_teacher_schedule(raw: &str, target_teacher: &str, bells: &BellConfig) -> ScheduleCache {
    let mut result: ScheduleCache = BTreeMap::new();
    if raw.trim().is_empty() || target_teacher.trim().is_empty() {
        return result;
    }
    let target_norm = normalize_teacher_key(target_teacher);

    for section in split_sections(raw) {
        let Some(date) = header_date_of(section) else {
            continue;
        };

        let chunks = extract_table_chunks(section);
        let mut lessons_by_hour: BTreeMap<u32, Vec<(String, String, String)>> = BTreeMap::new();

        for chunk in &chunks {
            let group_name = chunk
                .detected_group
                .clone()
                .unwrap_or_else(|| "—".to_string());
            for row in &chunk.rows {
                let names = extract_teacher_names(&row.teacher);
                let matches = names
                    .iter()
                    .any(|name| normalize_teacher_key(name) == target_norm)
                    || normalize_teacher_key(&row.teacher).contains(&target_norm);
                if !matches || row.subject.trim().is_empty() {
                    continue;
                }
                let room = clean_room(&row.room);
                for number in &row.lesson_nums {
                    if (1..=14).contains(number) {
                        lessons_by_hour.entry(*number).or_default().push((
                            row.subject.clone(),
                            room.clone(),
                            group_name.clone(),
                        ));
                    }
                }
            }
        }

        if lessons_by_hour.is_empty() {
            continue;
        }

        let timings = BellTimings::new(bells, date);
        let daily = build_pairs_for_teacher(&lessons_by_hour, &timings, target_teacher);
        if !daily.is_empty() {
            result.entry(date).or_default().extend(daily);
        }
    }

    for lessons in result.values_mut() {
        let mut seen: BTreeSet<(String, String, String)> = BTreeSet::new();
        lessons.retain(|pair| {
            seen.insert((
                pair.start_time.clone(),
                pair.subject.clone(),
                pair.lessons.clone(),
            ))
        });
        sort_and_renumber(lessons);
    }

    result
}

/// Собирает справочник преподавателей из текста (порт `extractAndSaveTeachers`).
///
/// Цвет аватара в оригинале случайный; здесь он детерминированно выводится из
/// имени, чтобы список не «перекрашивался» после каждого обновления.
pub fn extract_teachers(raw: &str, previous: &[Teacher]) -> Vec<Teacher> {
    const AVATAR_COLORS: [u32; 8] = [
        0xFFE91E63, 0xFF4CAF50, 0xFF2196F3, 0xFFFF9800, 0xFF9C27B0, 0xFF795548, 0xFF607D8B,
        0xFF3F51B5,
    ];

    let mut collected: Vec<(String, Vec<String>, Vec<String>)> = Vec::new();

    for section in split_sections(raw) {
        for row in extract_parsed_rows(section) {
            if row.subject.trim().is_empty() {
                continue;
            }
            let room = clean_room(&row.room);
            for name in extract_teacher_names(&row.teacher) {
                if !collected.iter().any(|(existing, _, _)| *existing == name) {
                    collected.push((name.clone(), Vec::new(), Vec::new()));
                }
                if let Some(entry) = collected.iter_mut().find(|(existing, _, _)| *existing == name) {
                    if !entry.1.contains(&row.subject) {
                        entry.1.push(row.subject.clone());
                    }
                    if room != "—" && !entry.2.contains(&room) {
                        entry.2.push(room.clone());
                    }
                }
            }
        }
    }

    let mut teachers: Vec<Teacher> = Vec::new();
    for (name, subjects, rooms) in collected {
        let old = previous.iter().find(|t| t.name == name);
        let color = old.map(|t| t.avatar_color).unwrap_or_else(|| {
            let hash = name
                .bytes()
                .fold(0u32, |acc, byte| acc.wrapping_mul(31).wrapping_add(byte as u32));
            AVATAR_COLORS[(hash as usize) % AVATAR_COLORS.len()]
        });
        teachers.push(Teacher {
            name,
            subject: subjects.join(", "),
            room: rooms.join(", "),
            email: old.map(|t| t.email.clone()).unwrap_or_else(|| "-".to_string()),
            avatar_color: color,
        });
    }

    // Преподаватели, которых в новом тексте нет, сохраняются с пустыми полями.
    for old in previous {
        if !teachers.iter().any(|t| t.name == old.name) {
            teachers.push(Teacher {
                subject: String::new(),
                room: String::new(),
                ..old.clone()
            });
        }
    }

    teachers.sort_by(|a, b| a.name.cmp(&b.name));
    teachers
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Текст в том виде, в каком его даёт PDF-экстрактор: шапка, таблица с
    /// рамками, две группы подряд.
    const SAMPLE: &str = "\
РАСПИСАНИЕ НА 23.11.2026
УТВЕРЖДАЮ Директор

No гр. Предмет Преподаватель

│21э│1│Математика│201│Иванов И.И.│
│21э│2│Физика│202│Петров П.П.│
│21э│3│История│203│Сидоров С.С.│
│22э│1│Химия│301│Козлов К.К.│
│22э│2│Биология│302│Орлов О.О.│
";

    fn bells() -> BellConfig {
        BellConfig::default()
    }

    #[test]
    fn splits_sections_without_lookahead() {
        let text = format!("{SAMPLE}\n{SAMPLE}");
        let sections = split_sections(&text);
        assert_eq!(sections.iter().filter(|s| s.contains("РАСПИСАНИЕ")).count(), 2);
    }

    #[test]
    fn validates_and_matches_groups() {
        assert!(is_valid_group("21э"));
        assert!(is_valid_group("гр. 21э"));
        assert!(is_valid_group("1221"));
        assert!(!is_valid_group("математика"));
        assert_eq!(normalize_group("Гр.21Э"), "21э");

        assert!(is_group_match(Some("21э"), "21э"));
        assert!(is_group_match(Some("21Э"), "гр. 21э"));
        assert!(is_group_match(Some("21э"), "21"), "в цели буквы может не быть");
        assert!(
            !is_group_match(Some("21"), "21э"),
            "в обнаруженной группе буквы нет — как в Kotlin, это не совпадение"
        );
        assert!(!is_group_match(Some("21э"), "22э"));
        assert!(!is_group_match(None, "21э"));
    }

    #[test]
    fn parses_lesson_numbers_like_kotlin() {
        assert_eq!(parse_lesson_numbers("1"), vec![1]);
        assert_eq!(parse_lesson_numbers("2,3"), vec![2, 3]);
        assert_eq!(parse_lesson_numbers("1 2"), vec![1, 2]);
        assert_eq!(parse_lesson_numbers("15"), vec![1, 5]);
        assert!(parse_lesson_numbers("").is_empty());
        assert!(parse_lesson_numbers("—").is_empty());
    }

    #[test]
    fn extracts_teacher_names() {
        assert_eq!(extract_teacher_names("Иванов И.И."), vec!["Иванов И.И."]);
        assert_eq!(extract_teacher_names("Иванов И."), vec!["Иванов И."]);
        assert!(extract_teacher_names("—").is_empty());
        assert!(extract_teacher_names("математика").is_empty());
    }

    #[test]
    fn parses_sample_schedule_for_group() {
        let cache = parse_raw_text(SAMPLE, "21э", &bells());
        let date = NaiveDate::from_ymd_opt(2026, 11, 23).unwrap();
        let lessons = cache.get(&date).expect("день должен распарситься");

        assert_eq!(lessons.len(), 3);
        assert_eq!(lessons[0].subject, "Математика");
        assert_eq!(lessons[0].room, "201");
        assert_eq!(lessons[0].teacher, "Иванов И.И.");
        // 23.11.2026 — понедельник, звонки берутся из таблицы MONDAY.
        assert_eq!(lessons[0].start_time, "08:30");
        assert_eq!(lessons[0].end_time, "09:15");
        assert_eq!(lessons[1].start_time, "09:25");
        assert_eq!(lessons[2].group, "");

        // Другая группа — свои пары.
        let other = parse_raw_text(SAMPLE, "22э", &bells());
        let other_lessons = other.get(&date).unwrap();
        assert_eq!(other_lessons.len(), 2);
        assert_eq!(other_lessons[0].subject, "Химия");
        assert_eq!(other_lessons[0].start_time, "08:30");
    }

    #[test]
    fn merges_consecutive_hours_into_one_block() {
        let raw = "\
РАСПИСАНИЕ НА 24.11.2026
│21э│1│Практика│101│Иванов И.И.│
│21э│2│Практика│101│Иванов И.И.│
│21э│3│Физика│102│Петров П.П.│
";
        let cache = parse_raw_text(raw, "21э", &bells());
        let date = NaiveDate::from_ymd_opt(2026, 11, 24).unwrap();
        let lessons = cache.get(&date).unwrap();

        assert_eq!(lessons.len(), 2, "часы 1 и 2 должны склеиться");
        assert_eq!(lessons[0].lessons, "1, 2");
        assert_eq!(lessons[0].start_time, "08:30");
        assert_eq!(lessons[0].end_time, "10:10");
        assert_eq!(lessons[1].lessons, "3");
    }

    #[test]
    fn collects_all_groups_sorted_by_number() {
        // Группа «09э» валидна (2 цифры + буква) и должна идти первой по номеру.
        let raw = format!("{SAMPLE}\nРАСПИСАНИЕ НА 24.11.2026\n│09э│1│Химия│1│Козлов К.К.│\n");
        let groups = extract_all_groups(&raw);
        assert_eq!(groups, vec!["09Э", "21Э", "22Э"]);
    }

    #[test]
    fn builds_teacher_schedule() {
        let cache = get_teacher_schedule(SAMPLE, "Иванов И.И.", &bells());
        let date = NaiveDate::from_ymd_opt(2026, 11, 23).unwrap();
        let lessons = cache.get(&date).unwrap();
        assert_eq!(lessons.len(), 1);
        assert_eq!(lessons[0].subject, "Математика");
        assert_eq!(lessons[0].group, "21э");
        assert_eq!(lessons[0].teacher, "Иванов И.И.");
    }

    #[test]
    fn collects_teachers_with_rooms() {
        let teachers = extract_teachers(SAMPLE, &[]);
        let ivanov = teachers
            .iter()
            .find(|t| t.name == "Иванов И.И.")
            .expect("Иванов должен быть в списке");
        assert_eq!(ivanov.subject, "Математика");
        assert_eq!(ivanov.room, "201");
    }

    #[test]
    fn empty_or_wrong_group_gives_empty_schedule() {
        let cache = parse_raw_text(SAMPLE, "77х", &bells());
        assert!(cache.is_empty());
        assert!(parse_raw_text("", "21э", &bells()).is_empty());
        assert!(parse_raw_text(SAMPLE, "", &bells()).is_empty());
    }

    #[test]
    fn schedule_cache_roundtrips_through_json() {
        let cache = parse_raw_text(SAMPLE, "21э", &bells());
        let json = serde_json::to_string(&cache).unwrap();
        let back: ScheduleCache = serde_json::from_str(&json).unwrap();
        assert_eq!(cache, back);
    }
}