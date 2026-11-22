//! Эмодзи и цвет предмета — порт `utils/SubjectThemeHelper.kt`.
//!
//! Порядок проверок сохранён: он важен, потому что условия пересекаются
//! («физика» против «физкультуры», «история» против «литературы»).

use regex::Regex;
use std::sync::LazyLock;

static NON_WORD: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"[^а-яa-z0-9іў]").expect("регулярное выражение должно компилироваться")
});
static SPACES: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\s+").expect("регулярное выражение должно компилироваться"));

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SubjectTheme {
    pub emoji: &'static str,
    /// Цвет в формате `0xRRGGBB`.
    pub color: u32,
    pub category: &'static str,
}

impl SubjectTheme {
    const fn new(emoji: &'static str, color: u32, category: &'static str) -> Self {
        Self {
            emoji,
            color,
            category,
        }
    }
}

/// Нормализация названия: нижний регистр, `ё → е`, всё кроме букв/цифр → пробел.
pub fn normalize_subject(raw: &str) -> String {
    let lowered = raw.to_lowercase().replace('ё', "е");
    let spaced = NON_WORD.replace_all(&lowered, " ");
    SPACES.replace_all(&spaced, " ").trim().to_string()
}

/// Тема предмета: эмодзи, цвет и категория.
pub fn subject_theme(raw_subject: &str) -> SubjectTheme {
    let clean = normalize_subject(raw_subject);
    let exact = format!(" {clean} ");

    let has = |needle: &str| clean.contains(needle);
    let exact_has = |needle: &str| exact.contains(needle);

    if has("математ")
        || has("матем")
        || has("алгебр")
        || has("геометр")
        || has("матан")
        || has("дифур")
        || has("теорвер")
        || exact_has(" мат ")
    {
        return SubjectTheme::new("📐", 0xFF7C4DFF, "Математика");
    }

    if has("информат")
        || has("инф тех")
        || has("програм")
        || has("инфа")
        || has("cad")
        || has("cam")
        || has("плк")
        || has("счпу")
        || has("чпу")
        || has("спу")
        || exact_has(" пу ")
        || has("scada")
        || has("микропроцессор")
        || has("микроконтроллер")
        // В оригинале здесь было `contains("ит")`, что ловило «лит(ературу)».
        // Проверяем отдельный токен: предмет «ИТ» по-прежнему распознаётся.
        || exact_has(" ит ")
    {
        return SubjectTheme::new("💻", 0xFF00E676, "Информатика и IT");
    }

    if (has("физик") || exact_has(" физ "))
        && !has("физк")
        && !has("физкульт")
        && !exact_has(" фк ")
    {
        return SubjectTheme::new("⚡", 0xFFFFD600, "Физика");
    }

    if has("электро")
        || has("электрич")
        || has("схем")
        || has("аналог схем")
        || has("тоэ")
        || has("датч")
        || has("компан")
    {
        return SubjectTheme::new("💡", 0xFFFFAB00, "Электроника и датчики");
    }

    if has("хим") {
        return SubjectTheme::new("⚛", 0xFF00B0FF, "Химия");
    }

    if has("биол") || has("эколог") {
        return SubjectTheme::new("🌱", 0xFF4CAF50, "Биология");
    }

    if has("географ") || has("геолог") {
        return SubjectTheme::new("🌍", 0xFF26A69A, "География");
    }

    if has("астроном") || has("космос") {
        return SubjectTheme::new("🔭", 0xFF7E57C2, "Астрономия");
    }

    if has("черчен") || has("инж граф") || has("начерт") {
        return SubjectTheme::new("📏", 0xFF00BCD4, "Инженерная графика");
    }

    if has("охр труд")
        || has("охрана труд")
        || has("бжд")
        || has("обж")
        || has("безопас")
        || exact_has(" от ")
    {
        return SubjectTheme::new("🚧", 0xFFFF6D00, "Охрана труда");
    }

    if has("фкиз")
        || has("физк")
        || has("физкульт")
        || has("спорт")
        // Официальное название предмета в колледже: «Физическая культура».
        // В оригинале оно не распознавалось и попадало в «Учебный предмет».
        || has("физическая культур")
        || exact_has(" фк ")
    {
        return SubjectTheme::new("🏃", 0xFFFF1744, "Физическая культура");
    }

    if has("дпю") || has("допризыв") || has("воен") {
        return SubjectTheme::new("🎖", 0xFF689F38, "Допризывная подготовка");
    }

    if has("ин яз")
        || has("англ")
        || has("english")
        || has("немец")
        || has("иностр")
    {
        return SubjectTheme::new("🇬🇧", 0xFF2979FF, "Иностранный язык");
    }

    if has("б яз")
        || has("бел яз")
        || has("белорус")
        || has("рус яз")
        || has("русск яз")
        || has("язык")
    {
        return SubjectTheme::new("📝", 0xFFFF4081, "Языкознание");
    }

    if has("лит") || has("литерат") || has("р лит") || has("б лит") {
        return SubjectTheme::new("📖", 0xFFAB47BC, "Литература");
    }

    if has("ист бел") || has("истор") || exact_has(" ист ") {
        return SubjectTheme::new("📜", 0xFF8D6E63, "История");
    }

    if has("прав") || has("закон") || has("юрисп") || has("обществ") {
        return SubjectTheme::new("⚖", 0xFF607D8B, "Правоведение");
    }

    if has("эконом")
        || has("эк орг")
        || has("маркетинг")
        || has("финанс")
        || has("бухгалт")
        || has("менедж")
    {
        return SubjectTheme::new("📈", 0xFF43A047, "Экономика");
    }

    if has("лог") || has("упр зап") || has("расп тр") || has("орг хран") || has("склад") {
        return SubjectTheme::new("📦", 0xFFFF9100, "Логистика и хранение");
    }

    if has("устр тс")
        || has("тех об авт")
        || has("автомоб")
        || has("двигател")
        || has("авто")
        || has("вост тех")
    {
        return SubjectTheme::new("🚗", 0xFFF4511E, "Автотранспорт");
    }

    if has("серв стан") || has("эксп то") {
        return SubjectTheme::new("⛽", 0xFF009688, "Сервис и ТО");
    }

    if has("пдд") || has("дорожн") {
        return SubjectTheme::new("🚦", 0xFFE53935, "ПДД");
    }

    if has("м в") || has("материал") || exact_has(" мв ") {
        return SubjectTheme::new("🔬", 0xFF3949AB, "Материаловедение");
    }

    if has("тех маш")
        || has("маш")
        || has("спец тех")
        || has("детали маш")
        || has("станк")
        || has("токар")
        || has("фрезер")
    {
        return SubjectTheme::new("⚙", 0xFFFF6F00, "Машиностроение");
    }

    if has("наладк") || has("нал авт") || has("обор") {
        return SubjectTheme::new("🛠", 0xFF00ACC1, "Наладка оборудования");
    }

    if has("под тран") || has("кран") {
        return SubjectTheme::new("🔗", 0xFFFB8C00, "Подъемное оборудование");
    }

    if has("мехман") || has("мехатрон") || has("манипул") || has("робот") {
        return SubjectTheme::new("🕹", 0xFF00E5FF, "Мехатроника");
    }

    if has("мрс") || has("борт") || has("монтаж") || has("ремонт") {
        return SubjectTheme::new("🔌", 0xFF26C6DA, "Системы и монтаж");
    }

    if has("реж инстр") || has("омии") || has("инструм") {
        return SubjectTheme::new("🔧", 0xFFFF7043, "Инструменты и оснастка");
    }

    if has("оснастк") {
        return SubjectTheme::new("🔩", 0xFF78909C, "Техническая оснастка");
    }

    if has("нормир") || has("стандарт") || has("метролог") {
        return SubjectTheme::new("⏱", 0xFF5C6BC0, "Нормирование и стандарты");
    }

    if has("гидро") || has("привод") || has("пневм") {
        return SubjectTheme::new("💧", 0xFF0288D1, "Гидро- и пневмоприводы");
    }

    if has("надежн") || has("контрол") || has("диагност") {
        return SubjectTheme::new("🛡", 0xFF5E35B1, "Надежность систем");
    }

    if has("коммун") || has("дел коммун") || has("этик") || has("психолог") {
        return SubjectTheme::new("💬", 0xFFFFB300, "Деловые коммуникации");
    }

    if has("практик") || has("диплом") || has("экзамен") || has("зачет") {
        return SubjectTheme::new("🏭", 0xFF00897B, "Практика");
    }

    SubjectTheme::new("📚", 0xFF6C48E1, "Учебный предмет")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_punctuation_and_yo() {
        assert_eq!(normalize_subject("Информатика (ЧПУ)"), "информатика чпу");
        assert_eq!(normalize_subject("Физкультура"), "физкультура");
        assert_eq!(normalize_subject("М-В и тех.мат."), "м в и тех мат");
    }

    #[test]
    fn recognizes_common_subjects() {
        assert_eq!(subject_theme("Математика").emoji, "📐");
        assert_eq!(subject_theme("Алгебра").category, "Математика");
        assert_eq!(subject_theme("Информатика").emoji, "💻");
        assert_eq!(subject_theme("Физика").emoji, "⚡");
        assert_eq!(subject_theme("Химия").category, "Химия");
        assert_eq!(subject_theme("История Беларуси").category, "История");
        assert_eq!(subject_theme("Русская литература").category, "Литература");
    }

    #[test]
    fn physical_education_is_not_physics() {
        assert_eq!(subject_theme("Физическая культура").category, "Физическая культура");
        assert_eq!(subject_theme("Физкультура").category, "Физическая культура");
        assert_eq!(subject_theme("ФКиЗ").category, "Физическая культура");
    }

    #[test]
    fn unknown_subject_falls_back() {
        let theme = subject_theme("Неизвестный предмет");
        assert_eq!(theme.emoji, "📚");
        assert_eq!(theme.color, 0xFF6C48E1);
        assert_eq!(theme.category, "Учебный предмет");
    }
}