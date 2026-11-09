package com.example.pulse.utils

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.example.pulse.screen.MainActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PulseCrashProtection {
    private const val TAG = "PulseCrashProtection"
    private const val PREFS_NAME = "pulse_crash_protection_prefs"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"
    private const val CRASH_LOOP_THRESHOLD_MS = 4000L

    fun install(application: Application) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception intercepted in thread ${thread.name}", throwable)
                val context = application.applicationContext

                // Log details to local storage
                writeCrashReport(context, thread, throwable)

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastCrash = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_CRASH_TIME, now).commit()

                // If app is looping in a crash, let OS handle it to avoid infinite loop
                if (now - lastCrash < CRASH_LOOP_THRESHOLD_MS) {
                    Log.w(TAG, "Crash loop detected (${now - lastCrash} ms). Delegating to system default handler.")
                    defaultHandler?.uncaughtException(thread, throwable)
                    return@setDefaultUncaughtExceptionHandler
                }

                // Restart MainActivity cleanly
                val restartIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("crash_recovered", true)
                }
                context.startActivity(restartIntent)

                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (recoveryException: Throwable) {
                Log.e(TAG, "Error in crash recovery handler", recoveryException)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun writeCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val file = File(context.filesDir, "mgkap_crash_log.txt")
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val content = buildString {
                appendLine("=== МГКАП CRASH LOG ===")
                appendLine("Time: $timestamp")
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: ${throwable::class.java.name}: ${throwable.message}")
                appendLine("Stack trace:")
                appendLine(stackTrace)
                appendLine("========================")
                appendLine()
            }

            file.appendText(content)
        } catch (_: Exception) {}
    }
}
