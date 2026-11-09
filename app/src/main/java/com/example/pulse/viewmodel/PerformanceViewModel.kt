package com.example.pulse.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SubjectGrade(
    val id: String = UUID.randomUUID().toString(),
    val subjectName: String,
    val teacher: String,
    val scores: List<Int>,
    val targetScore: Double? = null,
    val krScores: List<Int> = emptyList()
) {
    val average: Double
        get() = if (scores.isNotEmpty()) scores.average() else 0.0

    val krAverage: Double
        get() = if (krScores.isNotEmpty()) krScores.average() else 0.0

    val effectiveAverage: Double
        get() {
            val baseAvg = average
            val krAvg = krAverage

            if (baseAvg == 0.0 && krAvg == 0.0) return 0.0
            if (baseAvg == 0.0) return krAvg
            if (krAvg == 0.0) return baseAvg

            return (baseAvg + krAvg) / 2.0
        }
}

data class Semester(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val grades: List<SubjectGrade>
)

class PerformanceViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("PulsePerformancePrefs", Context.MODE_PRIVATE)
    private val appPrefs = application.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)

    private val _semesters = mutableStateListOf<Semester>()
    val semesters: List<Semester> get() = _semesters

    var selectedSemesterIndex by mutableIntStateOf(0)
        private set

    init {
        loadData()
        if (_semesters.isEmpty()) {
            _semesters.add(Semester(name = "1 семестр", grades = emptyList()))
            saveData()
        }
    }

    fun selectSemester(index: Int) {
        if (index in _semesters.indices) selectedSemesterIndex = index
    }

    fun addSemester(name: String) {
        _semesters.add(Semester(name = name, grades = emptyList()))
        selectedSemesterIndex = _semesters.size - 1
        saveData()
    }

    fun syncWithLessons(lessons: List<LessonPair>) {
        val autoTransfer = appPrefs.getBoolean("auto_transfer", true)
        if (!autoTransfer || _semesters.isEmpty() || lessons.isEmpty()) return

        val targetIndex = _semesters.lastIndex
        val currentSemester = _semesters[targetIndex]
        val existingSubjects = currentSemester.grades.map { it.subjectName.lowercase().trim() }.toMutableSet()
        val updatedGrades = currentSemester.grades.toMutableList()

        var hasChanges = false
        val subjectsFromSchedule = linkedMapOf<String, String>()

        lessons.forEach { lesson ->
            val cleanSubject = lesson.subject.substringBefore("(").substringBefore(",").trim()
            if (cleanSubject.isBlank() || cleanSubject == "—" || cleanSubject == "√") return@forEach
            if (cleanSubject.lowercase() in existingSubjects) return@forEach

            if (cleanSubject !in subjectsFromSchedule) {
                subjectsFromSchedule[cleanSubject] = lesson.teacher.ifBlank { "—" }
            }
        }

        subjectsFromSchedule.forEach { (cleanSubject, teacherName) ->
            if (updatedGrades.none { it.subjectName.lowercase() == cleanSubject.lowercase() }) {
                updatedGrades.add(
                    SubjectGrade(
                        subjectName = cleanSubject,
                        teacher = teacherName,
                        scores = emptyList(),
                        targetScore = null,
                        krScores = emptyList()
                    )
                )
                existingSubjects.add(cleanSubject.lowercase())
                hasChanges = true
            }
        }

        if (hasChanges) {
            _semesters[targetIndex] = currentSemester.copy(grades = updatedGrades)
            selectedSemesterIndex = targetIndex
            saveData()
        }
    }

    fun addOrUpdateGrade(subjectName: String, teacher: String, scores: List<Int>, targetScore: Double?, krScores: List<Int>, id: String?) {
        if (_semesters.isEmpty()) return
        val currentSemester = _semesters[selectedSemesterIndex]
        val updatedGrades = currentSemester.grades.toMutableList()

        val newGrade = SubjectGrade(
            id = id ?: UUID.randomUUID().toString(),
            subjectName = subjectName,
            teacher = teacher,
            scores = scores,
            targetScore = targetScore,
            krScores = krScores
        )

        if (id == null) {
            updatedGrades.add(newGrade)
        } else {
            val index = updatedGrades.indexOfFirst { it.id == id }
            if (index != -1) updatedGrades[index] = newGrade
        }
        _semesters[selectedSemesterIndex] = currentSemester.copy(grades = updatedGrades)
        saveData()
    }

    fun deleteGrade(id: String) {
        if (_semesters.isEmpty()) return
        val currentSemester = _semesters[selectedSemesterIndex]
        val updatedGrades = currentSemester.grades.filter { it.id != id }
        _semesters[selectedSemesterIndex] = currentSemester.copy(grades = updatedGrades)
        saveData()
    }

    private fun saveData() {
        val array = JSONArray()
        _semesters.forEach { sem ->
            val semObj = JSONObject().apply {
                put("id", sem.id)
                put("name", sem.name)
            }
            val gradesArray = JSONArray()
            sem.grades.forEach { g ->
                val gObj = JSONObject().apply {
                    put("id", g.id)
                    put("subjectName", g.subjectName)
                    put("teacher", g.teacher)
                    put("scores", JSONArray(g.scores))
                    put("krScores", JSONArray(g.krScores))
                    g.targetScore?.let { put("targetScore", it) }
                }
                gradesArray.put(gObj)
            }
            semObj.put("grades", gradesArray)
            array.put(semObj)
        }
        prefs.edit().putString("semesters_data", array.toString()).apply()
    }

    private fun loadData() {
        val json = prefs.getString("semesters_data", null) ?: return
        try {
            val array = JSONArray(json)
            val loadedSemesters = mutableListOf<Semester>()

            for (i in 0 until array.length()) {
                val semObj = array.getJSONObject(i)
                val gradesArray = semObj.getJSONArray("grades")
                val grades = mutableListOf<SubjectGrade>()

                for (j in 0 until gradesArray.length()) {
                    val gObj = gradesArray.getJSONObject(j)
                    val scores = mutableListOf<Int>()
                    val krScores = mutableListOf<Int>()

                    val scoresJson = gObj.getJSONArray("scores")
                    for (k in 0 until scoresJson.length()) scores.add(scoresJson.getInt(k))
                    if (gObj.has("krScores")) {
                        val krJson = gObj.getJSONArray("krScores")
                        for (k in 0 until krJson.length()) krScores.add(krJson.getInt(k))
                    } else if (gObj.has("krScore")) {
                        krScores.add(gObj.getInt("krScore"))
                    }

                    grades.add(SubjectGrade(
                        id = gObj.getString("id"),
                        subjectName = gObj.getString("subjectName"),
                        teacher = gObj.getString("teacher"),
                        scores = scores,
                        targetScore = if (gObj.has("targetScore")) gObj.getDouble("targetScore") else null,
                        krScores = krScores
                    ))
                }
                loadedSemesters.add(Semester(semObj.getString("id"), semObj.getString("name"), grades))
            }
            _semesters.clear()
            _semesters.addAll(loadedSemesters)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun deleteSemester(index: Int) {
        if (_semesters.size > 1 && index in _semesters.indices) {
            _semesters.removeAt(index)
            selectedSemesterIndex = minOf(selectedSemesterIndex, _semesters.size - 1).coerceAtLeast(0)
            saveData()
        }
    }
}