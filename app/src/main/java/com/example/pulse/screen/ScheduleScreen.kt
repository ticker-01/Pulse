package com.example.pulse.screen

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.pulse.utils.CustomTheme
import com.example.pulse.data.HomeworkItem
import com.example.pulse.data.HomeworkManager
import com.example.pulse.viewmodel.LessonPair
import com.example.pulse.utils.PulseStoryAnalyzer
import com.example.pulse.utils.ScheduleImageGenerator
import com.example.pulse.viewmodel.ScheduleViewModel
import com.example.pulse.utils.StoriesViewer
import com.example.pulse.utils.StoryAvatar
import com.example.pulse.utils.StoryGroup
import com.example.pulse.utils.UpdateChecker
import com.example.pulse.utils.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

@Composable
fun ScheduleScreen(
    navController: NavController,
    viewModel: ScheduleViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var homeworkList by remember { mutableStateOf(emptyList<HomeworkItem>()) }
    fun reloadHomeworks() { homeworkList = HomeworkManager.loadHomeworks(context) }

    var hwSubject by remember { mutableStateOf<String?>(null) }
    var editingHomework by remember { mutableStateOf<HomeworkItem?>(null) }
    var showConstructorDialog by remember { mutableStateOf(false) }
    var showGroupPickerSheet by remember { mutableStateOf(false) }

    var activeStoryGroup by remember { mutableStateOf<StoryGroup?>(null) }
    var storyUpdateTrigger by remember { mutableIntStateOf(0) }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showLiveFocus by remember { mutableStateOf(false) }

    val tutorialStory = remember(storyUpdateTrigger) { PulseStoryAnalyzer.getGreetingStory(context) }
    val holidayStory = remember(storyUpdateTrigger) { PulseStoryAnalyzer.getHolidayStory(context) }
    val weeklyStory = remember(state.selectedDate, homeworkList, storyUpdateTrigger) {
        PulseStoryAnalyzer.generateWeeklyStories(context, viewModel.fullScheduleHistory, homeworkList)
    }

    val availableStories = remember(tutorialStory, holidayStory, weeklyStory) {
        listOfNotNull(tutorialStory, holidayStory, weeklyStory).distinctBy { it.id }
    }

    val dailyHw = remember(state.lessons, homeworkList, state.selectedDate) {
        val cleanSubjectsOnDate = state.lessons.map { it.cleanSubject.lowercase() }.toSet()
        homeworkList.filter { hw ->
            if (hw.targetPairs == null) {
                hw.dueDate == state.selectedDate
            } else {
                val cleanHwSubject = hw.subject.substringBefore("(").trim().lowercase()
                cleanHwSubject in cleanSubjectsOnDate
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.selectDate(LocalDate.now())
        viewModel.refreshData()
        reloadHomeworks()

        val fetchedUpdate = UpdateChecker.checkForUpdates(context)
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val ignoredVersion = prefs.getInt("ignored_update_version", -1)

        if (fetchedUpdate?.hasUpdate == true && fetchedUpdate.versionCode > ignoredVersion) {
            updateInfo = fetchedUpdate
        }

        if (!prefs.getBoolean("tutorial_auto_opened", false)) {
            activeStoryGroup = tutorialStory
            prefs.edit().putBoolean("tutorial_auto_opened", true).apply()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(horizontal = 16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            HeaderSection(
                navController = navController,
                userName = state.userName,
                currentGroup = state.previewGroup ?: state.userGroup,
                isPreview = state.previewGroup != null,
                onGroupClick = { showGroupPickerSheet = true },
                onShareClick = { showConstructorDialog = true }
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }

        if (availableStories.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(availableStories, key = { it.id }) { group ->
                        StoryAvatar(group = group) { activeStoryGroup = group }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        item {
            LiveScheduleEngine(
                fullSchedule = viewModel.fullScheduleHistory,
                selectedDate = state.selectedDate,
                onOpenFocus = { showLiveFocus = true }
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }

        item {
            WeekCalendarCard(
                selectedDate = state.selectedDate,
                homeworks = homeworkList,
                onDateSelected = { viewModel.selectDate(it) }
            )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }

        // Баннер режима сверки расписания
        if (state.previewGroup != null) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(PrimaryPurple.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CompareArrows,
                                    contentDescription = null,
                                    tint = PrimaryPurple,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Группа ${state.previewGroup}",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(PrimaryPurple, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text("СВЕРКА", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    text = "Ваша группа: ${state.userGroup} (настройки не изменены)",
                                    color = TextGray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        TextButton(
                            onClick = { viewModel.setPreviewGroup(null) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Сбросить", color = PrimaryPurple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        val lastLoadedDate = viewModel.fullScheduleHistory.keys.maxOrNull()
        if (lastLoadedDate != null && state.selectedDate != lastLoadedDate && state.selectedDate != LocalDate.now()) {
            item {
                OutlinedButton(
                    onClick = { viewModel.selectDate(lastLoadedDate) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryPurple),
                    border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.EventAvailable, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Крайний загруженный день: ${lastLoadedDate.format(DateTimeFormatter.ofPattern("d MMMM", Locale("ru")))}")
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        } else {
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Задания на день", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    if (dailyHw.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .background(PrimaryPurple.copy(alpha = 0.25f), CircleShape)
                                .border(1.dp, PrimaryPurple.copy(alpha = 0.5f), CircleShape)
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text(dailyHw.size.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                TextButton(
                    onClick = { navController.navigate("homework") },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Все ДЗ", color = PrimaryPurple, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (dailyHw.isNotEmpty()) {
            items(dailyHw, key = { it.id }) { hw ->
                val pairsLeft = remember(hw, state.lessons) { viewModel.getRemainingPairs(hw) }

                HomeworkInteractiveCard(
                    homework = hw,
                    pairsLeft = pairsLeft,
                    onClick = { editingHomework = hw },
                    onToggle = {
                        HomeworkManager.saveHomework(context, hw.copy(isCompleted = !hw.isCompleted))
                        reloadHomeworks()
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        // Блок расписания с поддержкой горизонтального свайпа между днями
        item {
            var dragDistance by remember { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(state.selectedDate) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragDistance = 0f },
                            onDragEnd = {
                                val swipeThreshold = 55.dp.toPx()
                                if (dragDistance < -swipeThreshold) {
                                    // Свайп влево -> следующий день
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.selectDate(state.selectedDate.plusDays(1))
                                } else if (dragDistance > swipeThreshold) {
                                    // Свайп вправо -> предыдущий день
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.selectDate(state.selectedDate.minusDays(1))
                                }
                                dragDistance = 0f
                            },
                            onDragCancel = { dragDistance = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                dragDistance += dragAmount
                            }
                        )
                    }
            ) {
                AnimatedContent(
                    targetState = state.selectedDate,
                    transitionSpec = {
                        if (targetState.isAfter(initialState)) {
                            (slideInHorizontally(tween(320)) { it / 2 } + fadeIn(tween(320))) togetherWith
                                    (slideOutHorizontally(tween(320)) { -it / 2 } + fadeOut(tween(320)))
                        } else {
                            (slideInHorizontally(tween(320)) { -it / 2 } + fadeIn(tween(320))) togetherWith
                                    (slideOutHorizontally(tween(320)) { it / 2 } + fadeOut(tween(320)))
                        }
                    },
                    label = "schedule_day_swipe"
                ) { _ ->
                    if (state.isLoading) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = PrimaryPurple)
                        }
                    } else if (state.lessons.isEmpty()) {
                        EmptyScheduleNeonCard()
                    } else {
                        ScheduleTimelineList(
                            lessons = state.lessons,
                            selectedDate = state.selectedDate,
                            onAddHomework = { hwSubject = it }
                        )
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(32.dp)) }
    }

    if (showGroupPickerSheet) {
        CompareGroupBottomSheet(
            myGroup = state.userGroup,
            activeGroup = state.previewGroup,
            availableGroups = state.availableGroups,
            onGroupSelected = { chosen -> viewModel.setPreviewGroup(chosen) },
            onDismiss = { showGroupPickerSheet = false }
        )
    }

    if (showLiveFocus) {
        LiveFocusDashboardDialog(
            fullSchedule = viewModel.fullScheduleHistory,
            onDismiss = { showLiveFocus = false }
        )
    }

    if (activeStoryGroup != null) {
        StoriesViewer(
            group = activeStoryGroup!!,
            onClose = {
                activeStoryGroup = null
                storyUpdateTrigger++
            },
            onSharePage = { page ->
                ScheduleImageGenerator.generateStoryShareBitmap(context, page)
            }
        )
    }

    if (showConstructorDialog) {
        ScheduleConstructorDialog(
            date = state.selectedDate,
            lessons = state.lessons,
            onDismiss = { showConstructorDialog = false }
        )
    }

    if (hwSubject != null) {
        HomeworkDialog(
            subject = hwSubject!!, initialHomework = null, viewModel = viewModel,
            onDismiss = { hwSubject = null },
            onSaved = { savedDate -> reloadHomeworks(); viewModel.selectDate(savedDate); hwSubject = null }
        )
    }

    if (editingHomework != null) {
        HomeworkDialog(
            subject = editingHomework!!.subject, initialHomework = editingHomework, viewModel = viewModel,
            onDismiss = { editingHomework = null },
            onSaved = { savedDate -> reloadHomeworks(); viewModel.selectDate(savedDate); editingHomework = null }
        )
    }

    val safeUpdateInfo = updateInfo
    if (safeUpdateInfo?.hasUpdate == true) {
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            containerColor = SurfaceDark,
            title = {
                Text("Доступна новая версия ${safeUpdateInfo.versionName}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(safeUpdateInfo.message, color = TextGray, fontSize = 14.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        UpdateChecker.openDownloadLink(context, safeUpdateInfo.downloadUrl)
                        updateInfo = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
                ) { Text("Обновить", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putInt("ignored_update_version", safeUpdateInfo.versionCode).apply()
                    updateInfo = null
                }) { Text("Позже", color = TextGray) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareGroupBottomSheet(
    myGroup: String,
    activeGroup: String?,
    availableGroups: List<String>,
    onGroupSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filteredGroups = remember(searchQuery, availableGroups) {
        if (searchQuery.isBlank()) availableGroups
        else availableGroups.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextGray.copy(alpha = 0.5f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Сравнить расписание",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Временный просмотр, настройки не меняются",
                        color = TextGray,
                        fontSize = 12.sp
                    )
                }
                if (activeGroup != null) {
                    TextButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onGroupSelected(null)
                        onDismiss()
                    }) {
                        Text("Вернуть мою", color = PrimaryPurple, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Номер группы (например, 252)", color = TextGray.copy(alpha = 0.7f), fontSize = 14.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить", tint = TextGray, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = PrimaryPurple,
                    unfocusedBorderColor = PrimaryPurple.copy(alpha = 0.25f),
                    focusedContainerColor = BgDark,
                    unfocusedContainerColor = BgDark
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (searchQuery.isNotBlank() && !availableGroups.any { it.equals(searchQuery.trim(), ignoreCase = true) }) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onGroupSelected(searchQuery.trim().uppercase())
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Сверить с группой «${searchQuery.trim().uppercase()}»")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (activeGroup == null) PrimaryPurple.copy(alpha = 0.2f) else BgDark)
                    .border(1.dp, if (activeGroup == null) PrimaryPurple else PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onGroupSelected(null)
                        onDismiss()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(if (activeGroup == null) PrimaryPurple else Color(0xFF282830), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Моя группа ($myGroup)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Основное расписание по умолчанию", color = TextGray, fontSize = 11.sp)
                }
                if (activeGroup == null) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Группы из расписания:",
                color = TextGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            val chunked = remember(filteredGroups) { filteredGroups.chunked(4) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filteredGroups.isEmpty()) {
                    Text(
                        text = "Группы не найдены",
                        color = TextGray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    chunked.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { g ->
                                val isSelected = activeGroup.equals(g, ignoreCase = true)
                                val isMain = myGroup.equals(g, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) PrimaryPurple else BgDark)
                                        .border(
                                            1.dp,
                                            if (isSelected) PrimaryPurple else PrimaryPurple.copy(alpha = 0.2f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (isMain) onGroupSelected(null) else onGroupSelected(g)
                                            onDismiss()
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = g,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.9f),
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected || isMain) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                            for (k in 0 until (4 - rowItems.size)) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyScheduleNeonCard() {
    val transition = rememberInfiniteTransition(label = "wait")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.HourglassEmpty,
            contentDescription = null,
            tint = PrimaryPurple.copy(alpha = alpha),
            modifier = Modifier.size(36.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Ожидание данных...",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Расписание пока не опубликовано",
            color = TextGray,
            fontSize = 13.sp
        )
    }
}

sealed class LiveStatus {
    data class Ongoing(val lesson: LessonPair, val passedMins: Int, val totalMins: Int, val progress: Float) : LiveStatus()
    data class Break(val nextLesson: LessonPair, val minsUntil: Int) : LiveStatus()
    data class BeforeStart(val firstLesson: LessonPair, val minsUntil: Int) : LiveStatus()
    data class FinishedToday(val nextDate: LocalDate?, val nextLesson: LessonPair?) : LiveStatus()
    object PastDay : LiveStatus()
    data class FutureDay(val firstLesson: LessonPair?) : LiveStatus()
}

@Composable
fun LiveScheduleEngine(
    fullSchedule: Map<LocalDate, List<LessonPair>>,
    selectedDate: LocalDate,
    onOpenFocus: () -> Unit
) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    var currentDate by remember { mutableStateOf(LocalDate.now()) }

    val isToday = selectedDate == currentDate

    // Запускаем таймер обновления секунд только если выбран сегодняшний день
    LaunchedEffect(isToday) {
        if (!isToday) return@LaunchedEffect
        while (true) {
            delay(1000L)
            currentTime = LocalTime.now()
            currentDate = LocalDate.now()
        }
    }

    // Предвычисляем отсортированные уроки на сегодня (только при изменении расписания/даты)
    val todayLessons = remember(fullSchedule, currentDate) {
        fullSchedule[currentDate]?.sortedBy { it.startLocalTime ?: LocalTime.MIN } ?: emptyList()
    }

    // Предвычисляем следующий будущий урок один раз, а не каждую секунду
    val nextFutureLesson = remember(fullSchedule, currentDate) {
        val futureDates = fullSchedule.keys.filter { it.isAfter(currentDate) }.sorted()
        for (d in futureDates) {
            val lessons = fullSchedule[d]?.sortedBy { it.startLocalTime ?: LocalTime.MIN }
            if (!lessons.isNullOrEmpty()) return@remember Pair(d, lessons.first())
        }
        null
    }

    val status: LiveStatus = remember(currentTime, currentDate, selectedDate, todayLessons, nextFutureLesson) {
        if (selectedDate.isBefore(currentDate)) return@remember LiveStatus.PastDay
        if (selectedDate.isAfter(currentDate)) {
            val futureLessons = fullSchedule[selectedDate]?.sortedBy { it.startLocalTime ?: LocalTime.MIN }
            return@remember LiveStatus.FutureDay(futureLessons?.firstOrNull())
        }

        if (todayLessons.isEmpty()) {
            return@remember LiveStatus.FinishedToday(nextFutureLesson?.first, nextFutureLesson?.second)
        }

        val firstStart = todayLessons.first().startLocalTime ?: LocalTime.of(8, 30)
        val lastEnd = todayLessons.last().endLocalTime ?: LocalTime.of(21, 0)

        if (currentTime.isBefore(firstStart)) {
            val minsUntil = ChronoUnit.MINUTES.between(currentTime, firstStart).toInt()
            return@remember LiveStatus.BeforeStart(todayLessons.first(), minsUntil)
        }

        if (currentTime.isAfter(lastEnd) || currentTime == lastEnd) {
            return@remember LiveStatus.FinishedToday(nextFutureLesson?.first, nextFutureLesson?.second)
        }

        for (i in todayLessons.indices) {
            val lesson = todayLessons[i]
            val start = lesson.startLocalTime ?: continue
            val end = lesson.endLocalTime ?: continue

            if ((currentTime.isAfter(start) || currentTime == start) && currentTime.isBefore(end)) {
                val totalSecs = ChronoUnit.SECONDS.between(start, end).toFloat().coerceAtLeast(1f)
                val passedSecs = ChronoUnit.SECONDS.between(start, currentTime).toFloat().coerceAtLeast(0f)
                val progress = (passedSecs / totalSecs).coerceIn(0f, 1f)
                val passedMins = ChronoUnit.MINUTES.between(start, currentTime).toInt()
                val totalMins = ChronoUnit.MINUTES.between(start, end).toInt()
                return@remember LiveStatus.Ongoing(lesson, passedMins, totalMins, progress)
            }

            if (i < todayLessons.size - 1) {
                val nextLesson = todayLessons[i + 1]
                val nextStart = nextLesson.startLocalTime ?: continue
                if ((currentTime.isAfter(end) || currentTime == end) && currentTime.isBefore(nextStart)) {
                    val minsUntil = ChronoUnit.MINUTES.between(currentTime, nextStart).toInt() + 1
                    return@remember LiveStatus.Break(nextLesson, minsUntil)
                }
            }
        }

        LiveStatus.FinishedToday(nextFutureLesson?.first, nextFutureLesson?.second)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onOpenFocus() }
    ) {
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (scaleIn(tween(500, easing = LinearOutSlowInEasing)) + fadeIn(tween(500))) togetherWith
                        (scaleOut(tween(500, easing = FastOutLinearInEasing)) + fadeOut(tween(500)))
            },
            contentKey = { state ->
                when (state) {
                    is LiveStatus.Ongoing -> "Ongoing_${state.lesson.startTime}"
                    is LiveStatus.Break -> "Break_${state.nextLesson.startTime}"
                    is LiveStatus.BeforeStart -> "BeforeStart"
                    is LiveStatus.FinishedToday -> "Finished"
                    is LiveStatus.FutureDay -> "Future"
                    is LiveStatus.PastDay -> "Past"
                }
            },
            label = "live_status"
        ) { currentStatus ->
            when (currentStatus) {
                is LiveStatus.Ongoing -> StatusActiveCard(currentStatus.lesson, currentStatus.passedMins, currentStatus.totalMins, currentStatus.progress)
                is LiveStatus.Break -> StatusNextCard(currentStatus.nextLesson, currentStatus.minsUntil, "ПЕРЕМЕНА")
                is LiveStatus.BeforeStart -> StatusNextCard(currentStatus.firstLesson, currentStatus.minsUntil, "ДО НАЧАЛА ЗАНЯТИЙ")
                is LiveStatus.FinishedToday -> FinishedOrFutureCard(currentStatus.nextDate, currentStatus.nextLesson, isToday = true)
                is LiveStatus.FutureDay -> FinishedOrFutureCard(selectedDate, currentStatus.firstLesson, isToday = false)
                is LiveStatus.PastDay -> CalmStatusCard("День прошел", "Расписание за этот день не активно.", TextGray, Icons.Default.History)
            }
        }
    }
}

@Composable
fun LiveCardWaveBackground(progress: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "card_wave")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "phase"
    )
    val phase2 by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(5500, easing = LinearEasing), RepeatMode.Restart), label = "phase2"
    )

    val currentAccent = PrimaryPurple
    val currentSurface = SurfaceDark

    val backGradientColors = remember(currentAccent, currentSurface) {
        listOf(currentAccent.copy(alpha = 0.55f), currentSurface.copy(alpha = 0.8f))
    }
    val frontGradientColors = remember(currentAccent) {
        listOf(currentAccent.copy(alpha = 0.95f), currentAccent.copy(alpha = 0.55f))
    }

    Canvas(modifier = modifier) {
        val h = size.height
        val w = size.width

        val safeProgress = progress.coerceIn(0.18f, 0.88f)
        val waterLevel = h - (h * safeProgress)
        val step = 18f

        val backPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, waterLevel + 12f)
            var x = 0f
            while (x <= w) {
                lineTo(x, waterLevel + 12f + 16f * kotlin.math.sin((x / w) * 2 * Math.PI + phase2).toFloat())
                x += step
            }
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            backPath,
            brush = Brush.verticalGradient(backGradientColors)
        )

        val frontPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, waterLevel)
            var x = 0f
            while (x <= w) {
                lineTo(x, waterLevel + 14f * kotlin.math.sin((x / w) * 2.5 * Math.PI - phase).toFloat())
                x += step
            }
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            frontPath,
            brush = Brush.verticalGradient(frontGradientColors)
        )
    }
}

@Composable
fun StatusActiveCard(lesson: LessonPair, passedMins: Int, totalMins: Int, progress: Float) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().height(180.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.35f))
    ) {
        Box(modifier = Modifier.fillMaxSize().background(SurfaceDark)) {
            LiveCardWaveBackground(progress = progress, modifier = Modifier.fillMaxSize())

            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("ИДЁТ ПАРА", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                    Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(lesson.subject.substringBefore(" ("), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Ауд. ${lesson.room} • ${lesson.teacher}", color = Color.White.copy(alpha = 0.95f), fontSize = 13.sp, maxLines = 1)

                Spacer(modifier = Modifier.weight(1f))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("Прошло: $passedMins мин", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                    Text("Осталось: ${totalMins - passedMins} мин", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StatusNextCard(lesson: LessonPair, minsUntil: Int, label: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().height(120.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.35f))
    ) {
        Box(modifier = Modifier.fillMaxSize().background(SurfaceDark)) {
            LiveCardWaveBackground(progress = 0.2f, modifier = Modifier.fillMaxSize())

            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(PrimaryPurple.copy(alpha = 0.25f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                    Text("Через $minsUntil мин", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(lesson.subject.substringBefore(" ("), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("Ауд. ${lesson.room}", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun FinishedOrFutureCard(nextDate: LocalDate?, nextLesson: LessonPair?, isToday: Boolean) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().height(120.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.35f))
    ) {
        Box(modifier = Modifier.fillMaxSize().background(SurfaceDark)) {
            LiveCardWaveBackground(progress = 0.25f, modifier = Modifier.fillMaxSize())

            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                if (nextDate != null && nextLesson != null) {
                    val dateLabel = if (nextDate == LocalDate.now().plusDays(1)) "ЗАВТРА"
                    else nextDate.format(DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))).uppercase()

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(PrimaryPurple.copy(alpha = 0.25f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(if (isToday) "СЛЕДУЮЩАЯ ПАРА" else "ПЕРВАЯ ПАРА", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("$dateLabel В ${nextLesson.startTime}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(nextLesson.subject.substringBefore(" ("), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("Ауд. ${nextLesson.room}", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00C853), modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Расписание чистое", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Будущих пар пока не найдено.", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalmStatusCard(title: String, subtitle: String, tintColor: Color, icon: ImageVector) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.2f))
    ) {
        Row(modifier = Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).background(tintColor.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tintColor)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = TextGray, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun LiveFocusDashboardDialog(
    fullSchedule: Map<LocalDate, List<LessonPair>>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(Unit) {
        var parent = view.parent
        var targetWindow: android.view.Window? = null
        while (parent != null) {
            if (parent is DialogWindowProvider) {
                targetWindow = parent.window
                break
            }
            parent = parent.parent
        }
        if (targetWindow == null) {
            targetWindow = (context as? Activity)?.window
        }

        targetWindow?.let { win ->
            win.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val insetsController = WindowCompat.getInsetsController(win, win.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.statusBars())
        }

        onDispose {
            targetWindow?.let { win ->
                win.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val insetsController = WindowCompat.getInsetsController(win, win.decorView)
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    var nowTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            nowTime = LocalTime.now()
        }
    }

    val driftTransition = rememberInfiniteTransition(label = "drift")
    val driftX by driftTransition.animateFloat(
        initialValue = -6f, targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(45_000, easing = LinearEasing), RepeatMode.Reverse), label = "dx"
    )
    val driftY by driftTransition.animateFloat(
        initialValue = -4f, targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Reverse), label = "dy"
    )

    var isEcoMode by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(if (isEcoMode) 0.35f else 1f, label = "alpha")

    val today = LocalDate.now()
    val todayLessons = remember(today, fullSchedule) {
        fullSchedule[today]?.sortedBy { it.startLocalTime ?: LocalTime.MIN } ?: emptyList()
    }

    var currentLesson by remember { mutableStateOf<LessonPair?>(null) }
    var nextLesson by remember { mutableStateOf<LessonPair?>(null) }
    var isBreak by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableLongStateOf(0L) }
    var totalSeconds by remember { mutableLongStateOf(1L) }

    LaunchedEffect(nowTime, todayLessons) {
        currentLesson = null
        nextLesson = null
        isBreak = false

        for (i in todayLessons.indices) {
            val lesson = todayLessons[i]
            val start = lesson.startLocalTime ?: continue
            val end = lesson.endLocalTime ?: continue

            if ((nowTime.isAfter(start) || nowTime == start) && nowTime.isBefore(end)) {
                currentLesson = lesson
                nextLesson = todayLessons.getOrNull(i + 1)
                isBreak = false
                remainingSeconds = ChronoUnit.SECONDS.between(nowTime, end)
                totalSeconds = ChronoUnit.SECONDS.between(start, end).coerceAtLeast(1L)
                return@LaunchedEffect
            }

            if (i < todayLessons.size - 1) {
                val nLesson = todayLessons[i + 1]
                val nStart = nLesson.startLocalTime ?: continue
                if ((nowTime.isAfter(end) || nowTime == end) && nowTime.isBefore(nStart)) {
                    isBreak = true
                    nextLesson = nLesson
                    remainingSeconds = ChronoUnit.SECONDS.between(nowTime, nStart)
                    totalSeconds = ChronoUnit.SECONDS.between(end, nStart).coerceAtLeast(1L)
                    return@LaunchedEffect
                }
            }
        }
    }

    val progress = remember(remainingSeconds, totalSeconds) {
        (1f - (remainingSeconds.toFloat() / totalSeconds.toFloat())).coerceIn(0f, 1f)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val isLandscape = maxWidth > maxHeight

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .graphicsLayer {
                        translationX = driftX
                        translationY = driftY
                        alpha = contentAlpha
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = nowTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                        color = TextGray,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { isEcoMode = !isEcoMode },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF141418), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isEcoMode) Icons.Default.BrightnessMedium else Icons.Default.Bedtime,
                                contentDescription = "Eco",
                                tint = if (isEcoMode) PrimaryPurple else TextGray,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF141418), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val mins = remainingSeconds / 60
                            val secs = remainingSeconds % 60
                            Text(
                                text = String.format("%02d:%02d", mins, secs),
                                color = Color.White,
                                fontSize = 74.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-2).sp
                            )

                            Text(
                                text = if (isBreak) "до начала пары" else "до звонка",
                                color = TextGray,
                                fontSize = 13.sp
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.65f)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF1E1E26))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progress)
                                        .fillMaxHeight()
                                        .background(if (isBreak) Color(0xFF00FF66) else PrimaryPurple)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(120.dp)
                                .background(Color(0xFF1A1A22))
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 28.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (currentLesson != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PrimaryPurple.copy(alpha = 0.25f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${currentLesson!!.pairNumber} ПАРА",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = currentLesson!!.subject.substringBefore(" ("),
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "Ауд. ${currentLesson!!.room} • ${currentLesson!!.teacher}",
                                    color = TextGray,
                                    fontSize = 14.sp
                                )

                                if (nextLesson != null) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Далее: ${nextLesson!!.subject.substringBefore(" (")} (Ауд. ${nextLesson!!.room})",
                                        color = Color(0xFF6C6A7E),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            } else if (isBreak && nextLesson != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF00FF66).copy(alpha = 0.15f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "ПЕРЕМЕНА",
                                        color = Color(0xFF00FF66),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = nextLesson!!.subject.substringBefore(" ("),
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "Следующая: Ауд. ${nextLesson!!.room} • в ${nextLesson!!.startTime}",
                                    color = TextGray,
                                    fontSize = 14.sp
                                )
                            } else {
                                Text("Пар нет", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("Можно отдыхать", color = TextGray, fontSize = 13.sp)
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (currentLesson != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PrimaryPurple.copy(alpha = 0.25f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${currentLesson!!.pairNumber} ПАРА",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = currentLesson!!.subject.substringBefore(" ("),
                                    color = Color.White,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "Ауд. ${currentLesson!!.room} • ${currentLesson!!.teacher}",
                                    color = TextGray,
                                    fontSize = 14.sp
                                )
                            } else if (isBreak && nextLesson != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF00FF66).copy(alpha = 0.15f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "ПЕРЕМЕНА",
                                        color = Color(0xFF00FF66),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = nextLesson!!.subject.substringBefore(" ("),
                                    color = Color.White,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "Ауд. ${nextLesson!!.room} • звонок в ${nextLesson!!.startTime}",
                                    color = TextGray,
                                    fontSize = 14.sp
                                )
                            } else {
                                Text("Пар нет", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("Отдыхайте", color = TextGray, fontSize = 14.sp)
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val mins = remainingSeconds / 60
                            val secs = remainingSeconds % 60
                            Text(
                                text = String.format("%02d:%02d", mins, secs),
                                color = Color.White,
                                fontSize = 80.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-2).sp
                            )

                            Text(
                                text = if (isBreak) "до начала пары" else "до звонка",
                                color = TextGray,
                                fontSize = 14.sp
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.55f)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF1E1E26))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progress)
                                        .fillMaxHeight()
                                        .background(if (isBreak) Color(0xFF00FF66) else PrimaryPurple)
                                )
                            }
                        }

                        if (nextLesson != null && !isBreak) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceDark)
                                    .border(1.dp, PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text("Далее: ", color = TextGray, fontSize = 13.sp)
                                Text(
                                    text = "${nextLesson!!.subject.substringBefore(" (")} (Ауд. ${nextLesson!!.room})",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.height(40.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleConstructorDialog(
    date: LocalDate,
    lessons: List<LessonPair>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val themes = remember {
        listOf(
            "Neon МГКАП" to CustomTheme(
                0xFF101014.toInt(),
                0xFFFFFFFF.toInt(),
                0xFF9090A0.toInt(),
                0xFF6C48E1.toInt(),
                0xFF1C1C22.toInt()
            ),
            "Light Clean" to CustomTheme(
                0xFFF5F5F7.toInt(),
                0xFF1D1D1F.toInt(),
                0xFF86868B.toInt(),
                0xFF007AFF.toInt(),
                0xFFFFFFFF.toInt()
            ),
            "Cyber Matrix" to CustomTheme(
                0xFF0D1117.toInt(),
                0xFF00FF41.toInt(),
                0xFF8A9A5B.toInt(),
                0xFF00FF41.toInt(),
                0xFF161B22.toInt()
            ),
            "Sunset" to CustomTheme(
                0xFF2A0845.toInt(),
                0xFFFFF1E6.toInt(),
                0xFFFFB4A2.toInt(),
                0xFF3E1F5D.toInt(),
                0xFFFF6D00.toInt()
            )
        )
    }

    var selectedTheme by remember { mutableStateOf(themes.first()) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(selectedTheme, date, lessons) {
        withContext(Dispatchers.Default) {
            val bmp = ScheduleImageGenerator.generateBitmap(date, lessons, selectedTheme.second, null)
            previewBitmap = bmp
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth(0.9f).padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Конструктор расписания", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.65f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = "Предпросмотр",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = PrimaryPurple)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text("Стиль:", color = TextGray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(themes) { themePair ->
                        val isSelected = selectedTheme == themePair
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(themePair.second.bg))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) PrimaryPurple else Color(themePair.second.accent),
                                    shape = CircleShape
                                )
                                .clickable { selectedTheme = themePair },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(themePair.second.textPrimary))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Отмена", color = TextGray)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            previewBitmap?.let {
                                ScheduleImageGenerator.shareBitmap(context, it)
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Отправить")
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleTimelineList(lessons: List<LessonPair>, selectedDate: LocalDate, onAddHomework: (String) -> Unit) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while(true) {
            delay(60000L)
            currentTime = LocalTime.now()
        }
    }

    Column {
        lessons.forEachIndexed { index, lesson ->
            val isThisLessonActive by remember(currentTime, selectedDate, lesson) {
                derivedStateOf {
                    val s = lesson.startLocalTime
                    val e = lesson.endLocalTime
                    selectedDate == LocalDate.now() && s != null && e != null &&
                            (currentTime.isAfter(s) || currentTime == s) &&
                            currentTime.isBefore(e)
                }
            }

            TimelineItem(
                lesson = lesson,
                isLast = index == lessons.size - 1,
                isActive = isThisLessonActive,
                onAddHomework = onAddHomework
            )
        }
    }
}

@Composable
fun HomeworkInteractiveCard(homework: HomeworkItem, pairsLeft: Int?, onClick: () -> Unit, onToggle: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = if (homework.isCompleted) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = "Выполнено",
                    tint = if (homework.isCompleted) Color(0xFF00C853) else TextGray,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = homework.subject,
                    color = if (homework.isCompleted) TextGray else Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (homework.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = homework.task,
                    color = if (homework.isCompleted) Color(0xFF555560) else TextGray,
                    fontSize = 14.sp,
                    textDecoration = if (homework.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )

                if (!homework.isCompleted) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pairsLeft != null) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            if (pairsLeft > 1) {
                                Text("Осталось пар: $pairsLeft", color = PrimaryPurple, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            } else if (pairsLeft == 1) {
                                Text("Сдавать на следующей паре", color = Color(0xFFFF9100), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            } else {
                                Text("Нужно было сдать!", color = Color(0xFFF44336), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Сдавать: ${homework.dueDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}", color = PrimaryPurple, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Icon(Icons.Default.Edit, contentDescription = "Редактировать", tint = TextGray, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDialog(
    subject: String,
    initialHomework: HomeworkItem?,
    viewModel: ScheduleViewModel,
    onDismiss: () -> Unit,
    onSaved: (LocalDate) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var taskText by remember(initialHomework) { mutableStateOf(initialHomework?.task ?: "") }
    var notifyBefore by remember(initialHomework) { mutableStateOf(initialHomework?.notifyDayBefore ?: true) }

    var selectionMode by remember(initialHomework) { mutableIntStateOf(if (initialHomework?.targetPairs == null && initialHomework != null) 1 else 0) }
    var lessonOffset by remember(initialHomework) { mutableIntStateOf(if (initialHomework?.targetPairs != null) (initialHomework.targetPairs - 1).coerceAtLeast(0) else 0) }

    val cleanSubject = remember(subject) { subject.substringBefore("(").trim() }

    val calculatedDate = remember(cleanSubject, lessonOffset, viewModel.fullScheduleHistory) {
        viewModel.findDateForNextSubject(cleanSubject, lessonOffset)
    }

    var manualDate by remember(initialHomework) { mutableStateOf(initialHomework?.dueDate ?: LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMMM (EEE)", Locale("ru")) }
    val fullDateFormatter = remember { DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale("ru")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text(if (initialHomework == null) "Добавить задание" else "Редактировать", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(cleanSubject, color = PrimaryPurple, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = taskText,
                    onValueChange = { taskText = it },
                    label = { Text("Что задали?", color = TextGray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = PrimaryPurple, unfocusedBorderColor = TextGray
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Когда сдавать:", color = TextGray, fontSize = 12.sp)

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Button(
                        onClick = {
                            selectionMode = 0
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (selectionMode == 0) PrimaryPurple else BgDark),
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("По парам", fontSize = 12.sp) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            selectionMode = 1
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (selectionMode == 1) PrimaryPurple else BgDark),
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Календарь", fontSize = 12.sp) }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (selectionMode == 0) {
                    val targetCount = lessonOffset + 1
                    val label = when (lessonOffset) {
                        0 -> "На следующую пару (1 пара)"
                        1 -> "Через 1 пару (2-я пара)"
                        else -> "Через $lessonOffset пар ($targetCount-я пара)"
                    }

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = BgDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            IconButton(onClick = {
                                if (lessonOffset > 0) {
                                    lessonOffset--
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }) {
                                Icon(
                                    Icons.Default.RemoveCircleOutline,
                                    contentDescription = "-",
                                    tint = if (lessonOffset > 0) PrimaryPurple else Color(0xFF555560)
                                )
                            }

                            Text(
                                text = label,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )

                            IconButton(onClick = {
                                if (lessonOffset < 15) {
                                    lessonOffset++
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }) {
                                Icon(
                                    Icons.Default.AddCircleOutline,
                                    contentDescription = "+",
                                    tint = PrimaryPurple
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (calculatedDate != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.EventAvailable,
                                contentDescription = null,
                                tint = Color(0xFF00C853),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Дата в расписании: ${calculatedDate.format(dateFormatter)}",
                                color = Color(0xFF00C853),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = Color(0xFFFF9100),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Счетчик включен: дата подставится по заменам",
                                color = Color(0xFFFF9100),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Счетчик уменьшается сам по мере прохождения этой пары в колледже.",
                        color = TextGray,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, PrimaryPurple)
                    ) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(manualDate.format(fullDateFormatter))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = notifyBefore,
                        onCheckedChange = { notifyBefore = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = PrimaryPurple)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Напомнить за день", color = Color.White, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            val isByPairs = selectionMode == 0
            Button(
                onClick = {
                    val finalSafeDate = if (isByPairs) (calculatedDate ?: LocalDate.now()) else manualDate
                    val hw = HomeworkItem(
                        id = initialHomework?.id ?: UUID.randomUUID().toString(),
                        subject = cleanSubject,
                        task = taskText.trim(),
                        dueDate = finalSafeDate,
                        notifyDayBefore = notifyBefore,
                        isCompleted = initialHomework?.isCompleted ?: false,
                        assignDate = initialHomework?.assignDate ?: LocalDate.now(),
                        targetPairs = if (isByPairs) (lessonOffset + 1) else null
                    )
                    HomeworkManager.saveHomework(context, hw)
                    onSaved(finalSafeDate)
                    onDismiss()
                },
                enabled = taskText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
            ) { Text("Сохранить", color = Color.White, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (initialHomework != null) {
                    TextButton(onClick = {
                        HomeworkManager.deleteHomework(context, initialHomework.id)
                        onSaved(calculatedDate ?: LocalDate.now())
                        onDismiss()
                    }) { Text("Удалить", color = Color(0xFFF44336)) }
                }
                TextButton(onClick = onDismiss) { Text("Отмена", color = TextGray) }
            }
        }
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = manualDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        )

        MaterialTheme(
            colorScheme = darkColorScheme(
                surface = SurfaceDark, onSurface = Color.White,
                primary = PrimaryPurple, onPrimary = Color.White,
                surfaceVariant = SurfaceDark, onSurfaceVariant = Color.White,
                background = BgDark, onBackground = Color.White
            )
        ) {
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            manualDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        }
                        showDatePicker = false
                    }) { Text("Выбрать", color = PrimaryPurple, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Отмена", color = TextGray) } },
                colors = DatePickerDefaults.colors(containerColor = SurfaceDark)
            ) {
                DatePicker(
                    state = datePickerState,
                    colors = DatePickerDefaults.colors(
                        containerColor = SurfaceDark,
                        titleContentColor = PrimaryPurple, headlineContentColor = Color.White,
                        weekdayContentColor = TextGray, subheadContentColor = TextGray,
                        yearContentColor = Color.White, currentYearContentColor = PrimaryPurple,
                        selectedYearContentColor = Color.White, selectedYearContainerColor = PrimaryPurple,
                        dayContentColor = Color.White, selectedDayContentColor = Color.White,
                        selectedDayContainerColor = PrimaryPurple, todayContentColor = PrimaryPurple,
                        todayDateBorderColor = PrimaryPurple
                    )
                )
            }
        }
    }
}

@Composable
fun WeekCalendarCard(selectedDate: LocalDate, homeworks: List<HomeworkItem>, onDateSelected: (LocalDate) -> Unit) {
    val monthFormatter = remember { DateTimeFormatter.ofPattern("LLLL", Locale("ru")) }
    val haptic = LocalHapticFeedback.current
    var weekDragDistance by remember { mutableFloatStateOf(0f) }

    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(selectedDate) {
                detectHorizontalDragGestures(
                    onDragStart = { weekDragDistance = 0f },
                    onDragEnd = {
                        val swipeThreshold = 55.dp.toPx()
                        if (weekDragDistance < -swipeThreshold) {
                            // Свайп влево -> следующая неделя
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDateSelected(selectedDate.plusDays(7))
                        } else if (weekDragDistance > swipeThreshold) {
                            // Свайп вправо -> предыдущая неделя
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDateSelected(selectedDate.minusDays(7))
                        }
                        weekDragDistance = 0f
                    },
                    onDragCancel = { weekDragDistance = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        weekDragDistance += dragAmount
                    }
                )
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            PrimaryPurple.copy(alpha = 0.48f),
                            SurfaceDark.copy(alpha = 0.95f)
                        )
                    )
                )
                .padding(18.dp)
        ) {
            val monthName = selectedDate.format(monthFormatter).replaceFirstChar { it.uppercase() }
            val year = selectedDate.year

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onDateSelected(selectedDate.minusDays(7)) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null, tint = Color.White)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$monthName $year", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Учебная неделя", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                IconButton(onClick = { onDateSelected(selectedDate.plusDays(7)) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val startOfWeek = selectedDate.minusDays(selectedDate.dayOfWeek.value.toLong() - 1)
            val weekDays = remember(startOfWeek) { (0..6).map { startOfWeek.plusDays(it.toLong()) } }
            val shortDays = remember { listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС") }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                weekDays.forEachIndexed { index, date ->
                    val isSelected = date == selectedDate
                    val isToday = date == LocalDate.now()
                    val hasUncompletedHw = homeworks.any { it.dueDate == date && !it.isCompleted }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .width(42.dp)
                            .height(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) PrimaryPurple else Color.Transparent)
                            .then(
                                if (isToday && !isSelected) Modifier.border(1.5.dp, PrimaryPurple.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                                else Modifier
                            )
                            .clickable { onDateSelected(date) }
                    ) {
                        Text(shortDays[index], color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(date.dayOfMonth.toString(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                        Spacer(modifier = Modifier.height(4.dp))
                        if (hasUncompletedHw) {
                            Box(modifier = Modifier.size(6.dp).background(if (isSelected) Color.White else Color(0xFFFF9100), CircleShape))
                        } else {
                            Spacer(modifier = Modifier.size(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderSection(
    navController: NavController,
    userName: String,
    currentGroup: String,
    isPreview: Boolean,
    onGroupClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
                    .border(1.dp, PrimaryPurple.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Привет, $userName!", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))

                // Кликабельный чип для быстрой смены группы
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isPreview) PrimaryPurple else SurfaceDark.copy(alpha = 0.8f))
                        .border(
                            1.dp,
                            if (isPreview) PrimaryPurple else PrimaryPurple.copy(alpha = 0.35f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onGroupClick() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = if (isPreview) Icons.Default.Visibility else Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = if (isPreview) Color.White else PrimaryPurple,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isPreview) "Сверка: гр. $currentGroup" else "Гр. $currentGroup",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = if (isPreview) Color.White else TextGray,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark.copy(alpha = 0.6f))
                    .clickable { onShareClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Share, contentDescription = "Поделиться", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark.copy(alpha = 0.6f))
                    .clickable { navController.navigate("notifications") },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Notifications, contentDescription = "Уведомления", tint = Color.White, modifier = Modifier.size(24.dp))
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(PrimaryPurple).align(Alignment.TopEnd).offset(x = (-4).dp, y = 6.dp))
            }
        }
    }
}

@Composable
fun TimelineItem(lesson: LessonPair, isLast: Boolean, isActive: Boolean, onAddHomework: (String) -> Unit) {
    val style = getSubjectStyle(lesson.subject)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!isLast) {
                    val lineColor = if (isActive) PrimaryPurple else PrimaryPurple.copy(alpha = 0.25f)
                    val lineX = 46.dp.toPx() + 12.dp.toPx() + 18.dp.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(lineX, 36.dp.toPx()),
                        end = Offset(lineX, size.height + 16.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
    ) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(46.dp).padding(top = 4.dp)) {
            Text(lesson.startTime, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(lesson.endTime, color = TextGray, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier.width(36.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(style.color, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(style.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            modifier = Modifier.weight(1f).padding(bottom = 28.dp, top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(lesson.subject, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MeetingRoom, contentDescription = null, tint = TextGray, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ауд. ${lesson.room}", color = TextGray, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = TextGray, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(lesson.teacher, color = TextGray, fontSize = 12.sp)
                }
            }
            IconButton(onClick = { onAddHomework(lesson.subject) }, modifier = Modifier.size(24.dp).padding(top = 2.dp)) {
                Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Сохранить ДЗ", tint = PrimaryPurple, modifier = Modifier.size(20.dp))
            }
        }
    }
}

data class SubjectStyle(val color: Color, val icon: ImageVector)

fun getSubjectStyle(subjectName: String): SubjectStyle {
    val theme = com.example.pulse.utils.SubjectThemeHelper.getTheme(subjectName)
    return SubjectStyle(theme.color, theme.icon)
}