package com.example.pulse.utils

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.pulse.data.HomeworkItem
import com.example.pulse.screen.BgDark
import com.example.pulse.screen.PrimaryPurple
import com.example.pulse.screen.SurfaceDark
import com.example.pulse.screen.TextGray
import com.example.pulse.viewmodel.LessonPair
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.sin

data class StoryStepItem(
    val number: Int,
    val title: String,
    val description: String,
    val icon: ImageVector
)

data class StoryPage(
    val title: String,
    val subtitle: String,
    val mainHeadline: String,
    val description: String,
    val sectionTitle: String,
    val steps: List<StoryStepItem>,
    val footerTip: String,
    val nextStoryTitle: String? = null
)

data class StoryGroup(
    val id: String,
    val title: String,
    val isViewed: Boolean,
    val pages: List<StoryPage>
)

private data class HolidayConfig(
    val key: String,
    val title: String,
    val headline: String,
    val description: String,
    val step1Title: String,
    val step1Desc: String,
    val step2Title: String,
    val step2Desc: String
)

object PulseStoryAnalyzer {

    fun getGreetingStory(context: Context): StoryGroup? {
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val isViewed = prefs.getBoolean("story_viewed_daily_greeting", false)

        if (isViewed) return null

        val tutorialPages = listOf(
            StoryPage(
                title = "Расписание",
                subtitle = "Шаг 1 из 3",
                mainHeadline = "Расписание",
                description = "Все твои пары и перемены всегда под рукой в режиме реального времени.",
                sectionTitle = "Главные фишки",
                steps = listOf(
                    StoryStepItem(
                        number = 1,
                        title = "Следи за временем",
                        description = "Показывает прогресс текущей пары и отсчёт минут до её завершения.",
                        icon = Icons.Default.AccessTime
                    ),
                    StoryStepItem(
                        number = 2,
                        title = "Делись расписанием",
                        description = "Делись красивой картинкой расписания в один клик.",
                        icon = Icons.Outlined.Share
                    )
                ),
                footerTip = "Заглядывай в расписание накануне вечером!",
                nextStoryTitle = "Дальше: Оценки и цели"
            ),
            StoryPage(
                title = "Успеваемость",
                subtitle = "Шаг 2 из 3",
                mainHeadline = "Контроль оценок",
                description = "Рассчитывай средний балл и ставь цели по предметам.",
                sectionTitle = "Что умеет раздел",
                steps = listOf(
                    StoryStepItem(
                        number = 1,
                        title = "Точный средний балл",
                        description = "Учитывает обычные оценки и контрольные работы.",
                        icon = Icons.Outlined.BarChart
                    )
                ),
                footerTip = "Ставь амбициозные цели — мы поможем их достичь!",
                nextStoryTitle = "Дальше: Домашка"
            ),
            StoryPage(
                title = "Задания",
                subtitle = "Шаг 3 из 3",
                mainHeadline = "Домашние задания",
                description = "Никаких забытых дедлайнов — мы заставим вспомнить всё вовремя.",
                sectionTitle = "Преимущества трекера",
                steps = listOf(
                    StoryStepItem(
                        number = 1,
                        title = "Учёт по парам",
                        description = "Приложение считает, сколько именно пар осталось до сдачи.",
                        icon = Icons.Default.Schedule
                    ),
                    StoryStepItem(
                        number = 2,
                        title = "Вечерние напоминания",
                        description = "Уведомление напомнит о завтрашних заданиях с номерами пар.",
                        icon = Icons.Outlined.Notifications
                    )
                ),
                footerTip = "Ты готов к учебе! Желаем продуктивного семестра!",
                nextStoryTitle = null
            )
        )

        return StoryGroup(
            id = "daily_greeting",
            title = "Обучение",
            isViewed = false,
            pages = tutorialPages
        )
    }

    fun getHolidayStory(context: Context): StoryGroup? {
        val today = LocalDate.now()
        val month = today.monthValue
        val day = today.dayOfMonth

        val config = when {
            (month == 12 && day == 31) || (month == 1 && day in 1..2) -> {
                HolidayConfig(
                    key = "new_year",
                    title = "Праздник",
                    headline = "С Новым годом!",
                    description = "Поздравляем с праздником. Пусть наступающий год принесет легкую учебу и яркие победы.",
                    step1Title = "Успешная сессия",
                    step1Desc = "Желаем закрыть все зачеты и экзамены без лишних усилий.",
                    step2Title = "Новые возможности",
                    step2Desc = "Пусть каждый день открывает полезные знания и перспективы."
                )
            }
            month == 1 && day == 25 -> {
                HolidayConfig(
                    key = "student_day",
                    title = "Праздник",
                    headline = "С Днем студента!",
                    description = "Поздравляем всех студентов. Желаем бодрости духа, терпения и уверенности в себе.",
                    step1Title = "Легкие пары",
                    step1Desc = "Пусть занятия проходят быстро, а преподаватели радуют лояльностью.",
                    step2Title = "Верные друзья",
                    step2Desc = "Студенческое братство всегда выручит в трудную минуту."
                )
            }
            month == 2 && day == 23 -> {
                HolidayConfig(
                    key = "defenders_day",
                    title = "Праздник",
                    headline = "С 23 Февраля!",
                    description = "Поздравляем с Днем защитников Отечества. Мужества, целеустремленности и энергии.",
                    step1Title = "Стойкость и воля",
                    step1Desc = "Уверенно преодолевай любые трудности на учебном пути.",
                    step2Title = "Достижение целей",
                    step2Desc = "Ставь высокие планки и добивайся поставленных задач."
                )
            }
            month == 3 && day == 8 -> {
                HolidayConfig(
                    key = "women_day",
                    title = "Праздник",
                    headline = "С 8 Марта!",
                    description = "Поздравляем с Международным женским днем. Весеннего настроения, вдохновения и радости.",
                    step1Title = "Вдохновение",
                    step1Desc = "Пусть весна дарит тепло, улыбки и яркие эмоции каждый день.",
                    step2Title = "Учебные успехи",
                    step2Desc = "Легких зачетов, отличных оценок и только приятных событий."
                )
            }
            month == 5 && day == 9 -> {
                HolidayConfig(
                    key = "victory_day",
                    title = "Праздник",
                    headline = "С Днем Победы!",
                    description = "Поздравляем с Днем Победы. Мирного неба, благополучия и крепкого здоровья.",
                    step1Title = "Память и уважение",
                    step1Desc = "Помним подвиг тех, кто отстоял мирную жизнь и будущее.",
                    step2Title = "Мир и созидание",
                    step2Desc = "Стремись к созидательному труду и добрым делам."
                )
            }
            month == 9 && day == 1 -> {
                HolidayConfig(
                    key = "knowledge_day",
                    title = "Праздник",
                    headline = "С Днем знаний!",
                    description = "Поздравляем с началом нового учебного года. Пусть он будет продуктивным и интересным.",
                    step1Title = "Новый старт",
                    step1Desc = "Новые предметы, задачи и горизонты профессионального роста.",
                    step2Title = "Стремление вперед",
                    step2Desc = "Развивай навыки и уверенно иди к диплому специалиста."
                )
            }
            month == 11 && day == 17 -> {
                HolidayConfig(
                    key = "intl_student_day",
                    title = "Праздник",
                    headline = "С Днем студента!",
                    description = "Поздравляем с Международным днем студентов. Энергии, вдохновения и удачи на парах.",
                    step1Title = "Студенческая жизнь",
                    step1Desc = "Используй время с максимальной пользой для будущего.",
                    step2Title = "Успешные зачеты",
                    step2Desc = "Пусть сессия не доставит хлопот, а знания останутся навсегда."
                )
            }
            else -> null
        } ?: return null

        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val groupId = "holiday_${config.key}_${today.year}"
        val isViewed = prefs.getBoolean("story_viewed_$groupId", false)

        if (isViewed) return null

        val pages = listOf(
            StoryPage(
                title = config.title,
                subtitle = "Поздравление",
                mainHeadline = config.headline,
                description = config.description,
                sectionTitle = "Пожелания",
                steps = listOf(
                    StoryStepItem(
                        number = 1,
                        title = config.step1Title,
                        description = config.step1Desc,
                        icon = Icons.Default.AutoAwesome
                    ),
                    StoryStepItem(
                        number = 2,
                        title = config.step2Title,
                        description = config.step2Desc,
                        icon = Icons.Default.Star
                    )
                ),
                footerTip = "Проведи этот день с отличным настроением!",
                nextStoryTitle = null
            )
        )

        return StoryGroup(
            id = groupId,
            title = config.title,
            isViewed = false,
            pages = pages
        )
    }

    fun generateWeeklyStories(
        context: Context,
        history: Map<LocalDate, List<LessonPair>>,
        homeworks: List<HomeworkItem>
    ): StoryGroup? {
        val today = LocalDate.now()
        if (today.dayOfWeek !in listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
            return null
        }

        val startOfWeek = today.with(DayOfWeek.MONDAY)
        val endOfWeek = startOfWeek.plusDays(6)

        val weekLessons = history.filterKeys { !it.isBefore(startOfWeek) && !it.isAfter(endOfWeek) }
            .values.flatten()

        if (weekLessons.isEmpty()) return null

        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val groupId = "week_${startOfWeek}"
        val isViewed = prefs.getBoolean("story_viewed_$groupId", false)

        val totalHours = weekLessons.size * 2
        val topSubject = weekLessons.groupingBy { it.subject.substringBefore("(").trim() }
            .eachCount().maxByOrNull { it.value }

        val pages = listOf(
            StoryPage(
                title = "Итоги недели",
                subtitle = "Статистика",
                mainHeadline = "Итоги недели",
                description = "Отличная работа! Посмотри, как прошла твоя учебная неделя.",
                sectionTitle = "Твои достижения",
                steps = listOf(
                    StoryStepItem(
                        number = 1,
                        title = "Учебная нагрузка",
                        description = "Ты провел на парах около $totalHours часов за эту неделю.",
                        icon = Icons.Default.AccessTime
                    ),
                    StoryStepItem(
                        number = 2,
                        title = "Главный предмет",
                        description = "Больше всего времени занял предмет: ${topSubject?.key ?: "Учёба"}.",
                        icon = Icons.Outlined.School
                    )
                ),
                footerTip = "Отдохни как следует перед новой учебной неделей!",
                nextStoryTitle = null
            )
        )

        return StoryGroup(id = groupId, title = "Итоги", isViewed = isViewed, pages = pages)
    }

    fun markAsViewed(context: Context, groupId: String) {
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("story_viewed_$groupId", true).apply()
    }
}

@Composable
fun AnimatedGlowingWaveBackground(
    modifier: Modifier = Modifier,
    accentColor: Color = PrimaryPurple,
    secondaryColor: Color = PrimaryPurple.copy(alpha = 0.75f)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bg_anim")

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Restart),
        label = "phase1"
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase2"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(BgDark, SurfaceDark, BgDark)
            )
        )

        val wavePath1 = Path().apply {
            moveTo(0f, height * 0.28f)
            var x = 0f
            while (x <= width) {
                lineTo(x, height * 0.28f + 30f * sin((x / width) * 2.2 * Math.PI + phase1).toFloat())
                x += 10f
            }
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }

        drawPath(
            path = wavePath1,
            brush = Brush.verticalGradient(
                colors = listOf(accentColor.copy(alpha = 0.45f), secondaryColor.copy(alpha = 0.16f), Color.Transparent),
                startY = height * 0.28f, endY = height * 0.85f
            )
        )

        val wavePath2 = Path().apply {
            moveTo(0f, height * 0.32f)
            var x = 0f
            while (x <= width) {
                lineTo(x, height * 0.32f + 40f * sin((x / width) * 1.8 * Math.PI - phase2).toFloat())
                x += 10f
            }
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }

        drawPath(
            path = wavePath2,
            brush = Brush.verticalGradient(
                colors = listOf(secondaryColor.copy(alpha = 0.35f), accentColor.copy(alpha = 0.12f), Color.Transparent),
                startY = height * 0.34f, endY = height * 0.90f
            )
        )

        val waveLine = Path().apply {
            moveTo(0f, height * 0.28f)
            var x = 0f
            while (x <= width) {
                lineTo(x, height * 0.28f + 30f * sin((x / width) * 2.2 * Math.PI + phase1).toFloat())
                x += 10f
            }
        }
        drawPath(waveLine, color = secondaryColor.copy(alpha = 0.85f), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun StoryAvatar(group: StoryGroup, onClick: () -> Unit) {
    val isGreeting = group.id == "daily_greeting"
    val isHoliday = group.id.startsWith("holiday_")
    val borderColor = when {
        isGreeting -> PrimaryPurple
        isHoliday -> Color(0xFFEC4899)
        else -> PrimaryPurple.copy(alpha = 0.85f)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .border(2.dp, borderColor, CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(SurfaceDark, PrimaryPurple.copy(alpha = 0.65f)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    isGreeting -> Icons.Default.School
                    isHoliday -> Icons.Default.AutoAwesome
                    else -> Icons.Default.AutoAwesome
                },
                contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = group.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StoriesViewer(
    group: StoryGroup,
    onClose: () -> Unit,
    onSharePage: (StoryPage) -> Unit
) {
    val context = LocalContext.current
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }

    val pageCount = group.pages.size
    val currentStory = group.pages.getOrElse(currentPageIndex) { group.pages.first() }

    LaunchedEffect(Unit) {
        PulseStoryAnalyzer.markAsViewed(context, group.id)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        isPaused = true

                        val up = waitForUpOrCancellation()
                        isPaused = false

                        if (up != null) {
                            val duration = up.uptimeMillis - down.uptimeMillis
                            if (duration < 300) {
                                if (up.position.x < size.width * 0.3f) {
                                    currentPageIndex = maxOf(0, currentPageIndex - 1)
                                } else {
                                    if (currentPageIndex < pageCount - 1) currentPageIndex++ else onClose()
                                }
                            }
                        }
                    }
                }
        ) {
            AnimatedGlowingWaveBackground()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (i in 0 until pageCount) {
                            val progress = remember(currentPageIndex, isPaused) {
                                Animatable(if (i < currentPageIndex) 1f else 0f)
                            }

                            LaunchedEffect(currentPageIndex, isPaused) {
                                if (i == currentPageIndex) {
                                    if (!isPaused) {
                                        val remainingTime = (6000 * (1f - progress.value)).toInt()
                                        progress.animateTo(
                                            targetValue = 1f,
                                            animationSpec = tween(remainingTime, easing = LinearEasing)
                                        )
                                        if (currentPageIndex < pageCount - 1) currentPageIndex++ else onClose()
                                    }
                                } else if (i < currentPageIndex) {
                                    progress.snapTo(1f)
                                } else {
                                    progress.snapTo(0f)
                                }
                            }

                            LinearProgressIndicator(
                                progress = { progress.value },
                                modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape),
                                color = PrimaryPurple,
                                trackColor = PrimaryPurple.copy(alpha = 0.25f),
                                strokeCap = StrokeCap.Round
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceDark)
                                    .border(1.dp, PrimaryPurple.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.School, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(currentStory.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(currentStory.subtitle, color = PrimaryPurple, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(currentStory.mainHeadline, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(currentStory.description, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 20.sp)

                    Spacer(modifier = Modifier.height(24.dp))

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = SurfaceDark.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.35f)),
                        shadowElevation = 16.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(currentStory.sectionTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(16.dp))

                            currentStory.steps.forEachIndexed { index, step ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(44.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(BgDark)
                                                .border(1.dp, PrimaryPurple.copy(alpha = 0.25f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(step.icon, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(22.dp))
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .align(Alignment.TopEnd)
                                                .offset(x = 2.dp, y = (-2).dp)
                                                .clip(CircleShape)
                                                .background(PrimaryPurple),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(step.number.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(step.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(step.description, color = TextGray, fontSize = 12.sp, lineHeight = 16.sp)
                                    }
                                }
                                if (index < currentStory.steps.size - 1) {
                                    HorizontalDivider(color = PrimaryPurple.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(SurfaceDark)
                            .border(1.dp, PrimaryPurple.copy(alpha = 0.35f), CircleShape)
                            .clickable { currentPageIndex = maxOf(0, currentPageIndex - 1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White, modifier = Modifier.size(20.dp))
                    }

                    if (currentStory.nextStoryTitle != null) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = SurfaceDark,
                            border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.35f)),
                            modifier = Modifier.clickable { if (currentPageIndex < pageCount - 1) currentPageIndex++ }
                        ) {
                            Text(
                                currentStory.nextStoryTitle,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = SurfaceDark,
                            border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.5f)),
                            modifier = Modifier.clickable { onClose() }
                        ) {
                            Text(
                                "Завершить",
                                color = PrimaryPurple,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(PrimaryPurple.copy(alpha = 0.8f), PrimaryPurple)
                                )
                            )
                            .clickable { if (currentPageIndex < pageCount - 1) currentPageIndex++ else onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Далее", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}