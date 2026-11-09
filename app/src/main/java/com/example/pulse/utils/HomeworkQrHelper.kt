package com.example.pulse.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.example.pulse.data.HomeworkItem
import com.example.pulse.data.HomeworkManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object HomeworkQrHelper {

    private const val PREFIX = "MGKAP_HW:"
    private const val LEGACY_PREFIX = "PULSE_HW:"

    fun exportToQrPayload(homeworks: List<HomeworkItem>): String? {
        val activeItems = homeworks.filter { !it.isCompleted }
        if (activeItems.isEmpty()) return null

        val array = JSONArray()
        activeItems.forEach { hw ->
            val obj = JSONObject().apply {
                put("s", hw.subject)
                put("t", hw.task)
                put("d", hw.dueDate.toString())
                hw.targetPairs?.let { put("tp", it) }
                put("a", hw.assignDate.toString())
            }
            array.put(obj)
        }

        val rawBytes = array.toString().toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(rawBytes) }
        val compressed = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        return "$PREFIX$compressed"
    }

    fun importFromQrPayload(context: Context, rawData: String): Int {
        val base64 = when {
            rawData.startsWith(PREFIX) -> rawData.removePrefix(PREFIX)
            rawData.startsWith(LEGACY_PREFIX) -> rawData.removePrefix(LEGACY_PREFIX)
            else -> return 0
        }

        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val jsonString = GZIPInputStream(ByteArrayInputStream(bytes))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            val array = JSONArray(jsonString)
            val currentHomeworks = HomeworkManager.loadHomeworks(context)
            var addedCount = 0

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val subject = obj.getString("s")
                val task = obj.getString("t")
                val dueDate = DateTimeUtils.parseLocalDateOrNull(obj.optString("d")) ?: LocalDate.now().plusDays(1)
                val targetPairs = if (obj.has("tp")) obj.getInt("tp") else null
                val assignDate = DateTimeUtils.parseLocalDateOrNull(obj.optString("a")) ?: LocalDate.now()

                // Проверяем, есть ли уже в базе такое же задание
                val alreadyExists = currentHomeworks.any {
                    it.subject.trim().equals(subject.trim(), ignoreCase = true) &&
                            it.task.trim().equals(task.trim(), ignoreCase = true)
                }

                if (!alreadyExists) {
                    val newItem = HomeworkItem(
                        id = UUID.randomUUID().toString(),
                        subject = subject,
                        task = task,
                        dueDate = dueDate,
                        notifyDayBefore = true,
                        isCompleted = false,
                        assignDate = assignDate,
                        targetPairs = targetPairs
                    )
                    HomeworkManager.saveHomework(context, newItem)
                    addedCount++
                }
            }
            addedCount
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    fun generateQrBitmap(content: String, size: Int = 600): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)

        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x,
                    y,
                    if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                )
            }
        }
        return bitmap
    }
}