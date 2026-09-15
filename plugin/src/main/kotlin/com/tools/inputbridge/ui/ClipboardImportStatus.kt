package com.tools.inputbridge.ui

import com.tools.inputbridge.core.StyledText

/** Status line text describing what a device clipboard update did to the editor. */
internal object ClipboardImportStatus {
    fun message(incoming: StyledText): String {
        val text = incoming.text
        return when {
            incoming.isImportable -> "Device clipboard imported into the editor"
            // Importing nothing leaves the editor looking inert, so name what the device actually held.
            text.isEmpty() -> "Device clipboard holds no text"
            text.length == 1 -> "Device clipboard holds a single blank character"
            else -> "Device clipboard holds ${text.length} blank characters only"
        }
    }
}
