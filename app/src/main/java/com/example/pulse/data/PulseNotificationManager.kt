package com.example.pulse.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.pulse.R
import com.example.pulse.screen.MainActivity
import com.example.pulse.screen.NotificationItem
import com.example.pulse.screen.NotificationType
import com.example.pulse.utils.LiveNotificationManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object PulseNotificationManager {

    private const val CHANNEL_ID = "pulse_schedule_updates"
    private const val PREFS_NAME = "PulseNotificationsPrefs"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Изменения в расписании"
            val descriptionText = "Уведомления о новых парах и отменах"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            LiveNotificationManager.createLiveNotificationChannel(context)
        }
    }

    fun sendAndSaveNotification(context: Context, title: String, message: String, type: NotificationType, isImportant: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(UUID.randomUUID().hashCode(), builder.build())

        saveNotificationToPrefs(context, title, message, type, isImportant)
    }

    private fun saveNotificationToPrefs(context: Context, title: String, message: String, type: NotificationType, isImportant: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentJson = prefs.getString("notifications_list", "[]") ?: "[]"

        val array = JSONArray(currentJson)
        val newNotif = JSONObject().apply {
            put("title", title)
            put("message", message)
            put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM HH:mm")))
            put("type", type.name)
            put("isImportant", isImportant)
        }

        val newArray = JSONArray()
        newArray.put(newNotif)
        for (i in 0 until array.length()) {
            newArray.put(array.getJSONObject(i))
        }

        prefs.edit().putString("notifications_list", newArray.toString()).apply()
    }

    fun loadNotifications(context: Context): List<NotificationItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString("notifications_list", "[]") ?: "[]"
        val list = mutableListOf<NotificationItem>()

        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    NotificationItem(
                        title = obj.getString("title"),
                        message = obj.getString("message"),
                        time = obj.getString("time"),
                        type = NotificationType.valueOf(obj.getString("type")),
                        isImportant = obj.getBoolean("isImportant")
                    )
                )
            }
        } catch (e: Exception) { e.printStackTrace() }

        return list
    }
}