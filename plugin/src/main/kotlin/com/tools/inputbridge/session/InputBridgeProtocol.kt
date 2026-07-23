package com.tools.inputbridge.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

internal object InputBridgeProtocol {
    const val VERSION = 4
    const val MAX_INPUT_BYTES = 256 * 1024
    private const val MAX_FRAME_BYTES = 1024 * 1024 + 64

    private const val CLIENT_PASTE_TEXT = 1
    private const val CLIENT_PING = 2
    private const val CLIENT_CLOSE = 3
    private const val CLIENT_GET_CLIPBOARD = 4

    private const val SERVER_HELLO = 16
    private const val SERVER_CLIPBOARD = 17
    private const val SERVER_ACK = 18
    private const val SERVER_ERROR = 19
    private const val SERVER_PONG = 20

    sealed interface ServerMessage {
        data class Hello(val version: Int, val sdk: Int, val model: String) : ServerMessage
        data class Clipboard(val sequence: Long, val text: String?) : ServerMessage
        data class Ack(val requestId: Long, val success: Boolean, val message: String) : ServerMessage
        data class Error(val message: String) : ServerMessage
        data class Pong(val value: Long) : ServerMessage
    }

    fun read(input: DataInputStream): ServerMessage? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(length in 1..MAX_FRAME_BYTES) { "Invalid server frame length: $length" }
        val frame = ByteArray(length)
        input.readFully(frame)
        DataInputStream(ByteArrayInputStream(frame)).use { body ->
            return when (val type = body.readUnsignedByte()) {
                SERVER_HELLO -> {
                    require(length >= 9) { "Invalid hello frame" }
                    ServerMessage.Hello(body.readInt(), body.readInt(), body.readRemainingText(length - 9))
                }
                SERVER_CLIPBOARD -> {
                    require(length >= 10) { "Invalid clipboard frame" }
                    val sequence = body.readLong()
                    val hasText = body.readBoolean()
                    ServerMessage.Clipboard(sequence, body.readRemainingText(length - 10).takeIf { hasText })
                }
                SERVER_ACK -> {
                    require(length >= 10) { "Invalid acknowledgement frame" }
                    ServerMessage.Ack(body.readLong(), body.readBoolean(), body.readRemainingText(length - 10))
                }
                SERVER_ERROR -> ServerMessage.Error(body.readRemainingText(length - 1))
                SERVER_PONG -> {
                    require(length == 9) { "Invalid pong frame" }
                    ServerMessage.Pong(body.readLong())
                }
                else -> error("Unsupported server message type: $type")
            }
        }
    }

    fun writePaste(
        output: DataOutputStream,
        requestId: Long,
        text: String,
        appendEnter: Boolean,
        replaceExisting: Boolean = false,
    ) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty()) { "Input text is empty" }
        require(bytes.size <= MAX_INPUT_BYTES) { "Input text exceeds 256 KB" }
        writeFrame(output, CLIENT_PASTE_TEXT) {
            writeLong(requestId)
            val flags = (if (appendEnter) FLAG_APPEND_ENTER else 0) or
                (if (replaceExisting) FLAG_REPLACE_EXISTING else 0)
            writeByte(flags)
            write(bytes)
        }
    }

    fun writePing(output: DataOutputStream, value: Long) = writeFrame(output, CLIENT_PING) { writeLong(value) }

    fun writeClose(output: DataOutputStream) = writeFrame(output, CLIENT_CLOSE) {}

    fun writeGetClipboard(output: DataOutputStream) = writeFrame(output, CLIENT_GET_CLIPBOARD) {}

    private fun writeFrame(output: DataOutputStream, type: Int, writeBody: DataOutputStream.() -> Unit) {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { body ->
            body.writeByte(type)
            body.writeBody()
        }
        val frame = buffer.toByteArray()
        synchronized(output) {
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
        }
    }

    private fun DataInputStream.readRemainingText(length: Int): String {
        require(length >= 0)
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private const val FLAG_APPEND_ENTER = 1
    private const val FLAG_REPLACE_EXISTING = 1 shl 1
}
