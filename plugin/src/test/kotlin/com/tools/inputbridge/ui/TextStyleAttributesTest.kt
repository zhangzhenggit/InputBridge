package com.tools.inputbridge.ui

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextStyleAttributesTest {
    @Test
    fun `drops the alpha channel device colors carry`() {
        assertEquals(Color(0xFF, 0x00, 0x00), TextStyleAttributes.opaqueColor(0x80FF0000.toInt()))
    }

    @Test
    fun `keeps a device color that stays legible on the editor surface`() {
        assertTrue(TextStyleAttributes.isReadable(Color(0xB7, 0x1C, 0x1C), Color.WHITE))
    }

    @Test
    fun `rejects a device color authored for the opposite theme`() {
        assertFalse(TextStyleAttributes.isReadable(Color.WHITE, Color.WHITE))
        assertFalse(TextStyleAttributes.isReadable(Color(0xEE, 0xEE, 0xEE), Color.WHITE))
    }

    @Test
    fun `reports the reference contrast ratio for black on white`() {
        assertEquals(21.0, TextStyleAttributes.contrastRatio(Color.BLACK, Color.WHITE), 0.01)
    }

    @Test
    fun `scales the font size and clamps unreasonable device values`() {
        assertEquals(18, TextStyleAttributes.scaledFontSize(14, 125))
        assertEquals(11, TextStyleAttributes.scaledFontSize(14, 80))
        assertEquals(TextStyleAttributes.MIN_FONT_SIZE, TextStyleAttributes.scaledFontSize(14, 1))
        assertEquals(TextStyleAttributes.MAX_FONT_SIZE, TextStyleAttributes.scaledFontSize(14, 10_000))
    }
}
