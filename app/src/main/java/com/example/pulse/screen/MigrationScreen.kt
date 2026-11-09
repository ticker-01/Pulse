package com.example.pulse.screen

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlin.math.sin

@Composable
fun UpdateMigrationScreen(navController: NavController, currentMigrationKey: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE) }

    val fillProgress = remember { Animatable(0f) }
    val infiniteTransition = rememberInfiniteTransition(label = "wave_phase")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "wave_phase_anim"
    )

    LaunchedEffect(Unit) {
        prefs.edit()
            .remove("schedule_history")
            .remove("saved_teachers")
            .remove("last_raw_pdf_text")
            .remove("widget_lessons_today")
            .remove("last_pdf_url")
            .remove("last_pdf_filename")
            .remove("last_pdf_hash")
            .putBoolean(currentMigrationKey, true)
            .apply()

        fillProgress.animateTo(1f, tween(2500, easing = FastOutSlowInEasing))

        delay(200)

        navController.navigate("schedule") {
            popUpTo("migration") { inclusive = true }
        }
    }

    val waveColor = PrimaryPurple.copy(alpha = 0.95f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark),
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
            drawPath(path = path, color = waveColor)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(fillProgress.value * 100).toInt()}%",
                color = Color.White,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Оптимизация базы данных...",
                color = TextGray,
                fontSize = 14.sp
            )
        }
    }
}