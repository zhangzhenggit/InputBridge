package com.tools.inputbridge.history

import java.nio.charset.StandardCharsets

data class InputHistoryItem(
    val content: String,
    val lastUsedAtMillis: Long,
)

internal data class InputHistoryPreferences(
    val enabled: Boolean,
    val maxEntries: Int,
)

internal object InputHistoryRules {
    const val DEFAULT_MAX_ENTRIES = 50
    const val MAX_ENTRIES = 200
    const val MAX_CONTENT_BYTES = 256 * 1024
    const val MAX_TOTAL_CONTENT_BYTES = 10 * 1024 * 1024
    val ENTRY_LIMIT_OPTIONS = arrayOf(20, 50, 100, 200)

    fun normalize(content: String): String =
        content.replace("\r\n", "\n").replace('\r', '\n')

    fun validate(content: String): String? {
        val normalized = normalize(content)
        return normalized.takeIf {
            it.isNotBlank() && utf8Size(it) <= MAX_CONTENT_BYTES
        }
    }

    fun normalizeLimit(maxEntries: Int): Int =
        ENTRY_LIMIT_OPTIONS.firstOrNull { maxEntries <= it } ?: MAX_ENTRIES

    fun trim(items: List<InputHistoryItem>, maxEntries: Int): List<InputHistoryItem> {
        val retained = ArrayList<InputHistoryItem>()
        val contents = HashSet<String>()
        var totalBytes = 0
        val limit = normalizeLimit(maxEntries)
        for (item in items) {
            if (retained.size >= limit) break
            val content = validate(item.content) ?: continue
            if (!contents.add(content)) continue
            val candidateBytes = totalBytes + utf8Size(content)
            if (candidateBytes > MAX_TOTAL_CONTENT_BYTES) continue
            totalBytes = candidateBytes
            retained += item.copy(content = content)
        }
        return retained
    }

    fun preview(content: String): String {
        val firstLine = normalize(content)
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            .orEmpty()
        val count = firstLine.codePointCount(0, firstLine.length)
        if (count <= PREVIEW_CODE_POINTS) return firstLine
        return firstLine.substring(0, firstLine.offsetByCodePoints(0, PREVIEW_CODE_POINTS - 1)).trimEnd() + "…"
    }

    private fun utf8Size(content: String): Int = content.toByteArray(StandardCharsets.UTF_8).size

    private const val PREVIEW_CODE_POINTS = 80
}
