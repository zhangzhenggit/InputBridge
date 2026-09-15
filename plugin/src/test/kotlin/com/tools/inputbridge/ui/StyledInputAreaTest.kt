package com.tools.inputbridge.ui

import com.tools.inputbridge.core.StyledText
import com.tools.inputbridge.core.TextStyleKind
import com.tools.inputbridge.core.TextStyleRun
import java.awt.Color
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.text.AttributeSet
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyledInputAreaTest {
    @Test
    fun `appends device clipboard text on its own line`() = onEdt {
        val editor = editor("local")

        editor.applyStyled(StyledText("device"), replace = false)

        assertEquals("local\ndevice", editor.plainText)
    }

    @Test
    fun `replaces the editor when requested`() = onEdt {
        val editor = editor("local")

        editor.applyStyled(StyledText("device"), replace = true)

        assertEquals("device", editor.plainText)
    }

    @Test
    fun `keeps existing text when an empty clipboard is appended`() = onEdt {
        val editor = editor("local")

        editor.applyStyled(StyledText(""), replace = false)

        assertEquals("local", editor.plainText)
    }

    @Test
    fun `applies style runs at the appended offset`() = onEdt {
        val editor = editor("local")

        editor.applyStyled(
            StyledText("device", listOf(TextStyleRun(0, 3, TextStyleKind.BOLD, 0))),
            replace = false,
        )

        assertEquals("local\ndevice", editor.plainText)
        assertFalse(isStyled(editor, 0, StyleConstants.Bold))
        assertTrue(isStyled(editor, 6, StyleConstants.Bold))
        assertFalse(isStyled(editor, 9, StyleConstants.Bold))
    }

    @Test
    fun `keeps a device color that stays legible and drops one that does not`() = onEdt {
        val editor = editor("")
        editor.background = Color.WHITE

        editor.applyStyled(
            StyledText(
                "ab",
                listOf(
                    TextStyleRun(0, 1, TextStyleKind.FOREGROUND, 0xFFB71C1C.toInt()),
                    TextStyleRun(1, 2, TextStyleKind.FOREGROUND, 0xFFFFFFFF.toInt()),
                ),
            ),
            replace = true,
        )

        assertTrue(isStyled(editor, 0, StyleConstants.Foreground))
        assertEquals(Color(0xB7, 0x1C, 0x1C), attributesAt(editor, 0).getAttribute(StyleConstants.Foreground))
        assertFalse(isStyled(editor, 1, StyleConstants.Foreground))
    }

    @Test
    fun `measures a foreground against the background applied under it`() = onEdt {
        val editor = editor("")
        editor.background = Color.WHITE

        editor.applyStyled(
            StyledText(
                "a",
                listOf(
                    TextStyleRun(0, 1, TextStyleKind.FOREGROUND, 0xFFFFFFFF.toInt()),
                    TextStyleRun(0, 1, TextStyleKind.BACKGROUND, 0xFF1C1C1C.toInt()),
                ),
            ),
            replace = true,
        )

        assertTrue(isStyled(editor, 0, StyleConstants.Foreground))
    }

    @Test
    fun `colors links without underlining them`() = onEdt {
        val editor = editor("")

        editor.applyStyled(
            StyledText("link", listOf(TextStyleRun(0, 4, TextStyleKind.LINK, 0))),
            replace = true,
        )

        assertTrue(isStyled(editor, 0, StyleConstants.Foreground))
        assertFalse(isStyled(editor, 0, StyleConstants.Underline))
    }

    @Test
    fun `shrinks superscripts and subscripts on the normal baseline`() = onEdt {
        val editor = editor("")

        editor.applyStyled(
            StyledText(
                "x2y3",
                listOf(
                    TextStyleRun(1, 2, TextStyleKind.SUPERSCRIPT, 0),
                    TextStyleRun(3, 4, TextStyleKind.SUBSCRIPT, 0),
                ),
            ),
            replace = true,
        )

        for (offset in listOf(1, 3)) {
            assertEquals(
                TextStyleAttributes.scaledFontSize(editor.font.size, TextStyleAttributes.SCRIPT_SIZE_PERCENT),
                attributesAt(editor, offset).getAttribute(StyleConstants.FontSize),
            )
            assertFalse(isStyled(editor, offset, StyleConstants.Superscript))
            assertFalse(isStyled(editor, offset, StyleConstants.Subscript))
        }
    }

    @Test
    fun `drops device styling when the editor is rewritten`() = onEdt {
        val editor = editor("")
        editor.applyStyled(
            StyledText("bold", listOf(TextStyleRun(0, 4, TextStyleKind.BOLD, 0))),
            replace = true,
        )
        assertTrue(isStyled(editor, 0, StyleConstants.Bold))

        editor.setPlainText("plain")

        assertEquals("plain", editor.plainText)
        assertFalse(isStyled(editor, 0, StyleConstants.Bold))
    }

    private fun editor(text: String): StyledInputArea = StyledInputArea().apply { setPlainText(text) }

    private fun attributesAt(editor: StyledInputArea, offset: Int): AttributeSet =
        editor.styledDocument.getCharacterElement(offset).attributes

    /**
     * Attribute lookups resolve through the document default style, which already carries the
     * editor font and foreground, so only locally defined attributes prove a run was applied.
     */
    private fun isStyled(editor: StyledInputArea, offset: Int, key: Any): Boolean =
        attributesAt(editor, offset).isDefined(key)

    private fun onEdt(test: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            test()
            return
        }
        val failure = AtomicReference<Throwable?>()
        SwingUtilities.invokeAndWait {
            runCatching(test).exceptionOrNull()?.let(failure::set)
        }
        failure.get()?.let { throw it }
    }
}
