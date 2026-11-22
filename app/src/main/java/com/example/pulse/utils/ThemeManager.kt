package com.example.pulse.utils

import android.content.Context
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter as AndroidColorMatrixColorFilter
import android.graphics.RenderEffect as PlatformRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

enum class AppTheme(val id: String, val displayName: String, val description: String) {
    ORIGINAL("original", "Оригинальная", "Классический фиолетовый МГКАП — ваш текущий дизайн"),
    EXPRESSIVE("expressive", "Material Expressive", "Pixel-стиль: пружинистые анимации, экспрессивные радиусы, живые state layers"),
    LIQUID_GLASS("liquid_glass", "Жидкое стекло", "iOS 26 стиль: Blur, стеклянные поверхности, specular highlights, жидкие переходы")
}

data class ThemeConfig(
    val theme: AppTheme,
    val useDynamicColor: Boolean = false,
    val accentColor: Int = 0xFF6C48E1.toInt()
)

object ThemeManager {
    private const val PREFS_NAME = "PulseThemePrefs"
    private const val KEY_THEME = "selected_theme"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color_enabled"
    private const val KEY_ACCENT_COLOR = "accent_color"

    fun load(context: Context): ThemeConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeId = prefs.getString(KEY_THEME, AppTheme.ORIGINAL.id) ?: AppTheme.ORIGINAL.id
        val theme = AppTheme.values().firstOrNull { it.id == themeId } ?: AppTheme.ORIGINAL
        val useDynamic = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        val accent = prefs.getInt(KEY_ACCENT_COLOR, 0xFF6C48E1.toInt())
        return ThemeConfig(theme, useDynamic, accent)
    }

    fun save(context: Context, config: ThemeConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_THEME, config.theme.id)
            .putBoolean(KEY_DYNAMIC_COLOR, config.useDynamicColor)
            .putInt(KEY_ACCENT_COLOR, config.accentColor)
            .apply()
    }

    @Composable
    fun provideTheme(
        config: ThemeConfig,
        content: @Composable () -> Unit
    ) {
        val context = LocalContext.current
        val isDarkTheme = true

        val colorScheme = when {
            config.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            isDarkTheme -> buildDarkColorScheme(config.accentColor, config.theme)
            else -> buildLightColorScheme(config.accentColor, config.theme)
        }

        val typography = buildTypography(config.theme)
        val shapes = buildShapes(config.theme)
        val motion = remember { MotionTokens.create(config.theme) }

        CompositionLocalProvider(
            LocalMotionTokens provides motion,
            LocalGlassEffect provides GlassEffectTokens.create(config.theme)
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = typography,
                shapes = shapes,
                content = content
            )
        }
    }

    private fun buildDarkColorScheme(accentColor: Int, theme: AppTheme): ColorScheme {
        val accent = Color(accentColor)
        val colors: Triple<Color, Color, Color> = when (theme) {
            AppTheme.ORIGINAL -> {
                val primary = accent
                val primaryContainer = accent.copy(alpha = 0.15f)
                val secondary = accent.copy(alpha = 0.8f)
                Triple(primary, primaryContainer, secondary)
            }
            AppTheme.EXPRESSIVE -> {
                val expressivePrimary = Color(0xFF00B0FF)
                Triple(expressivePrimary, expressivePrimary.copy(alpha = 0.12f), expressivePrimary.copy(alpha = 0.9f))
            }
            AppTheme.LIQUID_GLASS -> {
                val glassPrimary = Color(0xFF00E5FF)
                Triple(glassPrimary, glassPrimary.copy(alpha = 0.1f), glassPrimary.copy(alpha = 0.85f))
            }
        }

        val primary = colors.first
        val primaryContainer = colors.second
        val secondary = colors.third
        val tertiary = when (theme) {
            AppTheme.ORIGINAL -> Color(0xFF00C853)
            AppTheme.EXPRESSIVE -> Color(0xFF00E5FF)
            AppTheme.LIQUID_GLASS -> Color(0xFF00FFFF)
        }
        val surface = when (theme) {
            AppTheme.ORIGINAL -> Color(0xFF1C1C22)
            AppTheme.EXPRESSIVE -> Color(0xFF121212)
            AppTheme.LIQUID_GLASS -> Color(0xFF000000).copy(alpha = 0.6f)
        }
        val onSurface = when (theme) {
            AppTheme.ORIGINAL -> Color.White
            AppTheme.EXPRESSIVE -> Color.White
            AppTheme.LIQUID_GLASS -> Color.White.copy(alpha = 0.95f)
        }
        val outline = when (theme) {
            AppTheme.ORIGINAL -> Color(0xFF3A3A46)
            AppTheme.EXPRESSIVE -> Color(0xFF333333)
            AppTheme.LIQUID_GLASS -> Color.White.copy(alpha = 0.3f)
        }
        val outlineVariant = when (theme) {
            AppTheme.ORIGINAL -> Color(0xFF2A2A32)
            AppTheme.EXPRESSIVE -> Color(0xFF282828)
            AppTheme.LIQUID_GLASS -> Color.White.copy(alpha = 0.15f)
        }

        return darkColorScheme(
            primary = primary,
            primaryContainer = primaryContainer,
            secondary = secondary,
            secondaryContainer = secondary.copy(alpha = 0.1f),
            tertiary = tertiary,
            tertiaryContainer = tertiary.copy(alpha = 0.1f),
            surface = surface,
            surfaceContainerHighest = surface.copy(alpha = 0.9f),
            surfaceContainerHigh = surface.copy(alpha = 0.95f),
            surfaceContainer = surface.copy(alpha = 0.98f),
            surfaceContainerLow = surface.copy(alpha = 0.99f),
            surfaceContainerLowest = surface,
            background = surface,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onTertiary = Color.White,
            onSurface = onSurface,
            onBackground = onSurface,
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = Color(0xFFF4F4F6),
            inversePrimary = accent,
            surfaceTint = primary,
            scrim = Color.Black
        )
    }

    private fun buildLightColorScheme(accentColor: Int, theme: AppTheme): ColorScheme {
        val accent = Color(accentColor)
        val primary: Color
        val primaryContainer: Color
        val secondary: Color
        val tertiary: Color
        val surface: Color
        val onSurface: Color
        val outline: Color
        val outlineVariant: Color

        when (theme) {
            AppTheme.ORIGINAL -> {
                primary = accent
                primaryContainer = accent.copy(alpha = 0.15f)
                secondary = accent.copy(alpha = 0.8f)
                tertiary = Color(0xFF009624)
                surface = Color.White
                onSurface = Color(0xFF1D1D1F)
                outline = Color(0xFF86868B)
                outlineVariant = Color(0xFFC8C8CC)
            }
            AppTheme.EXPRESSIVE -> {
                val expressivePrimary = Color(0xFF006EAA)
                primary = expressivePrimary
                primaryContainer = expressivePrimary.copy(alpha = 0.12f)
                secondary = expressivePrimary.copy(alpha = 0.9f)
                tertiary = Color(0xFF0099CC)
                surface = Color.White
                onSurface = Color(0xFF1D1D1F)
                outline = Color(0xFF737373)
                outlineVariant = Color(0xFFCACACA)
            }
            AppTheme.LIQUID_GLASS -> {
                val glassPrimary = Color(0xFF0099CC)
                primary = glassPrimary
                primaryContainer = glassPrimary.copy(alpha = 0.1f)
                secondary = glassPrimary.copy(alpha = 0.85f)
                tertiary = Color(0xFF00CCCC)
                surface = Color.White
                onSurface = Color(0xFF1D1D1F)
                outline = Color(0xFF737373)
                outlineVariant = Color(0xFFCACACA)
            }
        }

        return lightColorScheme(
            primary = primary,
            primaryContainer = primaryContainer,
            secondary = secondary,
            secondaryContainer = secondary.copy(alpha = 0.1f),
            tertiary = tertiary,
            tertiaryContainer = tertiary.copy(alpha = 0.1f),
            surface = surface,
            surfaceContainerHighest = surface.copy(alpha = 0.9f),
            surfaceContainerHigh = surface.copy(alpha = 0.95f),
            surfaceContainer = surface.copy(alpha = 0.98f),
            surfaceContainerLow = surface.copy(alpha = 0.99f),
            surfaceContainerLowest = surface,
            background = surface,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onTertiary = Color.White,
            onSurface = onSurface,
            onBackground = onSurface,
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = Color(0xFF1C1C22),
            inversePrimary = accent,
            surfaceTint = primary,
            scrim = Color.Black
        )
    }

    private fun buildTypography(theme: AppTheme): Typography {
        return when (theme) {
            AppTheme.ORIGINAL -> Typography(
                displayLarge = TextStyle(fontWeight = FontWeight.W300, fontSize = 57.sp),
                displayMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 45.sp),
                displaySmall = TextStyle(fontWeight = FontWeight.W400, fontSize = 36.sp),
                headlineLarge = TextStyle(fontWeight = FontWeight.W600, fontSize = 32.sp),
                headlineMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 28.sp),
                headlineSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 24.sp),
                titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp),
                titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
                titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
                bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
                bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
                bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp),
                labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
                labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
                labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp)
            )
            AppTheme.EXPRESSIVE -> Typography(
                displayLarge = TextStyle(fontWeight = FontWeight.Thin, fontSize = 57.sp, letterSpacing = -0.5.sp),
                displayMedium = TextStyle(fontWeight = FontWeight.W300, fontSize = 45.sp, letterSpacing = -0.25.sp),
                displaySmall = TextStyle(fontWeight = FontWeight.W400, fontSize = 36.sp),
                headlineLarge = TextStyle(fontWeight = FontWeight.W500, fontSize = 32.sp, letterSpacing = 0.0.sp),
                headlineMedium = TextStyle(fontWeight = FontWeight.W500, fontSize = 28.sp),
                headlineSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 24.sp),
                titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp),
                titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
                titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
                bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
                bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
                labelLarge = TextStyle(fontWeight = FontWeight.W600, fontSize = 14.sp, letterSpacing = 0.1.sp),
                labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
                labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp)
            )
            AppTheme.LIQUID_GLASS -> Typography(
                displayLarge = TextStyle(fontWeight = FontWeight.Thin, fontSize = 57.sp, letterSpacing = -0.75.sp),
                displayMedium = TextStyle(fontWeight = FontWeight.Light, fontSize = 45.sp, letterSpacing = -0.5.sp),
                displaySmall = TextStyle(fontWeight = FontWeight.Light, fontSize = 36.sp, letterSpacing = -0.25.sp),
                headlineLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 32.sp, letterSpacing = 0.0.sp),
                headlineMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 28.sp),
                headlineSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 24.sp),
                titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp),
                titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
                titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
                bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 28.sp, letterSpacing = -0.1.sp),
                bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
                bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
                labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = -0.1.sp),
                labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
                labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp)
            )
        }
    }

    private fun buildShapes(theme: AppTheme): Shapes {
        return when (theme) {
            AppTheme.ORIGINAL -> Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(24.dp)
            )
            AppTheme.EXPRESSIVE -> Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(40.dp)
            )
            AppTheme.LIQUID_GLASS -> Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(16.dp),
                medium = RoundedCornerShape(24.dp),
                large = RoundedCornerShape(32.dp),
                extraLarge = RoundedCornerShape(48.dp)
            )
        }
    }
}

// ==================== MOTION TOKENS ====================

@Immutable
data class MotionTokens(
    val springDamping: Float,
    val springStiffness: Float,
    val fastTween: AnimationSpec<Float>,
    val mediumTween: AnimationSpec<Float>,
    val slowTween: AnimationSpec<Float>,
    val pageTransition: AnimationSpec<Float>,
    val stateLayerDuration: Int,
    val elevationLevels: ElevationLevels,
    val rippleAlpha: Float,
    val ripplePressAlpha: Float
) {
    companion object {
        fun create(theme: AppTheme): MotionTokens = when (theme) {
            AppTheme.ORIGINAL -> MotionTokens(
                springDamping = 0.75f,
                springStiffness = 200f,
                fastTween = tween(150),
                mediumTween = tween(250),
                slowTween = tween(400),
                pageTransition = tween(350),
                stateLayerDuration = 100,
                elevationLevels = ElevationLevels(
                    level0 = 0.dp,
                    level1 = 1.dp,
                    level2 = 3.dp,
                    level3 = 6.dp,
                    level4 = 12.dp,
                    level5 = 24.dp
                ),
                rippleAlpha = 0.08f,
                ripplePressAlpha = 0.12f
            )
            AppTheme.EXPRESSIVE -> MotionTokens(
                springDamping = 0.68f,
                springStiffness = 300f,
                fastTween = tween(100),
                mediumTween = tween(200),
                slowTween = tween(350),
                pageTransition = tween(300),
                stateLayerDuration = 80,
                elevationLevels = ElevationLevels(
                    level0 = 0.dp,
                    level1 = 2.dp,
                    level2 = 6.dp,
                    level3 = 12.dp,
                    level4 = 20.dp,
                    level5 = 32.dp
                ),
                rippleAlpha = 0.1f,
                ripplePressAlpha = 0.16f
            )
            AppTheme.LIQUID_GLASS -> MotionTokens(
                springDamping = 0.82f,
                springStiffness = 250f,
                fastTween = tween(120),
                mediumTween = tween(220),
                slowTween = tween(400),
                pageTransition = tween(500),
                stateLayerDuration = 120,
                elevationLevels = ElevationLevels(
                    level0 = 0.dp,
                    level1 = 0.dp,
                    level2 = 0.dp,
                    level3 = 0.dp,
                    level4 = 0.dp,
                    level5 = 0.dp
                ),
                rippleAlpha = 0.06f,
                ripplePressAlpha = 0.1f
            )
        }
    }
}

@Immutable
data class ElevationLevels(
    val level0: Dp,
    val level1: Dp,
    val level2: Dp,
    val level3: Dp,
    val level4: Dp,
    val level5: Dp
)

val LocalMotionTokens = compositionLocalOf { MotionTokens.create(AppTheme.ORIGINAL) }

// ==================== GLASS EFFECT TOKENS ====================

@Immutable
data class GlassEffectTokens(
    val blurRadius: Float,
    val saturation: Float,
    val tintColor: Color,
    val borderColor: Color,
    val specularColor: Color,
    val specularIntensity: Float,
    val enableRefraction: Boolean,
    val surfaceAlpha: Float
) {
    companion object {
        fun create(theme: AppTheme): GlassEffectTokens = when (theme) {
            AppTheme.ORIGINAL -> GlassEffectTokens(
                blurRadius = 0f,
                saturation = 1f,
                tintColor = Color.Transparent,
                borderColor = Color(0xFF3A3A46),
                specularColor = Color.Transparent,
                specularIntensity = 0f,
                enableRefraction = false,
                surfaceAlpha = 1f
            )
            AppTheme.EXPRESSIVE -> GlassEffectTokens(
                blurRadius = 0f,
                saturation = 1f,
                tintColor = Color.Transparent,
                borderColor = Color(0xFF00B0FF).copy(alpha = 0.3f),
                specularColor = Color.Transparent,
                specularIntensity = 0f,
                enableRefraction = false,
                surfaceAlpha = 1f
            )
            AppTheme.LIQUID_GLASS -> GlassEffectTokens(
                blurRadius = 30f,
                saturation = 1.8f,
                tintColor = Color.White.copy(alpha = 0.05f),
                borderColor = Color.White.copy(alpha = 0.18f),
                specularColor = Color.White.copy(alpha = 0.4f),
                specularIntensity = 0.6f,
                enableRefraction = true,
                surfaceAlpha = 0.85f
            )
        }
    }
}

val LocalGlassEffect = compositionLocalOf { GlassEffectTokens.create(AppTheme.ORIGINAL) }

// ==================== GLASS SURFACE ====================

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    elevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val glassTokens = LocalGlassEffect.current
    val api31 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val combinedEffect: RenderEffect? = remember(glassTokens.blurRadius, glassTokens.saturation) {
        if (api31) {
            val blur = if (glassTokens.blurRadius > 0f) {
                PlatformRenderEffect.createBlurEffect(
                    glassTokens.blurRadius,
                    glassTokens.blurRadius,
                    Shader.TileMode.MIRROR
                )
            } else null

            val sat = if (glassTokens.saturation != 1f) {
                val colorMatrix = AndroidColorMatrix().apply {
                    setSaturation(glassTokens.saturation)
                }
                PlatformRenderEffect.createColorFilterEffect(
                    AndroidColorMatrixColorFilter(colorMatrix)
                )
            } else null

            when {
                blur != null && sat != null -> PlatformRenderEffect.createChainEffect(blur, sat).asComposeRenderEffect()
                blur != null -> blur.asComposeRenderEffect()
                sat != null -> sat.asComposeRenderEffect()
                else -> null
            }
        } else null
    }

    Box(
        modifier = modifier
            .background(
                color = glassTokens.tintColor.copy(alpha = glassTokens.surfaceAlpha),
                shape = shape
            )
            .border(1.dp, glassTokens.borderColor, shape)
            .graphicsLayer {
                if (api31 && combinedEffect != null) {
                    this.renderEffect = combinedEffect
                }
                this.shadowElevation = if (glassTokens.enableRefraction) 0f else elevation.toPx()
                if (glassTokens.enableRefraction) {
                    this.scaleX = 1f
                    this.scaleY = 1f
                }
            }
            .clip(shape)
    ) {
        if (glassTokens.specularIntensity > 0) {
            SpecularHighlight(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
                shape = shape,
                color = glassTokens.specularColor,
                intensity = glassTokens.specularIntensity
            )
        }
        content()
    }
}

@Composable
fun SpecularHighlight(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color = Color.White.copy(alpha = 0.3f),
    intensity: Float = 0.5f
) {
    Canvas(
        modifier = modifier
            .alpha(intensity)
            .clip(shape)
    ) {
        val width = size.width
        val height = size.height

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    color,
                    color.copy(alpha = 0.0f),
                    color.copy(alpha = 0.0f),
                    color.copy(alpha = 0.05f)
                ),
                start = Offset(0f, 0f),
                end = Offset(0f, height * 0.6f)
            ),
            topLeft = Offset.Zero,
            size = Size(width, height)
        )

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    color.copy(alpha = 0.5f),
                    color.copy(alpha = 0.0f)
                ),
                start = Offset(0f, 0f),
                end = Offset(0f, 2.dp.toPx())
            ),
            topLeft = Offset.Zero,
            size = Size(width, 2.dp.toPx())
        )
    }
}

// ==================== EXPRESSIVE SURFACE WITH STATE LAYERS ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpressiveSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color,
    contentColor: Color,
    elevation: Int = 1,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.() -> Unit
) {
    val motionTokens = LocalMotionTokens.current
    val api31 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()

    val stateLayerAlpha = animateStateLayerAlpha(
        isPressed = isPressed,
        isFocused = isFocused,
        isHovered = isHovered,
        isDragged = isDragged,
        motionTokens = motionTokens
    )

    val elevationDp = when (elevation) {
        0 -> motionTokens.elevationLevels.level0
        1 -> motionTokens.elevationLevels.level1
        2 -> motionTokens.elevationLevels.level2
        3 -> motionTokens.elevationLevels.level3
        4 -> motionTokens.elevationLevels.level4
        else -> motionTokens.elevationLevels.level5
    }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = motionTokens.springDamping,
            stiffness = motionTokens.springStiffness
        ),
        label = "scale"
    )

    val elevationAnim by animateDpAsState(
        targetValue = if (isPressed) 0.dp else elevationDp,
        animationSpec = spring(
            dampingRatio = motionTokens.springDamping,
            stiffness = motionTokens.springStiffness
        ),
        label = "elevation"
    )

    val clickableModifier = if (onClick != null) {
        Modifier.combinedClickable(
            onClick = onClick,
            interactionSource = interactionSource,
            indication = null
        )
    } else Modifier

    Box(
        modifier = modifier
            .graphicsLayer {
                this.scaleX = scale
                this.scaleY = scale
                if (api31 && elevation > 0) {
                    this.shadowElevation = elevationAnim.toPx()
                }
            }
            .clip(shape)
            .then(clickableModifier)
            .background(containerColor)
    ) {
        if (stateLayerAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = contentColor.copy(alpha = stateLayerAlpha),
                        shape = shape
                    )
                    .clip(shape)
            )
        }
        content()
    }
}

private fun animateStateLayerAlpha(
    isPressed: Boolean,
    isFocused: Boolean,
    isHovered: Boolean,
    isDragged: Boolean,
    motionTokens: MotionTokens
): Float {
    return when {
        isPressed -> motionTokens.ripplePressAlpha
        isFocused -> 0.08f
        isHovered -> 0.04f
        isDragged -> 0.12f
        else -> 0f
    }
}

// ==================== LIQUID GLASS ANIMATED CONTAINER ====================

@Composable
fun LiquidGlassContainer(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val glassTokens = LocalGlassEffect.current

    val infiniteTransition = rememberInfiniteTransition(label = "liquid_ambient")
    val ambientPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambient_phase"
    )

    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, delayMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    GlassSurface(
        modifier = modifier
            .graphicsLayer {
                this.rotationZ = (sin(ambientPhase * PI / 180.0) * 0.05f).toFloat()
            },
        shape = shape,
        elevation = 0.dp
    ) {
        if (glassTokens.specularIntensity > 0) {
            LiquidShimmer(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
                shape = shape,
                progress = shimmerProgress,
                color = glassTokens.specularColor,
                intensity = glassTokens.specularIntensity
            )
        }
        content()
    }
}

@Composable
fun LiquidShimmer(
    modifier: Modifier = Modifier,
    shape: Shape,
    progress: Float,
    color: Color,
    intensity: Float
) {
    Canvas(
        modifier = modifier.alpha(intensity * 0.5f).clip(shape)
    ) {
        val width = size.width
        val height = size.height

        val shimmerX = (progress * (width + 100.dp.toPx())) - 50.dp.toPx()
        val shimmerWidth = 80.dp.toPx()

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    color.copy(alpha = 0.0f),
                    color.copy(alpha = 0.15f),
                    color.copy(alpha = 0.0f)
                ),
                start = Offset(shimmerX - shimmerWidth, 0f),
                end = Offset(shimmerX + shimmerWidth, height)
            ),
            topLeft = Offset.Zero,
            size = Size(width, height)
        )
    }
}

// ==================== MATCHED GEOMETRY EFFECT HELPERS ====================

@Immutable
data class SharedTransitionKey(val id: String)

val LocalSharedTransition = compositionLocalOf<MutableMap<SharedTransitionKey, Rect>> { mutableMapOf() }

@Composable
fun rememberSharedTransitionScope(): MutableMap<SharedTransitionKey, Rect> {
    return remember { mutableMapOf() }
}

@Composable
fun MatchedGeometryEffect(
    key: SharedTransitionKey,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit
) {
    val sharedMap = LocalSharedTransition.current
    val bounds = remember { mutableStateOf<Rect?>(null) }
    val targetBounds = sharedMap[key]

    val animProgress by animateFloatAsState(
        targetValue = if (targetBounds != null) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "matched_geo"
    )

    val view = LocalView.current

    Box(
        modifier = modifier
            .graphicsLayer {
                val currentTarget = targetBounds
                val currentBounds = bounds.value
                if (currentTarget != null && currentBounds != null) {
                    val start = currentBounds
                    val end = currentTarget
                    val progress = animProgress
                    val currentLeft = start.left + (end.left - start.left) * progress
                    val currentTop = start.top + (end.top - start.top) * progress
                    val currentWidth = start.width + (end.width - start.width) * progress
                    val currentHeight = start.height + (end.height - start.height) * progress
                    this.translationX = currentLeft
                    this.translationY = currentTop
                    this.scaleX = if (start.width != 0f) currentWidth / start.width else 1f
                    this.scaleY = if (start.height != 0f) currentHeight / start.height else 1f
                }
            }
            .clip(shape)
    ) {
        content()

        DisposableEffect(key) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val rect = Rect(
                left = location[0].toFloat(),
                top = location[1].toFloat(),
                right = (location[0] + view.width).toFloat(),
                bottom = (location[1] + view.height).toFloat()
            )
            bounds.value = rect
            sharedMap[key] = rect
            onDispose {
                sharedMap.remove(key)
            }
        }
    }
}