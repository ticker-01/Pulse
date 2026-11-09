package com.example.pulse.utils

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.pulse.viewmodel.LessonPair

class LiveLessonService : Service() {

    override fun onCreate() {
        super.onCreate()
        LiveNotificationManager.createLiveNotificationChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val subject = intent?.getStringExtra("subject") ?: ""
        val room = intent?.getStringExtra("room") ?: ""
        val teacher = intent?.getStringExtra("teacher") ?: ""
        val pairNumber = intent?.getIntExtra("pair_number", 1) ?: 1
        val startTime = intent?.getStringExtra("start_time") ?: ""
        val endTime = intent?.getStringExtra("end_time") ?: ""

        val lesson = LessonPair(
            pairNumber = pairNumber,
            lessons = "",
            startTime = startTime,
            endTime = endTime,
            subject = subject,
            room = room,
            teacher = teacher
        )

        LiveNotificationManager.showOngoingLesson(this, lesson, isForegroundService = true, service = this)

        return START_STICKY
    }

    companion object {
        const val ACTION_START = "ACTION_START_LIVE"
        const val ACTION_STOP = "ACTION_STOP_LIVE"

        fun start(context: Context, lesson: LessonPair) {
            try {
                val intent = Intent(context, LiveLessonService::class.java).apply {
                    action = ACTION_START
                    putExtra("subject", lesson.subject)
                    putExtra("room", lesson.room)
                    putExtra("teacher", lesson.teacher)
                    putExtra("pair_number", lesson.pairNumber)
                    putExtra("start_time", lesson.startTime)
                    putExtra("end_time", lesson.endTime)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Запасной план: если система отклонила службу, постим напрямую
                LiveNotificationManager.showOngoingLesson(context, lesson)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, LiveLessonService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}