package com.example.pulse.screen

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.pulse.ui.theme.PulseButton
import com.example.pulse.ui.theme.PulseCard
import com.example.pulse.ui.theme.PulseChip
import com.example.pulse.ui.theme.PulseOutlinedButton
import com.example.pulse.ui.theme.PulseSwitch
import com.example.pulse.ui.theme.PulseTextButton
import com.example.pulse.ui.theme.PulseTopAppBar
import com.example.pulse.utils.AppTheme
import com.example.pulse.utils.ThemeConfig
import com.example.pulse.utils.ThemeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE) }
    val haptic = LocalHapticFeedback.current

    var currentConfig by remember {
        mutableStateOf(ThemeManager.load(context))
    }

    var showPreviewDialog by remember { mutableStateOf(false) }
    var previewConfig by remember { mutableStateOf<ThemeConfig?>(null) }

    fun applyTheme(config: ThemeConfig) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        currentConfig = config
        ThemeManager.save(context, config)
        prefs.edit()
            .putBoolean("dynamic_color_enabled", config.useDynamicColor)
            .apply()
    }

    val themes = listOf(
        AppTheme.ORIGINAL,
        AppTheme.EXPRESSIVE,
        AppTheme.LIQUID_GLASS
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                PulseTopAppBar(
                    title = { Text("Кастомизация", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.Default.ArrowBackIosNew,
                                contentDescription = "Назад",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(16.dp)) }

                item {
                    Text("Тема приложения", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
                item {
                    Text(
                        "Выберите визуальный стиль интерфейса. Изменения применяются сразу.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                items(themes) { theme ->
                    ThemeCard(
                        theme = theme,
                        isSelected = currentConfig.theme == theme,
                        onClick = {
                            val newConfig = currentConfig.copy(theme = theme)
                            applyTheme(newConfig)
                        },
                        onPreview = {
                            previewConfig = currentConfig.copy(theme = theme)
                            showPreviewDialog = true
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }

                item {
                    Text("Дополнительные настройки", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }

                item {
                    if (currentConfig.theme == AppTheme.EXPRESSIVE || currentConfig.theme == AppTheme.LIQUID_GLASS) {
                        DynamicColorToggle(
                            enabled = currentConfig.useDynamicColor,
                            onChange = { enabled ->
                                val newConfig = currentConfig.copy(useDynamicColor = enabled)
                                applyTheme(newConfig)
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                item {
                    AccentColorPicker(
                        currentColor = currentConfig.accentColor,
                        onColorChange = { color ->
                            val newConfig = currentConfig.copy(accentColor = color)
                            applyTheme(newConfig)
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }

                item {
                    PulseButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            navController.popBackStack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        content = { Text("Готово", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    )
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showPreviewDialog) {
        previewConfig?.let { cfg ->
            ThemePreviewDialog(
                config = cfg,
                onDismiss = { showPreviewDialog = false },
                onApply = { config ->
                    applyTheme(config)
                    showPreviewDialog = false
                }
            )
        }
    }
}

@Composable
fun ThemeCard(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPreview: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "card_anim")
    val glowProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow"
    )

    val (icon, iconBgColor, accentColor) = when (theme) {
        AppTheme.ORIGINAL -> Triple(Icons.Filled.Palette, Color(0xFF6C48E1), Color(0xFF6C48E1))
        AppTheme.EXPRESSIVE -> Triple(Icons.Filled.AutoAwesome, Color(0xFF00B0FF), Color(0xFF00B0FF))
        AppTheme.LIQUID_GLASS -> Triple(Icons.Filled.AutoAwesome, Color(0xFF00E5FF), Color(0xFF00E5FF))
    }

    val selectionScale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "selection_scale"
    )

    val borderColor by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.25f,
        animationSpec = tween(300),
        label = "border_alpha"
    )

    PulseCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = selectionScale; scaleY = selectionScale }
            .border(
                width = 1.5.dp,
                color = accentColor.copy(alpha = borderColor),
                shape = RoundedCornerShape(18.dp)
            ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        if (theme == AppTheme.EXPRESSIVE) {
                            rotationZ = (glowProgress * 360f).coerceIn(0f, 360f)
                        }
                    }
                    .clip(RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconBgColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = theme.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = theme.description,
                    fontSize = 13.sp
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Выбрано",
                    tint = accentColor,
                    modifier = Modifier.size(24.dp).padding(end = 8.dp)
                )
            } else {
                IconButton(onClick = onPreview) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Предпросмотр",
                        modifier = Modifier.size(20.dp).padding(end = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DynamicColorToggle(
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dynamic_toggle")
    val shimmer by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, delayMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    PulseCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!enabled) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        if (enabled) {
                            rotationZ = (shimmer * 360f).coerceIn(0f, 360f)
                        }
                    }
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Динамические цвета (Material You)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Подстройка под обои системы (Android 12+)",
                    fontSize = 12.sp
                )
            }

            PulseSwitch(
                checked = enabled,
                onCheckedChange = onChange
            )
        }
    }
}

@Composable
fun AccentColorPicker(
    currentColor: Int,
    onColorChange: (Int) -> Unit
) {
    val colors: List<Int> = listOf(
        0xFF6C48E1.toInt(),
        0xFF00B0FF.toInt(),
        0xFF00E5FF.toInt(),
        0xFFFF6B35.toInt(),
        0xFF00C853.toInt(),
        0xFFAA00FF.toInt(),
        0xFFFF3D00.toInt(),
        0xFF2979FF.toInt(),
        0xFF651FFF.toInt(),
        0xFFD500F9.toInt(),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Акцентный цвет", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 2.dp).fillMaxWidth()
        ) {
            items(colors) { colorInt: Int ->
                val color = Color(colorInt)
                val isSelected = currentColor == colorInt

                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.15f else 1f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
                    label = "color_scale"
                )

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clickable { onColorChange(colorInt) }
                        .clip(CircleShape)
                        .border(2.dp, if (isSelected) Color.White else Color.Transparent, CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(color, CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
fun ThemePreviewDialog(
    config: ThemeConfig,
    onDismiss: () -> Unit,
    onApply: (ThemeConfig) -> Unit
) {
    ThemeManager.provideTheme(config) {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            title = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Предпросмотр: ${config.theme.displayName}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть", modifier = Modifier.size(20.dp))
                    }
                }
            },
            text = {
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    PreviewContentCard()
                    Spacer(modifier = Modifier.height(16.dp))
                    PreviewButtonCard()
                    Spacer(modifier = Modifier.height(8.dp))
                    PreviewChipCard()
                    Spacer(modifier = Modifier.height(8.dp))
                    PreviewSwitchCard()
                }
            },
            confirmButton = {
                PulseButton(
                    onClick = { onApply(config) },
                    content = { Text("Применить", fontWeight = FontWeight.Bold) }
                )
            },
            dismissButton = {
                PulseTextButton(onClick = onDismiss, content = { Text("Отмена") })
            }
        )
    }
}

@Composable
fun PreviewContentCard() {
    PulseCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Карточка расписания", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Математика", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("08:30 – 09:15 | Ауд. 301", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                ) {
                    Text(
                        "1 пара",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PreviewButtonCard() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PulseButton(
            onClick = {},
            modifier = Modifier.weight(1f).height(44.dp),
            content = { Text("Основная", fontWeight = FontWeight.Medium) }
        )
        PulseOutlinedButton(
            onClick = {},
            modifier = Modifier.weight(1f).height(44.dp),
            content = { Text("Вторичная", fontWeight = FontWeight.Medium) }
        )
    }
}

@Composable
fun PreviewChipCard() {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(listOf("Сегодня", "Неделя", "Месяц", "Настройки")) { label ->
            PulseChip(
                selected = false,
                onClick = {},
                label = {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(36.dp)
            )
        }
    }
}

@Composable
fun PreviewSwitchCard() {
    var checked by remember { mutableStateOf(true) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Уведомления о парах", fontSize = 14.sp)
        PulseSwitch(
            checked = checked,
            onCheckedChange = { checked = it }
        )
    }
}