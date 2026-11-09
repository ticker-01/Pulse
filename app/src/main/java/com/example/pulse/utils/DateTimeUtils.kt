package com.example.pulse.utils

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateTimeUtils {
    val RU_LOCALE: Locale = Locale.forLanguageTag("ru")
    val DATE_FORMATTER_DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU_LOCALE)
    val DATE_FORMATTER_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RU_LOCALE)
    val TIME_FORMATTER_HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun parseLocalTimeOrNull(raw: String?): LocalTime? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()

        // Standard ISO "HH:mm"
        try {
            return LocalTime.parse(trimmed)
        } catch (_: Exception) {}

        // Handle variants like "8:30", "08.30", "8.30", "8:30:00"
        try {
            val normalized = trimmed
                .replace(".", ":")
                .replace("-", ":")
            val parts = normalized.split(":")
            if (parts.size >= 2) {
                val hour = parts[0].toIntOrNull() ?: return null
                val minute = parts[1].toIntOrNull() ?: return null
                if (hour in 0..23 && minute in 0..59) {
                    val second = if (parts.size >= 3) (parts[2].toIntOrNull() ?: 0).coerceIn(0, 59) else 0
                    return LocalTime.of(hour, minute, second)
                }
            }
        } catch (_: Exception) {}

        return null
    }

    fun parseLocalDateOrNull(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()

        // Standard ISO "yyyy-MM-dd"
        try {
            return LocalDate.parse(trimmed)
        } catch (_: Exception) {}

        // Russian format "dd.MM.yyyy" or "dd.MM.yy"
        try {
            val parts = trimmed.split(".")
            if (parts.size == 3) {
                val day = parts[0].toIntOrNull() ?: return null
                val month = parts[1].toIntOrNull() ?: return null
                val rawYear = parts[2].toIntOrNull() ?: return null
                val year = if (rawYear < 100) 2000 + rawYear else rawYear
                return LocalDate.of(year, month, day)
            }
        } catch (_: Exception) {}

        return null
    }
}

fun String?.parseLocalTimeOrNull(): LocalTime? = DateTimeUtils.parseLocalTimeOrNull(this)
fun String?.parseLocalDateOrNull(): LocalDate? = DateTimeUtils.parseLocalDateOrNull(this)
