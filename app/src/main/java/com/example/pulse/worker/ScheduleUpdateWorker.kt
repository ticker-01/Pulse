package com.example.pulse.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.pulse.data.PulseNotificationManager
import com.example.pulse.screen.NotificationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ScheduleUpdateWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d("PulseWorker", "Фоновая проверка расписания началась...")

        val prefs = appContext.getSharedPreferences("PulseAppPrefs", Context.MODE_PRIVATE)
        val changesEnabled = prefs.getBoolean("changes_enabled", true)
        if (!changesEnabled) return@withContext Result.success()

        val tempFiles = mutableListOf<File>()

        try {
            val pdfUrls = fetchAllPdfUrls()
            if (pdfUrls.isEmpty()) {
                Log.d("PulseWorker", "Файлы замен на странице не найдены")
                return@withContext Result.success()
            }

            val fileHashes = mutableListOf<String>()

            for ((index, urlString) in pdfUrls.withIndex()) {
                val tempFile = File(appContext.cacheDir, "bg_check_$index.pdf")
                tempFiles.add(tempFile)

                val downloaded = downloadFileSecurely(urlString, tempFile)
                if (!downloaded || !isValidPdf(tempFile)) {
                    Log.w("PulseWorker", "Файл поврежден или не является PDF: $urlString")
                    return@withContext Result.retry()
                }

                fileHashes.add(calculateMD5(tempFile))
            }

            val compositeNewHash = calculateStringMD5(fileHashes.joinToString(";"))
            val oldCompositeHash = prefs.getString("last_pdf_composite_hash", "") ?: ""
            val oldSingleHash = prefs.getString("last_pdf_hash", "") ?: ""

            val hasChanges = if (oldCompositeHash.isNotEmpty()) {
                compositeNewHash != oldCompositeHash
            } else if (oldSingleHash.isNotEmpty() && fileHashes.isNotEmpty()) {
                !fileHashes.contains(oldSingleHash)
            } else {
                false
            }

            if (hasChanges) {
                Log.i("PulseWorker", "Расписание действительно изменилось! Отправка уведомления.")

                PulseNotificationManager.sendAndSaveNotification(
                    context = appContext,
                    title = "Расписание обновлено!",
                    message = "Колледж опубликовал новое расписание замен. Нажмите, чтобы открыть.",
                    type = NotificationType.SCHEDULE_CHANGE,
                    isImportant = true
                )

                val latestUrl = pdfUrls.last()
                val fileNames = pdfUrls.joinToString(", ") { it.substringAfterLast("/") }
                val urlsJson = JSONArray().apply { pdfUrls.forEach { put(it) } }.toString()

                prefs.edit()
                    .putString("last_pdf_url", latestUrl)
                    .putString("last_pdf_filename", fileNames)
                    .putString("last_pdf_urls_json", urlsJson)
                    .putString("last_pdf_hash", fileHashes.lastOrNull() ?: "")
                    .putString("last_pdf_composite_hash", compositeNewHash)
                    .apply()
            } else {
                Log.d("PulseWorker", "Изменений нет. Хэши совпадают.")

                if (oldCompositeHash.isEmpty()) {
                    val latestUrl = pdfUrls.last()
                    val fileNames = pdfUrls.joinToString(", ") { it.substringAfterLast("/") }
                    val urlsJson = JSONArray().apply { pdfUrls.forEach { put(it) } }.toString()

                    prefs.edit()
                        .putString("last_pdf_url", latestUrl)
                        .putString("last_pdf_filename", fileNames)
                        .putString("last_pdf_urls_json", urlsJson)
                        .putString("last_pdf_hash", fileHashes.lastOrNull() ?: "")
                        .putString("last_pdf_composite_hash", compositeNewHash)
                        .apply()
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("PulseWorker", "Ошибка при фоновой проверке", e)
            Result.retry()
        } finally {
            tempFiles.forEach { file ->
                try { if (file.exists()) file.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun fetchAllPdfUrls(): List<String> {
        var connection: HttpURLConnection? = null
        try {
            val pageUrl = "https://college-ripo.by/uchashhimsya/zameny-v-raspisanii/"
            connection = URL(pageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            if (connection.responseCode != 200) return emptyList()

            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val candidates = linkedSetOf<String>()

            val buttonRegexes = listOf(
                Regex("""<a\b[^>]*?href=["']([^"']+\.pdf)["'][^>]*?class=["'][^"']*wp-block-file__button""", RegexOption.IGNORE_CASE),
                Regex("""<a\b[^>]*?class=["'][^"']*wp-block-file__button[^"']*["'][^>]*?href=["']([^"']+\.pdf)["']""", RegexOption.IGNORE_CASE)
            )
            for (regex in buttonRegexes) {
                regex.findAll(html).forEach { candidates.add(it.groupValues[1]) }
            }

            val objectRegex = Regex("""<object\b[^>]*?data=["']([^"']+\.pdf)["']""", RegexOption.IGNORE_CASE)
            objectRegex.findAll(html).forEach { candidates.add(it.groupValues[1]) }

            if (candidates.isEmpty()) {
                val generalPdfRegex = Regex("""href=["'](https?://[^"']*/wp-content/uploads/[^"']*\.pdf)["']""", RegexOption.IGNORE_CASE)
                generalPdfRegex.findAll(html).forEach { candidates.add(it.groupValues[1]) }
            }

            return candidates.map { rawUrl ->
                var u = rawUrl.trim()
                if (u.startsWith("//")) u = "https:$u"
                else if (u.startsWith("/")) u = "https://college-ripo.by$u"
                else if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://college-ripo.by/$u"
                if (u.startsWith("http://")) u = u.replaceFirst("http://", "https://")
                u
            }.filter { link ->
                val l = link.lowercase()
                l.contains("deti") || l.contains("дети") || l.contains("raspis") || l.contains("zamen") ||
                        Regex("""\d{2}\.\d{2}""").containsMatchIn(l)
            }.distinct()
        } catch (e: Exception) {
            Log.e("PulseWorker", "Ошибка парсинга ссылок", e)
            return emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun downloadFileSecurely(urlString: String, targetFile: File): Boolean {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            if (connection.responseCode != 200) return false

            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            return targetFile.exists() && targetFile.length() > 2048
        } catch (e: Exception) {
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun isValidPdf(file: File): Boolean {
        if (!file.exists() || file.length() < 100) return false
        return try {
            val header = ByteArray(4)
            FileInputStream(file).use { it.read(header) }
            header[0] == 0x25.toByte() && // %
                    header[1] == 0x50.toByte() && // P
                    header[2] == 0x44.toByte() && // D
                    header[3] == 0x46.toByte()    // F
        } catch (e: Exception) {
            false
        }
    }

    private fun calculateMD5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun calculateStringMD5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}