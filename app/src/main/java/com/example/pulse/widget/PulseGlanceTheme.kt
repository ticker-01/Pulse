package com.example.pulse.widget

import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders

val CustomWidgetColors = ColorProviders(
    light = lightColorScheme(
        primary = Color(0xFF7B61FF),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF262045),
        onPrimaryContainer = Color(0xFF7B61FF),
        surface = Color(0xFF161622),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF262045),
        onSurfaceVariant = Color.White
    ),
    dark = darkColorScheme(
        primary = Color(0xFF7B61FF),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF262045),
        onPrimaryContainer = Color(0xFF7B61FF),
        surface = Color(0xFF161622),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF262045),
        onSurfaceVariant = Color.White
    )
)

@Composable
fun PulseWidgetTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val canUseDynamic = useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    GlanceTheme(
        colors = if (canUseDynamic) GlanceTheme.colors else CustomWidgetColors
    ) {
        content()
    }
}