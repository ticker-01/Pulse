package com.example.pulse.utils

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.pulse.viewmodel.LessonPair
import com.example.pulse.worker.LessonReminderReceiver
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object LessonAlarmManager {

    @SuppressLint("ScheduleExactAlarm")
    fun scheduleAlarms(context: Context, scheduleMap: Map<LocalDate, List<LessonPair>>) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)

        val remindersEnabled = prefs.getBoolean("lesson_reminders_enabled", true)
        val reminderOffset = prefs.getInt("reminder_offset_minutes", 15)
        val notifyStart = prefs.getBoolean("notify_lesson_start", false)
        val notifyEnd = prefs.getBoolean("notify_lesson_end", false)
        val liveEnabled = prefs.getBoolean("live_notifications_enabled", false)

        SystemAlarmHelper.syncAutoAlarmForTomorrow(context, scheduleMap)

        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val now = System.currentTimeMillis()

        val lessonsToSchedule = mutableListOf<Pair<LocalDate, LessonPair>>()
        scheduleMap[today]?.let { lessonsToSchedule.addAll(it.map { l -> today to l }) }
        scheduleMap[tomorrow]?.let { lessonsToSchedule.addAll(it.map { l -> tomorrow to l }) }

        fun setSingleAlarm(timeMillis: Long, alarmType: String, lesson: LessonPair, date: LocalDate, idOffset: Int) {
            if (timeMillis <= now) return

            val intent = Intent(context, LessonReminderReceiver::class.java).apply {
                putExtra("subject", lesson.subject.substringBefore(" (").trim())
                putExtra("room", lesson.room)
                putExtra("teacher", lesson.teacher)
                putExtra("pair_number", lesson.pairNumber)
                putExtra("alarm_type", alarmType)
                putExtra("start_time", lesson.startTime)
                putExtra("end_time", lesson.endTime)
            }

            val requestCode = (date.hashCode() + lesson.startTime.hashCode() + idOffset)

            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMillis, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMillis, pendingIntent)
            }
        }

        lessonsToSchedule.forEach { (date, lesson) ->
            try {
                val startTime = lesson.startLocalTime ?: return@forEach
                val endTime = lesson.endLocalTime ?: return@forEach
                val startMillis = date.atTime(startTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endMillis = date.atTime(endTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                if (remindersEnabled) {
                    val preMillis = startMillis - (reminderOffset * 60 * 1000)
                    setSingleAlarm(preMillis, "PRE", lesson, date, 1)
                }

                if (notifyStart || liveEnabled) {
                    setSingleAlarm(startMillis, "START", lesson, date, 2)
                }

                if (notifyEnd || liveEnabled) {
                    setSingleAlarm(endMillis, "END", lesson, date, 3)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (liveEnabled) {
            LiveNotificationManager.syncCurrentLiveStatus(context)
        } else {
            LiveNotificationManager.clear(context)
        }
    }
}