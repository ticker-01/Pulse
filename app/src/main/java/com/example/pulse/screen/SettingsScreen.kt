package com.example.pulse.screen

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import com.example.pulse.viewmodel.ScheduleViewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.pulse.utils.LiveLessonService
import com.example.pulse.utils.LiveNotificationManager
import com.example.pulse.utils.SystemAlarmHelper
import com.example.pulse.widget.updateAllPulseWidgets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sin

data class PdfSourceItem(val name: String, val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val viewModel: ScheduleViewModel = viewModel()

    val isTeacherMode = prefs.getBoolean("is_teacher_mode", false)
    val teacherName = prefs.getString("teacher_name", "") ?: ""
    var isTeacherModeVar by remember { mutableStateOf(isTeacherMode) }
    var teacherNameVar by remember { mutableStateOf(teacherName) }

    var userName by remember { mutableStateOf(prefs.getString("user_name", "Студент") ?: "Студент") }
    var userGroup by remember { mutableStateOf(prefs.getString("user_group", "000") ?: "000") }

    var dynamicColorEnabled by remember { mutableStateOf(prefs.getBoolean("dynamic_color_enabled", false)) }

    var remindersEnabled by remember { mutableStateOf(prefs.getBoolean("lesson_reminders_enabled", true)) }
    var reminderOffset by remember { mutableStateOf(prefs.getInt("reminder_offset_minutes", 15)) }
    var notifyLessonStart by remember { mutableStateOf(prefs.getBoolean("notify_lesson_start", false)) }
    var notifyLessonEnd by remember { mutableStateOf(prefs.getBoolean("notify_lesson_end", false)) }
    var liveNotificationsEnabled by remember { mutableStateOf(prefs.getBoolean("live_notifications_enabled", false)) }

    var autoAlarmEnabled by remember { mutableStateOf(prefs.getBoolean("auto_alarm_enabled", false)) }
    var autoAlarmPrepMinutes by remember { mutableStateOf(prefs.getInt("auto_alarm_prep_minutes", 60)) }

    val lastPdfUrl = prefs.getString("last_pdf_url", "Сайт замен колледжа") ?: "Сайт замен колледжа"
    val lastPdfName = prefs.getString("last_pdf_filename", "Неизвестно") ?: "Неизвестно"
    val lastPdfUrlsJson = prefs.getString("last_pdf_urls_json", null)

    val sourceItems = remember(lastPdfName, lastPdfUrl, lastPdfUrlsJson) {
        val names = if (lastPdfName.isBlank() || lastPdfName == "Неизвестно") {
            emptyList()
        } else {
            lastPdfName.split(Regex("""[,;\n]+""")).map { it.trim() }.filter { it.isNotBlank() }
        }

        val urls = if (!lastPdfUrlsJson.isNullOrBlank()) {
            try {
                val jsonArr = JSONArray(lastPdfUrlsJson)
                (0 until jsonArr.length()).map { jsonArr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        names.mapIndexed { index, name ->
            val url = urls.getOrNull(index) ?: if (names.size == 1) lastPdfUrl else ""
            PdfSourceItem(name = name, url = url)
        }
    }

    var isCheckingUpdate by remember { mutableStateOf(false) }
    var manualUpdateInfo by remember { mutableStateOf<com.example.pulse.utils.UpdateInfo?>(null) }

    var showBellDialog by remember { mutableStateOf(false) }
    var showTimeEditorDialog by remember { mutableStateOf(false) }
    var isClearingCache by remember { mutableStateOf(false) }

    var showMagicBallDialog by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }

    var shortenedDays by remember { mutableStateOf(prefs.getStringSet("shortened_days", emptySet())?.toSet() ?: emptySet()) }

    val infiniteTransition = rememberInfiniteTransition(label = "refresh")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "angle"
    )

    fun saveSettings() {
        val wasTeacherMode = prefs.getBoolean("is_teacher_mode", false)
        val oldTeacherName = prefs.getString("teacher_name", "") ?: ""

        prefs.edit()
            .putString("user_name", userName)
            .putString("user_group", userGroup)
            .putBoolean("is_teacher_mode", isTeacherModeVar)
            .putString("teacher_name", teacherNameVar)
            .putBoolean("dynamic_color_enabled", dynamicColorEnabled)
            .putBoolean("lesson_reminders_enabled", remindersEnabled)
            .putInt("reminder_offset_minutes", reminderOffset)
            .putBoolean("notify_lesson_start", notifyLessonStart)
            .putBoolean("notify_lesson_end", notifyLessonEnd)
            .putBoolean("live_notifications_enabled", liveNotificationsEnabled)
            .putBoolean("auto_alarm_enabled", autoAlarmEnabled)
            .putInt("auto_alarm_prep_minutes", autoAlarmPrepMinutes)
            .apply()

        prefs.edit().remove("last_auto_alarm_state").apply()

        // Update teacher widget data if teacher mode is enabled
        if (isTeacherModeVar && teacherNameVar.isNotBlank()) {
            viewModel.updateTeacherWidgetData(teacherNameVar)
        }

        if (autoAlarmEnabled) {
            SystemAlarmHelper.triggerAutoAlarmNow(context)
        }

        coroutineScope.launch {
            updateAllPulseWidgets(context)
        }

        Toast.makeText(context, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Настройки", color = Color.White, fontWeight = FontWeight.Bold) },
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
            },
            containerColor = BgDark
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text("Профиль", color = PrimaryPurple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = userName, onValueChange = { userName = it },
                    label = { Text("Как к вам обращаться?", color = TextGray) },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = PrimaryPurple, unfocusedBorderColor = PrimaryPurple.copy(alpha = 0.25f),
                        focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark
                    ), modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Teacher mode toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text("Режим преподавателя", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Просмотр расписания и аналитика для учителей", color = TextGray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = isTeacherModeVar,
                        onCheckedChange = { isChecked ->
                            isTeacherModeVar = isChecked
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = PrimaryPurple)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isTeacherModeVar) {
                    // Teacher name field
                    OutlinedTextField(
                        value = teacherNameVar, onValueChange = { teacherNameVar = it },
                        label = { Text("Ваше имя (ФИО преподавателя)", color = TextGray) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = PrimaryPurple, unfocusedBorderColor = PrimaryPurple.copy(alpha = 0.25f),
                            focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark
                        ), modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                } else {
                    // Student group field
                    OutlinedTextField(
                        value = userGroup, onValueChange = { userGroup = it },
                        label = { Text("Учебная группа (например, 000)", color = TextGray) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = PrimaryPurple, unfocusedBorderColor = PrimaryPurple.copy(alpha = 0.25f),
                            focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark
                        ), modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("Отображение и расчеты", color = PrimaryPurple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Цвета системы (Material You)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    Text("Подстраивать тему под обои телефона", color = TextGray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = dynamicColorEnabled,
                                    onCheckedChange = { isChecked ->
                                        dynamicColorEnabled = isChecked
                                        prefs.edit().putBoolean("dynamic_color_enabled", isChecked).apply()
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        coroutineScope.launch {
                                            updateAllPulseWidgets(context)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(checkedTrackColor = PrimaryPurple)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = PrimaryPurple.copy(alpha = 0.15f), thickness = 1.dp)
                            Spacer(modifier = Modifier.height(8.dp))
                        }


                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showBellDialog = true }
                                .padding(vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(PrimaryPurple.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.EditCalendar,
                                    contentDescription = null,
                                    tint = PrimaryPurple,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Сокращенные дни", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("Выбрать даты в календаре", color = TextGray, fontSize = 12.sp)
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowForwardIos,
                                contentDescription = null,
                                tint = TextGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showTimeEditorDialog = true }
                                .padding(vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color(0xFFFF9800).copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Schedule,
                                    contentDescription = null,
                                    tint = Color(0xFFFF9800),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Редактор времени", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("Изменить расписание звонков", color = TextGray, fontSize = 12.sp)
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowForwardIos,
                                contentDescription = null,
                                tint = TextGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = PrimaryPurple.copy(alpha = 0.15f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))

                        if (sourceItems.size <= 1) {
                            val single = sourceItems.firstOrNull()
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color(0xFF00C853).copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PictureAsPdf,
                                        contentDescription = null,
                                        tint = Color(0xFF00C853),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Источник: ${single?.name ?: lastPdfName}",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = single?.url?.ifBlank { lastPdfUrl } ?: lastPdfUrl,
                                        color = TextGray,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(Color(0xFF00C853).copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.FolderCopy,
                                            contentDescription = null,
                                            tint = Color(0xFF00C853),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = "Источники замен (${sourceItems.size}):",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Все файлы объединены в расписании",
                                            color = TextGray,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    sourceItems.forEachIndexed { index, item ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(BgDark, RoundedCornerShape(10.dp))
                                                .border(1.dp, PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .background(PrimaryPurple.copy(alpha = 0.2f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "${index + 1}",
                                                    color = PrimaryPurple,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (item.url.isNotBlank() && item.url != "Сайт замен колледжа") {
                                                    Text(
                                                        text = item.url,
                                                        color = TextGray,
                                                        fontSize = 10.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("Уведомления и будильники", color = PrimaryPurple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text("Предупреждать до пары", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("Напоминание перед началом", color = TextGray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = remindersEnabled,
                                onCheckedChange = {
                                    remindersEnabled = it
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = PrimaryPurple)
                            )
                        }

                        AnimatedVisibility(visible = remindersEnabled) {
                            Column {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("За сколько минут предупреждать:", color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(10.dp))

                                val reminderOptions = listOf(5 to "5 мин", 10 to "10 мин", 15 to "15 мин", 30 to "30 мин", 45 to "45 мин", 60 to "1 час")
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    reminderOptions.forEach { (mins, label) ->
                                        val isSelected = reminderOffset == mins
                                        val bgColor by animateColorAsState(if (isSelected) PrimaryPurple else BgDark, label = "bg")
                                        val textColor by animateColorAsState(if (isSelected) Color.White else TextGray, label = "text")

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(bgColor)
                                                .border(1.dp, if (isSelected) PrimaryPurple else PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                                .clickable {
                                                    if (reminderOffset != mins) {
                                                        reminderOffset = mins
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                }
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(label, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = PrimaryPurple.copy(alpha = 0.15f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text("Начало пары", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("Уведомлять ровно со звонком", color = TextGray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = notifyLessonStart,
                                onCheckedChange = {
                                    notifyLessonStart = it
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = PrimaryPurple)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text("Конец пары", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("Сообщать об окончании занятия", color = TextGray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = notifyLessonEnd,
                                onCheckedChange = {
                                    notifyLessonEnd = it
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = PrimaryPurple)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Живое отслеживание", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("Прогресс пары прямо в шторке и на экране", color = TextGray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = liveNotificationsEnabled,
                                onCheckedChange = { isChecked ->
                                    liveNotificationsEnabled = isChecked
                                    prefs.edit().putBoolean("live_notifications_enabled", isChecked).apply()
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (isChecked) {
                                        LiveNotificationManager.syncCurrentLiveStatus(context)
                                    } else {
                                        LiveLessonService.stop(context)
                                        LiveNotificationManager.clear(context)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = PrimaryPurple)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = PrimaryPurple.copy(alpha = 0.15f), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Автобудильник в Часы", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("(лимит 4-я пара)", color = TextGray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = autoAlarmEnabled,
                                onCheckedChange = {
                                    autoAlarmEnabled = it
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = PrimaryPurple)
                            )
                        }

                        AnimatedVisibility(visible = autoAlarmEnabled) {
                            Column {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Время на сборы и дорогу до пары:", color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(10.dp))

                                val prepOptions = listOf(
                                    15 to "15 мин", 20 to "20 мин", 30 to "30 мин",
                                    45 to "45 мин", 60 to "1 час", 90 to "1.5 ч",
                                    120 to "2 ч", 150 to "2.5 ч", 180 to "3 ч",
                                    210 to "3.5 ч", 240 to "4 ч"
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    prepOptions.forEach { (mins, label) ->
                                        val isSelected = autoAlarmPrepMinutes == mins
                                        val bgColor by animateColorAsState(if (isSelected) PrimaryPurple else BgDark, label = "bg")
                                        val textColor by animateColorAsState(if (isSelected) Color.White else TextGray, label = "text")

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(bgColor)
                                                .border(1.dp, if (isSelected) PrimaryPurple else PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                                .clickable {
                                                    if (autoAlarmPrepMinutes != mins) {
                                                        autoAlarmPrepMinutes = mins
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                }
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(label, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("Приложение", color = PrimaryPurple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isCheckingUpdate) return@OutlinedButton
                        isCheckingUpdate = true
                        coroutineScope.launch {
                            val result = com.example.pulse.utils.UpdateChecker.checkForUpdates(context)
                            delay(1200)
                            isCheckingUpdate = false
                            if (result?.hasUpdate == true) manualUpdateInfo = result
                            else Toast.makeText(context, "У вас самая актуальная версия!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudSync,
                        contentDescription = "Обновить",
                        modifier = Modifier.size(20.dp).rotate(if (isCheckingUpdate) rotationAngle else 0f),
                        tint = PrimaryPurple
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = if (isCheckingUpdate) "Поиск обновлений..." else "Проверить обновления", fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isClearingCache = true
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE91E63)),
                    border = BorderStroke(1.dp, Color(0xFFE91E63).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = "Очистить кэш",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFFE91E63)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Очистить кэш расписания", fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        saveSettings()
                        navController.navigate("schedule") { popUpTo("schedule") { inclusive = true } }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Применить и сохранить", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            versionTapCount++
                            if (versionTapCount >= 3) {
                                versionTapCount = 0
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showMagicBallDialog = true
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "МГКАП v1.3.5 • Build 2026",
                        color = TextGray.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (isClearingCache) {
            WaveCacheClearOverlay(
                onClearCompleted = {
                    prefs.edit()
                        .remove("schedule_history")
                        .remove("saved_teachers")
                        .remove("last_raw_pdf_text")
                        .remove("widget_lessons_today")
                        .remove("last_pdf_url")
                        .remove("last_pdf_filename")
                        .remove("last_pdf_urls_json")
                        .remove("last_pdf_hash")
                        .apply()

                    Toast.makeText(context, "Кэш расписания успешно очищен", Toast.LENGTH_SHORT).show()
                    isClearingCache = false
                }
            )
        }
    }

    if (showBellDialog) {
        var selectedConfigDate by remember { mutableStateOf(LocalDate.now()) }
        val isCurrentDayShortened = shortenedDays.contains(selectedConfigDate.toString())
        val dateFormatter = remember { DateTimeFormatter.ofPattern("dd MMMM, EEE", Locale("ru")) }

        AlertDialog(
            onDismissRequest = { showBellDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Сокращенные дни", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
            text = {
                Column {
                    Text("Укажите дату, когда в колледже сокращены пары, чтобы расписание пересчиталось автоматически.", color = TextGray, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgDark, RoundedCornerShape(12.dp))
                            .border(1.dp, PrimaryPurple.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedConfigDate = selectedConfigDate.minusDays(1); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                            Icon(Icons.Default.ArrowBackIosNew, contentDescription = "-", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        Text(text = selectedConfigDate.format(dateFormatter), color = PrimaryPurple, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { selectedConfigDate = selectedConfigDate.plusDays(1); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                            Icon(Icons.Default.ArrowForwardIos, contentDescription = "+", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isCurrentDayShortened) PrimaryPurple.copy(alpha = 0.2f) else BgDark),
                        border = BorderStroke(1.dp, if (isCurrentDayShortened) PrimaryPurple else PrimaryPurple.copy(alpha = 0.2f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Column {
                                Text("Короткий день", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(if (isCurrentDayShortened) "Включено" else "Стандартное время", color = TextGray, fontSize = 12.sp)
                            }
                            Switch(
                                checked = isCurrentDayShortened,
                                onCheckedChange = { isChecked ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val newSet = if (isChecked) shortenedDays + selectedConfigDate.toString() else shortenedDays - selectedConfigDate.toString()
                                    shortenedDays = newSet
                                    prefs.edit().putStringSet("shortened_days", newSet).apply()
                                }, colors = SwitchDefaults.colors(checkedTrackColor = PrimaryPurple)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showBellDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple), shape = RoundedCornerShape(12.dp)) { Text("Готово", color = Color.White) }
            }
        )
    }

    if (showTimeEditorDialog) {
        BellScheduleEditorDialog(prefs = prefs, onDismiss = { showTimeEditorDialog = false })
    }

    if (manualUpdateInfo?.hasUpdate == true) {
        AlertDialog(
            onDismissRequest = { manualUpdateInfo = null }, containerColor = SurfaceDark,
            title = { Text("Найдено обновление ${manualUpdateInfo!!.versionName} 🚀", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = { Text(manualUpdateInfo!!.message, color = TextGray, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { com.example.pulse.utils.UpdateChecker.openDownloadLink(context, manualUpdateInfo!!.downloadUrl); manualUpdateInfo = null },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
                ) { Text("Скачать", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { manualUpdateInfo = null }) { Text("Отмена", color = TextGray) } }
        )
    }

    if (showMagicBallDialog) {
        Magic8BallDialog(onDismiss = { showMagicBallDialog = false })
    }
}
@Composable
fun WaveCacheClearOverlay(onClearCompleted: () -> Unit) {
    val fillProgress = remember { Animatable(0f) }
    val infiniteTransition = rememberInfiniteTransition(label = "wave_phase")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "wave_phase_anim"
    )

    val currentAccent = PrimaryPurple

    LaunchedEffect(Unit) {
        fillProgress.animateTo(1.2f, tween(1200, easing = FastOutSlowInEasing))
        onClearCompleted()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val waveAmplitude = 40.dp.toPx()
            val yOffset = height - (height * fillProgress.value)

            val path = Path().apply {
                moveTo(0f, height)
                for (x in 0..width.toInt() step 5) {
                    val y = yOffset + sin((x / width) * 2 * Math.PI.toFloat() + wavePhase) * waveAmplitude
                    lineTo(x.toFloat(), y)
                }
                lineTo(width, height)
                close()
            }
            drawPath(path = path, color = currentAccent.copy(alpha = 0.95f))
        }

        if (fillProgress.value > 0.5f) {
            Text(
                text = "Очистка...",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TimeInputField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 5) onValueChange(it) },
        modifier = Modifier.width(76.dp).height(54.dp),
        textStyle = TextStyle(fontSize = 15.sp, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PrimaryPurple,
            unfocusedBorderColor = PrimaryPurple.copy(alpha = 0.25f),
            focusedContainerColor = BgDark,
            unfocusedContainerColor = SurfaceDark
        ),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BellScheduleEditorDialog(prefs: SharedPreferences, onDismiss: () -> Unit) {
    val tabs = listOf("Обычные" to "REGULAR", "ПН" to "MONDAY", "ЧТ" to "THURSDAY", "СБ" to "SATURDAY", "Сокр." to "SHORTENED")
    var currentTab by remember { mutableStateOf("REGULAR") }

    fun getDefaultBells(key: String): List<Pair<String, String>> {
        val bells = when(key) {
            "MONDAY" -> listOf("08:30" to "09:15", "09:25" to "10:10", "10:20" to "11:05", "11:15" to "12:00", "13:00" to "13:25", "13:35" to "14:20", "14:30" to "15:15", "15:25" to "16:10", "16:20" to "17:05", "17:15" to "18:00", "18:10" to "18:55", "19:05" to "19:50", "20:00" to "20:45")
            "THURSDAY", "REGULAR" -> listOf("08:30" to "09:15", "09:25" to "10:10", "10:20" to "11:05", "11:15" to "12:00", "13:00" to "13:45", "13:55" to "14:40", "14:50" to "15:35", "15:45" to "16:30", "16:40" to "17:25", "17:35" to "18:20", "18:30" to "19:15", "19:25" to "20:10", "20:20" to "21:05")
            "SATURDAY" -> listOf("08:30" to "09:15", "09:25" to "10:10", "10:20" to "11:05", "11:30" to "12:15", "12:25" to "13:10", "13:20" to "14:05", "14:15" to "15:00", "15:10" to "15:55", "16:05" to "16:50", "17:00" to "17:45", "17:55" to "18:40", "18:50" to "19:35", "19:45" to "20:30")
            "SHORTENED" -> listOf("08:30" to "09:10", "09:10" to "09:50", "10:00" to "10:40", "10:40" to "11:20", "11:40" to "12:20", "12:20" to "13:00", "13:10" to "13:50", "13:50" to "14:30", "14:40" to "15:20", "15:20" to "16:00", "16:10" to "16:50", "16:50" to "17:30", "17:40" to "18:20", "18:20" to "19:00")
            else -> emptyList()
        }
        val padded = bells.toMutableList()
        while(padded.size < 14) padded.add("" to "")
        return padded
    }

    var scheduleMap by remember {
        mutableStateOf(
            run {
                val jsonStr = prefs.getString("custom_bell_schedule", null)
                val map = mutableMapOf<String, List<Pair<String, String>>>()
                if (jsonStr != null) {
                    try {
                        val root = JSONObject(jsonStr)
                        for (pair in tabs) {
                            if (root.has(pair.second)) {
                                val arr = root.getJSONArray(pair.second)
                                val list = mutableListOf<Pair<String, String>>()
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    list.add(obj.getString("start") to obj.getString("end"))
                                }
                                while(list.size < 14) list.add("" to "")
                                map[pair.second] = list
                            } else map[pair.second] = getDefaultBells(pair.second)
                        }
                        return@run map
                    } catch (e: Exception) {}
                }
                for (pair in tabs) map[pair.second] = getDefaultBells(pair.second)
                map
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        title = { Text("Редактор времени", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
        text = {
            Column {
                ScrollableTabRow(
                    selectedTabIndex = tabs.indexOfFirst { it.second == currentTab },
                    containerColor = Color.Transparent, edgePadding = 0.dp,
                    indicator = { tabPositions ->
                        Box(Modifier.tabIndicatorOffset(tabPositions[tabs.indexOfFirst { it.second == currentTab }]).height(3.dp).background(PrimaryPurple))
                    },
                    divider = {}
                ) {
                    tabs.forEach { pair ->
                        Tab(
                            selected = currentTab == pair.second,
                            onClick = { currentTab = pair.second },
                            text = { Text(pair.first, color = if (currentTab == pair.second) PrimaryPurple else TextGray, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val currentPairs = scheduleMap[currentTab] ?: getDefaultBells(currentTab)
                    currentPairs.forEachIndexed { index, timePair ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .background(BgDark, RoundedCornerShape(16.dp))
                                .border(1.dp, PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                                .padding(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(PrimaryPurple.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${index + 1}", color = PrimaryPurple, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            TimeInputField(
                                value = timePair.first,
                                onValueChange = { newStart ->
                                    val newList = currentPairs.toMutableList(); newList[index] = newStart to timePair.second
                                    scheduleMap = scheduleMap.toMutableMap().apply { put(currentTab, newList) }
                                }
                            )

                            Spacer(modifier = Modifier.width(8.dp))
                            Text("—", color = TextGray, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))

                            TimeInputField(
                                value = timePair.second,
                                onValueChange = { newEnd ->
                                    val newList = currentPairs.toMutableList(); newList[index] = timePair.first to newEnd
                                    scheduleMap = scheduleMap.toMutableMap().apply { put(currentTab, newList) }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val root = JSONObject()
                    for ((key, list) in scheduleMap) {
                        val arr = JSONArray()
                        for (pair in list) {
                            if (pair.first.isNotBlank() && pair.second.isNotBlank()) {
                                val obj = JSONObject()
                                obj.put("start", pair.first)
                                obj.put("end", pair.second)
                                arr.put(obj)
                            }
                        }
                        root.put(key, arr)
                    }
                    prefs.edit().putString("custom_bell_schedule", root.toString()).apply()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Готово", color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextGray) }
        }
    )
}