package com.tools.inputbridge.ui

import com.tools.inputbridge.core.StyledText
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardImportStatusTest {
    @Test
    fun `reports a successful import`() {
        assertEquals("Device clipboard imported into the editor", ClipboardImportStatus.message(StyledText("hello")))
    }

    @Test
    fun `names an empty clipboard`() {
        assertEquals("Device clipboard holds no text", ClipboardImportStatus.message(StyledText("")))
    }

    @Test
    fun `counts blank characters that were not imported`() {
        assertEquals("Device clipboard holds a single blank character", ClipboardImportStatus.message(StyledText(" ")))
        assertEquals("Device clipboard holds 3 blank characters only", ClipboardImportStatus.message(StyledText(" \t ")))
    }
}
