package com.example.pulse.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkDefaultColorScheme = darkColorScheme(
    primary = Color(0xFF6C48E1),
    surface = Color(0xFF1C1C22),
    background = Color(0xFF101014),
    onPrimary = Color.White
)

private val LightDefaultColorScheme = lightColorScheme(
    primary = Color(0xFF6C48E1),
    surface = Color(0xFFF4F4F6),
    background = Color(0xFFFFFFFF),
    onPrimary = Color.White
)

@Composable
fun PulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColorEnabled: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val useDynamic = dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        useDynamic && darkTheme -> dynamicDarkColorScheme(context)
        useDynamic && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> DarkDefaultColorScheme
        else -> LightDefaultColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}