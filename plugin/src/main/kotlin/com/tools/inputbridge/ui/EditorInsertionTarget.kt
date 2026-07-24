package com.tools.inputbridge.ui

import javax.swing.text.Document
import javax.swing.text.JTextComponent
import javax.swing.text.Position

internal class EditorInsertionTarget private constructor(
    private val editor: JTextComponent,
    private val document: Document,
    private val selectionStart: Position,
    private val selectionEnd: Position,
) {
    fun insert(text: String) {
        check(editor.document === document) { "The editor document changed while choosing a favorite" }
        val start = minOf(selectionStart.offset, selectionEnd.offset)
        val end = maxOf(selectionStart.offset, selectionEnd.offset)
        document.remove(start, end - start)
        document.insertString(start, text, null)
        editor.caretPosition = start + text.length
    }

    companion object {
        fun capture(editor: JTextComponent): EditorInsertionTarget {
            val document = editor.document
            return EditorInsertionTarget(
                editor = editor,
                document = document,
                selectionStart = document.createPosition(editor.selectionStart),
                selectionEnd = document.createPosition(editor.selectionEnd),
            )
        }
    }
}
