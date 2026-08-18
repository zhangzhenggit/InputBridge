package com.tools.inputbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardTextMergerTest {
    @Test
    fun `separates appended device text with a new line`() {
        assertEquals("\n", ClipboardTextMerger.separator("local"))
    }

    @Test
    fun `does not add an extra line when the editor already ends with one`() {
        assertEquals("", ClipboardTextMerger.separator("local\n"))
    }

    @Test
    fun `does not lead an empty editor with a new line`() {
        assertEquals("", ClipboardTextMerger.separator(""))
    }
}
