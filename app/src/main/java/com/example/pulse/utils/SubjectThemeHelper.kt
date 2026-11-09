package com.example.pulse.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.concurrent.ConcurrentHashMap

data class SubjectTheme(
    val emoji: String,
    val color: Color,
    val icon: ImageVector,
    val category: String
)

object SubjectThemeHelper {
    private val CACHE = ConcurrentHashMap<String, SubjectTheme>()

    fun getTheme(rawSubject: String): SubjectTheme {
        val key = rawSubject.trim()
        return CACHE.computeIfAbsent(key) { computeTheme(key) }
    }

    private fun computeTheme(rawSubject: String): SubjectTheme {
        val cleanName = rawSubject.lowercase()
            .replace("ё", "е")
            .replace(Regex("[^а-яa-z0-9іў]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val exact = " $cleanName "

        return when {
            // Математика / Алгебра / Геометрия
            cleanName.contains("математ") || cleanName.contains("матем") || cleanName.contains("алгебр") ||
            cleanName.contains("геометр") || cleanName.contains("матан") || cleanName.contains("дифур") ||
            cleanName.contains("теорвер") || exact.contains(" мат ") ->
                SubjectTheme("📐", Color(0xFF7C4DFF), Icons.Outlined.SquareFoot, "Математика")

            // Информатика / Программирование / IT / ЧПУ / CAD / ПЛК
            cleanName.contains("информат") || cleanName.contains("инф тех") || cleanName.contains("програм") ||
            cleanName.contains("инфа") || cleanName.contains("cad") || cleanName.contains("cam") ||
            cleanName.contains("плк") || cleanName.contains("счпу") || cleanName.contains("чпу") ||
            cleanName.contains("спу") || exact.contains(" пу ") || cleanName.contains("scada") ||
            cleanName.contains("микропроцессор") || cleanName.contains("микроконтроллер") || cleanName.contains("ит") ->
                SubjectTheme("💻", Color(0xFF00E676), Icons.Outlined.Terminal, "Информатика и IT")

            // Физика / Электротехника
            ((cleanName.contains("физик") || exact.contains(" физ ")) && !cleanName.contains("физк") && !cleanName.contains("физкульт") && !exact.contains(" фк ")) ->
                SubjectTheme("⚡", Color(0xFFFFD600), Icons.Outlined.ElectricBolt, "Физика")

            // Электроника / Электропривод / Схемотехника / Пром. датчики / ПЛК
            cleanName.contains("электро") || cleanName.contains("электрич") || cleanName.contains("схем") ||
            cleanName.contains("аналог схем") || cleanName.contains("тоэ") || cleanName.contains("датч") ||
            cleanName.contains("компан") ->
                SubjectTheme("💡", Color(0xFFFFAB00), Icons.Outlined.Sensors, "Электроника и датчики")

            // Химия
            cleanName.contains("хим") ->
                SubjectTheme("🧪", Color(0xFF00B0FF), Icons.Outlined.Science, "Химия")

            // Биология / Экология
            cleanName.contains("биол") || cleanName.contains("эколог") ->
                SubjectTheme("🧬", Color(0xFF4CAF50), Icons.Outlined.Biotech, "Биология")

            // География
            cleanName.contains("географ") || cleanName.contains("геолог") ->
                SubjectTheme("🌍", Color(0xFF26A69A), Icons.Outlined.Public, "География")

            // Астрономия
            cleanName.contains("астроном") || cleanName.contains("космос") ->
                SubjectTheme("🔭", Color(0xFF7E57C2), Icons.Outlined.Nightlight, "Астрономия")

            // Черчение / Инженерная графика / Основы инж. граф.
            cleanName.contains("черчен") || cleanName.contains("инж граф") || cleanName.contains("начерт") ->
                SubjectTheme("📏", Color(0xFF00BCD4), Icons.Outlined.Straighten, "Инженерная графика")

            // Охрана труда / ОБЖ / БЖД / Безопасность
            cleanName.contains("охр труд") || cleanName.contains("охрана труд") || cleanName.contains("бжд") ||
            cleanName.contains("обж") || cleanName.contains("безопас") || exact.contains(" от ") ->
                SubjectTheme("🦺", Color(0xFFFF6D00), Icons.Outlined.HealthAndSafety, "Охрана труда")

            // ФКиЗ / Физкультура / Спорт
            cleanName.contains("фкиз") || cleanName.contains("физк") || cleanName.contains("физкульт") ||
            cleanName.contains("спорт") || exact.contains(" фк ") ->
                SubjectTheme("🏃", Color(0xFFFF1744), Icons.Outlined.FitnessCenter, "Физическая культура")

            // Допризывная подготовка (ДПЮ / ДПО / Военная)
            cleanName.contains("дпю") || cleanName.contains("допризыв") || cleanName.contains("воен") ->
                SubjectTheme("🎖️", Color(0xFF689F38), Icons.Outlined.Shield, "Допризывная подготовка")

            // Иностранный язык (Ин.яз. / Английский / Немецкий)
            cleanName.contains("ин яз") || cleanName.contains("англ") || cleanName.contains("english") ||
            cleanName.contains("немец") || cleanName.contains("иностр") ->
                SubjectTheme("🇬🇧", Color(0xFF2979FF), Icons.Outlined.Translate, "Иностранный язык")

            // Русский / Белорусский язык
            cleanName.contains("б яз") || cleanName.contains("бел яз") || cleanName.contains("белорус") ||
            cleanName.contains("рус яз") || cleanName.contains("русск яз") || cleanName.contains("язык") ->
                SubjectTheme("📝", Color(0xFFFF4081), Icons.Outlined.Edit, "Языкознание")

            // Литература (Р.лит. / Б.лит.)
            cleanName.contains("лит") || cleanName.contains("литерат") || cleanName.contains("р лит") ||
            cleanName.contains("б лит") ->
                SubjectTheme("📖", Color(0xFFAB47BC), Icons.Outlined.MenuBook, "Литература")

            // История (Ист.Бел. / Ист.бел.гос. / История)
            cleanName.contains("ист бел") || cleanName.contains("истор") || exact.contains(" ист ") ->
                SubjectTheme("🏛️", Color(0xFF8D6E63), Icons.Outlined.AccountBalance, "История")

            // Право / Обществоведение
            cleanName.contains("прав") || cleanName.contains("закон") || cleanName.contains("юрисп") ||
            cleanName.contains("обществ") ->
                SubjectTheme("⚖️", Color(0xFF607D8B), Icons.Outlined.Gavel, "Правоведение")

            // Экономика / Маркетинг / Эк.орг.
            cleanName.contains("эконом") || cleanName.contains("эк орг") || cleanName.contains("маркетинг") ||
            cleanName.contains("финанс") || cleanName.contains("бухгалт") || cleanName.contains("менедж") ->
                SubjectTheme("📈", Color(0xFF43A047), Icons.Outlined.TrendingUp, "Экономика")

            // Логистика / Упр.зап.лог. / Расп.тр.лог. / Орг.хран.и кон.
            cleanName.contains("лог") || cleanName.contains("упр зап") || cleanName.contains("расп тр") ||
            cleanName.contains("орг хран") || cleanName.contains("склад") ->
                SubjectTheme("📦", Color(0xFFFF9100), Icons.Outlined.Inventory2, "Логистика и хранение")

            // Автомобили / Транспорт / Устр.ТС / Тех.об.авт.
            cleanName.contains("устр тс") || cleanName.contains("тех об авт") || cleanName.contains("автомоб") ||
            cleanName.contains("двигател") || cleanName.contains("авто") || cleanName.contains("вост тех") ->
                SubjectTheme("🚗", Color(0xFFF4511E), Icons.Outlined.DirectionsCar, "Автотранспорт")

            // Сервисные станции / ТО
            cleanName.contains("серв стан") || cleanName.contains("эксп то") ->
                SubjectTheme("⛽", Color(0xFF009688), Icons.Outlined.LocalGasStation, "Сервис и ТО")

            // ПДД
            cleanName.contains("пдд") || cleanName.contains("дорожн") ->
                SubjectTheme("🚦", Color(0xFFE53935), Icons.Outlined.Traffic, "ПДД")

            // Материаловедение (М-В / М-В и тех.мат.)
            cleanName.contains("м в") || cleanName.contains("материал") || exact.contains(" мв ") ->
                SubjectTheme("🔬", Color(0xFF3949AB), Icons.Outlined.Layers, "Материаловедение")

            // Технология машиностроения (Осн.тех.маш. / Совр.тех.маш. / Спец.тех. / Тех.маш.)
            cleanName.contains("тех маш") || cleanName.contains("маш") || cleanName.contains("спец тех") ||
            cleanName.contains("детали маш") || cleanName.contains("станк") || cleanName.contains("токар") ||
            cleanName.contains("фрезер") ->
                SubjectTheme("⚙️", Color(0xFFFF6F00), Icons.Outlined.PrecisionManufacturing, "Машиностроение")

            // Наладка / Пр.нал.авт.об. / Оборудование
            cleanName.contains("наладк") || cleanName.contains("нал авт") || cleanName.contains("обор") ->
                SubjectTheme("🛠️", Color(0xFF00ACC1), Icons.Outlined.Build, "Наладка оборудования")

            // Подъемно-транспортное оборудование
            cleanName.contains("под тран") || cleanName.contains("кран") ->
                SubjectTheme("🏗️", Color(0xFFFB8C00), Icons.Outlined.Construction, "Подъемное оборудование")

            // Механика / Мехатроника / МехМан
            cleanName.contains("мехман") || cleanName.contains("мехатрон") || cleanName.contains("манипул") ||
            cleanName.contains("робот") ->
                SubjectTheme("🤖", Color(0xFF00E5FF), Icons.Outlined.SmartToy, "Мехатроника")

            // Монтаж и ремонт систем / Бортовые системы (МРС / П-м Эк.д.МРС / П-м П.н.борт.)
            cleanName.contains("мрс") || cleanName.contains("борт") || cleanName.contains("монтаж") ||
            cleanName.contains("ремонт") ->
                SubjectTheme("🔌", Color(0xFF26C6DA), Icons.Outlined.Power, "Системы и монтаж")

            // Инструмент / Реж.инстр. / ОМиИ
            cleanName.contains("реж инстр") || cleanName.contains("омии") || cleanName.contains("инструм") ->
                SubjectTheme("🧰", Color(0xFFFF7043), Icons.Outlined.Handyman, "Инструменты и оснастка")

            // Технологическая оснастка
            cleanName.contains("оснастк") ->
                SubjectTheme("🔩", Color(0xFF78909C), Icons.Outlined.Hardware, "Техническая оснастка")

            // Нормирование
            cleanName.contains("нормир") || cleanName.contains("стандарт") || cleanName.contains("метролог") ->
                SubjectTheme("⏱️", Color(0xFF5C6BC0), Icons.Outlined.Timer, "Нормирование и стандарты")

            // Приводы / Гидропривод
            cleanName.contains("гидро") || cleanName.contains("привод") || cleanName.contains("пневм") ->
                SubjectTheme("💧", Color(0xFF0288D1), Icons.Outlined.WaterDrop, "Гидро- и пневмоприводы")

            // Надежность систем
            cleanName.contains("надежн") || cleanName.contains("контрол") || cleanName.contains("диагност") ->
                SubjectTheme("🛡️", Color(0xFF5E35B1), Icons.Outlined.VerifiedUser, "Надежность систем")

            // Деловые коммуникации / Психология
            cleanName.contains("коммун") || cleanName.contains("дел коммун") || cleanName.contains("этик") ||
            cleanName.contains("психолог") ->
                SubjectTheme("🤝", Color(0xFFFFB300), Icons.Outlined.Handshake, "Деловые коммуникации")

            // Практика / Диплом / Экзамен
            cleanName.contains("практик") || cleanName.contains("диплом") || cleanName.contains("экзамен") ||
            cleanName.contains("зачет") ->
                SubjectTheme("🏭", Color(0xFF00897B), Icons.Outlined.Engineering, "Практика")

            else ->
                SubjectTheme("📚", Color(0xFF6C48E1), Icons.Outlined.Class, "Учебный предмет")
        }
    }
}
