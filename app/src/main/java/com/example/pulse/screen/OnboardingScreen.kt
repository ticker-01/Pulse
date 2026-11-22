package com.example.pulse.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.pulse.viewmodel.ScheduleViewModel
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Composable
fun OnboardingScreen(navController: NavController) {
    var currentStep by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }

    var isGathering by remember { mutableStateOf(false) }
    var ringCenter by remember { mutableStateOf(Offset.Unspecified) }

    var notificationGranted by remember { mutableStateOf(false) }

    var showEasterEgg by remember { mutableStateOf(false) }
    var easterEggBoost by remember { mutableStateOf(false) }
    var secretTapCount by remember { mutableIntStateOf(0) }

    // Teacher mode
    var isTeacherMode by remember { mutableStateOf(false) }
    var customTeacherName by remember { mutableStateOf("") }
    var selectedTeacher by remember { mutableStateOf("") }

    val context = LocalContext.current
    val scheduleViewModel: ScheduleViewModel = viewModel()

    LaunchedEffect(Unit) {
        notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        // Preload teachers from cache
        scheduleViewModel.refreshData()
    }

    fun completeOnboarding() {
        isGathering = true
    }

    LaunchedEffect(isGathering) {
        if (isGathering) {
            delay(1200L)
            val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
            val teacherName = if (isTeacherMode) {
                if (customTeacherName.isNotBlank()) customTeacherName else selectedTeacher
            } else ""
            prefs.edit().apply {
                putString("user_name", name.ifBlank { if (isTeacherMode) "Преподаватель" else "Студент" })
                putBoolean("is_teacher_mode", isTeacherMode)
                if (isTeacherMode) {
                    putString("teacher_name", teacherName.ifBlank { "Преподаватель" })
                } else {
                    putString("user_group", group.ifBlank { "Без группы" })
                }
                putBoolean("onboarding_completed", true)
                putBoolean("notifications_asked", true)
                apply()
            }
            navController.navigate("schedule") {
                popUpTo("onboarding") { inclusive = true }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationGranted = isGranted
        completeOnboarding()
    }

    fun requestNotificationAndFinish() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val alreadyGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (alreadyGranted) {
                notificationGranted = true
                completeOnboarding()
            } else {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            notificationGranted = true
            completeOnboarding()
        }
    }

    LaunchedEffect(showEasterEgg) {
        if (showEasterEgg) {
            delay(5000L)
            showEasterEgg = false
            easterEggBoost = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgDark)) {

        AnimatedConstellationBackground(
            isGathering = isGathering,
            targetCenter = ringCenter,
            boost = easterEggBoost
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            secretTapCount++
                            if (secretTapCount >= 5) {
                                showEasterEgg = true
                                easterEggBoost = true
                                secretTapCount = 0
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    repeat(3) { index ->
                        val isSelected = currentStep == index
                        val width by animateDpAsState(
                            targetValue = if (isSelected) 26.dp else 8.dp,
                            animationSpec = tween(300),
                            label = "dotWidth"
                        )
                        val color by animateColorAsState(
                            targetValue = if (isSelected) PrimaryPurple else PrimaryPurple.copy(alpha = 0.25f),
                            animationSpec = tween(300),
                            label = "dotColor"
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .height(8.dp)
                                .width(width)
                                .clip(CircleShape)
                                .background(color)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(tween(400)) { it } + fadeIn(tween(400))) togetherWith
                                    (slideOutHorizontally(tween(400)) { -it } + fadeOut(tween(400)))
                        } else {
                            (slideInHorizontally(tween(400)) { -it } + fadeIn(tween(400))) togetherWith
                                    (slideOutHorizontally(tween(400)) { it } + fadeOut(tween(400)))
                        }
                    },
                    label = "OnboardingContent"
                ) { step ->
                    when (step) {
                        0 -> RoleSelectionStep(
                            isTeacherMode = isTeacherMode,
                            onRoleChange = { isTeacherMode = it },
                            onRingPositioned = { ringCenter = it }
                        )
                        1 -> StepContentUI(
                            icon = Icons.Default.WavingHand,
                            title = "Добро пожаловать!",
                            description = "Давай познакомимся. Как к тебе обращаться?",
                            value = name,
                            onValueChange = { name = it },
                            label = "Твоё имя",
                            inputIcon = Icons.Outlined.Person,
                            hint = "Ты всегда сможешь изменить имя\nв настройках приложения",
                            onRingPositioned = { ringCenter = it },
                            onLongPressIcon = {
                                showEasterEgg = true
                                easterEggBoost = true
                            }
                        )
                        2 -> if (isTeacherMode) TeacherStepContent(
                            customTeacherName = customTeacherName,
                            onCustomNameChange = { customTeacherName = it },
                            selectedTeacher = selectedTeacher,
                            onTeacherSelected = { selectedTeacher = it; customTeacherName = "" },
                            onRingPositioned = { ringCenter = it },
                            scheduleViewModel = scheduleViewModel
                        ) else StepContentUI(
                            icon = Icons.Default.School,
                            title = "Твоя группа",
                            description = "Укажи свою учебную группу, чтобы мы загрузили актуальное расписание.",
                            value = group,
                            onValueChange = { group = it },
                            label = "Например: 581",
                            inputIcon = Icons.Default.Groups,
                            hint = "Это поможет нам показывать расписание\nименно для твоей группы",
                            onRingPositioned = { ringCenter = it },
                            onLongPressIcon = {
                                showEasterEgg = true
                                easterEggBoost = true
                            }
                        )
                        3 -> StepContentUI(
                            icon = Icons.Default.NotificationsActive,
                            title = "Будь в курсе",
                            description = "Разреши уведомления, чтобы мы могли напоминать о парах и предупреждать об отменах.",
                            value = "",
                            onValueChange = {},
                            label = "",
                            inputIcon = Icons.Default.Notifications,
                            hint = "Мы отправляем только важные уведомления\nпо учёбе",
                            isInputEnabled = false,
                            onRingPositioned = { ringCenter = it },
                            onLongPressIcon = {
                                showEasterEgg = true
                                easterEggBoost = true
                            }
                        )
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showEasterEgg,
                    enter = scaleIn(tween(350)) + fadeIn(),
                    exit = scaleOut(tween(350)) + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = SurfaceDark,
                        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.6f)),
                        shadowElevation = 12.dp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = "Пасхалка активирована: спектральный режим",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Button(
                onClick = {
                    when {
                        currentStep < 3 -> currentStep++
                        else -> requestNotificationAndFinish()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(20.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(PrimaryPurple.copy(alpha = 0.82f), PrimaryPurple)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when {
                                currentStep < 3 -> "Далее"
                                else -> "Разрешить и начать"
                            },
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (currentStep < 3) Icons.Default.ArrowForward
                            else Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (currentStep == 3) {
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(
                    onClick = { completeOnboarding() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Пропустить",
                        color = TextGray,
                        fontSize = 14.sp
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun StepContentUI(
    icon: ImageVector,
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    inputIcon: ImageVector,
    hint: String,
    isInputEnabled: Boolean = true,
    onRingPositioned: (Offset) -> Unit,
    onLongPressIcon: () -> Unit = {}
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "iconScale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .scale(scale)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onLongPressIcon() }
                    )
                }
                .onGloballyPositioned { coordinates ->
                    val center = coordinates.positionInRoot() + Offset(
                        coordinates.size.width / 2f,
                        coordinates.size.height / 2f
                    )
                    onRingPositioned(center)
                }
        ) {
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .border(1.dp, PrimaryPurple.copy(alpha = 0.35f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(PrimaryPurple.copy(alpha = 0.16f), CircleShape)
                    .border(2.dp, PrimaryPurple, CircleShape)
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PrimaryPurple,
                modifier = Modifier.size(48.dp)
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(Color.White, CircleShape)
                    .align(Alignment.TopCenter)
                    .offset(y = 15.dp)
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(PrimaryPurple, CircleShape)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-20).dp, y = (-20).dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = title,
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = description,
            color = TextGray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        if (isInputEnabled) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = inputIcon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = PrimaryPurple
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = label, color = PrimaryPurple)
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = PrimaryPurple,
                    unfocusedBorderColor = SurfaceDark,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark.copy(alpha = 0.7f),
                    cursorColor = PrimaryPurple
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = PrimaryPurple.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = hint,
                    color = TextGray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val radius: Float,
    val alpha: Float,
    val colorShift: Float
)

@Composable
fun AnimatedConstellationBackground(
    isGathering: Boolean,
    targetCenter: Offset,
    boost: Boolean = false
) {
    val frameTransition = rememberInfiniteTransition(label = "frame")
    val frame by frameTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "frame"
    )

    val rgbBlend by animateFloatAsState(
        targetValue = if (boost) 1f else 0f,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "rgbBlend"
    )

    val hueTransition = rememberInfiniteTransition(label = "rgbHue")
    val currentHue by hueTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "currentHue"
    )

    val particles = remember {
        Array(48) {
            Particle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                vx = (Random.nextFloat() - 0.5f) * 0.0022f,
                vy = (Random.nextFloat() - 0.5f) * 0.0022f,
                radius = Random.nextFloat() * 3.2f + 1f,
                alpha = Random.nextFloat() * 0.65f + 0.2f,
                colorShift = Random.nextFloat() * 360f
            )
        }
    }

    val wavePath1 = remember { Path() }
    val wavePath2 = remember { Path() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val trigger = frame
        val w = size.width
        val h = size.height

        val gatherX = if (targetCenter.isSpecified) targetCenter.x else w / 2f
        val gatherY = if (targetCenter.isSpecified) targetCenter.y else h / 2f

        val time = System.currentTimeMillis()
        val phase1 = (time % 12000) / 12000f * 2f * Math.PI.toFloat()
        val phase2 = (time % 16000) / 16000f * 2f * Math.PI.toFloat()

        wavePath1.reset()
        wavePath1.moveTo(0f, h * 0.4f)
        var x1 = 0f
        while (x1 <= w) {
            wavePath1.lineTo(
                x1,
                h * 0.4f + (h * 0.15f) * sin(x1 / w * 2 * Math.PI + phase1).toFloat()
            )
            x1 += 10f
        }
        wavePath1.lineTo(w, h)
        wavePath1.lineTo(0f, h)
        wavePath1.close()

        drawPath(
            wavePath1,
            Brush.verticalGradient(
                listOf(PrimaryPurple.copy(alpha = 0.28f), Color.Transparent)
            )
        )

        wavePath2.reset()
        wavePath2.moveTo(0f, h * 0.6f)
        var x2 = 0f
        while (x2 <= w) {
            wavePath2.lineTo(
                x2,
                h * 0.6f + (h * 0.2f) * sin(x2 / w * 3 * Math.PI - phase2).toFloat()
            )
            x2 += 10f
        }
        wavePath2.lineTo(w, h)
        wavePath2.lineTo(0f, h)
        wavePath2.close()

        drawPath(
            wavePath2,
            Brush.verticalGradient(
                listOf(PrimaryPurple.copy(alpha = 0.16f), Color.Transparent)
            )
        )

        particles.forEach { p ->
            if (isGathering || boost) {
                val dx = gatherX - (p.x * w)
                val dy = gatherY - (p.y * h)
                val force = if (boost) 0.055f else 0.03f
                p.vx += (dx / w) * force
                p.vy += (dy / h) * force
                p.vx *= 0.86f
                p.vy *= 0.86f
            }

            p.x += p.vx
            p.y += p.vy

            if (!isGathering && !boost) {
                if (p.x < -0.1f) p.x = 1.1f
                if (p.x > 1.1f) p.x = -0.1f
                if (p.y < -0.1f) p.y = 1.1f
                if (p.y > 1.1f) p.y = -0.1f
            }

            val alphaMul = if (boost) 1.35f else 1f
            val calculatedHue = (currentHue + p.colorShift) % 360f

            val defaultHaloColor = PrimaryPurple.copy(alpha = (p.alpha * 0.38f * alphaMul).coerceIn(0f, 1f))
            val rgbHaloColor = Color.hsl(calculatedHue, 0.95f, 0.60f, (p.alpha * 0.65f * alphaMul).coerceIn(0f, 1f))
            val haloColor = lerp(defaultHaloColor, rgbHaloColor, rgbBlend)

            val defaultCoreColor = Color.White.copy(alpha = (p.alpha * alphaMul).coerceIn(0f, 1f))
            val rgbCoreColor = Color.hsl(calculatedHue, 0.55f, 0.92f, (p.alpha * 1.1f * alphaMul).coerceIn(0f, 1f))
            val coreColor = lerp(defaultCoreColor, rgbCoreColor, rgbBlend)

            drawCircle(
                color = haloColor,
                radius = p.radius * (3.1f + rgbBlend * 0.8f),
                center = Offset(p.x * w, p.y * h)
            )
            drawCircle(
                color = coreColor,
                radius = p.radius,
                center = Offset(p.x * w, p.y * h)
            )
        }

        if (!isGathering) {
            val maxDist = 125.dp.toPx()
            for (i in particles.indices) {
                for (j in i + 1 until particles.size) {
                    val p1 = particles[i]
                    val p2 = particles[j]
                    val dx = (p1.x - p2.x) * w
                    val dy = (p1.y - p2.y) * h
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < maxDist) {
                        val lineAlpha = (1f - (dist / maxDist)) * (if (boost) 0.6f else 0.4f)
                        val lineHue = (currentHue + p1.colorShift) % 360f

                        val defaultLineColor = PrimaryPurple.copy(alpha = lineAlpha)
                        val rgbLineColor = Color.hsl(lineHue, 0.9f, 0.65f, lineAlpha * 1.3f)
                        val lineColor = lerp(defaultLineColor, rgbLineColor, rgbBlend)

                        drawLine(
                            color = lineColor,
                            start = Offset(p1.x * w, p1.y * h),
                            end = Offset(p2.x * w, p2.y * h),
                            strokeWidth = if (boost) 2.6f else 1.8f
                        )
                    }
                }
            }
        }
    }
}
@Composable
fun RoleSelectionStep(
    isTeacherMode: Boolean,
    onRoleChange: (Boolean) -> Unit,
    onRingPositioned: (Offset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .onGloballyPositioned { coordinates ->
                    val center = coordinates.positionInRoot() + Offset(
                        coordinates.size.width / 2f,
                        coordinates.size.height / 2f
                    )
                    onRingPositioned(center)
                }
        ) {
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .border(1.dp, PrimaryPurple.copy(alpha = 0.35f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(PrimaryPurple.copy(alpha = 0.16f), CircleShape)
                    .border(2.dp, PrimaryPurple, CircleShape)
            )
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                tint = PrimaryPurple,
                modifier = Modifier.size(48.dp)
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(Color.White, CircleShape)
                    .align(Alignment.TopCenter)
                    .offset(y = 15.dp)
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(PrimaryPurple, CircleShape)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-20).dp, y = (-20).dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Кто вы?",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Выберите роль — это изменит интерфейс и функции приложения",
            color = TextGray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RoleCard(
                title = "Студент",
                subtitle = "Расписание, оценки, уведомления",
                icon = Icons.Default.School,
                isSelected = !isTeacherMode,
                onClick = { onRoleChange(false) }
            )
            RoleCard(
                title = "Преподаватель",
                subtitle = "Моё расписание, нагрузка, группы",
                icon = Icons.Default.Person,
                isSelected = isTeacherMode,
                onClick = { onRoleChange(true) }
            )
        }
    }
}

@Composable
fun RoleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) PrimaryPurple else SurfaceDark,
        animationSpec = tween(300),
        label = "roleCardBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) PrimaryPurple else PrimaryPurple.copy(alpha = 0.2f),
        animationSpec = tween(300),
        label = "roleCardBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color.White,
        animationSpec = tween(300),
        label = "roleCardText"
    )
    val subTextColor by animateColorAsState(
        targetValue = if (isSelected) Color.White.copy(alpha = 0.9f) else TextGray,
        animationSpec = tween(300),
        label = "roleCardSubText"
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(2.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clickable { onClick() }
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Color.White else PrimaryPurple,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = subTextColor,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
fun TeacherStepContent(
    customTeacherName: String,
    onCustomNameChange: (String) -> Unit,
    selectedTeacher: String,
    onTeacherSelected: (String) -> Unit,
    onRingPositioned: (Offset) -> Unit,
    scheduleViewModel: ScheduleViewModel
) {
    val cachedTeachers = scheduleViewModel.state.value.teachers
    val hasCachedTeachers = cachedTeachers.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .onGloballyPositioned { coordinates ->
                    val center = coordinates.positionInRoot() + Offset(
                        coordinates.size.width / 2f,
                        coordinates.size.height / 2f
                    )
                    onRingPositioned(center)
                }
        ) {
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .border(1.dp, PrimaryPurple.copy(alpha = 0.35f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(PrimaryPurple.copy(alpha = 0.16f), CircleShape)
                    .border(2.dp, PrimaryPurple, CircleShape)
            )
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = null,
                tint = PrimaryPurple,
                modifier = Modifier.size(48.dp)
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(Color.White, CircleShape)
                    .align(Alignment.TopCenter)
                    .offset(y = 15.dp)
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(PrimaryPurple, CircleShape)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-20).dp, y = (-20).dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Как к вам обращаться?",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = if (hasCachedTeachers) {
                "Выберите себя из списка или введите вручную"
            } else {
                "Загружаем список преподавателей... Можно ввести вручную"
            },
            color = TextGray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = customTeacherName,
            onValueChange = onCustomNameChange,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = PrimaryPurple
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Имя преподавателя", color = PrimaryPurple)
                }
            },
            placeholder = { Text("Например: Иванов И.И.", color = TextGray.copy(alpha = 0.7f)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = PrimaryPurple,
                unfocusedBorderColor = SurfaceDark,
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark.copy(alpha = 0.7f),
                cursorColor = PrimaryPurple
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Quick pick from cached list
        if (hasCachedTeachers) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                cachedTeachers.take(6).forEach { teacher ->
                    val isSelected = selectedTeacher == teacher.name
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) PrimaryPurple.copy(alpha = 0.2f) else SurfaceDark
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) PrimaryPurple else PrimaryPurple.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTeacherSelected(teacher.name) }
                            .padding(horizontal = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(PrimaryPurple.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = teacher.name.split(" ")
                                        .filter { it.isNotBlank() }
                                        .take(2)
                                        .map { it.first().uppercase() }
                                        .joinToString(""),
                                    color = PrimaryPurple,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = teacher.name,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (teacher.subject.isNotBlank()) {
                                    Text(
                                        text = teacher.subject,
                                        color = TextGray,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = PrimaryPurple,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Не нашли себя? Введите имя вручную выше ⬆️",
                color = TextGray.copy(alpha = 0.7f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = PrimaryPurple.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (hasCachedTeachers) {
                    "Список взят из загруженного расписания. Если вас нет — введите вручную."
                } else {
                    "Расписание загружается в фоне. Список появится автоматически."
                },
                color = TextGray,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}
