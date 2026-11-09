
package com.example.pulse.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.pulse.widget.CurrentLessonWidget
import com.example.pulse.widget.DayScheduleWidget
import com.example.pulse.widget.NextLessonWidget
import com.example.pulse.widget.PerformanceWidget

class WidgetUpdateWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {

            CurrentLessonWidget().updateAll(appContext)
            NextLessonWidget().updateAll(appContext)
            DayScheduleWidget().updateAll(appContext)
            PerformanceWidget().updateAll(appContext)

            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }
    }
}