package com.tools.inputbridge.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputBridgeProtocolTest {
    @Test
    fun `writes unicode paste frame`() {
        val buffer = ByteArrayOutputStream()
        InputBridgeProtocol.writePaste(DataOutputStream(buffer), 42L, "中文 text 😀", appendEnter = true)

        DataInputStream(ByteArrayInputStream(buffer.toByteArray())).use { input ->
            val length = input.readInt()
            assertEquals(length, input.available())
            assertEquals(1, input.readUnsignedByte())
            assertEquals(42L, input.readLong())
            assertEquals(1, input.readUnsignedByte())
            assertEquals("中文 text 😀", input.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `writes replace and enter flags independently`() {
        val buffer = ByteArrayOutputStream()
        InputBridgeProtocol.writePaste(
            DataOutputStream(buffer),
            requestId = 43L,
            text = "replacement",
            appendEnter = true,
            replaceExisting = true,
        )

        DataInputStream(ByteArrayInputStream(buffer.toByteArray())).use { input ->
            input.readInt()
            assertEquals(1, input.readUnsignedByte())
            assertEquals(43L, input.readLong())
            assertEquals(3, input.readUnsignedByte())
            assertEquals("replacement", input.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `writes current clipboard request frame`() {
        val buffer = ByteArrayOutputStream()
        InputBridgeProtocol.writeGetClipboard(DataOutputStream(buffer))

        DataInputStream(ByteArrayInputStream(buffer.toByteArray())).use { input ->
            assertEquals(1, input.readInt())
            assertEquals(4, input.readUnsignedByte())
            assertEquals(0, input.available())
        }
    }

    @Test
    fun `reads all server message types`() {
        val hello = readFrame(16) {
            writeInt(InputBridgeProtocol.VERSION)
            writeInt(36)
            write("Test Tablet".toByteArray())
        } as InputBridgeProtocol.ServerMessage.Hello
        assertEquals(
            InputBridgeProtocol.ServerMessage.Hello(InputBridgeProtocol.VERSION, 36, "Test Tablet"),
            hello,
        )

        val clipboard = readFrame(17) {
            writeLong(7)
            writeBoolean(true)
            write("设备剪贴板".toByteArray())
        } as InputBridgeProtocol.ServerMessage.Clipboard
        assertEquals("设备剪贴板", clipboard.text)

        val emptyClipboard = readFrame(17) {
            writeLong(8)
            writeBoolean(false)
        } as InputBridgeProtocol.ServerMessage.Clipboard
        assertNull(emptyClipboard.text)

        val acknowledgement = readFrame(18) {
            writeLong(9)
            writeBoolean(true)
            write("ok".toByteArray())
        } as InputBridgeProtocol.ServerMessage.Ack
        assertTrue(acknowledgement.success)

        assertEquals(
            InputBridgeProtocol.ServerMessage.Error("failure"),
            readFrame(19) { write("failure".toByteArray()) },
        )
        assertEquals(InputBridgeProtocol.ServerMessage.Pong(123), readFrame(20) { writeLong(123) })
    }

    @Test
    fun `rejects oversized and malformed frames`() {
        val text = "x".repeat(InputBridgeProtocol.MAX_INPUT_BYTES + 1)
        assertFailsWith<IllegalArgumentException> {
            InputBridgeProtocol.writePaste(DataOutputStream(ByteArrayOutputStream()), 1, text, false)
        }

        val malformed = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { it.writeInt(0) }
        }
        assertFailsWith<IllegalArgumentException> {
            InputBridgeProtocol.read(DataInputStream(ByteArrayInputStream(malformed.toByteArray())))
        }
    }

    private fun readFrame(type: Int, body: DataOutputStream.() -> Unit): InputBridgeProtocol.ServerMessage? {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use {
            it.writeByte(type)
            it.body()
        }
        val framed = ByteArrayOutputStream()
        DataOutputStream(framed).use {
            it.writeInt(payload.size())
            it.write(payload.toByteArray())
        }
        return InputBridgeProtocol.read(DataInputStream(ByteArrayInputStream(framed.toByteArray())))
    }
}
