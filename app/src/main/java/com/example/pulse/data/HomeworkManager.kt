package com.example.pulse.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

data class HomeworkItem(
    val id: String = UUID.randomUUID().toString(),
    val subject: String,
    val task: String,
    val dueDate: LocalDate,
    val notifyDayBefore: Boolean = true,
    val isCompleted: Boolean = false,
    val assignDate: LocalDate = LocalDate.now(),
    val targetPairs: Int? = null
)

object HomeworkManager {
    private const val PREFS_NAME = "PulseHomeworkPrefs"
    private const val KEY_LIST = "homework_list"
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun saveHomework(context: Context, homework: HomeworkItem) {
        val list = loadHomeworks(context).toMutableList()
        val index = list.indexOfFirst { it.id == homework.id }
        if (index >= 0) list[index] = homework else list.add(homework)
        saveList(context, list)
    }

    fun deleteHomework(context: Context, id: String) {
        val list = loadHomeworks(context).toMutableList()
        list.removeAll { it.id == id }
        saveList(context, list)
    }

    fun toggleCompleted(context: Context, id: String) {
        val list = loadHomeworks(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            val item = list[index]
            list[index] = item.copy(isCompleted = !item.isCompleted)
            saveList(context, list)
        }
    }

    fun deleteCompleted(context: Context) {
        val list = loadHomeworks(context).filter { !it.isCompleted }
        saveList(context, list)
    }

    fun loadHomeworks(context: Context): List<HomeworkItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val result = mutableListOf<HomeworkItem>()

        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                try {
                    result.add(
                        HomeworkItem(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            subject = obj.optString("subject", "Без предмета"),
                            task = obj.optString("task", ""),
                            dueDate = com.example.pulse.utils.DateTimeUtils.parseLocalDateOrNull(obj.optString("dueDate")) ?: LocalDate.now(),
                            notifyDayBefore = obj.optBoolean("notifyDayBefore", true),
                            isCompleted = obj.optBoolean("isCompleted", false),
                            assignDate = com.example.pulse.utils.DateTimeUtils.parseLocalDateOrNull(obj.optString("assignDate")) ?: LocalDate.now(),
                            targetPairs = if (obj.has("targetPairs") && !obj.isNull("targetPairs")) obj.getInt("targetPairs") else null
                        )
                    )
                } catch (e: Exception) { e.printStackTrace() }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return result.sortedWith(compareBy<HomeworkItem> { it.isCompleted }.thenBy { it.dueDate })
    }

    private fun saveList(context: Context, list: List<HomeworkItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()

        list.forEach { hw ->
            val obj = JSONObject().apply {
                put("id", hw.id)
                put("subject", hw.subject)
                put("task", hw.task)
                put("dueDate", hw.dueDate.format(dateFormatter))
                put("notifyDayBefore", hw.notifyDayBefore)
                put("isCompleted", hw.isCompleted)
                put("assignDate", hw.assignDate.format(dateFormatter))
                if (hw.targetPairs != null) put("targetPairs", hw.targetPairs) else put("targetPairs", JSONObject.NULL)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }
}