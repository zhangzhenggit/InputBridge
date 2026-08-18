package com.tools.inputbridge.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyledTextTest {
    @Test
    fun `treats a non-breaking space as nothing to import`() {
        // Observed from a device: an application copy handler that publishes only U+00A0.
        assertFalse(StyledText(" ").isImportable)
    }

    @Test
    fun `treats other invisible clipboard content as nothing to import`() {
        assertFalse(StyledText("").isImportable)
        assertFalse(StyledText("   ").isImportable)
        assertFalse(StyledText("\n\t").isImportable)
        assertFalse(StyledText("  ").isImportable)
    }

    @Test
    fun `imports content that has anything visible`() {
        assertTrue(StyledText("表格").isImportable)
        assertTrue(StyledText(" x ").isImportable)
    }
}
