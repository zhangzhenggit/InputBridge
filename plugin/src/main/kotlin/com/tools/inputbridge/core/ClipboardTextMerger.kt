package com.tools.inputbridge.core

internal object ClipboardTextMerger {
    fun merge(current: String, incoming: String, replace: Boolean): String {
        if (replace) return incoming
        if (incoming.isEmpty()) return current
        if (current.isEmpty()) return incoming
        return if (current.endsWith('\n')) current + incoming else "$current\n$incoming"
    }
}
