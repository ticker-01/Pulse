package com.example.pulse.screen

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.pulse.data.PulseNotificationManager
import org.json.JSONArray
import java.util.UUID
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(navController: NavController) {
    var selectedTab by remember { mutableStateOf("Все") }
    val context = LocalContext.current

    var notificationsList by remember { mutableStateOf(PulseNotificationManager.loadNotifications(context)) }

    LaunchedEffect(Unit) {
        notificationsList = PulseNotificationManager.loadNotifications(context)

        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val hasSeenWelcome = prefs.getBoolean("has_seen_welcome_notif", false)

        if (notificationsList.isEmpty() && !hasSeenWelcome) {
            PulseNotificationManager.sendAndSaveNotification(
                context,
                "Добро пожаловать в МГКАП",
                "Уведомления включены. Мы сообщим, если расписание изменится!",
                NotificationType.REMINDER,
                false
            )
            notificationsList = PulseNotificationManager.loadNotifications(context)
            prefs.edit().putBoolean("has_seen_welcome_notif", true).apply()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Назад",
                    tint = Color.White
                )
            }

            Text(
                text = "Уведомления",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            IconButton(onClick = { navController.navigate("settings") { launchSingleTop = true } }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Настройки",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(14.dp))
                .border(1.dp, PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                .padding(4.dp)
        ) {
            TabButton(
                text = "Все",
                isSelected = selectedTab == "Все",
                modifier = Modifier.weight(1f)
            ) { selectedTab = "Все" }

            TabButton(
                text = "Важные",
                isSelected = selectedTab == "Важные",
                modifier = Modifier.weight(1f)
            ) { selectedTab = "Важные" }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val filteredList = notificationsList.filter { if (selectedTab == "Важные") it.isImportant else true }

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Уведомлений пока нет",
                    color = TextGray,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(filteredList, key = { it.id }) { notification ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                notificationsList = notificationsList.filterNot { it.id == notification.id }
                                removeNotificationLocally(context, notification)
                                true
                            } else {
                                false
                            }
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            SwipeLiquidBackground(dismissState)
                        },
                        content = {
                            NotificationItemCard(notification)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeLiquidBackground(dismissState: SwipeToDismissBoxState) {
    val infiniteTransition = rememberInfiniteTransition(label = "smooth_wave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "smooth_phase"
    )

    val offset = try {
        val raw = dismissState.requireOffset()
        if (raw.isNaN()) 0f else raw
    } catch (_: Exception) {
        0f
    }
    val swipeDist = if (offset < 0f) -offset else 0f

    val isReadyToDelete = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart

    val iconScale by animateFloatAsState(
        targetValue = if (isReadyToDelete) 1.22f else 0.85f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "icon_scale"
    )
    val iconColor by animateColorAsState(
        targetValue = if (isReadyToDelete) Color.White else Color.White.copy(alpha = 0.5f),
        animationSpec = tween(200),
        label = "icon_color"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Transparent),
        contentAlignment = Alignment.CenterEnd
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            if (swipeDist > 2f && width > 0f && height > 0f) {
                val startX = (width - swipeDist).coerceAtLeast(0f)
                val centerY = height / 2f
                val breathing = sin(wavePhase) * 5.dp.toPx()
                val crestX = (startX - 26.dp.toPx() - breathing).coerceAtLeast(0f)

                val path = Path().apply {
                    moveTo(width, 0f)
                    lineTo(startX, 0f)

                    cubicTo(
                        x1 = startX,
                        y1 = centerY * 0.45f,
                        x2 = crestX,
                        y2 = centerY * 0.65f,
                        x3 = crestX,
                        y3 = centerY
                    )

                    cubicTo(
                        x1 = crestX,
                        y1 = centerY + (centerY * 0.35f),
                        x2 = startX,
                        y2 = centerY + (centerY * 0.55f),
                        x3 = startX,
                        y3 = height
                    )

                    lineTo(width, height)
                    close()
                }

                drawPath(path, Color(0xFFE91E63))
            }
        }

        Icon(
            imageVector = Icons.Default.DeleteSweep,
            contentDescription = "Удалить",
            tint = iconColor,
            modifier = Modifier
                .padding(end = 22.dp)
                .scale(iconScale)
        )
    }
}

@Composable
fun NotificationItemCard(notification: NotificationItem) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(notification.type.bgColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = notification.type.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = notification.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = notification.time,
                        color = TextGray,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = notification.message,
                    color = TextGray,
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

fun removeNotificationLocally(context: Context, target: NotificationItem) {
    val prefs = context.getSharedPreferences("PulseNotificationsPrefs", Context.MODE_PRIVATE)
    val jsonStr = prefs.getString("notifications_list", "[]") ?: "[]"
    try {
        val array = JSONArray(jsonStr)
        val newArray = JSONArray()
        var deleted = false

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (!deleted &&
                obj.optString("title") == target.title &&
                obj.optString("message") == target.message &&
                obj.optString("time") == target.time
            ) {
                deleted = true
                continue
            }
            newArray.put(obj)
        }

        prefs.edit().putString("notifications_list", newArray.toString()).apply()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Composable
fun TabButton(text: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) PrimaryPurple else Color.Transparent),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(0.dp),
        elevation = null
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.White else TextGray,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

enum class NotificationType(val defaultBgColor: Color, val icon: ImageVector) {
    SCHEDULE_CHANGE(Color(0xFF6C48E1), Icons.Default.EventBusy),
    NEW_COMMENT(Color(0xFFFF9100), Icons.Outlined.ChatBubbleOutline),
    REMINDER(Color(0xFF00C853), Icons.Default.Assignment),
    LESSON_REMINDER(Color(0xFF2196F3), Icons.Default.NotificationsActive);

    val bgColor: Color
        get() = if (this == SCHEDULE_CHANGE) PrimaryPurple else defaultBgColor
}

data class NotificationItem(
    val title: String,
    val message: String,
    val time: String,
    val type: NotificationType,
    val isImportant: Boolean = false,
    val id: String = UUID.randomUUID().toString()
)