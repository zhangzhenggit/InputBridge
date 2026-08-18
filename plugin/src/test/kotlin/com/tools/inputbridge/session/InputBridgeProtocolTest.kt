package com.tools.inputbridge.session

import com.tools.inputbridge.core.TextStyleKind
import com.tools.inputbridge.core.TextStyleRun
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
            writeClipboardBody(7, "设备剪贴板")
        } as InputBridgeProtocol.ServerMessage.Clipboard
        assertEquals("设备剪贴板", clipboard.text)
        assertTrue(clipboard.runs.isEmpty())

        val emptyClipboard = readFrame(17) {
            writeLong(8)
            writeBoolean(false)
            writeInt(0)
            writeInt(0)
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

    @Test
    fun `reads clipboard style runs`() {
        val clipboard = readFrame(17) {
            writeClipboardBody(
                sequence = 11,
                text = "粗体 link",
                spans = listOf(
                    Triple(0, 2, TextStyleKind.BOLD.wireId to 0),
                    Triple(3, 7, TextStyleKind.FOREGROUND.wireId to 0xFFB71C1C.toInt()),
                ),
            )
        } as InputBridgeProtocol.ServerMessage.Clipboard

        assertEquals("粗体 link", clipboard.text)
        assertEquals(
            listOf(
                TextStyleRun(0, 2, TextStyleKind.BOLD, 0),
                TextStyleRun(3, 7, TextStyleKind.FOREGROUND, 0xFFB71C1C.toInt()),
            ),
            clipboard.runs,
        )
    }

    @Test
    fun `drops style runs the editor cannot place`() {
        val clipboard = readFrame(17) {
            writeClipboardBody(
                sequence = 12,
                text = "abc",
                spans = listOf(
                    Triple(0, 99, TextStyleKind.BOLD.wireId to 0),
                    Triple(2, 1, TextStyleKind.ITALIC.wireId to 0),
                    Triple(0, 3, 200 to 0),
                    Triple(1, 2, TextStyleKind.UNDERLINE.wireId to 0),
                ),
            )
        } as InputBridgeProtocol.ServerMessage.Clipboard

        assertEquals(listOf(TextStyleRun(1, 2, TextStyleKind.UNDERLINE, 0)), clipboard.runs)
    }

    @Test
    fun `rejects a clipboard frame whose declared sizes do not fill it`() {
        assertFailsWith<IllegalArgumentException> {
            readFrame(17) {
                writeLong(13)
                writeBoolean(true)
                writeInt(3)
                write("abc".toByteArray())
                writeInt(2)
            }
        }
    }

    private fun DataOutputStream.writeClipboardBody(
        sequence: Long,
        text: String,
        spans: List<Triple<Int, Int, Pair<Int, Int>>> = emptyList(),
    ) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeLong(sequence)
        writeBoolean(true)
        writeInt(bytes.size)
        write(bytes)
        writeInt(spans.size)
        spans.forEach { (start, end, style) ->
            writeInt(start)
            writeInt(end)
            writeByte(style.first)
            writeInt(style.second)
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
