package com.tools.inputbridge.core

internal object ClipboardTextMerger {
    /** Device clipboard text is appended on its own line unless the editor already ends with one. */
    fun separator(current: String): String =
        if (current.isEmpty() || current.endsWith('\n')) "" else "\n"
}
