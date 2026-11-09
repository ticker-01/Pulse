package com.example.pulse.utils

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.example.pulse.viewmodel.LessonPair
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object SystemAlarmHelper {

    fun syncAutoAlarmForTomorrow(
        context: Context,
        scheduleMap: Map<LocalDate, List<LessonPair>>
    ) {
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("auto_alarm_enabled", false)
        if (!isEnabled) return

        val tomorrow = LocalDate.now().plusDays(1)
        val tomorrowLessons = scheduleMap[tomorrow] ?: emptyList()
        if (tomorrowLessons.isEmpty()) return

        val prepMinutes = prefs.getInt("auto_alarm_prep_minutes", 60)

        val sortedLessons = tomorrowLessons.sortedBy {
            it.startLocalTime ?: LocalTime.MIN
        }
        val firstLesson = sortedLessons.firstOrNull() ?: return

        // Ограничиваем максимум 4-й парой
        val targetSlot = if (firstLesson.pairNumber > 4) 4 else firstLesson.pairNumber.coerceIn(1, 4)

        val startTimeStr = if (targetSlot == firstLesson.pairNumber) {
            firstLesson.startTime
        } else {
            getBellStartTimeForLesson(context, tomorrow, targetSlot)
        }

        try {
            val lessonTime = DateTimeUtils.parseLocalTimeOrNull(startTimeStr) ?: return
            val wakeUpTime = lessonTime.minusMinutes(prepMinutes.toLong())

            val alarmTitle = "$targetSlot пара • Pulse"

            val lastSetKey = "last_auto_alarm_state"
            val currentState = "${tomorrow}_${targetSlot}_${wakeUpTime}"
            if (prefs.getString(lastSetKey, "") == currentState) return

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, wakeUpTime.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, wakeUpTime.minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, alarmTitle)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(intent)
            prefs.edit().putString(lastSetKey, currentState).apply()
            Log.d("SystemAlarmHelper", "Автобудильник установлен: $alarmTitle на $wakeUpTime")
        } catch (e: Exception) {
            Log.e("SystemAlarmHelper", "Ошибка установки будильника", e)
        }
    }

    fun triggerAutoAlarmNow(context: Context) {
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("schedule_history", null) ?: return

        try {
            val root = JSONObject(jsonStr)
            val scheduleMap = mutableMapOf<LocalDate, List<LessonPair>>()

            val keys = root.keys()
            while (keys.hasNext()) {
                val dateStr = keys.next()
                val date = DateTimeUtils.parseLocalDateOrNull(dateStr) ?: continue
                val array = root.optJSONArray(dateStr) ?: continue
                val lessons = mutableListOf<LessonPair>()

                for (i in 0 until array.length()) {
                    try {
                        val obj = array.getJSONObject(i)
                        lessons.add(
                            LessonPair(
                                pairNumber = obj.optInt("pairNumber", i + 1),
                                lessons = obj.optString("lessons", ""),
                                startTime = obj.optString("startTime", ""),
                                endTime = obj.optString("endTime", ""),
                                subject = obj.optString("subject", ""),
                                room = obj.optString("room", ""),
                                teacher = obj.optString("teacher", ""),
                                group = obj.optString("group", "")
                            )
                        )
                    } catch (_: Exception) {}
                }
                if (lessons.isNotEmpty()) {
                    scheduleMap[date] = lessons
                }
            }

            syncAutoAlarmForTomorrow(context, scheduleMap)
        } catch (e: Exception) {
            Log.e("SystemAlarmHelper", "Ошибка чтения кэша для будильника", e)
        }
    }

    private fun getBellStartTimeForLesson(context: Context, date: LocalDate, lessonNum: Int): String {
        val prefs = context.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val shortenedDays = prefs.getStringSet("shortened_days", emptySet()) ?: emptySet()
        val isShortened = shortenedDays.contains(date.toString())

        val dayKey = when {
            isShortened -> "SHORTENED"
            date.dayOfWeek == DayOfWeek.MONDAY -> "MONDAY"
            date.dayOfWeek == DayOfWeek.THURSDAY -> "THURSDAY"
            date.dayOfWeek == DayOfWeek.SATURDAY -> "SATURDAY"
            else -> "REGULAR"
        }

        val customJson = prefs.getString("custom_bell_schedule", null)
        if (customJson != null) {
            try {
                val root = JSONObject(customJson)
                if (root.has(dayKey)) {
                    val arr = root.getJSONArray(dayKey)
                    val index = lessonNum - 1
                    if (index in 0 until arr.length()) {
                        return arr.getJSONObject(index).getString("start")
                    }
                }
            } catch (_: Exception) {}
        }

        return when (dayKey) {
            "SHORTENED" -> when (lessonNum) {
                1 -> "08:30"
                2 -> "09:10"
                3 -> "10:00"
                else -> "10:40"
            }
            "SATURDAY" -> when (lessonNum) {
                1 -> "08:30"
                2 -> "09:25"
                3 -> "10:20"
                else -> "11:30"
            }
            else -> when (lessonNum) {
                1 -> "08:30"
                2 -> "09:25"
                3 -> "10:20"
                else -> "11:15"
            }
        }
    }
}