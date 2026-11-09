package com.example.pulse.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.pulse.R
import com.example.pulse.screen.MainActivity
import com.example.pulse.viewmodel.LessonPair
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object LiveNotificationManager {

    private const val CHANNEL_ID = "pulse_live_lesson_v2"
    const val NOTIFICATION_ID = 4004

    fun createLiveNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Текущая пара (Шторка и экран блокировки)",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Таймер пары и аудитория на экране блокировки"
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showOngoingLesson(
        context: Context,
        lesson: LessonPair,
        isForegroundService: Boolean = false,
        service: Service? = null
    ) {
        createLiveNotificationChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val endTime = lesson.endLocalTime ?: return

        val endEpochMillis = LocalDate.now()
            .atTime(endTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cleanSubject = lesson.subject.substringBefore("(").trim()
        val shortInfo = "Ауд. ${lesson.room} • ${lesson.pairNumber} пара"

        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val isDynamic = prefs.getBoolean("dynamic_color_enabled", false)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(cleanSubject)
            .setContentText(shortInfo)
            .setSubText("Идёт пара")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(endEpochMillis)
            .setShowWhen(true)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("${lesson.pairNumber} пара: $cleanSubject")
                    .bigText("Аудитория: ${lesson.room}\nПреподаватель: ${lesson.teacher.ifBlank { "Не указан" }}")
                    .setSummaryText("До звонка")
            )

        if (isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setColor(context.getColor(android.R.color.system_accent1_500))
        } else {
            builder.setColor(0xFF6C48E1.toInt())
        }

        val notification = builder.build()

        if (isForegroundService && service != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    service.startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    service.startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                manager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun showBreak(
        context: Context,
        nextLesson: LessonPair,
        isForegroundService: Boolean = false,
        service: Service? = null
    ) {
        createLiveNotificationChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val startTime = nextLesson.startLocalTime ?: return

        val startEpochMillis = LocalDate.now()
            .atTime(startTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cleanSubject = nextLesson.subject.substringBefore("(").trim()
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val isDynamic = prefs.getBoolean("dynamic_color_enabled", false)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Перемена • Далее: $cleanSubject")
            .setContentText("Кабинет ${nextLesson.room} • в ${nextLesson.startTime}")
            .setSubText("Перерыв")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(startEpochMillis)
            .setShowWhen(true)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("Перемена • Следующая: $cleanSubject")
                    .bigText("Аудитория: ${nextLesson.room}\nПреподаватель: ${nextLesson.teacher.ifBlank { "Не указан" }}")
                    .setSummaryText("До звонка на урок")
            )

        if (isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setColor(context.getColor(android.R.color.system_accent1_500))
        } else {
            builder.setColor(0xFF6C48E1.toInt())
        }

        val notification = builder.build()

        if (isForegroundService && service != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    service.startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    service.startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                manager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun clear(context: Context, service: Service? = null) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                it.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                it.stopForeground(true)
            }
        }
        manager.cancel(NOTIFICATION_ID)
    }

    fun triggerTestNotification(context: Context) {
        val testLesson = LessonPair(
            pairNumber = 2,
            lessons = "",
            startTime = LocalTime.now().minusMinutes(15).toString().substring(0, 5),
            endTime = LocalTime.now().plusMinutes(45).toString().substring(0, 5),
            subject = "Тестовая пара",
            room = "305",
            teacher = "Pulse Live"
        )
        LiveLessonService.start(context, testLesson)
    }

    fun syncCurrentLiveStatus(context: Context) {
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val liveEnabled = prefs.getBoolean("live_notifications_enabled", false)
        if (!liveEnabled) {
            LiveLessonService.stop(context)
            clear(context)
            return
        }

        val jsonString = prefs.getString("widget_lessons_today", "[]") ?: "[]"
        val lessons = mutableListOf<LessonPair>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                lessons.add(
                    LessonPair(
                        pairNumber = obj.optInt("pairNumber", 0),
                        lessons = obj.optString("lessons", ""),
                        startTime = obj.optString("startTime", ""),
                        endTime = obj.optString("endTime", ""),
                        subject = obj.optString("subject", "").substringBefore(" (").trim(),
                        room = obj.optString("room", "—"),
                        teacher = obj.optString("teacher", "—")
                    )
                )
            }
        } catch (_: Exception) {
            return
        }

        val now = LocalTime.now()
        val sorted = lessons.sortedBy { it.startLocalTime ?: LocalTime.MIN }

        for (i in sorted.indices) {
            val lesson = sorted[i]
            val start = lesson.startLocalTime ?: continue
            val end = lesson.endLocalTime ?: continue

            if ((now.isAfter(start) || now == start) && now.isBefore(end)) {
                LiveLessonService.start(context, lesson)
                return
            }

            if (i < sorted.size - 1) {
                val nextLesson = sorted[i + 1]
                val nextStart = nextLesson.startLocalTime ?: continue
                if ((now.isAfter(end) || now == end) && now.isBefore(nextStart)) {
                    showBreak(context, nextLesson)
                    return
                }
            }
        }

        LiveLessonService.stop(context)
        clear(context)
    }
}