package com.example.pulse.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.pulse.data.HomeworkManager
import com.example.pulse.data.PulseNotificationManager
import com.example.pulse.screen.NotificationType
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

class HomeworkWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val tomorrow = LocalDate.now().plusDays(1)
        val today = LocalDate.now()
        val homeworks = HomeworkManager.loadHomeworks(appContext)

        val prefs = appContext.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("schedule_history", "{}") ?: "{}"
        val tomorrowSubjects = mutableSetOf<String>()
        val subjectPairsMap = mutableMapOf<String, MutableList<Int>>()

        try {
            val jsonObject = JSONObject(historyJson)
            if (jsonObject.has(tomorrow.toString())) {
                val array = jsonObject.getJSONArray(tomorrow.toString())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val subj = obj.getString("subject").substringBefore("(").trim().lowercase()
                    tomorrowSubjects.add(subj)
                    subjectPairsMap.getOrPut(subj) { mutableListOf() }.add(obj.getInt("pairNumber"))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        val tasksToNotify = homeworks.filter { hw ->
            if (hw.isCompleted || !hw.notifyDayBefore) return@filter false

            if (hw.targetPairs != null) {
                val cleanSubj = hw.subject.substringBefore("(").trim().lowercase()
                var passedCount = 0
                try {
                    val jsonObject = JSONObject(historyJson)
                    jsonObject.keys().forEach { dateStr ->
                        val date = com.example.pulse.utils.DateTimeUtils.parseLocalDateOrNull(dateStr) ?: return@forEach
                        if (date.isAfter(hw.assignDate) && !date.isAfter(today)) {
                            val array = jsonObject.getJSONArray(dateStr)
                            for (i in 0 until array.length()) {
                                if (array.getJSONObject(i).getString("subject").substringBefore("(").trim().lowercase() == cleanSubj) {
                                    passedCount++
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}

                val pairsLeft = maxOf(0, hw.targetPairs - passedCount)
                (cleanSubj in tomorrowSubjects) && (pairsLeft <= 1)
            } else {
                hw.dueDate == tomorrow
            }
        }

        if (tasksToNotify.isNotEmpty()) {
            val tasksDetails = tasksToNotify.map { hw ->
                val cleanSubject = hw.subject.substringBefore("(").trim().lowercase()
                val pairs = subjectPairsMap[cleanSubject]
                if (pairs != null && pairs.isNotEmpty()) {
                    "• ${hw.subject} (${pairs.joinToString(" и ") { "$it-я пара" }})"
                } else {
                    "• ${hw.subject}"
                }
            }
            PulseNotificationManager.sendAndSaveNotification(
                appContext, "Домашка на завтра!", tasksDetails.joinToString("\n"),
                NotificationType.REMINDER, true
            )
        }
        return Result.success()
    }
}