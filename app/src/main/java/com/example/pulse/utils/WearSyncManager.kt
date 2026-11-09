package com.example.pulse.utils

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object WearSyncManager {
    private const val PATH = "/pulse_wear_data"

    fun syncDataToWatch(
        context: Context,
        studentName: String,
        nextSubject: String,
        nextRoom: String,
        nextTime: String,
        minsLeft: Int,
        averageScore: Float,
        lessonsLeft: Int,
        currentDate: String
    ) {
        val dataClient = Wearable.getDataClient(context)

        val putDataReq = PutDataMapRequest.create(PATH).run {
            dataMap.putString("student_name", studentName)
            dataMap.putString("next_subject", nextSubject)
            dataMap.putString("next_room", nextRoom)
            dataMap.putString("next_time", nextTime)
            dataMap.putInt("mins_left", minsLeft)
            dataMap.putFloat("average_score", averageScore)
            dataMap.putInt("lessons_left", lessonsLeft)
            dataMap.putString("current_date", currentDate)

            dataMap.putLong("timestamp", System.currentTimeMillis())

            asPutDataRequest()
        }

        // Отправляем на часы
        dataClient.putDataItem(putDataReq).addOnSuccessListener {
            Log.d("WearSync", "Данные успешно отправлены на часы!")
        }.addOnFailureListener {
            Log.e("WearSync", "Ошибка отправки на часы", it)
        }
    }
}