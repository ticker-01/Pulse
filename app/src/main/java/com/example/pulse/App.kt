package com.example.pulse

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.pulse.data.PulseNotificationManager
import com.example.pulse.worker.HomeworkWorker
import com.example.pulse.worker.ScheduleUpdateWorker
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        com.example.pulse.utils.PulseCrashProtection.install(this)

        try {
            PDFBoxResourceLoader.init(this)
        } catch (e: Exception) {
            android.util.Log.e("PulseApp", "PDFBox init error", e)
        }

        try {
            PulseNotificationManager.createNotificationChannel(this)
        } catch (e: Exception) {
            android.util.Log.e("PulseApp", "Notification channel init error", e)
        }

        try {
            setupBackgroundScheduleChecker()
            setupHomeworkNotifier()
        } catch (e: Exception) {
            android.util.Log.e("PulseApp", "Background worker setup error", e)
        }
    }

    private fun setupBackgroundScheduleChecker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val checkRequest = PeriodicWorkRequestBuilder<ScheduleUpdateWorker>(2L, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ScheduleCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            checkRequest
        )
    }

    private fun setupHomeworkNotifier() {
        val hwRequest = PeriodicWorkRequestBuilder<HomeworkWorker>(1L, TimeUnit.DAYS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "HomeworkCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            hwRequest
        )
    }
}