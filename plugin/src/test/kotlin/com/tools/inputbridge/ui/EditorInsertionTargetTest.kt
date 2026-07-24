package com.tools.inputbridge.ui

import java.util.concurrent.atomic.AtomicReference
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorInsertionTargetTest {
    @Test
    fun `inserts at captured caret position`() = onEdt {
        val editor = JTextArea("abcd").apply {
            caretPosition = 2
        }

        EditorInsertionTarget.capture(editor).insert("中文")

        assertEquals("ab中文cd", editor.text)
        assertEquals(4, editor.caretPosition)
    }

    @Test
    fun `replaces captured selection`() = onEdt {
        val editor = JTextArea("abcdef").apply {
            select(1, 4)
        }

        EditorInsertionTarget.capture(editor).insert("X")

        assertEquals("aXef", editor.text)
        assertEquals(2, editor.caretPosition)
    }

    @Test
    fun `captured position follows document changes while popup is open`() = onEdt {
        val editor = JTextArea("abcd").apply {
            caretPosition = 2
        }
        val target = EditorInsertionTarget.capture(editor)
        editor.document.insertString(0, "0", null)

        target.insert("X")

        assertEquals("0abXcd", editor.text)
        assertEquals(4, editor.caretPosition)
    }

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
