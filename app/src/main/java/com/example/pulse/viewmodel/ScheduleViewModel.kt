package com.example.pulse.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pulse.data.HomeworkItem
import com.example.pulse.utils.LessonAlarmManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

private object SchedulePatterns {
    val GROUP_PREFIX = Regex("""^(?:гр\.?|группа)\s*""", RegexOption.IGNORE_CASE)
    val VALID_GROUP = Regex("""^\d{2,4}[а-яА-Яa-zA-Z]?$""")
    val CLEAN_SUBJECT = Regex("""^[\d\s.\-│|]+""")
    val PIPE = Regex("""[│|]""")
    val MULTI_SPACES = Regex("""\s+""")
    val SPACES_TABS = Regex("[ \\t]+")
    val TEACHER_NAME = Regex("""([А-ЯЁ][а-яё\-]+)\s+([А-ЯЁ])\.?\s*([А-ЯЁ])?\.?""")
    val TEACHER_INITIALS = Regex("""[А-ЯЁ][а-яё\-]+\s+[А-ЯЁ]\s*\.?\s*[А-ЯЁ]?\s*\.?""")
    val JUNK_CHARS = Regex("""[\d├─│|No]+""")
    val LESSON_TOKENS = Regex("""[\s,\-]+""")
    val GROUP_AT_START = Regex("""^((?:гр\.?\s*)?\d{2,4}[а-яА-Яa-zA-Z]?)\s*[│|]""")
    val SCHEDULE_SPLIT = Regex("(?=РАСПИСАНИЕ\\s+НА)", RegexOption.IGNORE_CASE)
    val HEADER_DATE = Regex("""(\d{2}\.\d{2}\.\d{2,4})""")
    val URL_DATE = Regex("""(\d{2})\.(\d{2})\.(\d{2,4})""")
    val URL_PATH = Regex("""/uploads/(\d{4})/(\d{2})/""")

    val BROKEN_LINE_P1 = Regex("""^((?:гр\.?\s*)?\d{2,4}[а-яА-Яa-zA-Z]?|-)?\s*(\d{1,4}(?:\s+\d{1,2})?)\s+([А-Яа-яЁёA-Za-z0-9.\-\\/ ]+?)\s+(\d{2,4}[а-яА-Я]?|[МM]\d+[а-яА-Я]?|—|-)\s+([А-ЯЁ][а-яё\-]+\s+[А-ЯЁ]\.?\s*[А-ЯЁ]?\.?.*)$""")
    val BROKEN_LINE_P2 = Regex("""^(\d{1,4}(?:\s+\d{1,2})?)\s+([А-Яа-яЁёA-Za-z0-9.\-\\/ ]+?)\s+(\d{2,4}[а-яА-Я]?|[МM]\d+[а-яА-Я]?|—|-)\s+([А-ЯЁ][а-яё\-]+\s+[А-ЯЁ]\.?\s*[А-ЯЁ]?\.?.*)$""")
    val BROKEN_LINE_P3 = Regex("""^(\d{1,4}(?:\s+\d{1,2})?)\s+([А-Яа-яЁёA-Za-z0-9.\-\\/ ]+?)\s+([А-ЯЁ][а-яё\-]+\s+[А-ЯЁ]\.?\s*[А-ЯЁ]?\.?.*)$""")
}

data class LessonPair(
    val pairNumber: Int,
    val lessons: String,
    val startTime: String,
    val endTime: String,
    val subject: String,
    val room: String,
    val teacher: String,
    val group: String = ""
) {
    val startLocalTime: LocalTime? by lazy(LazyThreadSafetyMode.NONE) {
        com.example.pulse.utils.DateTimeUtils.parseLocalTimeOrNull(startTime)
    }
    val endLocalTime: LocalTime? by lazy(LazyThreadSafetyMode.NONE) {
        com.example.pulse.utils.DateTimeUtils.parseLocalTimeOrNull(endTime)
    }
    val cleanSubject: String by lazy(LazyThreadSafetyMode.NONE) {
        subject.substringBefore("(").substringBefore(",").trim()
    }
}

data class Teacher(
    val name: String,
    val subject: String,
    val room: String,
    val email: String,
    val avatarColor: Long
)

data class ScheduleState(
    val isLoading: Boolean = true,
    val selectedDate: LocalDate = LocalDate.now(),
    val lessons: List<LessonPair> = emptyList(),
    val teachers: List<Teacher> = emptyList(),
    val userGroup: String = "Неизвестно",
    val userName: String = "Студент",
    val pdfUrl: String? = null,
    val previewGroup: String? = null,
    val availableGroups: List<String> = emptyList()
)

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state.asStateFlow()
    private val scheduleCache = loadScheduleHistoryFromPrefs()
    private var previewScheduleCache: Map<LocalDate, List<LessonPair>> = emptyMap()

    val fullScheduleHistory: Map<LocalDate, List<LessonPair>>
        get() = if (_state.value.previewGroup != null && previewScheduleCache.isNotEmpty()) previewScheduleCache else scheduleCache

    private var lastRawText: String
        get() = prefs.getString("last_raw_pdf_text", "") ?: ""
        set(value) = prefs.edit().putString("last_raw_pdf_text", value).apply()

    init {
        val savedTeachers = loadTeachersFromPrefs()
        val today = LocalDate.now()
        val group = prefs.getString("user_group", "000") ?: "000"
        val name = prefs.getString("user_name", "Студент") ?: "Студент"
        val cachedGroups = extractAllGroups(lastRawText)

        _state.update {
            it.copy(
                selectedDate = today,
                lessons = scheduleCache[today] ?: emptyList(),
                teachers = savedTeachers,
                userGroup = group,
                userName = name,
                availableGroups = cachedGroups,
                isLoading = scheduleCache.isEmpty(),
                pdfUrl = prefs.getString("last_pdf_url", null)
            )
        }
    }

    @SuppressLint("NewApi")
    fun refreshData() {
        val group = prefs.getString("user_group", "000") ?: "000"
        val name = prefs.getString("user_name", "Студент") ?: "Студент"
        _state.update { it.copy(userGroup = group, userName = name, isLoading = true) }

        viewModelScope.launch {
            try {
                val pdfUrls = fetchAllPdfUrls()

                if (pdfUrls.isNotEmpty()) {
                    val lastUrl = pdfUrls.last()
                    val fileNames = pdfUrls.joinToString(", ") { it.substringAfterLast("/") }
                    prefs.edit()
                        .putString("last_pdf_url", lastUrl)
                        .putString("last_pdf_filename", fileNames)
                        .apply()
                    _state.update { it.copy(pdfUrl = lastUrl) }

                    val combinedRawTextBuilder = StringBuilder()
                    val allParsedDays = mutableMapOf<LocalDate, MutableList<LessonPair>>()

                    for (url in pdfUrls) {
                        try {
                            Log.d("PulseParser", "Скачивание и разбор файла: $url")
                            val rawText = downloadAndExtractPdf(url)
                            if (rawText.isNotBlank()) {
                                combinedRawTextBuilder.append(rawText).append("\n\n")
                                val parsed = parseRawText(rawText, group)
                                parsed.forEach { (date, lessons) ->
                                    allParsedDays.getOrPut(date) { mutableListOf() }.addAll(lessons)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("PulseParser", "Ошибка обработки $url", e)
                        }
                    }

                    val combinedRawText = combinedRawTextBuilder.toString()
                    if (combinedRawText.isNotBlank()) {
                        lastRawText = combinedRawText
                        extractAndSaveTeachers(combinedRawText)
                        val groups = extractAllGroups(combinedRawText)
                        _state.update { it.copy(availableGroups = groups) }
                    }

                    val finalSchedule = allParsedDays.mapValues { (_, lessons) ->
                        lessons.distinctBy { Triple(it.startTime, it.subject, it.room) }
                            .sortedBy { it.startLocalTime ?: LocalTime.MIN }
                            .mapIndexed { idx, pair -> pair.copy(pairNumber = idx + 1) }
                    }

                    if (finalSchedule.isNotEmpty()) {
                        updateCacheWithRollingWindow(finalSchedule)
                    } else {
                        handleOfflineMode()
                    }
                } else {
                    if (lastRawText.isNotEmpty()) {
                        val parsedSchedule = parseRawText(lastRawText, group)
                        val groups = extractAllGroups(lastRawText)
                        _state.update { it.copy(availableGroups = groups) }
                        if (parsedSchedule.isNotEmpty()) {
                            updateCacheWithRollingWindow(parsedSchedule)
                            return@launch
                        }
                    }
                    handleOfflineMode()
                }
            } catch (e: Exception) {
                Log.e("PulseParser", "Ошибка сети или парсинга PDF", e)
                handleOfflineMode()
            }
        }
    }

    /**
     * Быстрое переключение на чужую группу для сверки расписания без сохранения в постоянные настройки.
     * Если передать null — происходит мгновенный возврат к своему расписанию.
     */
    fun setPreviewGroup(group: String?) {
        val currentMainGroup = prefs.getString("user_group", "000") ?: "000"
        if (group.isNullOrBlank() || isGroupMatch(group, currentMainGroup)) {
            previewScheduleCache = emptyMap()
            val currDate = _state.value.selectedDate
            _state.update {
                it.copy(
                    previewGroup = null,
                    lessons = scheduleCache[currDate] ?: emptyList()
                )
            }
        } else {
            val cleanGroup = group.trim().uppercase()
            val parsed = parseRawText(lastRawText, cleanGroup)
            previewScheduleCache = parsed
            val currDate = _state.value.selectedDate
            _state.update {
                it.copy(
                    previewGroup = cleanGroup,
                    lessons = parsed[currDate] ?: emptyList()
                )
            }
        }
    }

    private fun extractAllGroups(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        val set = mutableSetOf<String>()
        val sections = rawText.split(SchedulePatterns.SCHEDULE_SPLIT)
        for (section in sections) {
            val chunks = extractTableChunks(section)
            for (chunk in chunks) {
                val g = chunk.detectedGroup
                if (!g.isNullOrBlank() && isValidGroup(g)) {
                    set.add(g.trim().uppercase())
                }
            }
        }
        return set.sortedWith(
            compareBy<String> { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
                .thenBy { it }
        )
    }

    private fun updateCacheWithRollingWindow(parsedSchedule: Map<LocalDate, List<LessonPair>>) {
        parsedSchedule.keys.forEach { scheduleCache.remove(it) }
        scheduleCache.putAll(parsedSchedule)

        val today = LocalDate.now()
        val minRetainDate = today.minusDays(14)

        // Храним до 14 дней истории (начиная с today - 14 дней) + все будущие загруженные дни
        val validDays = scheduleCache.keys
            .filter { !it.isBefore(minRetainDate) }
            .sorted()
            .takeLast(14)
            .toSet()
        scheduleCache.keys.retainAll(validDays)

        saveScheduleHistoryToPrefs(scheduleCache)
        val activeCache = if (_state.value.previewGroup != null) previewScheduleCache else scheduleCache
        _state.update { it.copy(selectedDate = today, lessons = activeCache[today] ?: emptyList(), isLoading = false) }
        updateWidgetData(scheduleCache)
    }

    private suspend fun fetchAllPdfUrls(): List<String> = withContext(Dispatchers.IO) {
        try {
            val pageUrl = "https://college-ripo.by/uchashhimsya/zameny-v-raspisanii/"
            val connection = URL(pageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            if (connection.responseCode != 200) {
                Log.e("PulseParser", "HTTP Error: ${connection.responseCode}")
                return@withContext emptyList()
            }

            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val candidates = linkedSetOf<String>()

            val buttonRegexes = listOf(
                Regex("""<a\b[^>]*?href=["']([^"']+\.pdf)["'][^>]*?class=["'][^"']*wp-block-file__button""", RegexOption.IGNORE_CASE),
                Regex("""<a\b[^>]*?class=["'][^"']*wp-block-file__button[^"']*["'][^>]*?href=["']([^"']+\.pdf)["']""", RegexOption.IGNORE_CASE)
            )
            for (regex in buttonRegexes) {
                regex.findAll(html).forEach { candidates.add(it.groupValues[1]) }
            }

            val objectRegex = Regex("""<object\b[^>]*?data=["']([^"']+\.pdf)["']""", RegexOption.IGNORE_CASE)
            objectRegex.findAll(html).forEach { candidates.add(it.groupValues[1]) }

            if (candidates.isEmpty()) {
                val generalPdfRegex = Regex("""href=["'](https?://[^"']*/wp-content/uploads/[^"']*\.pdf)["']""", RegexOption.IGNORE_CASE)
                generalPdfRegex.findAll(html).forEach { candidates.add(it.groupValues[1]) }
            }

            val resolvedUrls = candidates.map { rawUrl ->
                var u = rawUrl.trim()
                if (u.startsWith("//")) u = "https:$u"
                else if (u.startsWith("/")) u = "https://college-ripo.by$u"
                else if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://college-ripo.by/$u"
                if (u.startsWith("http://")) u = u.replaceFirst("http://", "https://")
                u
            }.filter { link ->
                val l = link.lowercase()
                l.contains("deti") || l.contains("дети") || l.contains("raspis") || l.contains("zamen") ||
                        Regex("""\d{2}\.\d{2}""").containsMatchIn(l)
            }.distinct()

            val sorted = resolvedUrls.sortedBy { link ->
                val dateMatch = Regex("""(\d{2})\.(\d{2})\.(\d{2,4})""").find(link)
                if (dateMatch != null) {
                    val d = dateMatch.groupValues[1].toIntOrNull() ?: 0
                    val m = dateMatch.groupValues[2].toIntOrNull() ?: 0
                    var y = dateMatch.groupValues[3].toIntOrNull() ?: 0
                    if (y < 100) y += 2000
                    y * 10000 + m * 100 + d
                } else {
                    val pathMatch = Regex("""/uploads/(\d{4})/(\d{2})/""").find(link)
                    if (pathMatch != null) {
                        val y = pathMatch.groupValues[1].toIntOrNull() ?: 0
                        val m = pathMatch.groupValues[2].toIntOrNull() ?: 0
                        y * 100 + m
                    } else 0
                }
            }

            Log.d("PulseParser", "Найдено кнопок расписания: ${sorted.size} -> $sorted")
            return@withContext sorted
        } catch (e: Exception) {
            Log.e("PulseParser", "Ошибка получения ссылок на PDF", e)
            return@withContext emptyList()
        }
    }

    @SuppressLint("NewApi")
    private fun handleOfflineMode() {
        val today = LocalDate.now()
        val activeCache = if (_state.value.previewGroup != null) previewScheduleCache else scheduleCache
        if (activeCache.isNotEmpty()) {
            _state.update { it.copy(selectedDate = today, lessons = activeCache[today] ?: emptyList(), isLoading = false) }
            updateWidgetData(scheduleCache)
        } else {
            _state.update { it.copy(selectedDate = today, lessons = emptyList(), isLoading = false) }
        }
    }

    @SuppressLint("NewApi")
    private fun saveScheduleHistoryToPrefs(schedule: Map<LocalDate, List<LessonPair>>) {
        try {
            val jsonObject = JSONObject()
            schedule.forEach { (date, lessons) ->
                val jsonArray = JSONArray()
                lessons.forEach { lesson ->
                    val lessonObj = JSONObject().apply {
                        put("pairNumber", lesson.pairNumber)
                        put("lessons", lesson.lessons)
                        put("startTime", lesson.startTime)
                        put("endTime", lesson.endTime)
                        put("subject", lesson.subject)
                        put("room", lesson.room)
                        put("teacher", lesson.teacher)
                        put("group", lesson.group)
                    }
                    jsonArray.put(lessonObj)
                }
                jsonObject.put(date.toString(), jsonArray)
            }
            prefs.edit().putString("schedule_history", jsonObject.toString()).apply()
        } catch (e: Exception) {
            Log.e("PulseParser", "Ошибка сохранения кэша расписания", e)
        }
    }

    @SuppressLint("NewApi")
    private fun loadScheduleHistoryFromPrefs(): MutableMap<LocalDate, List<LessonPair>> {
        val jsonStr = prefs.getString("schedule_history", "{}") ?: "{}"
        val map = mutableMapOf<LocalDate, List<LessonPair>>()
        try {
            val jsonObject = JSONObject(jsonStr)
            jsonObject.keys().forEach { dateStr ->
                val date = com.example.pulse.utils.DateTimeUtils.parseLocalDateOrNull(dateStr) ?: return@forEach
                val jsonArray = jsonObject.optJSONArray(dateStr) ?: return@forEach
                val lessons = mutableListOf<LessonPair>()
                for (i in 0 until jsonArray.length()) {
                    try {
                        val obj = jsonArray.getJSONObject(i)
                        lessons.add(LessonPair(
                            pairNumber = obj.optInt("pairNumber", i + 1),
                            lessons = obj.optString("lessons", ""),
                            startTime = obj.optString("startTime", ""),
                            endTime = obj.optString("endTime", ""),
                            subject = obj.optString("subject", ""),
                            room = obj.optString("room", ""),
                            teacher = obj.optString("teacher", ""),
                            group = obj.optString("group", "")
                        ))
                    } catch (e: Exception) {
                        Log.w("PulseParser", "Пропуск поврежденной записи урока", e)
                    }
                }
                if (lessons.isNotEmpty()) {
                    map[date] = lessons
                }
            }
        } catch (e: Exception) {
            Log.e("PulseParser", "Ошибка загрузки кэша", e)
        }
        return map
    }

    fun selectDate(date: LocalDate) {
        val activeCache = if (_state.value.previewGroup != null) previewScheduleCache else scheduleCache
        _state.update { it.copy(selectedDate = date, lessons = activeCache[date] ?: emptyList(), isLoading = false) }
    }

    fun getAllGroupLessons(): List<LessonPair> = scheduleCache.values.flatten()

    @SuppressLint("NewApi")
    fun getRemainingPairs(hw: HomeworkItem): Int? {
        if (hw.targetPairs == null) return null
        val cleanTarget = hw.subject.substringBefore("(").trim().lowercase()
        var passedCount = 0
        val today = LocalDate.now()
        val now = LocalTime.now()

        for ((date, lessons) in scheduleCache) {
            if (date.isBefore(hw.assignDate)) continue
            if (date.isAfter(today)) continue
            val validLessons = if (date == today) {
                lessons.filter { (it.startLocalTime ?: LocalTime.MIN).isBefore(now) }
            } else { lessons }
            passedCount += validLessons.count { it.cleanSubject.lowercase() == cleanTarget }
        }
        return maxOf(0, hw.targetPairs - passedCount)
    }

    @SuppressLint("NewApi")
    fun countLessonsUntil(targetSubject: String, dueDate: LocalDate): Int {
        val today = LocalDate.now()
        val now = LocalTime.now()
        if (dueDate.isBefore(today)) return 0
        val cleanTarget = targetSubject.substringBefore("(").trim().lowercase()
        var count = 0
        scheduleCache.forEach { (date, lessons) ->
            if (!date.isBefore(today) && date.isBefore(dueDate)) {
                val validLessons = if (date == today) {
                    lessons.filter { (it.startLocalTime ?: LocalTime.MIN).isAfter(now) }
                } else { lessons }
                count += validLessons.count { it.cleanSubject.lowercase() == cleanTarget }
            }
        }
        return count
    }

    @SuppressLint("NewApi")
    fun findDateForNextSubject(targetSubject: String, skipLessons: Int = 0): LocalDate? {
        val today = LocalDate.now()
        val now = LocalTime.now()
        val futureDates = scheduleCache.keys.filter { !it.isBefore(today) }.sorted()
        var matchCount = 0
        val cleanTarget = targetSubject.substringBefore("(").trim().lowercase()

        for (date in futureDates) {
            val lessonsOnDate = scheduleCache[date] ?: continue
            val validLessons = if (date == today) {
                lessonsOnDate.filter { (it.startLocalTime ?: LocalTime.MIN).isAfter(now) }
            } else { lessonsOnDate }

            val hasSubject = validLessons.any { it.cleanSubject.lowercase() == cleanTarget }
            if (hasSubject) {
                if (matchCount == skipLessons) return date
                matchCount++
            }
        }
        return null
    }

    @SuppressLint("NewApi")
    private fun updateWidgetData(scheduleMap: Map<LocalDate, List<LessonPair>>) {
        val today = LocalDate.now()
        val lessonsToday = scheduleMap[today] ?: emptyList()
        val jsonArray = JSONArray()
        lessonsToday.forEach { lesson ->
            val jsonObj = JSONObject().apply {
                put("pairNumber", lesson.pairNumber)
                put("lessons", lesson.lessons)
                put("startTime", lesson.startTime)
                put("endTime", lesson.endTime)
                put("subject", lesson.subject)
                put("room", lesson.room)
                put("teacher", lesson.teacher)
            }
            jsonArray.put(jsonObj)
        }
        prefs.edit().putString("widget_lessons_today", jsonArray.toString()).apply()

        try {
            LessonAlarmManager.scheduleAlarms(getApplication(), scheduleMap)
        } catch (e: Exception) {
            Log.e("ScheduleViewModel", "Ошибка планирования будильников", e)
        }
    }

    private fun isValidGroup(raw: String): Boolean {
        val clean = raw.trim()
            .replace(SchedulePatterns.GROUP_PREFIX, "")
            .trim()
        return clean.matches(SchedulePatterns.VALID_GROUP)
    }

    private fun normalizeGroup(raw: String): String {
        return raw.trim()
            .replace(SchedulePatterns.GROUP_PREFIX, "")
            .trim()
            .lowercase()
    }

    private fun isGroupMatch(detected: String?, target: String): Boolean {
        if (detected.isNullOrBlank() || target.isBlank()) return false
        val dNorm = normalizeGroup(detected)
        val tNorm = normalizeGroup(target)

        if (dNorm == tNorm) return true

        val dDigits = dNorm.filter { it.isDigit() }
        val tDigits = tNorm.filter { it.isDigit() }
        if (dDigits.length >= 2 && tDigits.length >= 2 && dDigits == tDigits) {
            val tLetter = tNorm.filter { it.isLetter() }
            val dLetter = dNorm.filter { it.isLetter() }
            return tLetter.isEmpty() || tLetter == dLetter
        }
        return false
    }

    private fun cleanSubjectName(raw: String): String {
        return raw.replace(SchedulePatterns.CLEAN_SUBJECT, "")
            .replace(SchedulePatterns.PIPE, "")
            .replace(SchedulePatterns.MULTI_SPACES, " ")
            .trim()
            .takeIf { it.isNotBlank() && it != "√" } ?: ""
    }

    private fun normalizeTeacherName(rawName: String): String {
        val match = SchedulePatterns.TEACHER_NAME.find(rawName.trim())
        if (match != null) {
            val surname = match.groupValues[1]
            val initial1 = match.groupValues[2]
            val initial2 = match.groupValues[3]
            return if (initial2.isNotEmpty()) "$surname $initial1.$initial2." else "$surname $initial1."
        }
        return rawName.trim().replace(SchedulePatterns.MULTI_SPACES, " ")
    }

    private fun extractTeacherNames(raw: String): List<String> {
        if (raw.isBlank() || raw == "—" || raw == "√") return emptyList()
        val found = SchedulePatterns.TEACHER_INITIALS.findAll(raw).map { normalizeTeacherName(it.value) }.toList()
        if (found.isNotEmpty()) return found

        val cleaned = raw.replace(SchedulePatterns.JUNK_CHARS, "").replace(SchedulePatterns.MULTI_SPACES, " ").trim()
        val lower = cleaned.lowercase()
        if (lower.contains("математ") || lower.contains("рус.лит") || lower.contains("физкульт") || lower.contains("нормир")) return emptyList()
        return if (cleaned.length >= 4) listOf(normalizeTeacherName(cleaned)) else emptyList()
    }

    private fun parseLessonNumbers(raw: String): List<Int> {
        val clean = raw.trim()
        if (clean.isBlank()) return emptyList()

        val tokens = clean.split(SchedulePatterns.LESSON_TOKENS).filter { it.isNotBlank() }
        val result = mutableListOf<Int>()

        for (token in tokens) {
            val num = token.filter { it.isDigit() }.toIntOrNull() ?: continue
            if (num in 1..14) {
                result.add(num)
            } else if (num > 14) {
                val digits = token.filter { it.isDigit() }.mapNotNull { it.toString().toIntOrNull() }.filter { it in 1..14 }
                result.addAll(digits)
            }
        }
        return result.distinct()
    }

    private fun tryParseBrokenLine(line: String): List<String>? {
        val cleanLine = line.replace(SchedulePatterns.PIPE, " ").replace(SchedulePatterns.MULTI_SPACES, " ").trim()

        val m1 = SchedulePatterns.BROKEN_LINE_P1.find(cleanLine)
        if (m1 != null) { val g = m1.groupValues; return listOf(g[1].trim(), g[2].trim(), g[3].trim(), g[4].trim(), g[5].trim()) }
        val m2 = SchedulePatterns.BROKEN_LINE_P2.find(cleanLine)
        if (m2 != null) { val g = m2.groupValues; return listOf("", g[1].trim(), g[2].trim(), g[3].trim(), g[4].trim()) }
        val m3 = SchedulePatterns.BROKEN_LINE_P3.find(cleanLine)
        if (m3 != null) { val g = m3.groupValues; return listOf("", g[1].trim(), g[2].trim(), "—", g[3].trim()) }
        return null
    }

    private data class ParsedRow(
        val group: String,
        val lessonNums: List<Int>,
        val subject: String,
        val room: String,
        val teacher: String
    )

    private class GroupChunk {
        var detectedGroup: String? = null
        val rows = mutableListOf<ParsedRow>()
        var maxLesson: Int = 0
    }

    private fun extractParsedRows(sectionText: String): List<ParsedRow> {
        val normalizedText = sectionText.replace("\r\n", "\n").replace("\r", "\n").replace(SchedulePatterns.SPACES_TABS, " ")
        val result = mutableListOf<ParsedRow>()
        var lastLessonNums = listOf(1)

        for (line in normalizedText.split("\n")) {
            var clean = line.trim()
            if (clean.isBlank()) continue
            if (clean.contains("No", true) && clean.contains("гр", true)) continue
            if (clean.contains("№", true) && clean.contains("гр", true)) continue
            if (clean.contains("Предмет", true) && clean.contains("Преподаватель", true)) continue
            if (clean.contains("РАСПИСАНИЕ", true) || clean.contains("УТВЕРЖДАЮ", true)) continue

            var g = ""
            var numStr = ""
            var subject = ""
            var room = ""
            var teacher = ""

            val hasGroupAtStart = SchedulePatterns.GROUP_AT_START.find(clean)
            if (hasGroupAtStart != null) {
                val candidate = hasGroupAtStart.groupValues[1].trim()
                if (isValidGroup(candidate)) {
                    g = candidate
                    clean = clean.substring(hasGroupAtStart.range.last + 1).trim()
                }
            }

            clean = clean.removePrefix("│").removePrefix("|").trim().removeSuffix("│").removeSuffix("|").trim()
            val cells = clean.split(SchedulePatterns.PIPE).map { it.trim() }.toMutableList()

            if (cells.size == 1 && isValidGroup(clean)) {
                result.add(ParsedRow(clean, lastLessonNums, "", "", ""))
                continue
            }

            if (cells.size >= 5) {
                if (isValidGroup(cells[0])) {
                    if (g.isEmpty()) g = cells[0]
                    numStr = cells[1]
                    subject = cells[2]
                    room = cells[3]
                    teacher = cells[4]
                } else if (parseLessonNumbers(cells[0]).isNotEmpty()) {
                    numStr = cells[0]
                    subject = cells[1]
                    room = cells[2]
                    teacher = cells[3]
                } else {
                    numStr = cells[1]
                    subject = cells[2]
                    room = cells[3]
                    teacher = cells[4]
                }
            } else if (cells.size == 4) {
                val firstNumList = parseLessonNumbers(cells[0])
                if (firstNumList.isNotEmpty()) {
                    numStr = cells[0]
                    subject = cells[1]
                    room = cells[2]
                    teacher = cells[3]
                } else if (isValidGroup(cells[0])) {
                    if (g.isEmpty()) g = cells[0]
                    numStr = cells[1]
                    subject = cells[2]
                    room = "—"
                    teacher = cells[3]
                } else {
                    numStr = cells[1]
                    subject = cells[2]
                    room = cells[3]
                    teacher = ""
                }
            } else if (cells.size == 3) {
                val firstNumList = parseLessonNumbers(cells[0])
                if (firstNumList.isNotEmpty()) {
                    numStr = cells[0]
                    subject = cells[1]
                    room = "—"
                    teacher = cells[2]
                }
            } else {
                val broken = tryParseBrokenLine(clean)
                if (broken != null) {
                    if (g.isEmpty() && isValidGroup(broken[0])) g = broken[0]
                    numStr = broken[1]
                    subject = broken[2]
                    room = broken[3]
                    teacher = broken[4]
                } else if (isValidGroup(clean)) {
                    g = clean
                }
            }

            val parsedNums = parseLessonNumbers(numStr)
            val finalNums = if (parsedNums.isNotEmpty()) {
                lastLessonNums = parsedNums
                parsedNums
            } else {
                lastLessonNums
            }

            val cleanSubj = cleanSubjectName(subject)
            if (cleanSubj.isNotEmpty() || g.isNotEmpty()) {
                result.add(ParsedRow(g, finalNums, cleanSubj, room, teacher))
            }
        }
        return result
    }

    private fun extractTableChunks(sectionText: String): List<GroupChunk> {
        val rows = extractParsedRows(sectionText)
        val chunks = mutableListOf<GroupChunk>()
        var currentChunk = GroupChunk()

        for (row in rows) {
            val rowGroup = if (isValidGroup(row.group)) row.group else ""
            val firstLesson = row.lessonNums.firstOrNull() ?: 1

            val isNewChunk = when {
                currentChunk.rows.isEmpty() -> false
                rowGroup.isNotEmpty() && currentChunk.detectedGroup != null && !isGroupMatch(currentChunk.detectedGroup, rowGroup) -> true
                firstLesson < currentChunk.maxLesson -> true
                else -> false
            }

            if (isNewChunk) {
                chunks.add(currentChunk)
                currentChunk = GroupChunk()
            }

            if (rowGroup.isNotEmpty()) {
                currentChunk.detectedGroup = rowGroup
            }
            currentChunk.rows.add(row)
            val rowMax = row.lessonNums.maxOrNull() ?: firstLesson
            if (rowMax > currentChunk.maxLesson) {
                currentChunk.maxLesson = rowMax
            }
        }
        if (currentChunk.rows.isNotEmpty()) {
            chunks.add(currentChunk)
        }
        return chunks
    }

    private fun extractAndSaveTeachers(rawText: String) {
        val oldTeachers = loadTeachersFromPrefs().associateBy { it.name }
        val sections = rawText.split(Regex("(?=РАСПИСАНИЕ\\s+НА)", RegexOption.IGNORE_CASE))
        val newTeachersMap = mutableMapOf<String, Pair<MutableSet<String>, MutableSet<String>>>()

        for (section in sections) {
            val rows = extractParsedRows(section)
            for (r in rows) {
                val cleanSubj = r.subject
                val cleanRoom = r.room.replace(Regex("""[│|]"""), "").trim().ifEmpty { "—" }
                val tNames = extractTeacherNames(r.teacher)

                if (tNames.isNotEmpty() && cleanSubj.isNotBlank()) {
                    tNames.forEach { tName ->
                        val entry = newTeachersMap.getOrPut(tName) { Pair(mutableSetOf(), mutableSetOf()) }
                        entry.first.add(cleanSubj)
                        if (cleanRoom != "—") entry.second.add(cleanRoom)
                    }
                }
            }
        }

        val finalList = mutableListOf<Teacher>()
        newTeachersMap.forEach { (name, data) ->
            val old = oldTeachers[name]
            finalList.add(Teacher(
                name = name,
                subject = data.first.joinToString(", "),
                room = data.second.joinToString(", "),
                email = old?.email ?: "-",
                avatarColor = old?.avatarColor ?: generateRandomAvatarColor()
            ))
        }
        oldTeachers.forEach { (name, old) ->
            if (!newTeachersMap.containsKey(name)) {
                finalList.add(Teacher(name = name, subject = "", room = "", email = old.email, avatarColor = old.avatarColor))
            }
        }
        val updatedList = finalList.sortedBy { it.name }
        saveTeachersToPrefs(updatedList)
        _state.update { it.copy(teachers = updatedList) }
    }

    @SuppressLint("NewApi")
    fun getTeacherSchedule(targetTeacher: String): Map<LocalDate, List<LessonPair>> {
        val rawText = lastRawText
        if (rawText.isBlank()) return emptyMap()
        val targetNorm = targetTeacher.replace(Regex("""[\s.]"""), "").lowercase()
        val result = mutableMapOf<LocalDate, List<LessonPair>>()
        val sections = rawText.split(Regex("(?=РАСПИСАНИЕ\\s+НА)", RegexOption.IGNORE_CASE))

        for (section in sections) {
            val headerMatch = Regex("""(\d{2}\.\d{2}\.\d{2,4})""").find(section.take(300)) ?: continue
            val theDate = try {
                val parts = headerMatch.groupValues[1].split(".")
                val yearPart = parts[2].toInt()
                val year = if (yearPart < 100) 2000 + yearPart else yearPart
                LocalDate.of(year, parts[1].toInt(), parts[0].toInt())
            } catch (e: Exception) { continue }

            val chunks = extractTableChunks(section)
            val timings = getBellSchedule(theDate)
            val lessonsByHour = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()

            for (chunk in chunks) {
                val groupName = chunk.detectedGroup ?: "—"
                for (row in chunk.rows) {
                    val tNames = extractTeacherNames(row.teacher)
                    val matches = tNames.any { it.replace(Regex("""[\s.]"""), "").lowercase() == targetNorm } ||
                            row.teacher.replace(Regex("""[\s.]"""), "").lowercase().contains(targetNorm)

                    if (matches && row.subject.isNotEmpty()) {
                        val cleanRm = row.room.replace(Regex("""[│|]"""), "").trim().ifEmpty { "—" }
                        for (num in row.lessonNums) {
                            if (num in 1..14) {
                                lessonsByHour.getOrPut(num) { mutableListOf() }.add(Triple(row.subject, cleanRm, groupName))
                            }
                        }
                    }
                }
            }

            if (lessonsByHour.isEmpty()) continue

            val sortedHours = lessonsByHour.keys.sorted()
            var currentBlockHours = mutableListOf<Int>()
            var currentSubject = ""
            var currentRoom = ""
            var currentGroupStr = ""
            var pairCounter = 1
            val dailyLessons = mutableListOf<LessonPair>()

            for (hour in sortedHours) {
                val entries = lessonsByHour[hour]!!
                val subjects = entries.map { it.first }.toSet().joinToString(" / ")
                val rooms = entries.map { it.second }.toSet().joinToString(" / ").ifEmpty { "—" }
                val groups = entries.map { it.third }.toSet().joinToString(" / ").ifEmpty { "—" }

                if (currentBlockHours.isEmpty()) {
                    currentBlockHours.add(hour)
                    currentSubject = subjects
                    currentRoom = rooms
                    currentGroupStr = groups
                } else {
                    val lastHour = currentBlockHours.last()
                    if (hour == lastHour + 1 && subjects == currentSubject && groups == currentGroupStr) {
                        currentBlockHours.add(hour)
                    } else {
                        val start = timings.getOrNull(currentBlockHours.first() - 1)?.first ?: "??:??"
                        val end = timings.getOrNull(currentBlockHours.last() - 1)?.second ?: "??:??"
                        dailyLessons.add(
                            LessonPair(
                                pairNumber = pairCounter++,
                                lessons = currentBlockHours.joinToString(", "),
                                startTime = start,
                                endTime = end,
                                subject = currentSubject,
                                room = currentRoom,
                                teacher = targetTeacher,
                                group = currentGroupStr
                            )
                        )

                        currentBlockHours = mutableListOf(hour)
                        currentSubject = subjects
                        currentRoom = rooms
                        currentGroupStr = groups
                    }
                }
            }
            if (currentBlockHours.isNotEmpty()) {
                val start = timings.getOrNull(currentBlockHours.first() - 1)?.first ?: "??:??"
                val end = timings.getOrNull(currentBlockHours.last() - 1)?.second ?: "??:??"
                dailyLessons.add(
                    LessonPair(
                        pairNumber = pairCounter++,
                        lessons = currentBlockHours.joinToString(", "),
                        startTime = start,
                        endTime = end,
                        subject = currentSubject,
                        room = currentRoom,
                        teacher = targetTeacher,
                        group = currentGroupStr
                    )
                )
            }

            if (dailyLessons.isNotEmpty()) {
                val existing = result.getOrPut(theDate) { mutableListOf() }
                (existing as MutableList).addAll(dailyLessons)
            }
        }

        result.keys.forEach { date ->
            val sortedList = result[date]!!.distinctBy { Triple(it.startTime, it.subject, it.lessons) }
                .sortedBy { it.startLocalTime ?: LocalTime.MIN }
            result[date] = sortedList.mapIndexed { index, pair -> pair.copy(pairNumber = index + 1) }
        }
        return result
    }

    private suspend fun downloadAndExtractPdf(urlString: String): String = withContext(Dispatchers.IO) {
        val context = getApplication<Application>().applicationContext
        val pdfFile = File.createTempFile("schedule_", ".pdf", context.cacheDir)
        var extractedText = ""
        var document: PDDocument? = null
        try {
            val url = URL(urlString)
            val connection = url.openConnection()
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000

            connection.getInputStream().use { input ->
                FileOutputStream(pdfFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    val maxBytes = 20 * 1024 * 1024
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalBytes += bytesRead
                        if (totalBytes > maxBytes) {
                            throw IllegalStateException("Размер PDF превышает лимит (20MB)")
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            document = PDDocument.load(pdfFile)
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            extractedText = stripper.getText(document)
        } finally {
            try { document?.close() } catch (e: Exception) { Log.e("PulseParser", "Ошибка закрытия PDF", e) }
            try { if (pdfFile.exists()) pdfFile.delete() } catch (e: Exception) { Log.e("PulseParser", "Ошибка удаления temp файла", e) }
        }
        return@withContext extractedText
    }

    @SuppressLint("NewApi")
    private fun parseRawText(rawText: String, targetGroup: String): Map<LocalDate, List<LessonPair>> {
        if (rawText.isBlank() || targetGroup.isBlank()) return emptyMap()

        val result = mutableMapOf<LocalDate, List<LessonPair>>()
        val sections = rawText.split(SchedulePatterns.SCHEDULE_SPLIT)

        for (section in sections) {
            val headerMatch = SchedulePatterns.HEADER_DATE.find(section.take(300)) ?: continue
            val theDate = try {
                val parts = headerMatch.groupValues[1].split(".")
                val yearPart = parts[2].toInt()
                val year = if (yearPart < 100) 2000 + yearPart else yearPart
                LocalDate.of(year, parts[1].toInt(), parts[0].toInt())
            } catch (e: Exception) { continue }

            val chunks = extractTableChunks(section)
            val matchingChunks = chunks.filter { chunk -> isGroupMatch(chunk.detectedGroup, targetGroup) }

            if (matchingChunks.isEmpty()) continue

            val timings = getBellSchedule(theDate)
            val lessonsByHour = mutableMapOf<Int, MutableList<Triple<String, String, String>>>()

            for (chunk in matchingChunks) {
                for (row in chunk.rows) {
                    val cleanSubj = row.subject
                    if (cleanSubj.isBlank()) continue
                    val cleanRm = row.room.replace(SchedulePatterns.PIPE, "").trim().ifEmpty { "—" }
                    val cleanTchr = extractTeacherNames(row.teacher).joinToString(" / ").ifEmpty {
                        row.teacher.replace(SchedulePatterns.JUNK_CHARS, "").trim().takeIf { it.length > 2 } ?: "—"
                    }

                    for (num in row.lessonNums) {
                        if (num in 1..14) {
                            lessonsByHour.getOrPut(num) { mutableListOf() }.add(Triple(cleanSubj, cleanRm, cleanTchr))
                        }
                    }
                }
            }

            if (lessonsByHour.isEmpty()) continue

            val sortedHours = lessonsByHour.keys.sorted()
            var currentBlockHours = mutableListOf<Int>()
            var currentSubject = ""
            var currentRoom = ""
            var currentTeacher = ""
            var pairCounter = 1
            val dailyLessons = mutableListOf<LessonPair>()

            for (hour in sortedHours) {
                val entries = lessonsByHour[hour]!!
                val subjects = entries.map { it.first }.toSet().joinToString(" / ")
                val rooms = entries.map { it.second }.toSet().joinToString(" / ").ifEmpty { "—" }
                val teachers = entries.map { it.third }.toSet().joinToString(" / ").ifEmpty { "—" }

                if (currentBlockHours.isEmpty()) {
                    currentBlockHours.add(hour)
                    currentSubject = subjects
                    currentRoom = rooms
                    currentTeacher = teachers
                } else {
                    val lastHour = currentBlockHours.last()
                    if (hour == lastHour + 1 && subjects == currentSubject && teachers == currentTeacher) {
                        currentBlockHours.add(hour)
                    } else {
                        val start = timings.getOrNull(currentBlockHours.first() - 1)?.first ?: "??:??"
                        val end = timings.getOrNull(currentBlockHours.last() - 1)?.second ?: "??:??"
                        dailyLessons.add(LessonPair(pairCounter++, currentBlockHours.joinToString(", "), start, end, currentSubject, currentRoom, currentTeacher))

                        currentBlockHours = mutableListOf(hour)
                        currentSubject = subjects
                        currentRoom = rooms
                        currentTeacher = teachers
                    }
                }
            }
            if (currentBlockHours.isNotEmpty()) {
                val start = timings.getOrNull(currentBlockHours.first() - 1)?.first ?: "??:??"
                val end = timings.getOrNull(currentBlockHours.last() - 1)?.second ?: "??:??"
                dailyLessons.add(LessonPair(pairCounter++, currentBlockHours.joinToString(", "), start, end, currentSubject, currentRoom, currentTeacher))
            }

            if (dailyLessons.isNotEmpty()) {
                val existing = result.getOrPut(theDate) { mutableListOf() }
                (existing as MutableList).addAll(dailyLessons)
            }
        }

        result.keys.forEach { date ->
            val sortedList = result[date]!!.sortedBy { it.startLocalTime ?: LocalTime.MIN }
            result[date] = sortedList.mapIndexed { index, pair -> pair.copy(pairNumber = index + 1) }
        }
        return result
    }

    @SuppressLint("NewApi")
    private fun getBellSchedule(date: LocalDate): List<Pair<String, String>> {
        val shortenedDays = prefs.getStringSet("shortened_days", emptySet()) ?: emptySet()
        val isShortened = shortenedDays.contains(date.toString())
        val dayKey = when {
            isShortened -> "SHORTENED"
            date.dayOfWeek == DayOfWeek.MONDAY -> "MONDAY"
            date.dayOfWeek == DayOfWeek.THURSDAY -> "THURSDAY"
            date.dayOfWeek == DayOfWeek.SATURDAY -> "SATURDAY"
            else -> "REGULAR"
        }
        val customJson = prefs.getString("custom_bell_schedule", null)
        if (customJson != null) {
            try {
                val root = JSONObject(customJson)
                if (root.has(dayKey)) {
                    val arr = root.getJSONArray(dayKey)
                    val list = mutableListOf<Pair<String, String>>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(obj.getString("start") to obj.getString("end"))
                    }
                    if (list.isNotEmpty()) return list
                }
            } catch (e: Exception) { Log.e("PulseParser", "Ошибка чтения кастомных звонков", e) }
        }

        return when (dayKey) {
            "MONDAY" -> listOf(
                "08:30" to "09:15", "09:25" to "10:10", "10:20" to "11:05", "11:15" to "12:00",
                "13:00" to "13:25", "13:35" to "14:20", "14:30" to "15:15", "15:25" to "16:10",
                "16:20" to "17:05", "17:15" to "18:00", "18:10" to "18:55", "19:05" to "19:50", "20:00" to "20:45"
            )
            "THURSDAY", "REGULAR" -> listOf(
                "08:30" to "09:15", "09:25" to "10:10", "10:20" to "11:05", "11:15" to "12:00",
                "13:00" to "13:45", "13:55" to "14:40", "14:50" to "15:35", "15:45" to "16:30",
                "16:40" to "17:25", "17:35" to "18:20", "18:30" to "19:15", "19:25" to "20:10", "20:20" to "21:05"
            )
            "SATURDAY" -> listOf(
                "08:30" to "09:15", "09:15" to "10:00", "10:15" to "11:05", "11:05" to "11:45",
                "12:00" to "13:10", "13:20" to "13:30", "13:55" to "15:00", "15:00" to "15:15",
                "15:30" to "16:50", "17:00" to "17:00", "17:55" to "18:40", "18:50" to "19:35", "19:45" to "20:30"
            )
            "SHORTENED" -> listOf(
                "08:30" to "09:10", "09:10" to "09:50", "10:00" to "10:40", "10:40" to "11:20",
                "11:40" to "12:20", "12:20" to "13:00", "13:10" to "13:50", "13:50" to "14:30",
                "14:40" to "15:20", "15:20" to "16:00", "16:10" to "16:50", "16:50" to "17:30",
                "17:40" to "18:20", "18:20" to "19:00"
            )
            else -> emptyList()
        }
    }

    private fun generateRandomAvatarColor(): Long {
        val colors = listOf(0xFFE91E63, 0xFF4CAF50, 0xFF2196F3, 0xFFFF9800, 0xFF9C27B0, 0xFF795548, 0xFF607D8B, 0xFF3F51B5)
        return colors.random()
    }

    private fun loadTeachersFromPrefs(): List<Teacher> {
        val json = prefs.getString("saved_teachers", "[]") ?: "[]"
        val list = mutableListOf<Teacher>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Teacher(
                    name = obj.getString("name"),
                    subject = obj.getString("subject"),
                    room = obj.getString("room"),
                    email = obj.getString("email"),
                    avatarColor = obj.getLong("avatarColor")
                ))
            }
        } catch (e: Exception) {
            Log.e("PulseParser", "Ошибка загрузки преподавателей", e)
        }
        return list
    }

    private fun saveTeachersToPrefs(teachers: List<Teacher>) {
        val array = JSONArray()
        teachers.forEach { t ->
            val obj = JSONObject()
            obj.put("name", t.name); obj.put("subject", t.subject); obj.put("room", t.room); obj.put("email", t.email); obj.put("avatarColor", t.avatarColor)
            array.put(obj)
        }
        prefs.edit().putString("saved_teachers", array.toString()).apply()
    }
}