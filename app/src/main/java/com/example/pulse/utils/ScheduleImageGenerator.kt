package com.example.pulse.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.example.pulse.screen.BgDark
import com.example.pulse.screen.PrimaryPurple
import com.example.pulse.screen.SurfaceDark
import com.example.pulse.screen.TextGray
import com.example.pulse.viewmodel.LessonPair
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CustomTheme(
    val bg: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val cardBg: Int
)

object ScheduleImageGenerator {

    fun getDynamicTheme(): CustomTheme {
        return CustomTheme(
            bg = BgDark.toArgb(),
            textPrimary = AndroidColor.WHITE,
            textSecondary = TextGray.toArgb(),
            accent = PrimaryPurple.toArgb(),
            cardBg = SurfaceDark.toArgb()
        )
    }

    fun generateStoryShareBitmap(context: Context, page: StoryPage) {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgArgb = BgDark.toArgb()
        val surfaceArgb = SurfaceDark.toArgb()
        val primaryArgb = PrimaryPurple.toArgb()
        val textSecondaryArgb = TextGray.toArgb()

        val colors = intArrayOf(
            surfaceArgb,
            bgArgb,
            bgArgb
        )
        val shader = LinearGradient(0f, 0f, 0f, height.toFloat(), colors, null, Shader.TileMode.CLAMP)
        val bgPaint = Paint().apply {
            this.shader = shader
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val glowPaint = Paint().apply {
            isAntiAlias = true
            color = primaryArgb
            alpha = 40
        }
        canvas.drawCircle(width / 2f, 320f, 450f, glowPaint)

        val paintText = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        paintText.color = primaryArgb
        paintText.alpha = 240
        paintText.textSize = 46f
        paintText.letterSpacing = 0.22f
        paintText.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(page.title.uppercase(), width / 2f, 340f, paintText)

        paintText.color = AndroidColor.WHITE
        paintText.alpha = 255
        paintText.textSize = 100f
        paintText.letterSpacing = 0f
        paintText.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(page.mainHeadline, width / 2f, 520f, paintText)

        paintText.color = primaryArgb
        paintText.textSize = 54f
        paintText.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText(page.sectionTitle, width / 2f, 650f, paintText)

        paintText.color = AndroidColor.WHITE
        paintText.alpha = 220
        paintText.textSize = 44f

        val descWords = page.description.split(" ")
        var currentLine = ""
        var descY = 790f

        for (word in descWords) {
            if (paintText.measureText(currentLine + word) < width - 220f) {
                currentLine += "$word "
            } else {
                canvas.drawText(currentLine, width / 2f, descY, paintText)
                currentLine = "$word "
                descY += 64f
            }
        }
        canvas.drawText(currentLine, width / 2f, descY, paintText)

        if (page.steps.isNotEmpty()) {
            descY += 80f
            paintText.alpha = 200
            paintText.textSize = 40f

            val stepPreviews = page.steps.take(2)
            stepPreviews.forEach { step ->
                canvas.drawText("${step.number}. ${step.title}", width / 2f, descY, paintText)
                descY += 56f
            }
            if (page.steps.size > 2) {
                paintText.color = textSecondaryArgb
                canvas.drawText("и ещё ${page.steps.size - 2}...", width / 2f, descY, paintText)
            }
        }

        paintText.color = textSecondaryArgb
        paintText.alpha = 180
        paintText.textSize = 34f
        paintText.letterSpacing = 0.05f
        canvas.drawText("Сгенерировано в МГКАП", width / 2f, height - 100f, paintText)

        shareBitmap(context, bitmap)
    }

    fun generateBitmap(
        date: LocalDate,
        lessons: List<LessonPair>,
        theme: CustomTheme,
        emojiBg: String?
    ): Bitmap {
        val width = 1080
        val headerHeight = 310
        val lessonHeight = 220
        val footerHeight = 150
        val height = headerHeight + (if (lessons.isEmpty()) 400 else lessons.size * lessonHeight) + footerHeight

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(theme.bg)

        val paintText = Paint().apply {
            isAntiAlias = true
        }
        val paintCard = Paint().apply {
            isAntiAlias = true
        }
        val paintStroke = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = theme.accent
            alpha = 60
        }

        val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", Locale("ru"))
        val dateText = date.format(formatter).uppercase()

        paintText.color = theme.accent
        paintText.textSize = 38f
        paintText.letterSpacing = 0.12f
        paintText.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("РАСПИСАНИЕ ЗАНЯТИЙ", 80f, 120f, paintText)

        paintText.color = theme.textPrimary
        paintText.textSize = 62f
        paintText.letterSpacing = 0f
        canvas.drawText(dateText, 80f, 210f, paintText)

        if (lessons.isEmpty()) {
            paintText.color = theme.textSecondary
            paintText.textSize = 48f
            paintText.textAlign = Paint.Align.CENTER
            canvas.drawText("Пар нет. Можно отдыхать!", width / 2f, headerHeight + 180f, paintText)
        } else {
            var currentY = headerHeight.toFloat()
            lessons.forEach { lesson ->
                paintCard.color = theme.cardBg
                val rect = RectF(60f, currentY, width - 60f, currentY + lessonHeight - 30f)
                canvas.drawRoundRect(rect, 28f, 28f, paintCard)
                canvas.drawRoundRect(rect, 28f, 28f, paintStroke)

                paintCard.color = theme.accent
                val accentRect = RectF(60f, currentY, 82f, currentY + lessonHeight - 30f)
                canvas.drawRoundRect(accentRect, 28f, 28f, paintCard)
                canvas.drawRect(72f, currentY, 82f, currentY + lessonHeight - 30f, paintCard)

                paintText.textAlign = Paint.Align.LEFT
                paintText.color = theme.textPrimary
                paintText.textSize = 44f
                paintText.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("${lesson.startTime} - ${lesson.endTime}", 125f, currentY + 70f, paintText)

                paintText.textSize = 38f
                paintText.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                canvas.drawText(lesson.subject, 125f, currentY + 128f, paintText)

                paintText.color = theme.textSecondary
                paintText.textSize = 32f
                canvas.drawText("Ауд. ${lesson.room} • ${lesson.teacher}", 125f, currentY + 178f, paintText)

                currentY += lessonHeight
            }
        }

        paintText.color = theme.textSecondary
        paintText.textSize = 32f
        paintText.textAlign = Paint.Align.CENTER
        canvas.drawText("Сгенерировано в МГКАП", width / 2f, height - 60f, paintText)

        return bitmap
    }

    fun shareBitmap(context: Context, bitmap: Bitmap) {
        try {
            val cachePath = File(context.cacheDir, "shared_schedules")
            cachePath.mkdirs()
            val file = File(cachePath, "schedule_${System.currentTimeMillis()}.png")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.close()

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Поделиться картинкой"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}