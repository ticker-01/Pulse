package com.example.pulse.screen

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import com.example.pulse.utils.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.pulse.viewmodel.PerformanceViewModel
import com.example.pulse.viewmodel.ScheduleViewModel
import com.example.pulse.viewmodel.Semester
import com.example.pulse.viewmodel.SubjectGrade
import com.example.pulse.viewmodel.Teacher
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

private val ColorGrade5 = Color(0xFF4CAF50)
private val ColorGrade4 = Color(0xFF2196F3)
private val ColorGrade3 = Color(0xFFFFC107)
private val ColorGrade2 = Color(0xFFF44336)
private val ColorGradeNone = Color(0xFF888899)

private fun isIgnoredSubjectGrade(subject: String): Boolean {
    val clean = subject.lowercase().replace(Regex("""[^a-zа-яё]"""), "")

    val keywords = setOf(
        "клчас", "классныйчас", "класчас", "класныйчас", "классныйч", "класч",
        "клч", "кч", "класныйч", "классч", "клчасы", "классныечасы", "класчасы",
        "часклассного", "часклассный", "часруководителя", "классныйруководитель",
        "клрук", "классный", "клас", "кл",
        "воспитательныйчас", "воспчас", "воспитчас", "воспитательскийчас",
        "беседа", "бес", "внеклассное", "внекл", "внеклас",
        "разговорыоважном", "разговор", "рв", "урокмужества", "урок",

        "курчас", "кураторскийчас", "курч", "кураторч", "кураторчас",
        "часкуратора", "часыкуратора", "куратчас", "кураторскиечасы",
        "курчасы", "крч", "куч", "кураторскийч", "куратор", "кур",
        "кураторский", "курат", "часкуратор",

        "информчас", "информационныйчас", "инфочас", "инфчас", "инфч",
        "инфоч", "информацчас", "информч", "информационныйч", "инф",
        "ич", "инфчасы", "информационныечасы", "инфочасы", "инфорчас",
        "инфомационныйчас", "информац", "инфор",

        "klchas", "klassnychas", "klasshour", "classhour",
        "kuratorchas", "curatorhour", "kuratorhour",
        "informchas", "infohour", "infochas"
    )

    return keywords.any { clean.contains(it) }
}

private fun normalizeString(text: String): String {
    return text.lowercase()
        .replace('ё', 'е')
        .replace(Regex("""[^a-zа-я0-9\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun levenshteinDistance(s1: String, s2: String): Int {
    val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
    for (i in 0..s1.length) dp[i][0] = i
    for (j in 0..s2.length) dp[0][j] = j

    for (i in 1..s1.length) {
        for (j in 1..s2.length) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = min(
                min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                dp[i - 1][j - 1] + cost
            )
        }
    }
    return dp[s1.length][s2.length]
}

private fun stringSimilarity(s1: String, s2: String): Double {
    val maxLen = max(s1.length, s2.length)
    if (maxLen == 0) return 1.0
    return 1.0 - (levenshteinDistance(s1, s2).toDouble() / maxLen.toDouble())
}

private fun areSubjectsSimilar(name1: String, name2: String): Boolean {
    val n1 = normalizeString(name1)
    val n2 = normalizeString(name2)

    if (n1 == n2) return true
    if (n1.isEmpty() || n2.isEmpty()) return false

    val compact1 = n1.replace(" ", "")
    val compact2 = n2.replace(" ", "")
    if (compact1.length >= 5 && compact2.length >= 5) {
        if (compact1.contains(compact2) || compact2.contains(compact1)) return true
    }

    val ignoredWords = setOf("и", "в", "по", "с", "к", "на", "о", "об")
    val tokens1 = n1.split(" ").filter { it.length > 1 && it !in ignoredWords }
    val tokens2 = n2.split(" ").filter { it.length > 1 && it !in ignoredWords }

    if (tokens1.isNotEmpty() && tokens2.isNotEmpty()) {
        var matchedTokens = 0
        for (t1 in tokens1) {
            val match = tokens2.any { t2 ->
                if (t1 == t2) return@any true
                val minPrefixLen = min(t1.length, t2.length)
                if (minPrefixLen >= 3 && (t1.startsWith(t2) || t2.startsWith(t1))) return@any true
                stringSimilarity(t1, t2) >= 0.8
            }
            if (match) matchedTokens++
        }

        val matchRatio1 = matchedTokens.toDouble() / tokens1.size
        val matchRatio2 = matchedTokens.toDouble() / tokens2.size
        if (matchRatio1 >= 0.65 && matchRatio2 >= 0.65) return true
    }

    return stringSimilarity(compact1, compact2) >= 0.82
}

private fun getSubjectGroupKey(name: String): String {
    val s = name.lowercase()
        .replace('ё', 'е')
        .replace(Regex("""[^а-яa-z0-9]"""), "")

    return when {
        (s.contains("стан") || s.contains("спу") || s.contains("чпу")) &&
                (s.contains("прогр") || s.contains("пр") || s.contains("стан")) -> "прогр_стан_спу"

        s.contains("эконом") -> "осн_экономики"
        s.contains("cad") || s.contains("cam") -> "прог_cad_cam"
        s.contains("налад") -> "наладка"
        s.contains("привод") -> "приводы"
        s.contains("техпроц") || s.contains("техмаш") || s.contains("технологиямаш") -> "тех_проц_маш"
        s.contains("сисупр") || (s.contains("упр") && s.contains("авт")) -> "сис_упр_авт_об"
        s == "мрс" || s.contains("ремонтстан") -> "мрс"
        s.contains("физкульт") || s.contains("физра") || s == "фк" -> "физкультура"
        s.contains("прав") -> "осн_права"
        s.contains("электротех") || s.contains("элтех") -> "электротехника"
        s.contains("принф") || s.contains("приклинф") || (s.contains("информ") && s.contains("прикл")) -> "пр_инф"
        s.contains("инфтех") || s.contains("информационнтех") -> "инф_тех"
        s.contains("инжграф") || s.contains("черчен") || s.contains("начерт") -> "инж_графика"
        s.contains("нормир") -> "нормирование"
        s.contains("охран") || s.contains("охртруд") -> "охрана_труда"
        s.contains("иняз") || s.contains("англ") || s.contains("немец") || s.contains("профл") -> "ин_яз"
        s.contains("рус") && s.contains("яз") -> "рус_яз"
        s.contains("рус") && s.contains("лит") -> "рус_лит"
        s.contains("бел") && s.contains("яз") -> "бел_яз"
        s.contains("бел") && s.contains("лит") -> "бел_лит"
        s.contains("обществ") -> "обществоведение"
        s.contains("истор") || s.contains("истбел") -> "история"
        s.contains("математ") || s.contains("матем") || s.contains("алгебр") -> "математика"
        s.contains("физик") -> "физика"
        s.contains("хими") -> "химия"
        s.contains("биолог") -> "биология"
        s.contains("геогр") -> "география"
        s.contains("дпю") || s.contains("допризыв") -> "дпю"
        s == "мв" || s.contains("микропроцессор") -> "микропроцессоры"
        s.contains("логистик") -> "логистика"
        s.contains("устравто") || s.contains("автомобил") -> "устр_авто"
        s.contains("пдд") || s.contains("дорожндвижен") -> "пдд"
        s.contains("ртк") || s.contains("робототехн") -> "ртк"
        s.contains("совртех") -> "совр_тех"
        s.contains("поверк") -> "поверка"
        s.contains("схем") -> "схемотехника"
        s.contains("изгдет") || s.contains("детал") -> "тех_изг_дет"
        s.contains("менедж") -> "менеджмент"
        else -> s
    }
}

private fun chooseBestSubjectName(names: List<String>): String {
    if (names.isEmpty()) return ""
    if (names.size == 1) return names.first().trim()

    val sampleKey = getSubjectGroupKey(names.first())
    val canonicalPrettyNames = mapOf(
        "прогр_стан_спу" to "Прогр.стан сПУ",
        "осн_экономики" to "Осн. экономики",
        "прог_cad_cam" to "Прог. CAD/CAM",
        "наладка" to "Наладка",
        "приводы" to "Приводы",
        "тех_проц_маш" to "Тех.проц.маш.",
        "сис_упр_авт_об" to "Сис.упр.авт.об.",
        "мрс" to "МРС",
        "физкультура" to "Физкультура",
        "осн_права" to "Осн. права",
        "электротехника" to "Электротехника",
        "пр_инф" to "Пр. инф.",
        "инф_тех" to "Инф. технологии",
        "инж_графика" to "Инженерная графика",
        "нормирование" to "Нормирование",
        "охрана_труда" to "Охрана труда",
        "ин_яз" to "Ин. яз. проф.л.",
        "рус_яз" to "Русский язык",
        "рус_лит" to "Русская литература",
        "бел_яз" to "Белорусский язык",
        "бел_лит" to "Белорусская литература",
        "обществоведение" to "Обществоведение",
        "история" to "История",
        "математика" to "Математика",
        "физика" to "Физика",
        "химия" to "Химия",
        "биология" to "Биология",
        "география" to "География",
        "дпю" to "ДПЮ",
        "микропроцессоры" to "Микропроцессоры",
        "логистика" to "Логистика",
        "устр_авто" to "Устройство авто",
        "пдд" to "ПДД",
        "ртк" to "РТК",
        "совр_тех" to "Совр. технологии",
        "поверка" to "Поверка",
        "схемотехника" to "Схемотехника",
        "тех_изг_дет" to "Тех.изг.дет.",
        "менеджмент" to "Осн. менеджмента"
    )

    canonicalPrettyNames[sampleKey]?.let { return it }

    return names.maxByOrNull { name ->
        var score = name.trim().length * 2
        if (name.contains(".")) score -= 5
        if (name.firstOrNull()?.isUpperCase() == true) score += 4
        if (name.length > 5 && name == name.uppercase()) score -= 6
        score
    }?.trim() ?: names.first().trim()
}

private data class MergedGradesData(
    val items: List<SubjectGrade>,
    val siblingIdsMap: Map<String, List<String>>
)

private fun mergeSimilarGrades(rawGrades: List<SubjectGrade>): MergedGradesData {
    if (rawGrades.isEmpty()) return MergedGradesData(emptyList(), emptyMap())

    val clusters = mutableListOf<MutableList<SubjectGrade>>()

    for (grade in rawGrades) {
        val targetCluster = clusters.firstOrNull { cluster ->
            cluster.any { existing ->
                val key1 = getSubjectGroupKey(existing.subjectName)
                val key2 = getSubjectGroupKey(grade.subjectName)
                (key1 == key2 && key1.isNotEmpty()) || areSubjectsSimilar(existing.subjectName, grade.subjectName)
            }
        }

        if (targetCluster != null) {
            targetCluster.add(grade)
        } else {
            clusters.add(mutableListOf(grade))
        }
    }

    val resultList = mutableListOf<SubjectGrade>()
    val siblingsMap = mutableMapOf<String, List<String>>()

    for (groupList in clusters) {
        if (groupList.size == 1) {
            val single = groupList.first()
            val pretty = chooseBestSubjectName(listOf(single.subjectName))
            resultList.add(if (single.subjectName != pretty) single.copy(subjectName = pretty) else single)
        } else {
            val primary = groupList.maxByOrNull { it.scores.size + it.krScores.size } ?: groupList.first()
            val siblingIds = groupList.filter { it.id != primary.id }.map { it.id }

            val combinedScores = groupList.flatMap { it.scores }
            val combinedKrScores = groupList.flatMap { it.krScores }
            val teachers = groupList.map { it.teacher.trim() }
                .filter { it.isNotBlank() && it != "—" && it != "-" }
                .distinct()
            val finalTeacher = if (teachers.isNotEmpty()) teachers.joinToString(" / ") else primary.teacher
            val target = groupList.mapNotNull { it.targetScore }.firstOrNull()
            val prettyName = chooseBestSubjectName(groupList.map { it.subjectName })

            val merged = primary.copy(
                subjectName = prettyName,
                teacher = finalTeacher,
                scores = combinedScores,
                targetScore = target,
                krScores = combinedKrScores
            )

            resultList.add(merged)
            if (siblingIds.isNotEmpty()) {
                siblingsMap[primary.id] = siblingIds
            }
        }
    }

    return MergedGradesData(resultList, siblingsMap)
}

@Composable
fun PerformanceScreen(
    navController: NavController,
    viewModel: PerformanceViewModel = viewModel(),
    schedViewModel: ScheduleViewModel? = null
) {
    val activity = LocalContext.current as ComponentActivity
    val scheduleViewModel: ScheduleViewModel = schedViewModel ?: viewModel(viewModelStoreOwner = activity)
    val context = LocalContext.current

    val semesters = viewModel.semesters
    val selectedSemesterIndex = viewModel.selectedSemesterIndex
    val scheduleState by scheduleViewModel.state.collectAsState()
    val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
    var roundingMode by remember { mutableIntStateOf(prefs.getInt("rounding_mode", 1)) }

    var showGradeDialog by remember { mutableStateOf(false) }
    var showSemesterDialog by remember { mutableStateOf(false) }
    var showDeleteSemesterDialog by remember { mutableStateOf(false) }
    var editingGrade by remember { mutableStateOf<SubjectGrade?>(null) }
    var activeSiblingIds by remember { mutableStateOf<List<String>>(emptyList()) }

    var isDeleting by remember { mutableStateOf(false) }

    var selectedModeTab by remember { mutableIntStateOf(0) }
    var simSubjectId by remember { mutableStateOf<String?>(null) }
    val simRegularScores = remember { mutableStateListOf<Int>() }
    val simKrScores = remember { mutableStateListOf<Int>() }
    var simScoreEntryMode by remember { mutableIntStateOf(0) }

    val deleteProgress by animateFloatAsState(
        targetValue = if (isDeleting) 1f else 0f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "tg_delete_effect"
    )

    LaunchedEffect(isDeleting) {
        if (isDeleting) {
            delay(400)
            viewModel.deleteSemester(selectedSemesterIndex)
            isDeleting = false
        }
    }

    val tgDeleteModifier = if (isDeleting) {
        Modifier.graphicsLayer {
            scaleX = 1f - (deleteProgress * 0.2f)
            scaleY = 1f - (deleteProgress * 0.8f)
            alpha = 1f - (deleteProgress * 1.5f).coerceIn(0f, 1f)
            rotationX = deleteProgress * 60f
            cameraDistance = 8f * density
        }
    } else Modifier

    LaunchedEffect(Unit) {
        if (scheduleViewModel.getAllGroupLessons().isEmpty()) scheduleViewModel.refreshData()
    }

    LaunchedEffect(scheduleState.isLoading) {
        if (!scheduleState.isLoading) {
            val groupLessons = scheduleViewModel.getAllGroupLessons()
            if (groupLessons.isNotEmpty()) viewModel.syncWithLessons(groupLessons)
        }
    }

    val currentSemester = semesters.getOrNull(selectedSemesterIndex)

    val (grades, siblingsMap) = remember(currentSemester) {
        val raw = currentSemester?.grades?.filter { !isIgnoredSubjectGrade(it.subjectName) } ?: emptyList()
        val merged = mergeSimilarGrades(raw)
        Pair(merged.items, merged.siblingIdsMap)
    }

    val totalSubjects = grades.size
    val activeGrades = remember(grades) { grades.filter { it.effectiveAverage > 0.0 } }

    val globalAverage = remember(activeGrades, roundingMode) {
        if (activeGrades.isNotEmpty()) {
            activeGrades.map { formatScoreByMode(it.effectiveAverage, roundingMode) }.average()
        } else {
            0.0
        }
    }

    val passedCount = remember(grades) { grades.count { it.effectiveAverage >= 5.0 } }
    val failedCount = remember(grades) { grades.count { it.effectiveAverage > 0.0 && it.effectiveAverage < 5.0 } }
    val inProgressCount = remember(grades) { grades.count { it.effectiveAverage == 0.0 } }

    val deepReport = remember(grades, roundingMode) {
        PerformanceAnalytics.buildDeepReport(grades, roundingMode)
    }

    val effectiveSimSubjectId = simSubjectId ?: grades.firstOrNull()?.id
    val simulationResult = remember(grades, effectiveSimSubjectId, simRegularScores.toList(), simKrScores.toList(), roundingMode) {
        if (effectiveSimSubjectId != null) {
            PerformanceAnalytics.simulateGrades(
                originalGrades = grades,
                targetSubjectId = effectiveSimSubjectId,
                addedRegularScores = simRegularScores.toList(),
                addedKrScores = simKrScores.toList(),
                roundingMode = roundingMode
            )
        } else null
    }

    Box(modifier = Modifier.fillMaxSize().background(BgDark)) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item { TopBarEditable(navController) { editingGrade = null; activeSiblingIds = emptyList(); showGradeDialog = true } }
            item { Spacer(modifier = Modifier.height(24.dp)) }

            item {
                DynamicSemesterTabs(
                    semesters = semesters,
                    selectedIndex = selectedSemesterIndex,
                    onSelect = { viewModel.selectSemester(it) },
                    onAddSemester = { showSemesterDialog = true },
                    onDeleteSemester = { showDeleteSemesterDialog = true },
                    surfaceColor = SurfaceDark,
                    primaryColor = PrimaryPurple,
                    textGray = TextGray
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }

            item {
                ModeSwitcher(
                    selectedTab = selectedModeTab,
                    onTabSelected = { selectedModeTab = it },
                    surfaceColor = SurfaceDark,
                    primaryColor = PrimaryPurple,
                    textGray = TextGray
                )
            }
            item { Spacer(modifier = Modifier.height(20.dp)) }

            if (selectedModeTab == 0) {
                item {
                    Box(modifier = tgDeleteModifier) {
                        AverageScoreCard(globalAverage, totalSubjects, grades, roundingMode, SurfaceDark) { newMode ->
                            roundingMode = newMode
                            prefs.edit().putInt("rounding_mode", newMode).apply()
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                item {
                    Box(modifier = tgDeleteModifier) {
                        StatsRow(totalSubjects, passedCount, inProgressCount, failedCount, PrimaryPurple, TextGray)
                    }
                }
                item { Spacer(modifier = Modifier.height(32.dp)) }

                item {
                    Box(modifier = tgDeleteModifier) { SectionHeader("Оценки по предметам") }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                if (grades.isEmpty()) {
                    item {
                        Box(modifier = tgDeleteModifier) {
                            Text("В этом семестре пока нет предметов. Нажмите '+' чтобы добавить.", color = TextGray, modifier = Modifier.padding(16.dp), fontSize = 14.sp)
                        }
                    }
                } else {
                    items(grades, key = { it.id }) { gradeItem ->
                        Box(modifier = tgDeleteModifier) {
                            EditableGradeCard(gradeItem, roundingMode, SurfaceDark, TextGray) {
                                editingGrade = gradeItem
                                activeSiblingIds = siblingsMap[gradeItem.id] ?: emptyList()
                                showGradeDialog = true
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
                item {
                    Box(modifier = tgDeleteModifier) { SectionHeader("Динамика успеваемости") }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
                item {
                    Box(modifier = tgDeleteModifier) { LineChartCard(grades, SurfaceDark, PrimaryPurple, TextGray) }
                }
            } else {
                // Вкладка "Глубокая аналитика"
                item {
                    ScholarshipCard(deepReport.scholarshipStatus, SurfaceDark, PrimaryPurple, TextGray)
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                item {
                    QualityMetricsGrid(deepReport.distribution, deepReport.diagnostics, SurfaceDark, PrimaryPurple, TextGray)
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                item {
                    GradeDistributionCard(deepReport.distribution, SurfaceDark, TextGray)
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                if (deepReport.drivers.isNotEmpty() || deepReport.anchors.isNotEmpty()) {
                    item {
                        DriversAndAnchorsCard(deepReport.drivers, deepReport.anchors, SurfaceDark, TextGray)
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }

                item {
                    DiagnosticsAndTrendsCard(deepReport.trends, deepReport.diagnostics, SurfaceDark, TextGray)
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                if (grades.isNotEmpty()) {
                    item {
                        WhatIfSimulatorCard(
                            grades = grades,
                            selectedSubjectId = effectiveSimSubjectId,
                            onSelectSubject = { simSubjectId = it },
                            regularScores = simRegularScores.toList(),
                            krScores = simKrScores.toList(),
                            scoreEntryMode = simScoreEntryMode,
                            onScoreEntryModeChange = { simScoreEntryMode = it },
                            onAddScore = { score ->
                                if (simScoreEntryMode == 0) simRegularScores.add(score)
                                else simKrScores.add(score)
                            },
                            onRemoveRegularScore = { simRegularScores.remove(it) },
                            onRemoveKrScore = { simKrScores.remove(it) },
                            onReset = {
                                simRegularScores.clear()
                                simKrScores.clear()
                            },
                            simulationResult = simulationResult,
                            surfaceColor = SurfaceDark,
                            primaryColor = PrimaryPurple,
                            textGray = TextGray
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (showDeleteSemesterDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSemesterDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Удалить семестр?", color = Color.White) },
            text = { Text("Вы уверены, что хотите удалить этот семестр и все его оценки?", color = TextGray) },
            confirmButton = {
                Button(
                    onClick = { showDeleteSemesterDialog = false; isDeleting = true },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorGrade2)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSemesterDialog = false }) { Text("Отмена", color = TextGray) }
            }
        )
    }

    if (showGradeDialog) {
        AddEditGradeDialog(
            initialGrade = editingGrade, teachersList = scheduleState.teachers, onDismiss = { showGradeDialog = false },
            surfaceColor = SurfaceDark, primaryColor = PrimaryPurple, textGray = TextGray, bgDark = BgDark,
            onSave = { name, tchr, scrs, trgt, krScrs ->
                viewModel.addOrUpdateGrade(name, tchr, scrs, trgt, krScrs, editingGrade?.id)
                activeSiblingIds.forEach { viewModel.deleteGrade(it) }
                activeSiblingIds = emptyList()
                showGradeDialog = false
            },
            onDelete = {
                editingGrade?.let {
                    viewModel.deleteGrade(it.id)
                    activeSiblingIds.forEach { sibId -> viewModel.deleteGrade(sibId) }
                }
                activeSiblingIds = emptyList()
                showGradeDialog = false
            }
        )
    }

    if (showSemesterDialog) {
        AddSemesterDialog(
            onDismiss = { showSemesterDialog = false },
            surfaceColor = SurfaceDark, primaryColor = PrimaryPurple, textGray = TextGray,
            onSave = { viewModel.addSemester(it); showSemesterDialog = false }
        )
    }
}

@Composable
private fun DynamicSemesterTabs(
    semesters: List<Semester>, selectedIndex: Int, onSelect: (Int) -> Unit,
    onAddSemester: () -> Unit, onDeleteSemester: () -> Unit,
    surfaceColor: Color, primaryColor: Color, textGray: Color
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor, RoundedCornerShape(20.dp))
            .border(1.dp, primaryColor.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(semesters, key = { _, semester -> semester.id }) { index, semester ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) primaryColor else Color.Transparent)
                    .clickable { if (index != selectedIndex) onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .animateContentSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = semester.name,
                    color = if (isSelected) Color.White else textGray,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
        item { IconButton(onClick = onAddSemester, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Add, contentDescription = "Новый семестр", tint = textGray) } }
        if (semesters.size > 1) {
            item { IconButton(onClick = onDeleteSemester, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить семестр", tint = ColorGrade2) } }
        }
    }
}

@Composable
private fun AddSemesterDialog(onDismiss: () -> Unit, surfaceColor: Color, primaryColor: Color, textGray: Color, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = surfaceColor,
        title = { Text("Новый семестр", color = Color.White) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название", color = textGray) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = primaryColor), singleLine = true) },
        confirmButton = { Button(onClick = { onSave(name) }, enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = primaryColor)) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = textGray) } }
    )
}

@Composable
private fun AddEditGradeDialog(
    initialGrade: SubjectGrade?, teachersList: List<Teacher>, onDismiss: () -> Unit,
    surfaceColor: Color, primaryColor: Color, textGray: Color, bgDark: Color,
    onSave: (String, String, List<Int>, Double?, List<Int>) -> Unit, onDelete: () -> Unit
) {
    var subjectName by remember { mutableStateOf(initialGrade?.subjectName ?: "") }
    var teacher by remember { mutableStateOf(initialGrade?.teacher ?: "") }
    var targetText by remember { mutableStateOf(initialGrade?.targetScore?.toString() ?: "") }

    val selectedScores = remember { mutableStateListOf<Int>().apply { addAll(initialGrade?.scores ?: emptyList()) } }
    val selectedKRScores = remember { mutableStateListOf<Int>().apply { addAll(initialGrade?.krScores ?: emptyList()) } }

    var isTeacherFocused by remember { mutableStateOf(false) }
    var scoreEntryMode by remember { mutableIntStateOf(0) }

    val filteredTeachers = teachersList.filter { it.name.contains(teacher, ignoreCase = true) && it.name != teacher }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = surfaceColor,
        title = { Text(if (initialGrade == null) "Добавить предмет" else "Редактировать", color = Color.White) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(value = subjectName, onValueChange = { subjectName = it }, label = { Text("Название предмета", color = textGray) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = primaryColor), singleLine = true, modifier = Modifier.fillMaxWidth())

                Column(modifier = Modifier.animateContentSize()) {
                    OutlinedTextField(value = teacher, onValueChange = { teacher = it }, label = { Text("Преподаватель", color = textGray) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = primaryColor), singleLine = true, modifier = Modifier.fillMaxWidth().onFocusChanged { isTeacherFocused = it.isFocused })
                    AnimatedVisibility(visible = isTeacherFocused && filteredTeachers.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).border(1.dp, Color(0xFF2C2C36), RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(bgDark)) {
                            items(filteredTeachers) { t -> Text(text = t.name, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable { teacher = t.name; isTeacherFocused = false }.padding(12.dp)) }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                    Text("Текущие оценки (нажми, чтобы удалить):", color = textGray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (selectedScores.isEmpty() && selectedKRScores.isEmpty()) Text("Пока нет оценок", color = Color.DarkGray, fontSize = 14.sp)
                    if (selectedScores.isNotEmpty()) {
                        Text("Обычные:", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(selectedScores.toList()) { score -> Box(modifier = Modifier.size(36.dp).background(getGradeVisual(score.toDouble()).color.copy(alpha = 0.2f), CircleShape).border(1.dp, getGradeVisual(score.toDouble()).color, CircleShape).clickable { selectedScores.remove(score) }, contentAlignment = Alignment.Center) { Text(score.toString(), color = getGradeVisual(score.toDouble()).color, fontWeight = FontWeight.Bold) } }
                        }
                    }
                    if (selectedKRScores.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("За КР:", color = Color(0xFFFF9100), fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(selectedKRScores.toList()) { score -> Box(modifier = Modifier.size(36.dp).background(Color(0xFFFF9100).copy(alpha = 0.2f), CircleShape).border(1.dp, Color(0xFFFF9100), CircleShape).clickable { selectedKRScores.remove(score) }, contentAlignment = Alignment.Center) { Text(score.toString(), color = Color(0xFFFF9100), fontWeight = FontWeight.Bold) } }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Добавить оценку:", color = textGray, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RoundingChip("Обычную", scoreEntryMode == 0, primaryColor, textGray, bgDark) { scoreEntryMode = 0 }
                            RoundingChip("За КР", scoreEntryMode == 1, primaryColor, textGray, bgDark) { scoreEntryMode = 1 }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { (6..10).forEach { num -> GradeAddButton(num, surfaceColor) { if (scoreEntryMode == 0) selectedScores.add(num) else selectedKRScores.add(num) } } }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { (1..5).forEach { num -> GradeAddButton(num, surfaceColor) { if (scoreEntryMode == 0) selectedScores.add(num) else selectedKRScores.add(num) } } }
                }

                OutlinedTextField(value = targetText, onValueChange = { targetText = it }, label = { Text("Желаемый средний балл", color = textGray) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = primaryColor), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(subjectName, teacher, selectedScores.toList(), targetText.replace(",", ".").toDoubleOrNull(), selectedKRScores.toList()) }, enabled = subjectName.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = primaryColor)) { Text("Сохранить") } },
        dismissButton = { Row { if (initialGrade != null) TextButton(onClick = onDelete) { Text("Удалить", color = ColorGrade2) }; TextButton(onClick = onDismiss) { Text("Отмена", color = textGray) } } }
    )
}

@Composable
fun GradeAddButton(number: Int, surfaceColor: Color, onClick: () -> Unit) {
    val visual = getGradeVisual(number.toDouble())
    Box(modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(surfaceColor).border(1.dp, visual.color.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) { Text(number.toString(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun AverageScoreCard(globalAverage: Double, totalSubjects: Int, grades: List<SubjectGrade>, roundingMode: Int, surfaceColor: Color, onRoundingChange: (Int) -> Unit) {
    val exactGlobal = round(globalAverage * 100) / 100.0
    val formattedScore = if (exactGlobal % 1.0 == 0.0) exactGlobal.toInt().toString() else exactGlobal.toString()
    val starColor = getGradeVisual(globalAverage).color

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            PrimaryPurple.copy(alpha = 0.42f),
                            SurfaceDark.copy(alpha = 0.95f)
                        )
                    )
                )
                .padding(20.dp)
                .fillMaxWidth()
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Общий средний балл", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (globalAverage > 0) formattedScore else "—", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        if (globalAverage > 0) Icon(Icons.Default.Star, contentDescription = null, tint = starColor, modifier = Modifier.size(24.dp))
                    }
                }
                DynamicDonutChart(modifier = Modifier.size(100.dp), totalSubjects = totalSubjects, grades = grades, surfaceColor = surfaceColor)
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Округление предметов:", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoundingChip("Целые", roundingMode == 0, PrimaryPurple, TextGray, BgDark) { onRoundingChange(0) }
                    RoundingChip("Десятые", roundingMode == 1, PrimaryPurple, TextGray, BgDark) { onRoundingChange(1) }
                    RoundingChip("Сотые", roundingMode == 2, PrimaryPurple, TextGray, BgDark) { onRoundingChange(2) }
                }
            }
        }
    }
}

@Composable
private fun RoundingChip(text: String, isSelected: Boolean, primaryColor: Color, textGray: Color, bgDark: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) primaryColor else bgDark)
            .border(1.dp, if (isSelected) primaryColor else primaryColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (isSelected) Color.White else textGray, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun DynamicDonutChart(modifier: Modifier, totalSubjects: Int, grades: List<SubjectGrade>, surfaceColor: Color) {
    val total = if (totalSubjects > 0) totalSubjects.toFloat() else 1f
    val eS by animateFloatAsState(targetValue = (grades.count { it.effectiveAverage >= 8.5 } / total) * 360f, animationSpec = tween(1000), label = "")
    val gS by animateFloatAsState(targetValue = (grades.count { it.effectiveAverage in 6.5..8.49 } / total) * 360f, animationSpec = tween(1000), label = "")
    val oS by animateFloatAsState(targetValue = (grades.count { it.effectiveAverage in 4.5..6.49 } / total) * 360f, animationSpec = tween(1000), label = "")
    val bS by animateFloatAsState(targetValue = (grades.count { it.effectiveAverage > 0.0 && it.effectiveAverage < 4.5 } / total) * 360f, animationSpec = tween(1000), label = "")
    val nS by animateFloatAsState(targetValue = (grades.count { it.effectiveAverage == 0.0 } / total) * 360f, animationSpec = tween(1000), label = "")

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sw = 12.dp.toPx()
            var a = -90f
            if (totalSubjects == 0) {
                drawArc(color = surfaceColor, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = Stroke(sw), size = size)
            } else {
                if (eS > 0) { drawArc(color = ColorGrade5, startAngle = a, sweepAngle = eS, useCenter = false, style = Stroke(sw, cap = StrokeCap.Round), size = size); a += eS }
                if (gS > 0) { drawArc(color = ColorGrade4, startAngle = a, sweepAngle = gS, useCenter = false, style = Stroke(sw, cap = StrokeCap.Round), size = size); a += gS }
                if (oS > 0) { drawArc(color = ColorGrade3, startAngle = a, sweepAngle = oS, useCenter = false, style = Stroke(sw, cap = StrokeCap.Round), size = size); a += oS }
                if (bS > 0) { drawArc(color = ColorGrade2, startAngle = a, sweepAngle = bS, useCenter = false, style = Stroke(sw, cap = StrokeCap.Round), size = size); a += bS }
                if (nS > 0) { drawArc(color = ColorGradeNone, startAngle = a, sweepAngle = nS, useCenter = false, style = Stroke(sw, cap = StrokeCap.Round), size = size) }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(totalSubjects.toString(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("предметов", color = TextGray, fontSize = 10.sp) }
    }
}

@Composable
private fun LineChartCard(grades: List<SubjectGrade>, surfaceColor: Color, primaryColor: Color, textGray: Color) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().height(200.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp).fillMaxSize()) {
            val validGrades = grades.filter { it.effectiveAverage > 0.0 }
            if (validGrades.isEmpty()) {
                Text("Добавьте оценки, чтобы увидеть график", color = textGray, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val p = Path()
                    val cAvgs = mutableListOf<Double>()
                    var s = 0.0
                    for (i in validGrades.indices) { s += validGrades[i].effectiveAverage; cAvgs.add(s / (i + 1)) }
                    val sX = if (cAvgs.size > 1) size.width / (cAvgs.size - 1) else size.width / 2
                    val pts = cAvgs.mapIndexed { i, a -> Offset(if (cAvgs.size == 1) size.width / 2 else i * sX, (size.height - ((a.toFloat() / 10f) * size.height)).coerceIn(10.dp.toPx(), size.height - 10.dp.toPx())) }
                    p.moveTo(pts.first().x, pts.first().y); pts.forEach { p.lineTo(it.x, it.y) }
                    drawPath(p, primaryColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    pts.forEach { pt -> drawCircle(surfaceColor, 6.dp.toPx(), pt); drawCircle(primaryColor, 4.dp.toPx(), pt, style = Stroke(2.dp.toPx())) }
                }
            }
        }
    }
}

@Composable
private fun TopBarEditable(navController: NavController, onAddClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White) }
            Text("Успеваемость", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onAddClick) { Icon(Icons.Default.AddBox, contentDescription = "Добавить", tint = Color.White, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
private fun StatsRow(total: Int, passed: Int, inProgress: Int, failed: Int, primaryColor: Color, textGray: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        StatItem(total.toString(), "Предметов", Icons.Default.MenuBook, primaryColor, textGray)
        StatItem(passed.toString(), "Сдано", Icons.Default.CheckCircle, ColorGrade5, textGray)
        StatItem(inProgress.toString(), "В процессе", Icons.Default.Schedule, ColorGrade3, textGray)
        StatItem(failed.toString(), "Не сдано", Icons.Default.Cancel, ColorGrade2, textGray)
    }
}

@Composable
private fun StatItem(value: String, label: String, icon: ImageVector, color: Color, textGray: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Text(label, color = textGray, fontSize = 12.sp)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun EditableGradeCard(grade: SubjectGrade, roundingMode: Int, surfaceColor: Color, textGray: Color, onClick: () -> Unit) {
    val style = getSubjectStyle(grade.subjectName)
    val gradeVisual = getGradeVisual(grade.effectiveAverage)
    val roundedScore = formatScoreByMode(grade.effectiveAverage, roundingMode)
    val scoreText = if (roundedScore % 1.0 == 0.0) roundedScore.toInt().toString() else roundedScore.toString()

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(42.dp).background(style.color, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(style.icon, contentDescription = null, tint = Color.White) }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(grade.subjectName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (grade.teacher.isNotBlank()) Text(grade.teacher, color = textGray, fontSize = 12.sp)
                if (grade.scores.isNotEmpty()) Text("Оценки: ${grade.scores.joinToString(", ")}", color = Color.LightGray, fontSize = 12.sp)
                if (grade.krScores.isNotEmpty()) Text("КР: ${grade.krScores.joinToString(", ")}", color = Color(0xFFFF9100), fontSize = 12.sp)
                if (grade.targetScore != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Цель ${grade.targetScore}: ${getNeededGradesText(grade, grade.targetScore)}", color = if (grade.effectiveAverage >= grade.targetScore) ColorGrade5 else ColorGrade3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(modifier = Modifier.border(1.dp, gradeVisual.color.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = if (grade.effectiveAverage > 0.0) scoreText else "—", color = gradeVisual.color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(6.dp))
                Text(gradeVisual.text, color = gradeVisual.color, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(Icons.Default.Edit, contentDescription = "Редактировать", tint = textGray, modifier = Modifier.size(18.dp))
        }
    }
}

fun formatScoreByMode(score: Double, mode: Int): Double {
    if (score.isNaN() || score == 0.0) return 0.0
    return when (mode) {
        0 -> customRound(score)
        2 -> round(score * 100) / 100.0
        else -> round(score * 10) / 10.0
    }
}

private fun customRound(value: Double): Double {
    val integerPart = value.toInt()
    val fractionalPart = value - integerPart
    return if (fractionalPart >= 0.5) (integerPart + 1).toDouble() else integerPart.toDouble()
}

private fun getNeededGradesText(grade: SubjectGrade, target: Double): String {
    if (target > 10.0) return "Невозможно"
    if (grade.effectiveAverage >= target) return "Достигнуто!"

    val krAvg = grade.krAverage
    val requiredBaseAvg = if (grade.krScores.isNotEmpty()) 2 * target - krAvg else target

    if (requiredBaseAvg > 10.0) return "Невозможно"
    if (requiredBaseAvg <= 0.0) return "Уже достигнуто!"

    val sum = grade.scores.sum()
    val count = grade.scores.size

    if (count == 0) {
        val reqInt = ceil(requiredBaseAvg).toInt()
        if (reqInt > 10) return "Невозможно"
        return "Нужно: «$reqInt»x1"
    }

    fun calc(v: Int): Int? {
        if (v <= requiredBaseAvg) return null
        val n = (requiredBaseAvg * count - sum) / (v - requiredBaseAvg)
        if (n <= 0 || !n.isFinite()) return null
        return ceil(n).toInt()
    }

    val p = mutableListOf<String>()
    calc(10)?.takeIf { it < 25 }?.let { p.add("«10»x$it") }
    calc(9)?.takeIf { it < 25 }?.let { p.add("«9»x$it") }
    calc(8)?.takeIf { it < 25 }?.let { p.add("«8»x$it") }

    if (p.isEmpty()) {
        val needed10 = calc(10)
        if (needed10 != null) return "Нужно ОЧЕНЬ много 10-к ($needed10 шт)"
        return "Недостижимо"
    }

    return p.joinToString(" или ")
}

private data class GradeVisual(val color: Color, val text: String)
private fun getGradeVisual(score: Double) = when {
    score >= 8.5 -> GradeVisual(ColorGrade5, "Отлично")
    score >= 6.5 -> GradeVisual(ColorGrade4, "Хорошо")
    score >= 4.0 -> GradeVisual(ColorGrade3, "Удовл.")
    score > 0.0 -> GradeVisual(ColorGrade2, "Неуд.")
    else -> GradeVisual(ColorGradeNone, "Нет")
}

@Composable
fun ModeSwitcher(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    surfaceColor: Color,
    primaryColor: Color,
    textGray: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor, RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(if (selectedTab == 0) primaryColor else Color.Transparent)
                .clickable { onTabSelected(0) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = if (selectedTab == 0) Color.White else textGray,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Оценки",
                    color = if (selectedTab == 0) Color.White else textGray,
                    fontSize = 13.sp,
                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(if (selectedTab == 1) primaryColor else Color.Transparent)
                .clickable { onTabSelected(1) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoGraph,
                    contentDescription = null,
                    tint = if (selectedTab == 1) Color.White else textGray,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Глубокая аналитика",
                    color = if (selectedTab == 1) Color.White else textGray,
                    fontSize = 13.sp,
                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun ScholarshipCard(
    status: ScholarshipStatus,
    surfaceColor: Color,
    primaryColor: Color,
    textGray: Color
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(status.color.copy(alpha = 0.12f), Color.Transparent)
                    )
                )
                .padding(18.dp)
        ) {
            // Верхняя строка: Иконка, Заголовок, Бейдж
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(status.color.copy(alpha = 0.14f), CircleShape)
                            .border(1.dp, status.color.copy(alpha = 0.28f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = status.icon,
                            contentDescription = null,
                            tint = status.color,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Стипендия",
                            color = textGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = status.title,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(status.color.copy(alpha = 0.16f))
                        .border(1.dp, status.color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = status.badgeText,
                        color = status.color,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Сетка ступеней стипендии: 6+, 7+, 8+, 9+
            val tiers = if (status.isFivePointScale) {
                listOf("3.5+", "4.0+", "4.5+", "5.0")
            } else {
                listOf("6+", "7+", "8+", "9+")
            }

            val currentTierIndex = when (status.tier) {
                "6+" -> 0
                "7+" -> 1
                "8+" -> 2
                "9+" -> 3
                else -> -1
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tiers.forEachIndexed { index, tierLabel ->
                    val isCurrent = index == currentTierIndex
                    val isPast = currentTierIndex != -1 && index < currentTierIndex

                    val pillBg = when {
                        isCurrent -> status.color
                        isPast -> status.color.copy(alpha = 0.15f)
                        else -> Color.White.copy(alpha = 0.04f)
                    }
                    val pillBorder = when {
                        isCurrent -> status.color
                        isPast -> status.color.copy(alpha = 0.3f)
                        else -> Color.White.copy(alpha = 0.06f)
                    }
                    val textColor = when {
                        isCurrent -> Color.White
                        isPast -> status.color
                        else -> textGray.copy(alpha = 0.7f)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(pillBg)
                            .border(1.dp, pillBorder, RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tierLabel,
                            color = textColor,
                            fontSize = 13.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            // Индикатор прогресса до следующей категории
            if (!status.hasDebts && status.targetGpa != null && status.tier != "9+") {
                Spacer(modifier = Modifier.height(14.dp))
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Текущий GPA: ${status.currentGpa}",
                            color = textGray,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Цель: ${status.targetGpa}",
                            color = textGray,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(status.progress.coerceIn(0.05f, 1f))
                                .fillMaxHeight()
                                .background(status.color, RoundedCornerShape(3.dp))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = status.description,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun QualityMetricsGrid(
    dist: GradeDistribution,
    diag: DiagnosticsSummary,
    surfaceColor: Color,
    primaryColor: Color,
    textGray: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QualityMetricItem(
                modifier = Modifier.weight(1f),
                title = "Качество знаний",
                value = if (dist.totalCount > 0) "${dist.qualityOfKnowledgePercent}%" else "—",
                subtext = "Оценки 6..10",
                icon = Icons.Outlined.School,
                color = ColorGrade4,
                surfaceColor = surfaceColor,
                textGray = textGray
            )
            QualityMetricItem(
                modifier = Modifier.weight(1f),
                title = "Успеваемость",
                value = if (dist.totalCount > 0) "${dist.absolutePassPercent}%" else "—",
                subtext = "Без двоек (4..10)",
                icon = Icons.Outlined.CheckCircle,
                color = ColorGrade5,
                surfaceColor = surfaceColor,
                textGray = textGray
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QualityMetricItem(
                modifier = Modifier.weight(1f),
                title = "Всего оценок",
                value = "${dist.totalCount}",
                subtext = "${dist.regularCount} ур. • ${dist.krCount} КР",
                icon = Icons.Outlined.AutoStories,
                color = primaryColor,
                surfaceColor = surfaceColor,
                textGray = textGray
            )
            QualityMetricItem(
                modifier = Modifier.weight(1f),
                title = "Стабильность",
                value = diag.stabilityTitle,
                subtext = "Разброс баллов",
                icon = Icons.Outlined.ShowChart,
                color = diag.stabilityColor,
                surfaceColor = surfaceColor,
                textGray = textGray
            )
        }
    }
}

@Composable
private fun QualityMetricItem(
    modifier: Modifier,
    title: String,
    value: String,
    subtext: String,
    icon: ImageVector,
    color: Color,
    surfaceColor: Color,
    textGray: Color
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = textGray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(color.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtext, color = textGray.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
fun GradeDistributionCard(dist: GradeDistribution, surfaceColor: Color, textGray: Color) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Распределение оценок", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("Всего: ${dist.totalCount}", color = textGray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))

            val total = if (dist.totalCount > 0) dist.totalCount.toFloat() else 1f
            val eFrac = dist.excellentCount / total
            val gFrac = dist.goodCount / total
            val sFrac = dist.satisfactoryCount / total
            val fFrac = dist.failCount / total

            // Горизонтальный составной бар
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = 0.06f))
            ) {
                if (eFrac > 0) Box(modifier = Modifier.weight(eFrac).fillMaxHeight().background(ColorGrade5))
                if (gFrac > 0) Box(modifier = Modifier.weight(gFrac).fillMaxHeight().background(ColorGrade4))
                if (sFrac > 0) Box(modifier = Modifier.weight(sFrac).fillMaxHeight().background(ColorGrade3))
                if (fFrac > 0) Box(modifier = Modifier.weight(fFrac).fillMaxHeight().background(ColorGrade2))
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Легенда
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DistributionLegendItem("9–10", dist.excellentCount, ColorGrade5)
                DistributionLegendItem("6–8", dist.goodCount, ColorGrade4)
                DistributionLegendItem("4–5", dist.satisfactoryCount, ColorGrade3)
                DistributionLegendItem("1–3", dist.failCount, ColorGrade2)
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(10.dp))

            // Детализация по баллам 10..1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                (10 downTo 1).forEach { num ->
                    val cnt = dist.countsByScore[num] ?: 0
                    val col = when (num) {
                        in 9..10 -> ColorGrade5
                        in 6..8 -> ColorGrade4
                        in 4..5 -> ColorGrade3
                        else -> ColorGrade2
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$num", color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "$cnt",
                            color = if (cnt > 0) Color.White else textGray.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = if (cnt > 0) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DistributionLegendItem(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(4.dp))
        Text("$label: ", color = Color.LightGray, fontSize = 11.sp)
        Text("$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DriversAndAnchorsCard(
    drivers: List<SubjectImpact>,
    anchors: List<SubjectImpact>,
    surfaceColor: Color,
    textGray: Color
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Влияние предметов на средний балл", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text("Какие дисциплины повышают или снижают GPA", color = textGray, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(14.dp))

            if (drivers.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TrendingUp, contentDescription = null, tint = ColorGrade5, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Тянут средний балл вверх", color = ColorGrade5, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                drivers.forEach { item ->
                    ImpactSubjectRow(item, isPositive = true, textGray = textGray)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            if (anchors.isNotEmpty()) {
                if (drivers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TrendingDown, contentDescription = null, tint = ColorGrade2, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Тянут средний балл вниз", color = ColorGrade2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                anchors.forEach { item ->
                    ImpactSubjectRow(item, isPositive = false, textGray = textGray)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun ImpactSubjectRow(item: SubjectImpact, isPositive: Boolean, textGray: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.subject.subjectName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("Средний: ${item.effectiveAverage}", color = textGray, fontSize = 11.sp)
        }
        val badgeColor = if (isPositive) ColorGrade5 else ColorGrade2
        val sign = if (isPositive) "+" else "-"
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(badgeColor.copy(alpha = 0.12f))
                .border(1.dp, badgeColor.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                "$sign${item.estimatedGpaLossOrGain} к GPA",
                color = badgeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun DiagnosticsAndTrendsCard(
    trends: List<SubjectTrend>,
    diag: DiagnosticsSummary,
    surfaceColor: Color,
    textGray: Color
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Диагностика оценок", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            // Блок КР
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(diag.krColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Assignment, contentDescription = null, tint = diag.krColor, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Контрольные работы", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("КР: ${diag.overallKrAvg} • Уроки: ${diag.overallRegularAvg}", color = diag.krColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(diag.krImpactDescription, color = textGray, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }

            // Выбросы
            if (diag.outlierNote != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ColorGrade3.copy(alpha = 0.08f))
                        .border(1.dp, ColorGrade3.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Report, contentDescription = null, tint = ColorGrade3, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(diag.outlierNote, color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, lineHeight = 15.sp)
                }
            }

            // Тренды
            if (trends.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text("Динамика по предметам", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                trends.take(3).forEach { t ->
                    val (icon, tint) = when (t.direction) {
                        TrendDirection.UP -> Pair(Icons.Default.ArrowUpward, ColorGrade5)
                        TrendDirection.DOWN -> Pair(Icons.Default.ArrowDownward, ColorGrade2)
                        TrendDirection.STABLE -> Pair(Icons.Default.ArrowForward, ColorGrade4)
                        TrendDirection.NOT_ENOUGH_DATA -> Pair(Icons.Default.HorizontalRule, textGray)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(tint.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t.subject.subjectName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(t.description, color = textGray, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WhatIfSimulatorCard(
    grades: List<SubjectGrade>,
    selectedSubjectId: String?,
    onSelectSubject: (String) -> Unit,
    regularScores: List<Int>,
    krScores: List<Int>,
    scoreEntryMode: Int,
    onScoreEntryModeChange: (Int) -> Unit,
    onAddScore: (Int) -> Unit,
    onRemoveRegularScore: (Int) -> Unit,
    onRemoveKrScore: (Int) -> Unit,
    onReset: () -> Unit,
    simulationResult: SimulationResult?,
    surfaceColor: Color,
    primaryColor: Color,
    textGray: Color
) {
    if (grades.isEmpty()) return

    val currentSubject = grades.firstOrNull { it.id == selectedSubjectId } ?: grades.first()

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Calculate, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Калькулятор оценок ", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text("Примерьте оценки и посмотрите прогноз среднего балла", color = textGray, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(14.dp))

            // Горизонтальный список предметов
            Text("Выберите предмет для симуляции:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(grades, key = { it.id }) { g ->
                    val isSelected = g.id == currentSubject.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) primaryColor else Color.White.copy(alpha = 0.04f))
                            .border(1.dp, if (isSelected) primaryColor else Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .clickable { onSelectSubject(g.id) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = g.subjectName,
                            color = if (isSelected) Color.White else textGray,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Блок ввода виртуальных оценок
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Добавить виртуальную:", color = textGray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoundingChip("Обычную", scoreEntryMode == 0, primaryColor, textGray, Color.White.copy(alpha = 0.04f)) { onScoreEntryModeChange(0) }
                    RoundingChip("За КР", scoreEntryMode == 1, primaryColor, textGray, Color.White.copy(alpha = 0.04f)) { onScoreEntryModeChange(1) }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (10 downTo 6).forEach { score ->
                    GradeAddButton(score, surfaceColor) { onAddScore(score) }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (5 downTo 1).forEach { score ->
                    GradeAddButton(score, surfaceColor) { onAddScore(score) }
                }
            }

            // Добавленные виртуальные оценки
            if (regularScores.isNotEmpty() || krScores.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Виртуальные оценки (нажмите для удаления):", color = textGray, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(regularScores) { sc ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(getGradeVisual(sc.toDouble()).color.copy(alpha = 0.2f), CircleShape)
                                .border(1.dp, getGradeVisual(sc.toDouble()).color, CircleShape)
                                .clickable { onRemoveRegularScore(sc) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$sc", color = getGradeVisual(sc.toDouble()).color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    items(krScores) { sc ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFFFF9100).copy(alpha = 0.2f), CircleShape)
                                .border(1.dp, Color(0xFFFF9100), CircleShape)
                            .clickable { onRemoveKrScore(sc) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("К$sc", color = Color(0xFFFF9100), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Результаты симуляции
            if (simulationResult != null && (regularScores.isNotEmpty() || krScores.isNotEmpty())) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Прогноз результата:", color = primaryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        // Предмет
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Балл предмета (${currentSubject.subjectName}):", color = Color.White, fontSize = 12.sp)
                            val deltaSign = if (simulationResult.subjectDelta >= 0) "+" else ""
                            val deltaColor = if (simulationResult.subjectDelta >= 0) ColorGrade5 else ColorGrade2
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${simulationResult.originalSubjectAvg} ➔ ", color = textGray, fontSize = 12.sp)
                                Text("${simulationResult.simulatedSubjectAvg}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("($deltaSign${simulationResult.subjectDelta})", color = deltaColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(6.dp))

                        // Общий GPA
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Общий средний балл (GPA):", color = Color.White, fontSize = 12.sp)
                            val deltaSign = if (simulationResult.globalDelta >= 0) "+" else ""
                            val deltaColor = if (simulationResult.globalDelta >= 0) ColorGrade5 else ColorGrade2
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${simulationResult.originalGlobalAvg} ➔ ", color = textGray, fontSize = 12.sp)
                                Text("${simulationResult.simulatedGlobalAvg}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("($deltaSign${simulationResult.globalDelta})", color = deltaColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onReset) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = textGray, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Сбросить симуляцию", color = textGray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
