package com.example.pulse.screen

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val PI_FLOAT = PI.toFloat()

private val ANSWERS = listOf(
    "АВТОМАТ\nГАРАНТИРОВАН",
    "ПРЕПОД\nСПИТ",
    "ЛУЧШЕ\nВЫСПИСЬ",
    "ПЕРЕСДАЧА\nБЛИЗКО...",
    "100%\nДА",
    "ДАЖЕ НЕ\nДУМАЙ",
    "БУДЕТ\nЗАЧЁТ",
    "СПРОСИ У\nСТАРОСТЫ",
    "ЗВЁЗДЫ\nГОВОРЯТ ДА",
    "ШПАРГАЛКА\nСПАСЁТ",
    "СЕГОДНЯ\nБЕЗ ПАР",
    "ШАНСЫ\n50 / 50",
    "НЕ В ЭТОТ\nРАЗ",
    "ИДИ НА\nПАРУ!"
)

private class FluidBubble(
    var x: Float,
    var y: Float,
    val radius: Float,
    val speed: Float,
    val swaySpeed: Float,
    var alpha: Float
)

@Composable
fun Magic8BallDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isShaking by remember { mutableStateOf(false) }
    var currentAnswer by remember { mutableStateOf("ПОТРЯСИ\nМЕНЯ") }

    val depthAnim = remember { Animatable(1f) }
    val rotationAnim = remember { Animatable(0f) }
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val waveTurbulence = remember { Animatable(0f) }

    val random = remember { Random() }
    val bubbles = remember {
        List(14) {
            FluidBubble(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 1.5f + random.nextFloat() * 2.5f,
                speed = 0.003f + random.nextFloat() * 0.005f,
                swaySpeed = 1.5f + random.nextFloat() * 2.5f,
                alpha = 0.25f + random.nextFloat() * 0.45f
            )
        }
    }

    val timeTransition = rememberInfiniteTransition(label = "fluid_time")
    val fluidTime by timeTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI_FLOAT,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "time"
    )

    fun safeVibrate(heavy: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val effect = if (heavy) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                } else {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                }
                vm?.defaultVibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                val duration = if (heavy) 35L else 12L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val amp = if (heavy) VibrationEffect.DEFAULT_AMPLITUDE else 80
                    v?.vibrate(VibrationEffect.createOneShot(duration, amp))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(duration)
                }
            }
        } catch (_: Throwable) {}
    }

    var revealJob by remember { mutableStateOf<Job?>(null) }

    fun triggerAgitation() {
        if (!isShaking) {
            isShaking = true
            safeVibrate(false)
            coroutineScope.launch {
                launch { waveTurbulence.animateTo(1f, tween(120)) }
                launch { depthAnim.animateTo(0f, tween(200, easing = FastOutLinearInEasing)) }
            }
        }
    }

    fun triggerSettling() {
        revealJob?.cancel()
        revealJob = coroutineScope.launch {
            delay(320)
            currentAnswer = ANSWERS.random()
            rotationAnim.snapTo((-12..12).random().toFloat())

            launch {
                waveTurbulence.animateTo(0f, tween(900, easing = LinearOutSlowInEasing))
            }

            depthAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.62f,
                    stiffness = Spring.StiffnessLow
                )
            )
            safeVibrate(true)
        }
    }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var lastX = 0f
        var lastY = 0f
        var lastZ = 0f
        var lastVibeTime = 0L
        var lastShakeTime = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                coroutineScope.launch {
                    tiltX.animateTo((-x * 2.8f).coerceIn(-16f, 16f), tween(90))
                    tiltY.animateTo((y * 2.8f).coerceIn(-16f, 16f), tween(90))
                }

                val deltaX = abs(x - lastX)
                val deltaY = abs(y - lastY)
                val deltaZ = abs(z - lastZ)
                val totalMovement = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)
                val now = System.currentTimeMillis()

                if (totalMovement > 11.8f) {
                    lastShakeTime = now
                    triggerAgitation()

                    if (now - lastVibeTime > 160L) {
                        lastVibeTime = now
                        safeVibrate(false)
                    }
                } else if (isShaking && (now - lastShakeTime > 600L)) {
                    isShaking = false
                    triggerSettling()
                }

                lastX = x
                lastY = y
                lastZ = z
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose {
            sensorManager?.unregisterListener(listener)
            revealJob?.cancel()
        }
    }

    val wavePath1 = remember { Path() }
    val wavePath2 = remember { Path() }
    val clipCirclePath = remember { Path() }
    val trianglePath = remember { Path() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    triggerAgitation()
                    coroutineScope.launch {
                        delay(450)
                        isShaking = false
                        triggerSettling()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .size(40.dp)
                    .background(SurfaceDark, CircleShape)
                    .border(1.dp, PrimaryPurple.copy(alpha = 0.25f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "ШАР ПРЕДСКАЗАНИЙ",
                    color = PrimaryPurple,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isShaking) "Жидкость бурлит..." else "Потряси телефон или коснись сферы",
                    color = TextGray,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(34.dp))

                Box(
                    modifier = Modifier
                        .size(314.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(SurfaceDark, BgDark, Color.Black),
                                center = Offset(240f, 240f)
                            )
                        )
                        .border(1.5.dp, PrimaryPurple.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                                center = Offset(size.width * 0.32f, size.height * 0.25f),
                                radius = size.width * 0.36f
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(178.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF040209))
                            .border(3.5.dp, PrimaryPurple.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val depth = depthAnim.value
                        val turbulence = waveTurbulence.value
                        val idleWave = sin(fluidTime) * 3f
                        val idleSway = cos(fluidTime * 0.8f) * 2f

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            clipCirclePath.reset()
                            clipCirclePath.addOval(Rect(0f, 0f, w, h))

                            clipPath(clipCirclePath) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(BgDark, SurfaceDark, Color(0xFF060312))
                                    )
                                )

                                val waveHeight = 12f + (turbulence * 22f)

                                wavePath1.reset()
                                wavePath1.moveTo(0f, h * 0.45f)
                                for (x in 0..w.toInt() step 6) {
                                    val nx = x.toFloat()
                                    val ny = (h * 0.48f) + sin((nx / w) * 3f * PI_FLOAT + fluidTime) * waveHeight
                                    wavePath1.lineTo(nx, ny)
                                }
                                wavePath1.lineTo(w, h)
                                wavePath1.lineTo(0f, h)
                                wavePath1.close()

                                drawPath(
                                    path = wavePath1,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(PrimaryPurple.copy(alpha = 0.55f), SurfaceDark.copy(alpha = 0.85f))
                                    )
                                )

                                wavePath2.reset()
                                wavePath2.moveTo(0f, h * 0.52f)
                                for (x in 0..w.toInt() step 6) {
                                    val nx = x.toFloat()
                                    val ny = (h * 0.54f) + cos((nx / w) * 2.5f * PI_FLOAT - (fluidTime * 1.2f)) * (waveHeight * 0.75f)
                                    wavePath2.lineTo(nx, ny)
                                }
                                wavePath2.lineTo(w, h)
                                wavePath2.lineTo(0f, h)
                                wavePath2.close()

                                drawPath(
                                    path = wavePath2,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(PrimaryPurple.copy(alpha = 0.75f), SurfaceDark.copy(alpha = 0.70f))
                                    )
                                )

                                bubbles.forEach { b ->
                                    b.y -= b.speed * (1f + turbulence * 3f)
                                    if (b.y < 0.05f) b.y = 0.95f

                                    val sway = sin(fluidTime * b.swaySpeed + b.x * 10f) * 6f
                                    val bx = (b.x * w) + sway
                                    val by = b.y * h

                                    drawCircle(
                                        color = PrimaryPurple.copy(alpha = b.alpha * (0.45f + turbulence * 0.55f)),
                                        radius = b.radius.dp.toPx(),
                                        center = Offset(bx, by)
                                    )
                                    drawCircle(
                                        color = Color.White.copy(alpha = b.alpha * 0.7f),
                                        radius = (b.radius * 0.4f).dp.toPx(),
                                        center = Offset(bx - b.radius * 0.3f, by - b.radius * 0.3f)
                                    )
                                }
                            }
                        }

                        Canvas(
                            modifier = Modifier
                                .size(142.dp)
                                .graphicsLayer {
                                    scaleX = 0.38f + (depth * 0.62f)
                                    scaleY = 0.38f + (depth * 0.62f)
                                    alpha = (depth * depth * depth).coerceIn(0f, 1f)
                                    rotationZ = rotationAnim.value
                                    translationX = tiltX.value + idleSway
                                    translationY = tiltY.value + idleWave
                                }
                        ) {
                            val w = size.width
                            val h = size.height

                            trianglePath.reset()
                            trianglePath.moveTo(w / 2f, 10.dp.toPx())
                            trianglePath.lineTo(w - 12.dp.toPx(), h - 14.dp.toPx())
                            trianglePath.lineTo(12.dp.toPx(), h - 14.dp.toPx())
                            trianglePath.close()

                            drawPath(
                                path = trianglePath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(PrimaryPurple.copy(alpha = 0.85f), SurfaceDark)
                                )
                            )
                            drawPath(
                                path = trianglePath,
                                color = PrimaryPurple,
                                style = Stroke(width = 2.4.dp.toPx())
                            )
                        }

                        Text(
                            text = currentAnswer,
                            color = Color.White.copy(alpha = (depth * 1.3f).coerceIn(0f, 1f)),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp,
                            letterSpacing = 0.6.sp,
                            modifier = Modifier
                                .padding(top = 18.dp)
                                .graphicsLayer {
                                    scaleX = 0.52f + (depth * 0.48f)
                                    scaleY = 0.52f + (depth * 0.48f)
                                    rotationZ = rotationAnim.value
                                    translationX = tiltX.value + idleSway
                                    translationY = tiltY.value + idleWave
                                }
                        )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color.Transparent, Color(0xFF060312).copy(alpha = 0.75f)),
                                    radius = size.width * 0.5f
                                )
                            )
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                                    center = Offset(size.width * 0.36f, size.height * 0.28f),
                                    radius = size.width * 0.35f
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}