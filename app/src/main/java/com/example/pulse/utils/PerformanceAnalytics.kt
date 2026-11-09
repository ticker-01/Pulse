package com.example.pulse.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.pulse.screen.formatScoreByMode
import com.example.pulse.viewmodel.SubjectGrade
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.sqrt

// Цветовые константы оценок
private val ColorExcellent = Color(0xFF4CAF50)
private val ColorGood = Color(0xFF2196F3)
private val ColorSatisfactory = Color(0xFFFFC107)
private val ColorFail = Color(0xFFF44336)
private val ColorNeutral = Color(0xFF9090A0)

data class ScholarshipStatus(
    val tier: String,            // "9+", "8+", "7+", "6+", "< 6", "Долг", "—"
    val title: String,           // "Самая высокая стипендия", "Повышенная стипендия", "Базовая стипендия", "Минимальная стипендия", "Без стипендии"
    val badgeText: String,       // "9+", "8+", "7+", "6+", "< 6", "Долг"
    val description: String,
    val currentGpa: Double,
    val targetGpa: Double?,
    val gpaNeeded: Double?,
    val progress: Float,
    val color: Color,
    val icon: ImageVector,
    val hasDebts: Boolean,
    val isFivePointScale: Boolean
)

data class AcademicStatus(
    val title: String,
    val badgeText: String,
    val description: String,
    val color: Color,
    val icon: ImageVector,
    val advice: String
)

data class GradeDistribution(
    val countsByScore: Map<Int, Int>, // от 1 до 10
    val excellentCount: Int,          // 9, 10
    val goodCount: Int,               // 6, 7, 8
    val satisfactoryCount: Int,       // 4, 5
    val failCount: Int,               // 1, 2, 3
    val totalCount: Int,
    val regularCount: Int,
    val krCount: Int,
    val qualityOfKnowledgePercent: Double, // КЗ: (9-10 + 6-8) / total * 100
    val absolutePassPercent: Double        // Успеваемость: (total - fail) / total * 100
)

data class SubjectImpact(
    val subject: SubjectGrade,
    val effectiveAverage: Double,
    val deltaFromGlobalAvg: Double,
    val estimatedGpaLossOrGain: Double // Оценка влияния на общий средний балл
)

enum class RiskType {
    FAIL_DEBT,        // Средний балл < 4.0 — академзадолженность
    BORDERLINE_FAIL,  // Средний балл 4.0..4.3 — на грани срыва
    BORDERLINE_GOOD,  // Средний балл 6.0..6.4 — чуть-чуть до хорошиста
    BORDERLINE_EXC,   // Средний балл 8.0..8.4 — чуть-чуть до отличника
    NO_GRADES,        // Нет оценок вообще — угроза в конце семестра
    LOW_KR            // КР значительно ниже текущих оценок
}

data class SubjectRisk(
    val subject: SubjectGrade,
    val riskType: RiskType,
    val severity: Int, // 1 - Критично, 2 - Внимание, 3 - Возможность
    val title: String,
    val description: String,
    val color: Color,
    val icon: ImageVector
)

enum class TrendDirection {
    UP, DOWN, STABLE, NOT_ENOUGH_DATA
}

data class SubjectTrend(
    val subject: SubjectGrade,
    val direction: TrendDirection,
    val firstHalfAvg: Double,
    val secondHalfAvg: Double,
    val description: String
)

data class DiagnosticsSummary(
    val overallKrAvg: Double,
    val overallRegularAvg: Double,
    val krImpactDescription: String,
    val krColor: Color,
    val stabilityTitle: String,
    val stabilityDescription: String,
    val stabilityColor: Color,
    val outlierNote: String?
)

data class ActionRecommendation(
    val priority: Int,
    val badge: String,
    val title: String,
    val subjectName: String,
    val actionText: String,
    val potentialGpaDelta: Double?,
    val color: Color,
    val icon: ImageVector
)

data class SimulationResult(
    val originalSubjectAvg: Double,
    val simulatedSubjectAvg: Double,
    val subjectDelta: Double,
    val originalGlobalAvg: Double,
    val simulatedGlobalAvg: Double,
    val globalDelta: Double
)

data class DeepPerformanceReport(
    val scholarshipStatus: ScholarshipStatus,
    val academicStatus: AcademicStatus,
    val distribution: GradeDistribution,
    val drivers: List<SubjectImpact>,
    val anchors: List<SubjectImpact>,
    val risks: List<SubjectRisk> = emptyList(),
    val trends: List<SubjectTrend>,
    val diagnostics: DiagnosticsSummary,
    val recommendations: List<ActionRecommendation> = emptyList()
)

object PerformanceAnalytics {

    fun buildDeepReport(
        grades: List<SubjectGrade>,
        roundingMode: Int
    ): DeepPerformanceReport {
        val activeGrades = grades.filter { it.effectiveAverage > 0.0 }
        val globalAverage = if (activeGrades.isNotEmpty()) {
            activeGrades.map { formatScoreByMode(it.effectiveAverage, roundingMode) }.average()
        } else 0.0

        val distribution = calculateDistribution(grades)
        val scholarship = calculateScholarshipStatus(globalAverage, grades)
        val status = calculateAcademicStatus(scholarship)
        val (drivers, anchors) = calculateDriversAndAnchors(activeGrades, globalAverage)
        val risks = emptyList<SubjectRisk>()
        val trends = calculateTrends(activeGrades)
        val diagnostics = calculateDiagnostics(grades, globalAverage)
        val recommendations = emptyList<ActionRecommendation>()

        return DeepPerformanceReport(
            scholarshipStatus = scholarship,
            academicStatus = status,
            distribution = distribution,
            drivers = drivers,
            anchors = anchors,
            risks = risks,
            trends = trends,
            diagnostics = diagnostics,
            recommendations = recommendations
        )
    }

    private fun calculateDistribution(grades: List<SubjectGrade>): GradeDistribution {
        val counts = mutableMapOf<Int, Int>()
        for (i in 1..10) counts[i] = 0

        var regularCount = 0
        var krCount = 0

        grades.forEach { grade ->
            grade.scores.forEach { score ->
                if (score in 1..10) {
                    counts[score] = (counts[score] ?: 0) + 1
                    regularCount++
                }
            }
            grade.krScores.forEach { kr ->
                if (kr in 1..10) {
                    counts[kr] = (counts[kr] ?: 0) + 1
                    krCount++
                }
            }
        }

        val total = regularCount + krCount
        val excellent = (counts[9] ?: 0) + (counts[10] ?: 0)
        val good = (counts[6] ?: 0) + (counts[7] ?: 0) + (counts[8] ?: 0)
        val satisfactory = (counts[4] ?: 0) + (counts[5] ?: 0)
        val fail = (counts[1] ?: 0) + (counts[2] ?: 0) + (counts[3] ?: 0)

        val quality = if (total > 0) ((excellent + good).toDouble() / total) * 100.0 else 0.0
        val pass = if (total > 0) ((total - fail).toDouble() / total) * 100.0 else 0.0

        return GradeDistribution(
            countsByScore = counts,
            excellentCount = excellent,
            goodCount = good,
            satisfactoryCount = satisfactory,
            failCount = fail,
            totalCount = total,
            regularCount = regularCount,
            krCount = krCount,
            qualityOfKnowledgePercent = round(quality * 10) / 10.0,
            absolutePassPercent = round(pass * 10) / 10.0
        )
    }

    fun calculateScholarshipStatus(
        globalAvg: Double,
        grades: List<SubjectGrade>
    ): ScholarshipStatus {
        val activeGrades = grades.filter { it.effectiveAverage > 0.0 }
        if (grades.isEmpty() || activeGrades.isEmpty()) {
            return ScholarshipStatus(
                tier = "—",
                title = "Стипендия",
                badgeText = "Нет оценок",
                description = "Добавьте первые оценки по предметам для расчёта категории стипендии.",
                currentGpa = 0.0,
                targetGpa = 6.0,
                gpaNeeded = null,
                progress = 0f,
                color = ColorNeutral,
                icon = Icons.Outlined.School,
                hasDebts = false,
                isFivePointScale = false
            )
        }

        val allScores = grades.flatMap { it.scores + it.krScores }
        val maxScore = allScores.maxOrNull() ?: 10
        val isFivePointScale = maxScore <= 5 && globalAvg <= 5.0

        val minPassThreshold = if (isFivePointScale) 3.0 else 4.0
        val hasDebts = activeGrades.any { it.effectiveAverage < minPassThreshold }

        if (hasDebts) {
            val debtCount = activeGrades.count { it.effectiveAverage < minPassThreshold }
            return ScholarshipStatus(
                tier = "Долг",
                title = "Без стипендии",
                badgeText = "Долг",
                description = "Есть академическая задолженность ($debtCount предм.). Закройте долги для начисления стипендии.",
                currentGpa = round(globalAvg * 100) / 100.0,
                targetGpa = if (isFivePointScale) 3.5 else 6.0,
                gpaNeeded = null,
                progress = 0f,
                color = ColorFail,
                icon = Icons.Default.WarningAmber,
                hasDebts = true,
                isFivePointScale = isFivePointScale
            )
        }

        if (!isFivePointScale) {
            // 10-балльная шкала: 9+, 8+, 7+, 6+
            val roundedGpa = round(globalAvg * 100) / 100.0
            return when {
                roundedGpa >= 9.0 -> {
                    ScholarshipStatus(
                        tier = "9+",
                        title = "Самая высокая стипендия",
                        badgeText = "9+",
                        description = "Максимальный коэффициент стипендии! Отличная успеваемость.",
                        currentGpa = roundedGpa,
                        targetGpa = null,
                        gpaNeeded = 0.0,
                        progress = 1f,
                        color = ColorExcellent,
                        icon = Icons.Default.EmojiEvents,
                        hasDebts = false,
                        isFivePointScale = false
                    )
                }
                roundedGpa >= 8.0 -> {
                    val needed = round((9.0 - roundedGpa) * 100) / 100.0
                    val prog = ((roundedGpa - 8.0) / 1.0).toFloat().coerceIn(0f, 1f)
                    ScholarshipStatus(
                        tier = "8+",
                        title = "Повышенная стипендия",
                        badgeText = "8+",
                        description = "До самой высокой стипендии (9+) осталось поднять GPA на $needed.",
                        currentGpa = roundedGpa,
                        targetGpa = 9.0,
                        gpaNeeded = needed,
                        progress = prog,
                        color = ColorGood,
                        icon = Icons.Default.Star,
                        hasDebts = false,
                        isFivePointScale = false
                    )
                }
                roundedGpa >= 7.0 -> {
                    val needed = round((8.0 - roundedGpa) * 100) / 100.0
                    val prog = ((roundedGpa - 7.0) / 1.0).toFloat().coerceIn(0f, 1f)
                    ScholarshipStatus(
                        tier = "7+",
                        title = "Базовая стипендия",
                        badgeText = "7+",
                        description = "До повышенной стипендии (8+) осталось поднять GPA на $needed.",
                        currentGpa = roundedGpa,
                        targetGpa = 8.0,
                        gpaNeeded = needed,
                        progress = prog,
                        color = ColorSatisfactory,
                        icon = Icons.Default.School,
                        hasDebts = false,
                        isFivePointScale = false
                    )
                }
                roundedGpa >= 6.0 -> {
                    val needed = round((7.0 - roundedGpa) * 100) / 100.0
                    val prog = ((roundedGpa - 6.0) / 1.0).toFloat().coerceIn(0f, 1f)
                    ScholarshipStatus(
                        tier = "6+",
                        title = "Минимальная стипендия",
                        badgeText = "6+",
                        description = "До базовой стипендии (7+) осталось поднять GPA на $needed.",
                        currentGpa = roundedGpa,
                        targetGpa = 7.0,
                        gpaNeeded = needed,
                        progress = prog,
                        color = Color(0xFFFF9800),
                        icon = Icons.Default.TrendingUp,
                        hasDebts = false,
                        isFivePointScale = false
                    )
                }
                else -> {
                    val needed = round((6.0 - roundedGpa) * 100) / 100.0
                    val prog = (roundedGpa / 6.0).toFloat().coerceIn(0f, 1f)
                    ScholarshipStatus(
                        tier = "< 6",
                        title = "Без стипендии",
                        badgeText = "< 6",
                        description = "Для начисления стипендии (6+) поднимите GPA на $needed.",
                        currentGpa = roundedGpa,
                        targetGpa = 6.0,
                        gpaNeeded = needed,
                        progress = prog,
                        color = ColorFail,
                        icon = Icons.Default.Info,
                        hasDebts = false,
                        isFivePointScale = false
                    )
                }
            }
        } else {
            // 5-балльная шкала: 4.8+ (9+), 4.5+ (8+), 4.0+ (7+), 3.5+ (6+)
            val roundedGpa = round(globalAvg * 100) / 100.0
            return when {
                roundedGpa >= 4.8 -> {
                    ScholarshipStatus(
                        tier = "9+",
                        title = "Самая высокая стипендия",
                        badgeText = "Отличник",
                        description = "Максимальный коэффициент стипендии! Отличная успеваемость.",
                        currentGpa = roundedGpa,
                        targetGpa = null,
                        gpaNeeded = 0.0,
                        progress = 1f,
                        color = ColorExcellent,
                        icon = Icons.Default.EmojiEvents,
                        hasDebts = false,
                        isFivePointScale = true
                    )
                }
                roundedGpa >= 4.5 -> {
                    val needed = round((4.8 - roundedGpa) * 100) / 100.0
                    val prog = ((roundedGpa - 4.5) / 0.3).toFloat().coerceIn(0f, 1f)
                    ScholarshipStatus(
                        tier = "8+",
                        title = "Повышенная стипендия",
                        badgeText = "4.5+",
                        description = "До максимальной стипендии осталось поднять GPA на $needed.",
                        currentGpa = roundedGpa,
                        targetGpa = 4.8,
                        gpaNeeded = needed,
                        progress = prog,
                        color = ColorGood,
                        icon = Icons.Default.Star,
                        hasDebts = false,
                        isFivePointScale = true
                    )
                }
                roundedGpa >= 4.0 -> {
                    val needed = round((4.5 - roundedGpa) * 100) / 100.0
                    val prog = ((roundedGpa - 4.0) / 0.5).toFloat().coerceIn(0f, 1f)
                    ScholarshipStatus(
                        tier = "7+",
                        title = "Базовая стипендия",
                        badgeText = "4.0+",
                        description = "До повышенной стипендии осталось поднять GPA на $needed.",
                        currentGpa = roundedGpa,
                        targetGpa = 4.5,
                        gpaNeeded = needed,
                        progress = prog,
                        color = ColorSatisfactory,
                        icon = Icons.Default.School,
                        hasDebts = false,
                        isFivePointScale = true
                    )
                }
                roundedGpa >= 3.5 -> {
                    val needed = round((4.0 - roundedGpa) * 100) / 100.0
                    val prog = ((roundedGpa - 3.5) / 0.5).toFloat().coerceIn(0f, 1f)
                    ScholarshipStatus(
                        tier = "6+",
                        title = "Минимальная стипендия",
                        badgeText = "3.5+",
                        description = "До базовой стипендии осталось поднять GPA на $needed.",
                        currentGpa = roundedGpa,
                        targetGpa = 4.0,
                        gpaNeeded = needed,
                        progress = prog,
                        color = Color(0xFFFF9800),
                        icon = Icons.Default.TrendingUp,
                        hasDebts = false,
                        isFivePointScale = true
                    )
                }
                else -> {
                    val needed = round((3.5 - roundedGpa) * 100) / 100.0
                    val prog = (roundedGpa / 3.5).toFloat().coerceIn(0f, 1f)
                    ScholarshipStatus(
                        tier = "< 6",
                        title = "Без стипендии",
                        badgeText = "< 3.5",
                        description = "Для начисления стипендии поднимите GPA на $needed.",
                        currentGpa = roundedGpa,
                        targetGpa = 3.5,
                        gpaNeeded = needed,
                        progress = prog,
                        color = ColorFail,
                        icon = Icons.Default.Info,
                        hasDebts = false,
                        isFivePointScale = true
                    )
                }
            }
        }
    }

    private fun calculateAcademicStatus(
        scholarship: ScholarshipStatus
    ): AcademicStatus {
        return AcademicStatus(
            title = scholarship.title,
            badgeText = scholarship.badgeText,
            description = scholarship.description,
            color = scholarship.color,
            icon = scholarship.icon,
            advice = if (scholarship.gpaNeeded != null && scholarship.gpaNeeded > 0)
                "Поднимите GPA на ${scholarship.gpaNeeded} для перехода на следующий уровень."
            else "У вас наивысшая категория стипендии!"
        )
    }

    private fun calculateDriversAndAnchors(
        activeGrades: List<SubjectGrade>,
        globalAvg: Double
    ): Pair<List<SubjectImpact>, List<SubjectImpact>> {
        if (activeGrades.isEmpty()) return Pair(emptyList(), emptyList())

        val count = activeGrades.size
        val impacts = activeGrades.map { grade ->
            val delta = grade.effectiveAverage - globalAvg
            // Оценка влияния: насколько балл этого предмета сдвигает общий GPA вверх/вниз
            val gpaDelta = delta / count
            SubjectImpact(
                subject = grade,
                effectiveAverage = round(grade.effectiveAverage * 100) / 100.0,
                deltaFromGlobalAvg = round(delta * 100) / 100.0,
                estimatedGpaLossOrGain = round(abs(gpaDelta) * 100) / 100.0
            )
        }

        val drivers = impacts.filter { it.deltaFromGlobalAvg > 0 }
            .sortedByDescending { it.deltaFromGlobalAvg }
            .take(3)

        val anchors = impacts.filter { it.deltaFromGlobalAvg < 0 }
            .sortedBy { it.deltaFromGlobalAvg }
            .take(3)

        return Pair(drivers, anchors)
    }

    private fun detectRisks(grades: List<SubjectGrade>): List<SubjectRisk> {
        val risks = mutableListOf<SubjectRisk>()

        grades.forEach { grade ->
            val avg = grade.effectiveAverage
            when {
                // Долг
                avg in 0.01..3.99 -> {
                    risks.add(
                        SubjectRisk(
                            subject = grade,
                            riskType = RiskType.FAIL_DEBT,
                            severity = 1,
                            title = "Угроза неаттестации (${round(avg * 10) / 10.0})",
                            description = "Средний балл ниже порога 4.0. Предмет считается несданным.",
                            color = ColorFail,
                            icon = Icons.Default.Cancel
                        )
                    )
                }

                // Пограничный риск не сдать
                avg in 4.0..4.3 -> {
                    risks.add(
                        SubjectRisk(
                            subject = grade,
                            riskType = RiskType.BORDERLINE_FAIL,
                            severity = 2,
                            title = "На грани срыва (${round(avg * 10) / 10.0})",
                            description = "Любая новая тройка или двойка опустит предмет в долги.",
                            color = ColorSatisfactory,
                            icon = Icons.Default.ReportProblem
                        )
                    )
                }

                // Пограничный к хорошисту
                avg in 6.0..6.4 -> {
                    risks.add(
                        SubjectRisk(
                            subject = grade,
                            riskType = RiskType.BORDERLINE_GOOD,
                            severity = 3,
                            title = "Близко к «Хорошо» (${round(avg * 10) / 10.0})",
                            description = "Не хватает всего 0.1–0.4 балла для перехода на 7+ баллов.",
                            color = ColorGood,
                            icon = Icons.Default.TrendingUp
                        )
                    )
                }

                // Пустой предмет
                avg == 0.0 -> {
                    risks.add(
                        SubjectRisk(
                            subject = grade,
                            riskType = RiskType.NO_GRADES,
                            severity = 2,
                            title = "Нет оценок",
                            description = "По предмету нет текущих отметок — риск накопления долгов к концу семестра.",
                            color = ColorNeutral,
                            icon = Icons.Outlined.HelpOutline
                        )
                    )
                }
            }

            // Проваленные КР
            if (grade.krAverage > 0 && grade.average > 0 && (grade.average - grade.krAverage) >= 1.5) {
                risks.add(
                    SubjectRisk(
                        subject = grade,
                        riskType = RiskType.LOW_KR,
                        severity = 2,
                        title = "Провал КР (${round(grade.krAverage * 10) / 10.0})",
                        description = "Балл за контрольные существенно ниже текущих (${round(grade.average * 10) / 10.0}). КР тянет вниз.",
                        color = Color(0xFFFF9100),
                        icon = Icons.Outlined.AssignmentLate
                    )
                )
            }
        }

        return risks.sortedBy { it.severity }
    }

    private fun calculateTrends(activeGrades: List<SubjectGrade>): List<SubjectTrend> {
        val trends = mutableListOf<SubjectTrend>()

        activeGrades.forEach { grade ->
            val allScores = grade.scores
            if (allScores.size >= 4) {
                val half = allScores.size / 2
                val firstHalf = allScores.take(half).average()
                val secondHalf = allScores.drop(half).average()
                val diff = secondHalf - firstHalf

                val dir = when {
                    diff >= 0.5 -> TrendDirection.UP
                    diff <= -0.5 -> TrendDirection.DOWN
                    else -> TrendDirection.STABLE
                }

                val desc = when (dir) {
                    TrendDirection.UP -> "Оценки растут: с ${round(firstHalf * 10) / 10.0} до ${round(secondHalf * 10) / 10.0} 📈"
                    TrendDirection.DOWN -> "Спад результатов: с ${round(firstHalf * 10) / 10.0} до ${round(secondHalf * 10) / 10.0} 📉"
                    TrendDirection.STABLE -> "Стабильный уровень (${round(secondHalf * 10) / 10.0}) ➡️"
                    TrendDirection.NOT_ENOUGH_DATA -> "Мало данных"
                }

                trends.add(
                    SubjectTrend(
                        subject = grade,
                        direction = dir,
                        firstHalfAvg = round(firstHalf * 10) / 10.0,
                        secondHalfAvg = round(secondHalf * 10) / 10.0,
                        description = desc
                    )
                )
            }
        }

        return trends.sortedBy {
            when (it.direction) {
                TrendDirection.DOWN -> 0 // Сначала тревожные спады
                TrendDirection.UP -> 1
                TrendDirection.STABLE -> 2
                TrendDirection.NOT_ENOUGH_DATA -> 3
            }
        }
    }

    private fun calculateDiagnostics(
        grades: List<SubjectGrade>,
        globalAvg: Double
    ): DiagnosticsSummary {
        // Контрольные работы vs Текущие
        val gradesWithKr = grades.filter { it.krScores.isNotEmpty() }
        val allKrScores = gradesWithKr.flatMap { it.krScores }
        val allRegularScores = grades.flatMap { it.scores }

        val krAvg = if (allKrScores.isNotEmpty()) allKrScores.average() else 0.0
        val regAvg = if (allRegularScores.isNotEmpty()) allRegularScores.average() else 0.0

        val (krDesc, krColor) = when {
            allKrScores.isEmpty() -> Pair("Контрольные работы пока не выставлялись", ColorNeutral)
            krAvg < regAvg - 0.7 -> Pair(
                "КР заметно снижают общий балл (${round(krAvg * 10) / 10.0} против ${round(regAvg * 10) / 10.0} за устные ответы). Уделите внимание подготовке к письменным срезам!",
                ColorFail
            )
            krAvg > regAvg + 0.5 -> Pair(
                "КР вытягивают средний балл (${round(krAvg * 10) / 10.0} против ${round(regAvg * 10) / 10.0}). Письменные работы даются лучше ответов на парах!",
                ColorExcellent
            )
            else -> Pair(
                "Оценки за КР (${round(krAvg * 10) / 10.0}) и текущие (${round(regAvg * 10) / 10.0}) сбалансированы.",
                ColorGood
            )
        }

        // Стабильность (Дисперсия / Волатильность)
        val allCombined = allRegularScores + allKrScores
        val (stabTitle, stabDesc, stabColor) = if (allCombined.size >= 4) {
            val mean = allCombined.average()
            val variance = allCombined.sumOf { (it - mean) * (it - mean) } / allCombined.size
            val stdDev = sqrt(variance)

            when {
                stdDev <= 1.1 -> Triple(
                    "Высокая стабильность",
                    "Разброс оценок минимален (±${round(stdDev * 10) / 10.0}). Вы учитесь предсказуемо и ровно.",
                    ColorExcellent
                )
                stdDev <= 1.8 -> Triple(
                    "Умеренный разброс",
                    "Оценки немного колеблются (±${round(stdDev * 10) / 10.0}), что естественно для учебного процесса.",
                    ColorGood
                )
                else -> Triple(
                    "Высокая волатильность",
                    "Оценки сильно скачут (±${round(stdDev * 10) / 10.0}): от двоек до девяток. Нет предсказуемости.",
                    ColorSatisfactory
                )
            }
        } else {
            Triple("Недостаточно данных", "Для оценки стабильности нужно больше оценок.", ColorNeutral)
        }

        // Выбросы (случайные двойки у сильных предметов)
        val outlierSubjects = grades.filter { grade ->
            grade.effectiveAverage >= 6.5 && grade.scores.any { it <= 3 }
        }

        val outlierNote = if (outlierSubjects.isNotEmpty()) {
            val names = outlierSubjects.take(2).joinToString(", ") { it.subjectName }
            "Разовые двойки тянут вниз сильные предметы ($names). Исправление одной оценки заметно поднимет средний балл."
        } else null

        return DiagnosticsSummary(
            overallKrAvg = round(krAvg * 100) / 100.0,
            overallRegularAvg = round(regAvg * 100) / 100.0,
            krImpactDescription = krDesc,
            krColor = krColor,
            stabilityTitle = stabTitle,
            stabilityDescription = stabDesc,
            stabilityColor = stabColor,
            outlierNote = outlierNote
        )
    }

    private fun generateRecommendations(
        grades: List<SubjectGrade>,
        globalAvg: Double,
        risks: List<SubjectRisk>,
        roundingMode: Int
    ): List<ActionRecommendation> {
        val list = mutableListOf<ActionRecommendation>()
        var priorityCounter = 1

        // 1. Ликвидация академических долгов
        val debts = risks.filter { it.riskType == RiskType.FAIL_DEBT }
        debts.forEach { debt ->
            list.add(
                ActionRecommendation(
                    priority = priorityCounter++,
                    badge = "Срочно",
                    title = "Закрыть долг по ${debt.subject.subjectName}",
                    subjectName = debt.subject.subjectName,
                    actionText = "Получите оценку не ниже 6, чтобы выйти из зоны риска неаттестации.",
                    potentialGpaDelta = 0.35,
                    color = ColorFail,
                    icon = Icons.Default.PriorityHigh
                )
            )
        }

        // 2. Исправление проваленной КР
        val krRisks = risks.filter { it.riskType == RiskType.LOW_KR }
        krRisks.take(2).forEach { r ->
            list.add(
                ActionRecommendation(
                    priority = priorityCounter++,
                    badge = "Контрольная",
                    title = "Пересдать КР: ${r.subject.subjectName}",
                    subjectName = r.subject.subjectName,
                    actionText = "Балл за КР составляет ${r.subject.krAverage}. Пересдача на 8 даст мощный буст баллу предмета.",
                    potentialGpaDelta = 0.25,
                    color = Color(0xFFFF9100),
                    icon = Icons.Outlined.AssignmentTurnedIn
                )
            )
        }

        // 3. Быстрый апгрейд пограничных предметов (до Хорошо)
        val borderlineGood = risks.filter { it.riskType == RiskType.BORDERLINE_GOOD }
        borderlineGood.take(2).forEach { b ->
            val needed = ceil((6.5 * (b.subject.scores.size + 1)) - b.subject.scores.sum()).toInt().coerceIn(7, 10)
            list.add(
                ActionRecommendation(
                    priority = priorityCounter++,
                    badge = "Быстрый рост",
                    title = "Дожать до «Хорошо»: ${b.subject.subjectName}",
                    subjectName = b.subject.subjectName,
                    actionText = "Текущий балл ${round(b.subject.effectiveAverage * 10) / 10.0}. Достаточно получить одну «$needed», чтобы зафиксировать статус.",
                    potentialGpaDelta = 0.15,
                    color = ColorGood,
                    icon = Icons.Default.TrendingUp
                )
            )
        }

        // 4. Пустые предметы
        val emptySubjects = risks.filter { it.riskType == RiskType.NO_GRADES }
        if (emptySubjects.isNotEmpty()) {
            val emptyNames = emptySubjects.take(2).joinToString(", ") { it.subject.subjectName }
            list.add(
                ActionRecommendation(
                    priority = priorityCounter++,
                    badge = "Профилактика",
                    title = "Получить первые оценки: $emptyNames",
                    subjectName = emptyNames,
                    actionText = "Не откладывайте на конец семестра, чтобы не образовался завал.",
                    potentialGpaDelta = null,
                    color = ColorNeutral,
                    icon = Icons.Outlined.Schedule
                )
            )
        }

        return list.take(4)
    }

    fun simulateGrades(
        originalGrades: List<SubjectGrade>,
        targetSubjectId: String,
        addedRegularScores: List<Int>,
        addedKrScores: List<Int>,
        roundingMode: Int
    ): SimulationResult {
        val targetSubject = originalGrades.firstOrNull { it.id == targetSubjectId }
            ?: return SimulationResult(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        val origSubjAvg = targetSubject.effectiveAverage

        val newScores = targetSubject.scores + addedRegularScores
        val newKrScores = targetSubject.krScores + addedKrScores

        val newRegularAvg = if (newScores.isNotEmpty()) newScores.average() else 0.0
        val newKrAvg = if (newKrScores.isNotEmpty()) newKrScores.average() else 0.0

        val newSubjAvg = when {
            newRegularAvg == 0.0 && newKrAvg == 0.0 -> 0.0
            newRegularAvg == 0.0 -> newKrAvg
            newKrAvg == 0.0 -> newRegularAvg
            else -> (newRegularAvg + newKrAvg) / 2.0
        }

        val origActive = originalGrades.filter { it.effectiveAverage > 0.0 }
        val origGlobal = if (origActive.isNotEmpty()) {
            origActive.map { formatScoreByMode(it.effectiveAverage, roundingMode) }.average()
        } else 0.0

        val simulatedGrades = originalGrades.map { g ->
            if (g.id == targetSubjectId) {
                g.copy(scores = newScores, krScores = newKrScores)
            } else g
        }

        val simActive = simulatedGrades.filter { it.effectiveAverage > 0.0 }
        val simGlobal = if (simActive.isNotEmpty()) {
            simActive.map { formatScoreByMode(it.effectiveAverage, roundingMode) }.average()
        } else 0.0

        return SimulationResult(
            originalSubjectAvg = round(origSubjAvg * 100) / 100.0,
            simulatedSubjectAvg = round(newSubjAvg * 100) / 100.0,
            subjectDelta = round((newSubjAvg - origSubjAvg) * 100) / 100.0,
            originalGlobalAvg = round(origGlobal * 100) / 100.0,
            simulatedGlobalAvg = round(simGlobal * 100) / 100.0,
            globalDelta = round((simGlobal - origGlobal) * 100) / 100.0
        )
    }
}
