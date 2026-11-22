package com.example.pulse.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pulse.utils.ExpressiveSurface
import com.example.pulse.utils.GlassSurface
import com.example.pulse.utils.LocalGlassEffect
import com.example.pulse.utils.LocalMotionTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color? = null,
    contentColor: Color? = null,
    elevation: Int = 1,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    border: BorderStroke? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val motionTokens = LocalMotionTokens.current
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction
    val isExpressive = motionTokens.springStiffness > 250f && !isLiquidGlass

    val resolvedContainerColor = containerColor ?: (if (isLiquidGlass) Color.Transparent else colorScheme.surfaceContainer)
    val resolvedContentColor = contentColor ?: colorScheme.onSurface

    if (isLiquidGlass) {
        GlassSurface(
            modifier = modifier
                .fillMaxWidth()
                .graphicsLayer {
                    if (onClick != null) {
                        this.transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                    }
                },
            shape = shape,
            elevation = 0.dp
        ) {
            if (onClick != null) {
                ExpressiveSurface(
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                    containerColor = Color.Transparent,
                    contentColor = resolvedContentColor,
                    elevation = 0,
                    onClick = onClick,
                    interactionSource = interactionSource
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    } else if (isExpressive) {
        ExpressiveSurface(
            modifier = modifier,
            shape = shape,
            containerColor = resolvedContainerColor,
            contentColor = resolvedContentColor,
            elevation = elevation,
            onClick = onClick,
            interactionSource = interactionSource
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (border != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(border, shape)
                            .clip(shape)
                    )
                }
                content()
            }
        }
    } else {
        val elevationDp = when (elevation) {
            0 -> motionTokens.elevationLevels.level0
            1 -> motionTokens.elevationLevels.level1
            2 -> motionTokens.elevationLevels.level2
            3 -> motionTokens.elevationLevels.level3
            4 -> motionTokens.elevationLevels.level4
            else -> motionTokens.elevationLevels.level5
        }

        if (onClick != null) {
            Card(
                onClick = onClick,
                modifier = modifier,
                shape = shape,
                colors = CardDefaults.cardColors(
                    containerColor = resolvedContainerColor,
                    contentColor = resolvedContentColor
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = elevationDp,
                    pressedElevation = elevationDp,
                    focusedElevation = elevationDp,
                    hoveredElevation = elevationDp,
                    draggedElevation = elevationDp
                ),
                border = border,
                interactionSource = interactionSource
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        } else {
            Card(
                modifier = modifier,
                shape = shape,
                colors = CardDefaults.cardColors(
                    containerColor = resolvedContainerColor,
                    contentColor = resolvedContentColor
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = elevationDp,
                    pressedElevation = elevationDp,
                    focusedElevation = elevationDp,
                    hoveredElevation = elevationDp,
                    draggedElevation = elevationDp
                ),
                border = border
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    containerColor: Color? = null,
    contentColor: Color? = null,
    buttonHeight: Dp = 52.dp,
    content: @Composable RowScope.() -> Unit
) {
    val motionTokens = LocalMotionTokens.current
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction
    val isExpressive = motionTokens.springStiffness > 250f && !isLiquidGlass

    val resolvedContainerColor = containerColor ?: colorScheme.primary
    val resolvedContentColor = contentColor ?: colorScheme.onPrimary

    if (isLiquidGlass) {
        GlassSurface(
            modifier = modifier
                .height(buttonHeight)
                .fillMaxWidth()
                .graphicsLayer {
                    this.transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                },
            shape = shape,
            elevation = 0.dp
        ) {
            ExpressiveSurface(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                containerColor = resolvedContainerColor.copy(alpha = glassTokens.surfaceAlpha),
                contentColor = resolvedContentColor,
                elevation = 0,
                onClick = onClick
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }
    } else if (isExpressive) {
        Button(
            onClick = onClick,
            modifier = modifier.height(buttonHeight).fillMaxWidth(),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = resolvedContainerColor,
                contentColor = resolvedContentColor,
                disabledContainerColor = colorScheme.surfaceContainerHighest.copy(alpha = 0.12f),
                disabledContentColor = colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            interactionSource = remember { MutableInteractionSource() }
        ) {
            content()
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.height(buttonHeight).fillMaxWidth(),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = resolvedContainerColor,
                contentColor = resolvedContentColor,
                disabledContainerColor = colorScheme.surfaceContainerHighest.copy(alpha = 0.12f),
                disabledContentColor = colorScheme.onSurface.copy(alpha = 0.38f)
            )
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    borderColor: Color? = null,
    contentColor: Color? = null,
    buttonHeight: Dp = 52.dp,
    content: @Composable RowScope.() -> Unit
) {
    val motionTokens = LocalMotionTokens.current
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction
    val isExpressive = motionTokens.springStiffness > 250f && !isLiquidGlass

    val resolvedBorderColor = borderColor ?: colorScheme.outline
    val resolvedContentColor = contentColor ?: colorScheme.primary

    if (isLiquidGlass) {
        GlassSurface(
            modifier = modifier
                .height(buttonHeight)
                .fillMaxWidth()
                .border(1.dp, resolvedBorderColor.copy(alpha = 0.5f), shape),
            shape = shape,
            elevation = 0.dp
        ) {
            ExpressiveSurface(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                containerColor = Color.Transparent,
                contentColor = resolvedContentColor,
                elevation = 0,
                onClick = onClick
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }
    } else if (isExpressive) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(buttonHeight).fillMaxWidth(),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = resolvedContentColor,
                disabledContentColor = colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            border = BorderStroke(1.dp, resolvedBorderColor),
            interactionSource = remember { MutableInteractionSource() }
        ) {
            content()
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(buttonHeight).fillMaxWidth(),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = resolvedContentColor,
                disabledContentColor = colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            border = BorderStroke(1.dp, resolvedBorderColor)
        ) {
            content()
        }
    }
}

@Composable
fun PulseTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val resolvedContentColor = contentColor ?: colorScheme.primary

    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = resolvedContentColor,
            disabledContentColor = colorScheme.onSurface.copy(alpha = 0.38f)
        )
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PulseSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbContent: @Composable (() -> Unit)? = null,
    trackColor: Color? = null,
    thumbColor: Color? = null
) {
    val motionTokens = LocalMotionTokens.current
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val isLiquidGlass = glassTokens.enableRefraction
    val isExpressive = motionTokens.springStiffness > 250f && !isLiquidGlass

    val resolvedTrackColor = trackColor ?: colorScheme.primary
    val resolvedThumbColor = thumbColor ?: colorScheme.onPrimary

    if (isLiquidGlass) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed = interactionSource.collectIsPressedAsState().value
        val thumbRadiusPx = with(density) { 13.dp.toPx() }
        val thumbTravelDistancePx = with(density) { 22.dp.toPx() }

        GlassSurface(
            modifier = modifier
                .width(52.dp)
                .height(30.dp)
                .border(1.dp, glassTokens.borderColor, RoundedCornerShape(15.dp))
                .graphicsLayer {
                    this.transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                },
            shape = RoundedCornerShape(15.dp),
            elevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .combinedClickable(
                        onClick = { onCheckedChange(!checked) },
                        interactionSource = interactionSource,
                        indication = null
                    )
                    .clip(RoundedCornerShape(15.dp))
            ) {
                val thumbPosition: Float by animateFloatAsState(
                    targetValue = if (checked) thumbTravelDistancePx else 0f,
                    animationSpec = spring(
                        dampingRatio = motionTokens.springDamping,
                        stiffness = motionTokens.springStiffness
                    ),
                    label = "switch_thumb"
                )

                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(26.dp)
                        .graphicsLayer { translationX = thumbPosition }
                        .background(
                            brush = Brush.radialGradient(
                                listOf(
                                    resolvedThumbColor,
                                    resolvedThumbColor.copy(alpha = 0.7f)
                                ),
                                center = Offset(thumbRadiusPx, thumbRadiusPx),
                                radius = thumbRadiusPx
                            ),
                            shape = CircleShape
                        )
                        .graphicsLayer {
                            if (isPressed) {
                                scaleX = 1.1f
                                scaleY = 1.1f
                            }
                        }
                        .clip(CircleShape)
                ) {
                    thumbContent?.invoke()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    resolvedTrackColor.copy(alpha = if (checked) 0.5f else 0.1f),
                                    resolvedTrackColor.copy(alpha = if (checked) 0.3f else 0.05f)
                                )
                            ),
                            shape = RoundedCornerShape(15.dp)
                        )
                        .clip(RoundedCornerShape(15.dp))
                )
            }
        }
    } else if (isExpressive) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = resolvedTrackColor,
                uncheckedTrackColor = colorScheme.outlineVariant,
                checkedThumbColor = resolvedThumbColor,
                uncheckedThumbColor = colorScheme.surfaceContainerHighest
            ),
            thumbContent = thumbContent
        )
    } else {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = resolvedTrackColor,
                uncheckedTrackColor = colorScheme.outlineVariant,
                checkedThumbColor = resolvedThumbColor,
                uncheckedThumbColor = colorScheme.surfaceContainerHighest
            ),
            thumbContent = thumbContent
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseChip(
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    containerColor: Color? = null,
    contentColor: Color? = null,
    borderColor: Color? = null
) {
    val motionTokens = LocalMotionTokens.current
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction
    val isExpressive = motionTokens.springStiffness > 250f && !isLiquidGlass

    val resolvedContainerColor = containerColor ?: (if (selected) colorScheme.primaryContainer else colorScheme.surfaceContainerHighest)
    val resolvedContentColor = contentColor ?: (if (selected) colorScheme.onPrimaryContainer else colorScheme.onSurface)
    val resolvedBorderColor = borderColor ?: (if (isLiquidGlass) glassTokens.borderColor else Color.Transparent)

    if (isLiquidGlass) {
        GlassSurface(
            modifier = modifier
                .height(36.dp)
                .fillMaxWidth()
                .border(1.dp, resolvedBorderColor, shape)
                .graphicsLayer {
                    this.transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                },
            shape = shape,
            elevation = 0.dp
        ) {
            ExpressiveSurface(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                containerColor = resolvedContainerColor.copy(alpha = glassTokens.surfaceAlpha),
                contentColor = resolvedContentColor,
                elevation = 0,
                onClick = onClick
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    label()
                }
            }
        }
    } else {
        FilterChip(
            selected = selected,
            onClick = { onClick?.invoke() },
            modifier = modifier.height(36.dp),
            shape = shape,
            border = BorderStroke(1.dp, resolvedBorderColor),
            colors = FilterChipDefaults.filterChipColors(
                containerColor = resolvedContainerColor,
                selectedContainerColor = resolvedContainerColor,
                disabledContainerColor = colorScheme.surfaceContainerHighest.copy(alpha = 0.12f),
                labelColor = resolvedContentColor,
                selectedLabelColor = resolvedContentColor,
                disabledLabelColor = colorScheme.onSurface.copy(alpha = 0.38f),
                iconColor = resolvedContentColor,
                selectedLeadingIconColor = resolvedContentColor,
                disabledLeadingIconColor = colorScheme.onSurface.copy(alpha = 0.38f),
                selectedTrailingIconColor = resolvedContentColor,
                disabledTrailingIconColor = colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            label = { label() }
        )
    }
}

@Composable
fun PulseCircularProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    color: Color? = null,
    trackColor: Color? = null,
    strokeWidth: Dp = 4.dp,
    size: Dp = 24.dp
) {
    val motionTokens = LocalMotionTokens.current
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction
    val isExpressive = motionTokens.springStiffness > 250f && !isLiquidGlass

    val resolvedColor = color ?: colorScheme.primary
    val resolvedTrackColor = trackColor ?: colorScheme.surfaceContainerHighest.copy(alpha = 0.3f)

    if (isLiquidGlass) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse_loader")
        val rotation: Float by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )

        val sweep: Float by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, delayMillis = 200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sweep"
        )

        Box(
            modifier = modifier.size(size).graphicsLayer { rotationZ = rotation },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(size)) {
                val stroke = strokeWidth.toPx()
                val radius = (this.size.minDimension - stroke) / 2f
                val center = this.center

                drawCircle(
                    color = resolvedTrackColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            resolvedColor.copy(alpha = 0.0f),
                            resolvedColor,
                            resolvedColor.copy(alpha = 0.6f),
                            resolvedColor.copy(alpha = 0.0f)
                        ),
                        center = center
                    ),
                    startAngle = rotation - sweep,
                    sweepAngle = sweep.coerceIn(10f, 300f),
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2)
                )

                val headAngle = (rotation - sweep + sweep.coerceIn(10f, 300f)) * PI / 180f
                val headX = center.x + radius * cos(headAngle).toFloat()
                val headY = center.y + radius * sin(headAngle).toFloat()
                drawCircle(
                    color = resolvedColor.copy(alpha = glassTokens.specularIntensity),
                    radius = stroke * 0.75f,
                    center = Offset(headX, headY)
                )
            }
        }
    } else if (isExpressive) {
        if (progress != null) {
            CircularProgressIndicator(
                progress = progress,
                modifier = modifier.size(size),
                color = resolvedColor,
                trackColor = resolvedTrackColor,
                strokeWidth = strokeWidth,
                strokeCap = StrokeCap.Round
            )
        } else {
            CircularProgressIndicator(
                modifier = modifier.size(size),
                color = resolvedColor,
                trackColor = resolvedTrackColor,
                strokeWidth = strokeWidth,
                strokeCap = StrokeCap.Round
            )
        }
    } else {
        if (progress != null) {
            CircularProgressIndicator(
                progress = progress,
                modifier = modifier.size(size),
                color = resolvedColor,
                trackColor = resolvedTrackColor,
                strokeWidth = strokeWidth
            )
        } else {
            CircularProgressIndicator(
                modifier = modifier.size(size),
                color = resolvedColor,
                trackColor = resolvedTrackColor,
                strokeWidth = strokeWidth
            )
        }
    }
}

@Composable
fun PulseLinearProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float,
    color: Color? = null,
    trackColor: Color? = null,
    height: Dp = 4.dp
) {
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction

    val resolvedColor = color ?: colorScheme.primary
    val resolvedTrackColor = trackColor ?: colorScheme.surfaceContainerHighest.copy(alpha = 0.3f)

    if (isLiquidGlass) {
        val infiniteTransition = rememberInfiniteTransition(label = "linear_loader")
        val shimmer: Float by infiniteTransition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, delayMillis = 500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer"
        )

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(resolvedTrackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height / 2))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val heightPx = size.height

                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                resolvedColor,
                                resolvedColor.copy(alpha = 0.3f),
                                resolvedColor.copy(alpha = 0.6f),
                                resolvedColor
                            ),
                            startX = 0f,
                            endX = width
                        ),
                        topLeft = Offset.Zero,
                        size = Size(width, heightPx)
                    )

                    val shimmerWidth = 200.dp.toPx()
                    val startOffset = -100.dp.toPx() + (shimmer * (width + shimmerWidth))
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                glassTokens.specularColor.copy(alpha = 0.4f),
                                Color.Transparent
                            ),
                            startX = startOffset,
                            endX = startOffset + shimmerWidth
                        ),
                        topLeft = Offset(startOffset, 0f),
                        size = Size(shimmerWidth, heightPx)
                    )
                }
            }
        }
    } else {
        LinearProgressIndicator(
            progress = progress,
            modifier = modifier.fillMaxWidth().height(height),
            color = resolvedColor,
            trackColor = resolvedTrackColor,
            strokeCap = StrokeCap.Round
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets
) {
    val motionTokens = LocalMotionTokens.current
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction
    val isExpressive = motionTokens.springStiffness > 250f && !isLiquidGlass

    val barHeight = 64.dp
    val elevationDp = if (isLiquidGlass) 0.dp else motionTokens.elevationLevels.level1

    if (isLiquidGlass) {
        GlassSurface(
            modifier = modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .height(barHeight),
            shape = RoundedCornerShape(0.dp),
            elevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                navigationIcon?.invoke()
                Spacer(modifier = Modifier.weight(1f))
                Box(contentAlignment = Alignment.Center) { title() }
                Spacer(modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.End, content = actions)
            }
        }
    } else {
        TopAppBar(
            modifier = modifier
                .fillMaxWidth()
                .shadow(elevationDp),
            title = title,
            navigationIcon = { navigationIcon?.invoke() },
            actions = actions,
            windowInsets = windowInsets,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colorScheme.surfaceContainer.copy(alpha = if (isExpressive) 0.9f else 1f),
                titleContentColor = colorScheme.onSurface,
                navigationIconContentColor = colorScheme.onSurface,
                actionIconContentColor = colorScheme.onSurface
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseBottomBar(
    items: List<PulseBottomBarItem>,
    selectedIndex: Int,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = NavigationBarDefaults.windowInsets
) {
    val motionTokens = LocalMotionTokens.current
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction

    val animatedIndex: Float by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = motionTokens.springDamping,
            stiffness = motionTokens.springStiffness
        ),
        label = "nav_index"
    )

    if (isLiquidGlass) {
        GlassSurface(
            modifier = modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .height(68.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            elevation = 0.dp
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = selectedIndex == index
                    val iconScale: Float by animateFloatAsState(
                        targetValue = if (isSelected) 1.15f else 1f,
                        animationSpec = spring(
                            dampingRatio = motionTokens.springDamping,
                            stiffness = motionTokens.springStiffness
                        ),
                        label = "tab_icon_$index"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onItemClick(index) }
                            .padding(top = 8.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = item.title,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    } else {
        val isExpressive = motionTokens.springStiffness > 250f
        Surface(
            color = colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .height(68.dp),
            border = if (isExpressive) BorderStroke(1.dp, colorScheme.outlineVariant) else null,
            shadowElevation = motionTokens.elevationLevels.level3
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val indicatorWidth = 50.dp
                val indicatorHeight = 32.dp
                val itemWidth = if (items.isNotEmpty()) maxWidth / items.size else 0.dp

                Box(
                    modifier = Modifier
                        .offset(
                            x = itemWidth * animatedIndex + (itemWidth - indicatorWidth) / 2,
                            y = 8.dp
                        )
                        .width(indicatorWidth)
                        .height(indicatorHeight)
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    colorScheme.primary.copy(alpha = 0.7f),
                                    colorScheme.primary
                                )
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .graphicsLayer {
                            val movementDiff = selectedIndex.toFloat() - animatedIndex
                            val stretchFactorX = 1f + (abs(movementDiff) * 0.35f).coerceAtMost(0.5f)
                            val squashFactorY = 1f - (abs(movementDiff) * 0.15f).coerceAtMost(0.25f)
                            val dynamicTilt = (-movementDiff * 2.5f).coerceIn(-5f, 5f)
                            this.scaleX = stretchFactorX
                            this.scaleY = squashFactorY
                            this.rotationZ = dynamicTilt
                        }
                        .clip(RoundedCornerShape(16.dp))
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, item ->
                        val isSelected = selectedIndex == index
                        val iconScale: Float by animateFloatAsState(
                            targetValue = if (isSelected) 1.12f else 1f,
                            animationSpec = spring(
                                dampingRatio = motionTokens.springDamping,
                                stiffness = motionTokens.springStiffness
                            ),
                            label = "tab_icon_$index"
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { onItemClick(index) }
                                .padding(top = 8.dp, bottom = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(32.dp)
                                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    tint = if (isSelected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = item.title,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) colorScheme.onPrimary else colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

data class PulseBottomBarItem(
    val route: String,
    val title: String,
    val icon: ImageVector
)

@Composable
fun PulseSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color? = null,
    contentColor: Color? = null,
    elevation: Int = 0,
    content: @Composable BoxScope.() -> Unit
) {
    val motionTokens = LocalMotionTokens.current
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction
    val isExpressive = motionTokens.springStiffness > 250f && !isLiquidGlass

    val resolvedContainerColor = containerColor ?: (if (isLiquidGlass) glassTokens.tintColor.copy(alpha = glassTokens.surfaceAlpha) else colorScheme.surface)
    val resolvedContentColor = contentColor ?: colorScheme.onSurface

    if (isLiquidGlass) {
        GlassSurface(
            modifier = modifier,
            shape = shape,
            elevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(resolvedContainerColor, shape)
            ) {
                content()
            }
        }
    } else if (isExpressive) {
        val elevationDp = when (elevation) {
            0 -> motionTokens.elevationLevels.level0
            1 -> motionTokens.elevationLevels.level1
            2 -> motionTokens.elevationLevels.level2
            3 -> motionTokens.elevationLevels.level3
            4 -> motionTokens.elevationLevels.level4
            else -> motionTokens.elevationLevels.level5
        }

        Surface(
            modifier = modifier,
            shape = shape,
            color = resolvedContainerColor,
            contentColor = resolvedContentColor,
            shadowElevation = elevationDp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = resolvedContainerColor,
            contentColor = resolvedContentColor,
            shadowElevation = elevation.toFloat().dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

@Composable
fun PulseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null
) {
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction

    val containerColor = if (isLiquidGlass) {
        glassTokens.tintColor.copy(alpha = glassTokens.surfaceAlpha)
    } else {
        colorScheme.surfaceContainer
    }

    val borderColor = if (isLiquidGlass) {
        glassTokens.borderColor
    } else if (isError) {
        colorScheme.error
    } else {
        colorScheme.outlineVariant
    }

    val focusedBorderColor = if (isLiquidGlass) {
        glassTokens.specularColor
    } else if (isError) {
        colorScheme.error
    } else {
        colorScheme.primary
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText,
        shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colorScheme.onSurface,
            unfocusedTextColor = colorScheme.onSurface,
            disabledTextColor = colorScheme.onSurface.copy(alpha = 0.38f),
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor,
            disabledContainerColor = containerColor.copy(alpha = 0.12f),
            cursorColor = colorScheme.primary,
            focusedBorderColor = focusedBorderColor,
            unfocusedBorderColor = borderColor,
            disabledBorderColor = borderColor.copy(alpha = 0.12f),
            focusedLabelColor = focusedBorderColor,
            unfocusedLabelColor = colorScheme.onSurfaceVariant,
            disabledLabelColor = colorScheme.onSurface.copy(alpha = 0.38f),
            focusedPlaceholderColor = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            unfocusedPlaceholderColor = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            focusedLeadingIconColor = focusedBorderColor,
            unfocusedLeadingIconColor = colorScheme.onSurfaceVariant,
            disabledLeadingIconColor = colorScheme.onSurface.copy(alpha = 0.38f),
            focusedTrailingIconColor = focusedBorderColor,
            unfocusedTrailingIconColor = colorScheme.onSurfaceVariant,
            disabledTrailingIconColor = colorScheme.onSurface.copy(alpha = 0.38f),
            focusedSupportingTextColor = focusedBorderColor,
            unfocusedSupportingTextColor = colorScheme.onSurfaceVariant,
            disabledSupportingTextColor = colorScheme.onSurface.copy(alpha = 0.38f),
            errorContainerColor = colorScheme.errorContainer,
            errorTextColor = colorScheme.onErrorContainer
        )
    )
}

@Composable
fun PulseDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null
) {
    val glassTokens = LocalGlassEffect.current
    val colorScheme = MaterialTheme.colorScheme
    val isLiquidGlass = glassTokens.enableRefraction

    val shape = RoundedCornerShape(24.dp)
    val containerColor = if (isLiquidGlass) {
        glassTokens.tintColor.copy(alpha = 0.9f)
    } else {
        colorScheme.surfaceContainer
    }

    if (isLiquidGlass) {
        GlassSurface(
            modifier = modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .graphicsLayer { this.transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center },
            shape = shape,
            elevation = 0.dp
        ) {
            AlertDialog(
                onDismissRequest = onDismissRequest,
                title = title,
                text = text,
                confirmButton = { confirmButton?.invoke() },
                dismissButton = dismissButton?.let { btn -> { btn() } },
                containerColor = containerColor
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            title = title,
            text = text,
            confirmButton = { confirmButton?.invoke() },
            dismissButton = dismissButton?.let { btn -> { btn() } },
            containerColor = containerColor
        )
    }
}