package com.example.pulse.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.pulse.data.TeacherNotesManager
import com.example.pulse.viewmodel.LessonPair
import com.example.pulse.viewmodel.ScheduleViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherScheduleScreen(
    navController: NavController,
    teacherName: String,
    viewModel: ScheduleViewModel = viewModel()
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scheduleByDate = remember { viewModel.getTeacherSchedule(teacherName) }

    var noteText by remember { mutableStateOf(TeacherNotesManager.getNote(context, teacherName)) }
    var isDossierExpanded by remember { mutableStateOf(noteText.isNotBlank()) }

    // Подсчет метрик преподавателя
    val totalLessons = remember(scheduleByDate) { scheduleByDate.values.sumOf { it.size } }
    val uniqueRooms = remember(scheduleByDate) {
        scheduleByDate.values.flatten()
            .map { it.room.trim() }
            .filter { it.isNotBlank() && it != "—" }
            .distinct()
    }
    val uniqueGroups = remember(scheduleByDate) {
        scheduleByDate.values.flatten()
            .map { lesson ->
                when {
                    lesson.group.isNotBlank() && lesson.group != "—" -> lesson.group
                    lesson.subject.contains("Гр.") -> lesson.subject.substringAfter("Гр.").substringBefore(")").trim()
                    else -> ""
                }
            }
            .filter { it.isNotBlank() && it != "—" }
            .flatMap { it.split("/").map { g -> g.trim() } }
            .filter { it.isNotBlank() && it != "—" }
            .distinct()
    }

    // Определение текущего статуса прямо сейчас
    val currentLessonInfo = remember(scheduleByDate) {
        val today = LocalDate.now()
        val now = LocalTime.now()
        val todayLessons = scheduleByDate[today] ?: emptyList()

        todayLessons.firstOrNull { lesson ->
            val start = lesson.startLocalTime
            val end = lesson.endLocalTime
            if (start != null && end != null) {
                (now.isAfter(start) || now == start) && now.isBefore(end)
            } else false
        }
    }

    // Генерация инициалов для аватара (например "Иванов И. И." -> "ИИ")
    val teacherInitials = remember(teacherName) {
        teacherName.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercase() }
            .joinToString("")
            .ifBlank { "П" }
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Карточка преподавателя",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBackIosNew,
                            contentDescription = "Назад",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Hero-карточка профиля
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Градиентный аватар с инициалами
                            Box(
                                modifier = Modifier
                                    .size(58.dp)
                                    .background(
                                        brush = Brush.linearGradient(
                                            listOf(PrimaryPurple, Color(0xFF9C27B0))
                                        ),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = teacherInitials,
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = teacherName,
                                    color = Color.White,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Преподаватель колледжа",
                                    color = TextGray,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        // Индикатор текущего урока «Прямо сейчас»
                        if (currentLessonInfo != null) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF00E676).copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0xFF00E676).copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFF00E676), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val currentGroup = when {
                                    currentLessonInfo.group.isNotBlank() && currentLessonInfo.group != "—" -> " Гр. ${currentLessonInfo.group}"
                                    currentLessonInfo.subject.contains("Гр.") -> " Гр. ${currentLessonInfo.subject.substringAfter("Гр.").substringBefore(")").trim()}"
                                    else -> ""
                                }
                                Text(
                                    text = "Сейчас: ${currentLessonInfo.cleanSubject}$currentGroup (ауд. ${currentLessonInfo.room})",
                                    color = Color(0xFF00E676),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = PrimaryPurple.copy(alpha = 0.15f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(14.dp))

                        // Чипы со статистикой
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MetricBadge(
                                label = "Всего пар",
                                value = "$totalLessons",
                                icon = Icons.Outlined.AutoStories,
                                modifier = Modifier.weight(1f)
                            )
                            MetricBadge(
                                label = "Группы",
                                value = if (uniqueGroups.isNotEmpty()) uniqueGroups.size.toString() else "—",
                                icon = Icons.Outlined.Groups,
                                modifier = Modifier.weight(1f)
                            )
                            MetricBadge(
                                label = "Кабинеты",
                                value = if (uniqueRooms.isNotEmpty()) uniqueRooms.take(2).joinToString() else "—",
                                icon = Icons.Outlined.MeetingRoom,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Досье и заметки с быстрыми тегами
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    isDossierExpanded = !isDossierExpanded
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(PrimaryPurple.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.RateReview,
                                        contentDescription = null,
                                        tint = PrimaryPurple,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Личное досье", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("Требования, автоматы, формат сдачи", color = TextGray, fontSize = 11.sp)
                                }
                            }
                            Icon(
                                imageVector = if (isDossierExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                tint = TextGray
                            )
                        }

                        AnimatedVisibility(visible = isDossierExpanded) {
                            Column {
                                Spacer(modifier = Modifier.height(14.dp))

                                // Быстрые кликабельные теги
                                val presetTags = listOf(
                                    "Автомат с 8+ баллов",
                                    "Строго по времени",
                                    "Лабы только в Word",
                                    "Конспекты от руки",
                                    "Спрашивает теорию"
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    presetTags.forEach { tag ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = BgDark,
                                            border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.25f)),
                                            modifier = Modifier.clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val newText = if (noteText.isBlank()) tag else "$noteText • $tag"
                                                noteText = newText
                                                TeacherNotesManager.saveNote(context, teacherName, newText)
                                            }
                                        ) {
                                            Text(
                                                text = "+ $tag",
                                                color = TextGray,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = noteText,
                                    onValueChange = {
                                        noteText = it
                                        TeacherNotesManager.saveNote(context, teacherName, it)
                                    },
                                    placeholder = {
                                        Text(
                                            "Запишите полезную информацию о преподавателе...",
                                            color = TextGray.copy(alpha = 0.6f),
                                            fontSize = 13.sp
                                        )
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = PrimaryPurple,
                                        unfocusedBorderColor = PrimaryPurple.copy(alpha = 0.25f),
                                        focusedContainerColor = BgDark,
                                        unfocusedContainerColor = BgDark
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 3
                                )
                            }
                        }
                    }
                }
            }

            // Заголовок секции расписания
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = PrimaryPurple,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Расписание занятий",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Пустое состояние
            if (scheduleByDate.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.15f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .background(PrimaryPurple.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.EventBusy,
                                    contentDescription = null,
                                    tint = PrimaryPurple,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                "У преподавателя нет занятий",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "В текущем загруженном расписании\nпар у данного преподавателя не найдено.",
                                color = TextGray,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            } else {
                val dateFormatter = com.example.pulse.utils.DateTimeUtils.DATE_FORMATTER_DAY_MONTH
                val today = LocalDate.now()
                val tomorrow = today.plusDays(1)

                scheduleByDate.forEach { (date, lessons) ->
                    item(key = "date_${date}") {
                        val isToday = date == today
                        val isTomorrow = date == tomorrow

                        // Заголовок даты с бейджем "Сегодня / Завтра"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = date.format(dateFormatter).replaceFirstChar { it.uppercase() },
                                color = if (isToday) PrimaryPurple else Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isToday || isTomorrow) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isToday) PrimaryPurple else PrimaryPurple.copy(alpha = 0.2f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isToday) "Сегодня" else "Завтра",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Карточка с таймлайном дня
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            border = BorderStroke(
                                1.dp,
                                if (isToday) PrimaryPurple.copy(alpha = 0.45f) else PrimaryPurple.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(top = 18.dp, start = 16.dp, end = 16.dp, bottom = 6.dp)
                            ) {
                                lessons.forEachIndexed { index, lesson ->
                                    TeacherTimelineItem(
                                        lesson = lesson,
                                        nextLesson = lessons.getOrNull(index + 1),
                                        isLast = index == lessons.size - 1,
                                        isToday = isToday
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(28.dp)) }
        }
    }
}

@Composable
fun MetricBadge(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(BgDark, RoundedCornerShape(12.dp))
            .border(1.dp, PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(PrimaryPurple.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(13.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = label,
                color = TextGray,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TeacherTimelineItem(
    lesson: LessonPair,
    nextLesson: LessonPair? = null,
    isLast: Boolean,
    isToday: Boolean
) {
    // Проверяем, идет ли пара прямо сейчас
    val isLiveNow = remember(lesson, isToday) {
        if (!isToday) false
        else {
            val now = LocalTime.now()
            val start = lesson.startLocalTime
            val end = lesson.endLocalTime
            if (start != null && end != null) (now.isAfter(start) || now == start) && now.isBefore(end)
            else false
        }
    }

    // Проверяем, есть ли «окно» (перерыв >= 35 минут) до следующей пары
    val isBreak = remember(lesson, nextLesson) {
        if (nextLesson == null) false
        else {
            val end = lesson.endLocalTime
            val nextStart = nextLesson.startLocalTime
            if (end != null && nextStart != null) {
                java.time.Duration.between(end, nextStart).toMinutes() >= 35
            } else false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!isLast) {
                    val lineColor = if (isLiveNow) Color(0xFF00E676).copy(alpha = 0.5f) else PrimaryPurple.copy(alpha = 0.35f)
                    val lineX = 48.dp.toPx() + 12.dp.toPx() + 18.dp.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(lineX, 36.dp.toPx()),
                        end = Offset(lineX, size.height),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = if (isBreak) PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) else null
                    )
                }
            }
    ) {
        // Колонка времени
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .width(48.dp)
                .padding(top = 4.dp)
        ) {
            Text(
                lesson.startTime,
                color = if (isLiveNow) Color(0xFF00E676) else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                lesson.endTime,
                color = TextGray,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Направляющая линия и номер пары
        Box(
            modifier = Modifier.width(36.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(SurfaceDark, RoundedCornerShape(12.dp))
                    .background(
                        if (isLiveNow) Color(0xFF00E676).copy(alpha = 0.15f) else PrimaryPurple.copy(alpha = 0.2f),
                        RoundedCornerShape(12.dp)
                    )
                    .border(
                        1.dp,
                        if (isLiveNow) Color(0xFF00E676) else PrimaryPurple.copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lesson.pairNumber.toString(),
                    color = if (isLiveNow) Color(0xFF00E676) else PrimaryPurple,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Информация о паре, группе и кабинете
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 22.dp, top = 2.dp)
        ) {
            val cleanSubject = lesson.subject.substringBefore(" (Гр.").substringBefore(" (").trim()
            val displayGroup = when {
                lesson.group.isNotBlank() && lesson.group != "—" -> lesson.group
                lesson.subject.contains("Гр.") -> lesson.subject.substringAfter("Гр.").substringBefore(")").trim()
                else -> ""
            }

            Text(
                text = cleanSubject,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 19.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Аудитория
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(BgDark, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MeetingRoom,
                        contentDescription = null,
                        tint = PrimaryPurple,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Ауд. ${lesson.room.ifBlank { "—" }}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Учебная группа
                if (displayGroup.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(BgDark, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Groups,
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Гр. $displayGroup",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}