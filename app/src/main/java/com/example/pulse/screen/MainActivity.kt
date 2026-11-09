package com.example.pulse.screen

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.pulse.data.PulseNotificationManager
import com.example.pulse.worker.WidgetUpdateWorker
import java.util.concurrent.TimeUnit
import kotlin.math.abs

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.example.pulse.viewmodel.ScheduleViewModel
import com.example.pulse.viewmodel.PerformanceViewModel
import com.example.pulse.screen.OnboardingScreen
import com.example.pulse.screen.ScheduleScreen
import com.example.pulse.screen.TeachersScreen
import com.example.pulse.screen.PerformanceScreen
import com.example.pulse.screen.NotificationsScreen
import com.example.pulse.screen.SettingsScreen
import com.example.pulse.screen.HomeworkScreen
import com.example.pulse.screen.TeacherScheduleScreen
import com.example.pulse.screen.UpdateMigrationScreen
import androidx.compose.foundation.BorderStroke

internal var appPrimaryColor by mutableStateOf(Color(0xFF6C48E1))
internal var appSurfaceColor by mutableStateOf(Color(0xFF1C1C22))
internal var appBgColor by mutableStateOf(Color(0xFF101014))

val BgDark: Color get() = appBgColor
val SurfaceDark: Color get() = appSurfaceColor
val PrimaryPurple: Color get() = appPrimaryColor
val TextGray = Color(0xFF9090A0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableHighRefreshRateMode()

        PulseNotificationManager.createNotificationChannel(this)

        val updateRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "widget_sync_work",
            ExistingPeriodicWorkPolicy.UPDATE,
            updateRequest
        )

        val prefs = getSharedPreferences("PulseAppPrefs", MODE_PRIVATE)
        val isOnboardingCompleted = prefs.getBoolean("onboarding_completed", false)

        val currentMigrationKey = "migration_1.1.0_clean"
        val isMigrated = prefs.getBoolean(currentMigrationKey, false)

        val startScreen = when {
            !isOnboardingCompleted -> "onboarding"
            !isMigrated -> "migration"
            else -> "schedule"
        }

        val initialDynamic = prefs.getBoolean("dynamic_color_enabled", false)
        if (initialDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scheme = dynamicDarkColorScheme(this)
            appPrimaryColor = scheme.primary
            appSurfaceColor = scheme.surface
            appBgColor = scheme.background
        } else {
            appPrimaryColor = Color(0xFF6C48E1)
            appSurfaceColor = Color(0xFF1C1C22)
            appBgColor = Color(0xFF101014)
        }

        setContent {
            val context = LocalContext.current
            var dynamicColorEnabled by remember {
                mutableStateOf(prefs.getBoolean("dynamic_color_enabled", false))
            }

            DisposableEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "dynamic_color_enabled") {
                        dynamicColorEnabled = prefs.getBoolean("dynamic_color_enabled", false)
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    prefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }

            val colorScheme = if (dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else {
                darkColorScheme(
                    primary = Color(0xFF6C48E1),
                    surface = Color(0xFF1C1C22),
                    background = Color(0xFF101014)
                )
            }

            SideEffect {
                if (appPrimaryColor != colorScheme.primary) appPrimaryColor = colorScheme.primary
                if (appSurfaceColor != colorScheme.surface) appSurfaceColor = colorScheme.surface
                if (appBgColor != colorScheme.background) appBgColor = colorScheme.background
            }

            MaterialTheme(colorScheme = colorScheme) {
                PulseApp(startDestination = startScreen, currentMigrationKey = currentMigrationKey)
            }
        }
    }

    private fun enableHighRefreshRateMode() {
        try {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
                val lp = window.attributes
                if (currentDisplay != null) {
                    val modes = currentDisplay.supportedModes
                    val maxRefreshMode = modes.maxByOrNull { it.refreshRate }
                    if (maxRefreshMode != null && maxRefreshMode.refreshRate >= 89f) {
                        lp.preferredDisplayModeId = maxRefreshMode.modeId
                    }
                }
                lp.preferredRefreshRate = 120f
                window.attributes = lp
            }
        } catch (e: Exception) {
            android.util.Log.w("PulsePerformance", "Could not set 120Hz display mode", e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseApp(startDestination: String, currentMigrationKey: String) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val activity = LocalContext.current as ComponentActivity
    val sharedScheduleViewModel: ScheduleViewModel = viewModel(activity)
    val sharedPerformanceViewModel: PerformanceViewModel = viewModel(activity)

    var showMoreSheet by remember { mutableStateOf(false) }

    val showBottomBar = currentRoute != "onboarding" &&
            currentRoute != "migration" &&
            currentRoute?.startsWith("teacher_schedule") != true &&
            currentRoute != "homework"

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                PulseBottomBar(
                    navController = navController,
                    onMoreClick = { showMoreSheet = true }
                )
            }
        },
        containerColor = BgDark
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(paddingValues),
            enterTransition = { fadeIn(animationSpec = tween(140, easing = LinearOutSlowInEasing)) },
            exitTransition = { fadeOut(animationSpec = tween(140, easing = FastOutLinearInEasing)) },
            popEnterTransition = { fadeIn(animationSpec = tween(140, easing = LinearOutSlowInEasing)) },
            popExitTransition = { fadeOut(animationSpec = tween(140, easing = FastOutLinearInEasing)) }
        ) {
            composable("migration") {
                UpdateMigrationScreen(
                    navController = navController,
                    currentMigrationKey = currentMigrationKey
                )
            }

            composable("onboarding") { OnboardingScreen(navController) }
            composable("schedule") { ScheduleScreen(navController, sharedScheduleViewModel) }
            composable("teachers") { TeachersScreen(navController, sharedScheduleViewModel, sharedPerformanceViewModel) }
            composable("performance") { PerformanceScreen(navController, sharedPerformanceViewModel, sharedScheduleViewModel) }
            composable("notifications") { NotificationsScreen(navController) }
            composable("settings") { SettingsScreen(navController) }
            composable("homework") { HomeworkScreen(navController) }

            composable("teacher_schedule/{teacherName}") { backStackEntry ->
                val encodedName = backStackEntry.arguments?.getString("teacherName") ?: ""
                val teacherName = Uri.decode(encodedName)
                TeacherScheduleScreen(navController, teacherName, sharedScheduleViewModel)
            }
        }
    }

    if (showMoreSheet) {
        MoreActionSheet(
            onDismiss = { showMoreSheet = false },
            onOpenSettings = {
                showMoreSheet = false
                navController.navigate("settings") { launchSingleTop = true }
            }
        )
    }
}

private data class NavTabItem(
    val route: String,
    val title: String,
    val icon: ImageVector
)

@Composable
fun PulseBottomBar(
    navController: NavHostController,
    onMoreClick: () -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val haptic = LocalHapticFeedback.current

    val items = remember {
        listOf(
            NavTabItem("schedule", "Расписание", Icons.Default.CalendarToday),
            NavTabItem("teachers", "Учителя", Icons.Default.People),
            NavTabItem("performance", "Успеваемость", Icons.Default.BarChart),
            NavTabItem("notifications", "Уведомления", Icons.Outlined.Notifications),
            NavTabItem("more", "Ещё", Icons.Default.MoreHoriz)
        )
    }

    var lastValidIndex by remember { mutableIntStateOf(0) }
    val targetIndex = when (currentRoute) {
        "schedule" -> 0
        "teachers" -> 1
        "performance" -> 2
        "notifications" -> 3
        "settings" -> 4
        else -> lastValidIndex
    }

    LaunchedEffect(targetIndex) {
        lastValidIndex = targetIndex
    }

    val animatedIndex by animateFloatAsState(
        targetValue = targetIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "nav_spring_index"
    )

    val movementDiff = targetIndex.toFloat() - animatedIndex
    val stretchFactorX = 1f + (abs(movementDiff) * 0.38f).coerceAtMost(0.55f)
    val squashFactorY = 1f - (abs(movementDiff) * 0.18f).coerceAtMost(0.28f)
    val dynamicTilt = (-movementDiff * 2.8f).coerceIn(-6f, 6f)

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(68.dp)
        ) {
            val barWidth = maxWidth
            val indicatorWidth = 50.dp
            val indicatorHeight = 32.dp
            val currentCenterX = (animatedIndex + 0.5f) * (barWidth.value / items.size.toFloat())

            Box(
                modifier = Modifier
                    .offset(
                        x = (currentCenterX - indicatorWidth.value / 2f).dp,
                        y = 8.dp
                    )
                    .width(indicatorWidth)
                    .height(indicatorHeight)
                    .graphicsLayer {
                        scaleX = stretchFactorX
                        scaleY = squashFactorY
                        rotationZ = dynamicTilt
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                PrimaryPurple.copy(alpha = 0.75f),
                                PrimaryPurple
                            )
                        )
                    )
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = targetIndex == index

                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.12f else 1f,
                        animationSpec = spring(
                            dampingRatio = 0.55f,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "tab_icon_scale"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (item.route == "more") {
                                    onMoreClick()
                                } else {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (currentRoute == "settings") {
                                        val previousRoute = navController.previousBackStackEntry?.destination?.route
                                        if (previousRoute == item.route) {
                                            navController.popBackStack()
                                            return@clickable
                                        } else {
                                            navController.popBackStack()
                                        }
                                    }
                                    if (targetIndex != index || currentRoute == "settings") {
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                }
                            }
                            .padding(top = 8.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = if (isSelected) Color.White else TextGray,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = item.title,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else TextGray,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreActionSheet(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF3A3A46))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {
            Text(
                text = "Дополнительно",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            MoreMenuItem(
                icon = Icons.Outlined.Settings,
                iconBgColor = PrimaryPurple,
                title = "Настройки",
                subtitle = "Группа, уведомления, тема",
                onClick = onOpenSettings
            )

            Spacer(modifier = Modifier.height(12.dp))

            MoreMenuItem(
                icon = Icons.Outlined.Lightbulb,
                iconBgColor = Color(0xFFFF9800),
                title = "Предложить идею",
                subtitle = "Сообщить о баге или предложить фичу",
                onClick = {
                    onDismiss()
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://docs.google.com/forms/d/e/1FAIpQLSexmJNblRY41CyebPoX5d664-GCUqQE8q2rnY08cne2w-FQkg/viewform?usp=sharing&ouid=113519787430406906565")
                    )
                    context.startActivity(intent)
                }
            )
        }
    }
}

@Composable
fun MoreMenuItem(
    icon: ImageVector,
    iconBgColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF26262E)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(iconBgColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconBgColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextGray,
                    fontSize = 12.sp
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextGray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}