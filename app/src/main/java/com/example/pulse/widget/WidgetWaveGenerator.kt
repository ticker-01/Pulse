package com.example.pulse.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader

object WidgetWaveGenerator {
    fun createWaveBitmap(progress: Float, width: Int = 1000, height: Int = 1000): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.parseColor("#161622"))

        if (progress <= 0f) return bitmap

        val safeProgress = progress.coerceIn(0.02f, 1f)
        val waterLevelY = height - (height * safeProgress)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        paint.shader = LinearGradient(
            0f, waterLevelY, 0f, height.toFloat(),
            Color.parseColor("#4A3B99"), Color.parseColor("#0E0E14"), // Уходит в темноту
            Shader.TileMode.CLAMP
        )
        val backWavePath = Path().apply {
            moveTo(0f, waterLevelY + 40f)
            quadTo(width * 0.25f, waterLevelY - 60f, width * 0.5f, waterLevelY + 20f)
            quadTo(width * 0.75f, waterLevelY + 100f, width.toFloat(), waterLevelY - 20f)
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        canvas.drawPath(backWavePath, paint)

        // 2. Передняя (яркая акцентная) волна
        paint.shader = LinearGradient(
            0f, waterLevelY, 0f, height.toFloat(),
            Color.parseColor("#7B61FF"), Color.parseColor("#2A165D"),
            Shader.TileMode.CLAMP
        )
        val frontWavePath = Path().apply {
            moveTo(0f, waterLevelY)
            quadTo(width * 0.25f, waterLevelY + 80f, width * 0.5f, waterLevelY)
            quadTo(width * 0.75f, waterLevelY - 80f, width.toFloat(), waterLevelY + 40f)
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        canvas.drawPath(frontWavePath, paint)

        return bitmap
    }
}