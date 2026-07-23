package com.tools.inputbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardTextMergerTest {
    @Test
    fun `replaces existing text when requested`() {
        assertEquals("device", ClipboardTextMerger.merge("local", "device", replace = true))
    }

    @Test
    fun `appends device text on a new line by default`() {
        assertEquals("local\ndevice", ClipboardTextMerger.merge("local", "device", replace = false))
    }

    @Test
    fun `does not add an extra line when the editor already ends with one`() {
        assertEquals("local\ndevice", ClipboardTextMerger.merge("local\n", "device", replace = false))
    }

    @Test
    fun `keeps existing text when an empty clipboard is appended`() {
        assertEquals("local", ClipboardTextMerger.merge("local", "", replace = false))
    }
}
