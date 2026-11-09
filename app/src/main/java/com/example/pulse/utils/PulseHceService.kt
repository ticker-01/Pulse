package com.example.pulse.utils

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class PulseHceService : HostApduService() {

    companion object {
        private val SELECT_APDU_HEADER = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte()
        )

        private const val CLA_GET_RESPONSE = 0x00.toByte()
        private const val INS_GET_RESPONSE = 0xC0.toByte()

        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())

        // Маркер фрагментированного протокола
        const val CHUNK_MAGIC: Byte = 0xAA.toByte()
        const val MAX_CHUNK_DATA_SIZE = 220

        @Volatile
        var currentPayload: String? = null

        @Volatile
        var onPayloadSent: (() -> Unit)? = null

        private val mainHandler = Handler(Looper.getMainLooper())
    }

    private var cachedChunks: List<ByteArray>? = null

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return STATUS_FAILED
        val payload = currentPayload ?: return STATUS_FAILED

        // 1. Команда выбора AID приложения Pulse (SELECT AID)
        if (isSelectCommand(commandApdu)) {
            val dataBytes = payload.toByteArray(Charsets.UTF_8)

            // Если данные компактные и влезают в один APDU (<= 220 байт), отправляем за 1 кадр
            if (dataBytes.size <= MAX_CHUNK_DATA_SIZE) {
                cachedChunks = null
                notifySent()
                return dataBytes + STATUS_SUCCESS
            }

            // Иначе разбиваем на чанки
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < dataBytes.size) {
                val chunkSize = minOf(MAX_CHUNK_DATA_SIZE, dataBytes.size - offset)
                val chunk = dataBytes.copyOfRange(offset, offset + chunkSize)
                chunks.add(chunk)
                offset += chunkSize
            }
            cachedChunks = chunks

            // Отправляем первый чанк: [0xAA (Magic)] [TotalChunks] [ChunkIndex (0)] [Data...] [90 00]
            val totalChunks = chunks.size.toByte()
            val chunkIdx = 0.toByte()
            val firstChunk = chunks[0]

            val response = ByteArray(3 + firstChunk.size + 2)
            response[0] = CHUNK_MAGIC
            response[1] = totalChunks
            response[2] = chunkIdx
            System.arraycopy(firstChunk, 0, response, 3, firstChunk.size)
            response[response.size - 2] = 0x90.toByte()
            response[response.size - 1] = 0x00.toByte()

            return response
        }

        // 2. Команда получения следующего чанка: 00 C0 [ChunkIndex] 00 [Le]
        if (isGetChunkCommand(commandApdu)) {
            val chunks = cachedChunks ?: return STATUS_FAILED
            val requestedIdx = commandApdu[2].toInt() and 0xFF

            if (requestedIdx in chunks.indices) {
                val chunk = chunks[requestedIdx]
                val totalChunks = chunks.size.toByte()
                val chunkIdx = requestedIdx.toByte()

                val response = ByteArray(3 + chunk.size + 2)
                response[0] = CHUNK_MAGIC
                response[1] = totalChunks
                response[2] = chunkIdx
                System.arraycopy(chunk, 0, response, 3, chunk.size)
                response[response.size - 2] = 0x90.toByte()
                response[response.size - 1] = 0x00.toByte()

                // Если отдали последний чанк — уведомляем UI отправителя
                if (requestedIdx == chunks.size - 1) {
                    notifySent()
                    cachedChunks = null
                }

                return response
            }
        }

        return STATUS_SUCCESS
    }

    private fun notifySent() {
        mainHandler.post {
            try {
                onPayloadSent?.invoke()
            } catch (_: Exception) {}
        }
    }

    override fun onDeactivated(reason: Int) {
        cachedChunks = null
    }

    private fun isSelectCommand(apdu: ByteArray): Boolean {
        if (apdu.size < 4) return false
        return apdu[0] == SELECT_APDU_HEADER[0] &&
                apdu[1] == SELECT_APDU_HEADER[1] &&
                apdu[2] == SELECT_APDU_HEADER[2] &&
                apdu[3] == SELECT_APDU_HEADER[3]
    }

    private fun isGetChunkCommand(apdu: ByteArray): Boolean {
        if (apdu.size < 4) return false
        return apdu[0] == CLA_GET_RESPONSE && apdu[1] == INS_GET_RESPONSE
    }
}