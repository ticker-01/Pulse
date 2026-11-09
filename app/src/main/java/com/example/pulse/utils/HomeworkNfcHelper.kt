package com.example.pulse.utils

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.example.pulse.data.HomeworkItem
import com.example.pulse.data.HomeworkManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID

sealed class NfcWriteResult {
    data class Success(
        val tagType: String,
        val bytesWritten: Int,
        val maxBytes: Int,
        val itemsCount: Int,
        val isStudentProfile: Boolean = false
    ) : NfcWriteResult()

    object ReadOnly : NfcWriteResult()
    data class NotEnoughSpace(val needed: Int, val max: Int) : NfcWriteResult()
    data class NotFormatable(val techList: List<String>) : NfcWriteResult()
    data class Failure(val message: String) : NfcWriteResult()
}

sealed class NfcImportResult {
    data class HomeworkImported(val count: Int, val senderName: String) : NfcImportResult()
    data class StudentProfile(val senderName: String, val group: String = "") : NfcImportResult()
    object AlreadyExists : NfcImportResult()
    data class Error(val message: String) : NfcImportResult()
}

object HomeworkNfcHelper {


    private val SELECT_PULSE_AID_APDU = byteArrayOf(
        0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
        0x07.toByte(),
        0xF0.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
    )

    fun isNfcSupported(context: Context): Boolean {
        return NfcAdapter.getDefaultAdapter(context) != null
    }

    fun isNfcEnabled(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return adapter != null && adapter.isEnabled
    }

    fun buildCardPayload(
        senderName: String,
        homeworks: List<HomeworkItem>,
        groupCode: String = "",
        maxCapacityBytes: Int = 137
    ): Pair<String, Int> {
        val cleanSender = senderName.replace("\n", " ").replace("\t", " ").replace("|", " ").trim()
            .ifBlank { "Студент" }
        val activeItems = homeworks.filter { !it.isCompleted }.sortedBy { it.dueDate }

        if (activeItems.isEmpty()) {
            val cleanGroup = groupCode.replace("\n", " ").replace("|", " ").trim()
            return Pair("PULSE_ID:$cleanSender|$cleanGroup", 0)
        }

        val header = "PULSE_B:$cleanSender"
        val lines = mutableListOf<String>()
        var currentBytes = header.toByteArray(Charsets.UTF_8).size

        for (hw in activeItems) {
            val s = hw.subject.replace("\n", " ").replace("\t", " ").trim()
            val t = hw.task.replace("\n", " ").replace("\t", " ").trim()
            val d = hw.dueDate.toString()
            val tp = hw.targetPairs?.toString() ?: ""
            val line = "\n$s\t$t\t$d\t$tp"
            val lineBytes = line.toByteArray(Charsets.UTF_8).size

            if (lines.isNotEmpty() && currentBytes + lineBytes > maxCapacityBytes) {
                break
            }
            lines.add(line)
            currentBytes += lineBytes
        }

        val resultPayload = header + lines.joinToString("")
        return Pair(resultPayload, lines.size)
    }


    fun compressForCard(senderName: String, qrPayload: String): String {
        val cleanSender = senderName.replace("|", "").trim()
        return "PULSE_C:$cleanSender|$qrPayload"
    }

    /**
     * Универсальная декомпрессия и извлечение данных (P2P IsoDep, NFC-метка, QR).
     * Возвращает пару (ИмяОтправителя, ЧистыйQrPayload) или null.
     */
    fun parseAnyPayload(raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // 1. Формат ультра-сжатия для карт: PULSE_C:Имя|PULSE_HW:H4sI...
        if (trimmed.startsWith("PULSE_C:")) {
            val afterPrefix = trimmed.removePrefix("PULSE_C:")
            val sender = afterPrefix.substringBefore("|", "Pulse User").ifBlank { "Pulse User" }
            val hwData = afterPrefix.substringAfter("|", "")
            if (hwData.startsWith("PULSE_HW:")) {
                return Pair(sender, hwData)
            }
        }

        // 2. Формат JSON: {"v":2, "from":"Имя", "data":"PULSE_HW:..."}
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                val sender = json.optString("from", "Pulse User").ifBlank { "Pulse User" }
                val hwData = json.optString("data", "")
                if (hwData.startsWith("PULSE_HW:")) {
                    return Pair(sender, hwData)
                }
            } catch (_: Exception) {}
        }

        // 3. Чистый PULSE_HW:H4sI...
        if (trimmed.startsWith("PULSE_HW:")) {
            return Pair("Pulse User", trimmed)
        }

        return null
    }

    fun importAnyPayload(context: Context, raw: String?): NfcImportResult {
        if (raw.isNullOrBlank()) return NfcImportResult.Error("Нет данных для импорта")
        val trimmed = raw.trim()

        // 1. Формат PULSE_B
        if (trimmed.startsWith("PULSE_B:")) {
            val lines = trimmed.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return NfcImportResult.Error("Поврежден формат заданий")
            val sender = lines[0].removePrefix("PULSE_B:").trim().ifBlank { "Студент" }

            val currentHomeworks = HomeworkManager.loadHomeworks(context)
            var addedCount = 0

            for (i in 1 until lines.size) {
                val parts = lines[i].split("\t")
                if (parts.size >= 3) {
                    val subject = parts[0].trim()
                    val task = parts[1].trim()
                    val dueDate = DateTimeUtils.parseLocalDateOrNull(parts[2].trim()) ?: LocalDate.now()
                    val targetPairs = parts.getOrNull(3)?.toIntOrNull()

                    val alreadyExists = currentHomeworks.any {
                        it.subject.trim().equals(subject, ignoreCase = true) &&
                                it.task.trim().equals(task, ignoreCase = true)
                    }

                    if (!alreadyExists) {
                        val newItem = HomeworkItem(
                            id = UUID.randomUUID().toString(),
                            subject = subject,
                            task = task,
                            dueDate = dueDate,
                            notifyDayBefore = true,
                            isCompleted = false,
                            assignDate = LocalDate.now(),
                            targetPairs = targetPairs
                        )
                        HomeworkManager.saveHomework(context, newItem)
                        addedCount++
                    }
                }
            }

            return if (addedCount > 0) {
                NfcImportResult.HomeworkImported(addedCount, sender)
            } else {
                NfcImportResult.AlreadyExists
            }
        }

        if (trimmed.startsWith("PULSE_ID:")) {
            val data = trimmed.removePrefix("PULSE_ID:").trim()
            val name = data.substringBefore("|", "Студент").ifBlank { "Студент" }
            val group = data.substringAfter("|", "").trim()
            return NfcImportResult.StudentProfile(name, group)
        }

        val parsed = parseAnyPayload(trimmed)
        if (parsed != null) {
            val (sender, hwData) = parsed
            val count = HomeworkQrHelper.importFromQrPayload(context, hwData)
            return if (count > 0) {
                NfcImportResult.HomeworkImported(count, sender)
            } else {
                NfcImportResult.AlreadyExists
            }
        }

        return NfcImportResult.Error("Нераспознанный формат Pulse")
    }

    fun readPayloadFromTag(tag: Tag): String? {
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            try {
                if (!isoDep.isConnected) {
                    isoDep.connect()
                }
                isoDep.timeout = 5000
                val response = isoDep.transceive(SELECT_PULSE_AID_APDU)

                if (response != null && response.size >= 2) {
                    val status1 = response[response.size - 2]
                    val status2 = response[response.size - 1]
                    if (status1 == 0x90.toByte() && status2 == 0x00.toByte()) {


                        if (response.size >= 5 && response[0] == PulseHceService.CHUNK_MAGIC) {
                            val totalChunks = response[1].toInt() and 0xFF
                            val buffer = ByteArrayOutputStream()

                            val firstChunkLen = response.size - 5
                            if (firstChunkLen > 0) {
                                buffer.write(response, 3, firstChunkLen)
                            }

                            var allChunksReceived = true
                            for (c in 1 until totalChunks) {
                                val getChunkCmd = byteArrayOf(0x00.toByte(), 0xC0.toByte(), c.toByte(), 0x00.toByte(), 0x00.toByte())
                                val chunkResp = isoDep.transceive(getChunkCmd)

                                if (chunkResp != null && chunkResp.size >= 5 &&
                                    chunkResp[chunkResp.size - 2] == 0x90.toByte() &&
                                    chunkResp[chunkResp.size - 1] == 0x00.toByte() &&
                                    chunkResp[0] == PulseHceService.CHUNK_MAGIC) {
                                    val len = chunkResp.size - 5
                                    if (len > 0) {
                                        buffer.write(chunkResp, 3, len)
                                    }
                                } else {
                                    allChunksReceived = false
                                    break
                                }
                            }

                            isoDep.close()
                            if (allChunksReceived) {
                                return String(buffer.toByteArray(), Charsets.UTF_8)
                            }
                        } else {
                            // Обычный одиночный ответ
                            isoDep.close()
                            val payloadBytes = response.copyOfRange(0, response.size - 2)
                            val text = String(payloadBytes, Charsets.UTF_8)
                            if (text.contains("PULSE_") || text.contains("\"data\":")) {
                                return text
                            }
                        }
                    }
                }
                isoDep.close()
            } catch (_: Exception) {
                try { isoDep.close() } catch (_: Exception) {}
            }
        }


        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                if (!ndef.isConnected) {
                    ndef.connect()
                }
                val message = ndef.ndefMessage
                ndef.close()
                if (message != null) {
                    for (record in message.records) {
                        val rawBytes = record.payload

                        val text = if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
                            if (rawBytes.isNotEmpty()) {
                                val status = rawBytes[0].toInt()
                                val isUtf16 = (status and 0x80) != 0
                                val langLen = status and 0x3F
                                val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
                                if (rawBytes.size > 1 + langLen) {
                                    String(rawBytes, 1 + langLen, rawBytes.size - 1 - langLen, charset)
                                } else {
                                    String(rawBytes, charset)
                                }
                            } else ""
                        } else {
                            String(rawBytes, Charsets.UTF_8)
                        }

                        if (text.contains("PULSE_B:") || text.contains("PULSE_ID:") ||
                            text.contains("PULSE_C:") || text.contains("PULSE_HW:") ||
                            text.contains("{\"v\":") || text.contains("\"data\":")) {
                            return text
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }


    fun writePayloadToPhysicalTag(
        context: Context,
        tag: Tag,
        payload: String,
        itemsCount: Int = 0
    ): NfcWriteResult {
        val isProfile = payload.startsWith("PULSE_ID:")
        val textRecord = NdefRecord.createTextRecord("ru", payload)
        val compactMessage = NdefMessage(arrayOf(textRecord))

        // Полное сообщение с Application Record (только если места действительно с запасом, > 250 байт)
        val aar = NdefRecord.createApplicationRecord(context.packageName)
        val fullMessage = NdefMessage(arrayOf(textRecord, aar))

        var lastException: Exception? = null


        val ndef = Ndef.get(tag)
        if (ndef != null) {
            for (attempt in 1..3) {
                try {
                    if (!ndef.isConnected) {
                        ndef.connect()
                    }
                    if (!ndef.isWritable) {
                        try { ndef.close() } catch (_: Exception) {}
                        return NfcWriteResult.ReadOnly
                    }

                    val msgToWrite = if (ndef.maxSize >= fullMessage.byteArrayLength) fullMessage else compactMessage
                    if (ndef.maxSize < msgToWrite.byteArrayLength) {
                        val needed = msgToWrite.byteArrayLength
                        val max = ndef.maxSize
                        try { ndef.close() } catch (_: Exception) {}
                        return NfcWriteResult.NotEnoughSpace(needed, max)
                    }

                    ndef.writeNdefMessage(msgToWrite)
                    val rawType = ndef.type.substringAfterLast(".").uppercase().ifBlank { "NFC TAG" }
                    val cleanType = when {
                        rawType.contains("TYPE2") || rawType.contains("NTAG") -> "NTAG / Type 2"
                        rawType.contains("MIFARE") -> "Mifare Classic"
                        else -> rawType
                    }
                    val bytesWritten = msgToWrite.byteArrayLength
                    val maxBytes = ndef.maxSize
                    try { ndef.close() } catch (_: Exception) {}
                    return NfcWriteResult.Success(cleanType, bytesWritten, maxBytes, itemsCount, isProfile)
                } catch (e: Exception) {
                    lastException = e
                    try { ndef.close() } catch (_: Exception) {}
                    try { Thread.sleep(50) } catch (_: Exception) {}
                }
            }
        }


        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            for (attempt in 1..3) {
                try {
                    if (!formatable.isConnected) {
                        formatable.connect()
                    }
                    formatable.format(compactMessage)
                    val bytesWritten = compactMessage.byteArrayLength
                    try { formatable.close() } catch (_: Exception) {}
                    return NfcWriteResult.Success("NFC Card (Formatted)", bytesWritten, 144, itemsCount, isProfile)
                } catch (e: Exception) {
                    lastException = e
                    try { formatable.close() } catch (_: Exception) {}
                    try { Thread.sleep(50) } catch (_: Exception) {}
                }
            }
        }


        val ultralight = MifareUltralight.get(tag)
        if (ultralight != null) {
            for (attempt in 1..2) {
                try {
                    if (!ultralight.isConnected) {
                        ultralight.connect()
                    }

                    ultralight.writePage(3, byteArrayOf(0xE1.toByte(), 0x10.toByte(), 0x12.toByte(), 0x00.toByte()))


                    val msgBytes = compactMessage.toByteArray()
                    val bos = ByteArrayOutputStream()
                    bos.write(0x03)
                    if (msgBytes.size < 255) {
                        bos.write(msgBytes.size)
                    } else {
                        bos.write(0xFF)
                        bos.write((msgBytes.size shr 8) and 0xFF)
                        bos.write(msgBytes.size and 0xFF)
                    }
                    bos.write(msgBytes)
                    bos.write(0xFE) // Terminator TLV
                    val tlv = bos.toByteArray()

                    var page = 4
                    var offset = 0
                    while (offset < tlv.size) {
                        val pageData = ByteArray(4)
                        val chunk = minOf(4, tlv.size - offset)
                        System.arraycopy(tlv, offset, pageData, 0, chunk)
                        ultralight.writePage(page, pageData)
                        page++
                        offset += 4
                    }

                    val bytesWritten = compactMessage.byteArrayLength
                    try { ultralight.close() } catch (_: Exception) {}
                    return NfcWriteResult.Success("NTAG213 (Raw CC)", bytesWritten, 144, itemsCount, isProfile)
                } catch (e: Exception) {
                    lastException = e
                    try { ultralight.close() } catch (_: Exception) {}
                    try { Thread.sleep(50) } catch (_: Exception) {}
                }
            }
        }

        if (lastException != null) {
            return NfcWriteResult.Failure(lastException.localizedMessage ?: "Сбой соединения с чипом")
        }

        val techList = tag.techList.map { it.substringAfterLast(".") }
        return NfcWriteResult.NotFormatable(techList)
    }
}