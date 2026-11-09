package com.example.pulse.widget

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import org.json.JSONArray
import java.time.LocalTime
import java.time.temporal.ChronoUnit

import com.example.pulse.viewmodel.LessonPair
import com.example.pulse.screen.MainActivity

private val TextWhite = ColorProvider(Color.White)
private val TextGray = ColorProvider(Color(0xFFAAAAAA))

data class WidgetScheduleData(
    val currentLesson: LessonPair?,
    val nextLesson: LessonPair?,
    val allLessonsToday: List<LessonPair>
)

suspend fun updateAllPulseWidgets(context: Context) {
    try {
        CurrentLessonWidget().updateAll(context)
        NextLessonWidget().updateAll(context)
        DayScheduleWidget().updateAll(context)
        PerformanceWidget().updateAll(context)
        QuickAccessWidget().updateAll(context)
    } catch (_: Exception) {}
}

@SuppressLint("NewApi")
fun loadWidgetData(context: Context): WidgetScheduleData {
    val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
    val jsonString = prefs.getString("widget_lessons_today", "[]") ?: "[]"

    val lessons = mutableListOf<LessonPair>()
    try {
        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            lessons.add(
                LessonPair(
                    pairNumber = obj.optInt("pairNumber", 0),
                    lessons = obj.optString("lessons", ""),
                    startTime = obj.optString("startTime", ""),
                    endTime = obj.optString("endTime", ""),
                    subject = obj.optString("subject", "").substringBefore(" (").trim(),
                    room = obj.optString("room", "—"),
                    teacher = obj.optString("teacher", "—")
                )
            )
        }
    } catch (e: Exception) { e.printStackTrace() }

    val sortedLessons = lessons.sortedBy { it.startLocalTime ?: LocalTime.MIN }
    val now = LocalTime.now()
    var current: LessonPair? = null
    var next: LessonPair? = null

    for (i in sortedLessons.indices) {
        val lesson = sortedLessons[i]
        val start = lesson.startLocalTime ?: continue
        val end = lesson.endLocalTime ?: continue
        if ((now.isAfter(start) || now == start) && now.isBefore(end)) {
            current = lesson
            next = sortedLessons.getOrNull(i + 1)
            break
        }
        if (now.isBefore(start) && next == null) {
            next = lesson
        }
    }
    return WidgetScheduleData(current, next, sortedLessons)
}

fun loadGlobalAverageScore(context: Context): Float {
    val prefs = context.getSharedPreferences("PulsePerformancePrefs", Context.MODE_PRIVATE)
    val jsonString = prefs.getString("semesters_data", "[]") ?: "[]"
    var sum = 0.0
    var count = 0

    try {
        val array = JSONArray(jsonString)
        if (array.length() > 0) {
            val lastSemester = array.getJSONObject(array.length() - 1)
            val gradesArray = lastSemester.getJSONArray("grades")

            for (i in 0 until gradesArray.length()) {
                val gObj = gradesArray.getJSONObject(i)

                val scores = gObj.getJSONArray("scores")
                var sSum = 0
                for (k in 0 until scores.length()) sSum += scores.getInt(k)
                val baseAvg = if (scores.length() > 0) sSum.toDouble() / scores.length() else 0.0

                val krScores = if (gObj.has("krScores")) gObj.getJSONArray("krScores") else JSONArray()
                var krSum = 0
                for (k in 0 until krScores.length()) krSum += krScores.getInt(k)
                val krAvg = if (krScores.length() > 0) krSum.toDouble() / krScores.length() else 0.0

                val effective = when {
                    baseAvg == 0.0 && krAvg == 0.0 -> 0.0
                    baseAvg == 0.0 -> krAvg
                    krAvg == 0.0 -> baseAvg
                    else -> (baseAvg + krAvg) / 2.0
                }

                if (effective > 0) {
                    sum += effective
                    count++
                }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }

    return if (count > 0) (Math.round((sum / count) * 100) / 100f) else 0f
}

class CurrentLessonWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val dynamicColorEnabled = prefs.getBoolean("dynamic_color_enabled", false)

        provideContent {
            PulseWidgetTheme(useDynamicColor = dynamicColorEnabled) {
                val lesson = data.currentLesson
                var progress = 0f
                var passedStr = "0 мин"
                var leftStr = "0 мин"

                if (lesson != null) {
                    val start = lesson.startLocalTime
                    val end = lesson.endLocalTime
                    if (start != null && end != null) {
                        val now = LocalTime.now()
                        val totalMins = ChronoUnit.MINUTES.between(start, end).toFloat().coerceAtLeast(1f)
                        val passedMins = ChronoUnit.MINUTES.between(start, now).toFloat().coerceIn(0f, totalMins)

                        progress = passedMins / totalMins
                        passedStr = "${passedMins.toInt()} мин"
                        leftStr = "${(totalMins - passedMins).toInt()} мин"
                    }
                }

                Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).clickable(actionStartActivity<MainActivity>())) {
                    Image(
                        provider = ImageProvider(WidgetWaveGenerator.createWaveBitmap(progress)),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    Column(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
                        if (lesson != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = GlanceModifier.background(GlanceTheme.colors.primaryContainer).cornerRadius(8.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    Text("${lesson.pairNumber} пара", style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                                }
                            }
                            Spacer(modifier = GlanceModifier.height(12.dp))
                            Text(lesson.subject, style = TextStyle(color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                            Spacer(modifier = GlanceModifier.height(4.dp))
                            Text("${lesson.startTime} - ${lesson.endTime}", style = TextStyle(color = TextGray, fontSize = 14.sp))

                            Spacer(modifier = GlanceModifier.defaultWeight())

                            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                Column(modifier = GlanceModifier.defaultWeight()) {
                                    Text("Прошло", style = TextStyle(color = TextGray, fontSize = 12.sp))
                                    Text(passedStr, style = TextStyle(color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                                }
                                Text("${(progress * 100).toInt()}%", style = TextStyle(color = TextWhite, fontSize = 32.sp, fontWeight = FontWeight.Bold))
                                Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.End) {
                                    Text("Осталось", style = TextStyle(color = TextGray, fontSize = 12.sp))
                                    Text(leftStr, style = TextStyle(color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                                }
                            }
                        } else {
                            Text("ПАР НЕТ", style = TextStyle(color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                            Text("Можно отдыхать", style = TextStyle(color = TextGray, fontSize = 14.sp))
                        }
                    }
                }
            }
        }
    }
}
class CurrentLessonWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = CurrentLessonWidget() }

class NextLessonWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val dynamicColorEnabled = prefs.getBoolean("dynamic_color_enabled", false)

        provideContent {
            PulseWidgetTheme(useDynamicColor = dynamicColorEnabled) {
                val lesson = data.nextLesson

                Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).clickable(actionStartActivity<MainActivity>())) {
                    Image(
                        provider = ImageProvider(WidgetWaveGenerator.createWaveBitmap(0.2f)),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    Column(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
                        Text("Следующая пара", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                        Spacer(modifier = GlanceModifier.height(8.dp))

                        if (lesson != null) {
                            Text(lesson.subject, style = TextStyle(color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                            Spacer(modifier = GlanceModifier.defaultWeight())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(lesson.startTime, style = TextStyle(color = TextGray, fontSize = 14.sp))
                                Spacer(modifier = GlanceModifier.width(16.dp))
                                Text("Каб. ${lesson.room}", style = TextStyle(color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                            }
                        } else {
                            Text("Занятий больше нет", style = TextStyle(color = TextWhite, fontSize = 16.sp))
                        }
                    }
                }
            }
        }
    }
}
class NextLessonWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = NextLessonWidget() }

class DayScheduleWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val dynamicColorEnabled = prefs.getBoolean("dynamic_color_enabled", false)

        provideContent {
            PulseWidgetTheme(useDynamicColor = dynamicColorEnabled) {
                Box(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(24.dp).padding(16.dp)) {
                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        Text("Сегодня", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        Spacer(modifier = GlanceModifier.height(12.dp))

                        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                            items(data.allLessonsToday) { lesson ->
                                val isActive = lesson.pairNumber == data.currentLesson?.pairNumber

                                Row(
                                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)
                                        .background(if (isActive) GlanceTheme.colors.primaryContainer else ColorProvider(Color.Transparent))
                                        .cornerRadius(12.dp).padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = GlanceModifier.size(24.dp).background(if (isActive) GlanceTheme.colors.primary else GlanceTheme.colors.surfaceVariant).cornerRadius(12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(lesson.pairNumber.toString(), style = TextStyle(color = if (isActive) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                                    }
                                    Spacer(modifier = GlanceModifier.width(12.dp))
                                    Column {
                                        Text(lesson.subject, style = TextStyle(color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                                        Text("${lesson.startTime} - ${lesson.endTime}", style = TextStyle(color = TextGray, fontSize = 12.sp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
class DayScheduleWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = DayScheduleWidget() }

class PerformanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val averageScore = loadGlobalAverageScore(context)
        val progress = (averageScore / 10f).coerceIn(0f, 1f)
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val dynamicColorEnabled = prefs.getBoolean("dynamic_color_enabled", false)

        provideContent {
            PulseWidgetTheme(useDynamicColor = dynamicColorEnabled) {
                Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).clickable(actionStartActivity<MainActivity>())) {
                    Image(
                        provider = ImageProvider(WidgetWaveGenerator.createWaveBitmap(progress)),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    Column(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
                        Text("Средний балл", style = TextStyle(color = TextGray, fontSize = 12.sp))
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Text(
                            text = if (averageScore > 0) averageScore.toString() else "—",
                            style = TextStyle(color = TextWhite, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}
class PerformanceWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = PerformanceWidget() }

class QuickAccessWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val dynamicColorEnabled = prefs.getBoolean("dynamic_color_enabled", false)

        provideContent {
            PulseWidgetTheme(useDynamicColor = dynamicColorEnabled) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(24.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    QuickActionItem("Расписание", "Р")
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    QuickActionItem("Оценки", "О")
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    QuickActionItem("Задания", "З")
                    Spacer(modifier = GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun QuickActionItem(title: String, initial: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>())
    ) {
        Box(
            modifier = GlanceModifier
                .size(56.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(
            text = title,
            style = TextStyle(
                color = TextWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
class QuickAccessWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = QuickAccessWidget() }