package com.example.pulse.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.pulse.data.PulseNotificationManager
import com.example.pulse.screen.NotificationType
import com.example.pulse.utils.LiveNotificationManager
import com.example.pulse.viewmodel.LessonPair

class LessonReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val subject = intent.getStringExtra("subject") ?: ""
        val room = intent.getStringExtra("room") ?: ""
        val teacher = intent.getStringExtra("teacher") ?: ""
        val pairNumber = intent.getIntExtra("pair_number", 1)
        val alarmType = intent.getStringExtra("alarm_type") ?: ""
        val startTime = intent.getStringExtra("start_time") ?: ""
        val endTime = intent.getStringExtra("end_time") ?: ""

        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val notifyStart = prefs.getBoolean("notify_lesson_start", false)
        val notifyEnd = prefs.getBoolean("notify_lesson_end", false)
        val liveEnabled = prefs.getBoolean("live_notifications_enabled", false)

        when (alarmType) {
            "PRE" -> {
                PulseNotificationManager.sendAndSaveNotification(
                    context = context,
                    title = "Скоро пара: $subject",
                    message = "Кабинет $room • Начало в $startTime",
                    type = NotificationType.LESSON_REMINDER,
                    isImportant = false
                )
            }

            "START" -> {
                if (notifyStart) {
                    PulseNotificationManager.sendAndSaveNotification(
                        context = context,
                        title = "Звонок на пару!",
                        message = "$pairNumber пара: $subject в ауд. $room",
                        type = NotificationType.LESSON_REMINDER,
                        isImportant = false
                    )
                }

                if (liveEnabled) {
                    val lesson = LessonPair(
                        pairNumber = pairNumber,
                        lessons = "",
                        startTime = startTime,
                        endTime = endTime,
                        subject = subject,
                        room = room,
                        teacher = teacher
                    )
                    LiveNotificationManager.showOngoingLesson(context, lesson)
                }
            }

            "END" -> {
                if (notifyEnd) {
                    PulseNotificationManager.sendAndSaveNotification(
                        context = context,
                        title = "Пара завершена!",
                        message = "Занятие «$subject» окончено",
                        type = NotificationType.LESSON_REMINDER,
                        isImportant = false
                    )
                }

                if (liveEnabled) {
                    LiveNotificationManager.syncCurrentLiveStatus(context)
                }
            }
        }
    }
}