package com.example.pulse.data

import android.content.Context

object TeacherNotesManager {
    private const val PREFS_NAME = "PulseTeacherNotes"

    fun saveNote(context: Context, teacherName: String, note: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(teacherName, note).apply()
    }

    fun getNote(context: Context, teacherName: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(teacherName, "") ?: ""
    }
}
