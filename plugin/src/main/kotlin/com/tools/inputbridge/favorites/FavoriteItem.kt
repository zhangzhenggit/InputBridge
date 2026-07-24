package com.tools.inputbridge.favorites

import java.nio.charset.StandardCharsets
import java.util.UUID

data class FavoriteItem(
    val id: String,
    val content: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

class FavoriteValidationException(message: String) : IllegalArgumentException(message)

internal enum class FavoriteAddStatus {
    READY,
    EMPTY,
    ITEM_TOO_LARGE,
    DUPLICATE,
    COLLECTION_FULL,
    STORAGE_FULL,
}

internal data class FavoriteAddition(
    val status: FavoriteAddStatus,
    val content: String? = null,
)

internal object FavoriteRules {
    const val MAX_FAVORITES = 1_000
    const val MAX_CONTENT_BYTES = 256 * 1024
    const val MAX_TOTAL_CONTENT_BYTES = 10 * 1024 * 1024

    fun create(content: String, nowMillis: Long = System.currentTimeMillis()): FavoriteItem =
        FavoriteItem(
            id = UUID.randomUUID().toString(),
            content = validate(content),
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )

    fun validate(content: String): String {
        val normalized = normalizeContent(content)
        when {
            normalized.isBlank() -> throw FavoriteValidationException("Favorite content is required")
            utf8Size(normalized) > MAX_CONTENT_BYTES ->
                throw FavoriteValidationException("Favorite content exceeds 256 KB")
        }
        return normalized
    }

    fun evaluateAddition(content: String, existing: List<FavoriteItem>): FavoriteAddition {
        val normalized = normalizeContent(content)
        if (normalized.isBlank()) return FavoriteAddition(FavoriteAddStatus.EMPTY)
        val contentBytes = utf8Size(normalized)
        if (contentBytes > MAX_CONTENT_BYTES) return FavoriteAddition(FavoriteAddStatus.ITEM_TOO_LARGE)
        if (existing.any { it.content == normalized }) return FavoriteAddition(FavoriteAddStatus.DUPLICATE)
        if (existing.size >= MAX_FAVORITES) return FavoriteAddition(FavoriteAddStatus.COLLECTION_FULL)
        val totalBytes = existing.sumOf { utf8Size(it.content) }
        if (totalBytes + contentBytes > MAX_TOTAL_CONTENT_BYTES) {
            return FavoriteAddition(FavoriteAddStatus.STORAGE_FULL)
        }
        return FavoriteAddition(FavoriteAddStatus.READY, normalized)
    }

    fun validateCollection(items: List<FavoriteItem>): List<FavoriteItem> {
        if (items.size > MAX_FAVORITES) {
            throw FavoriteValidationException("A maximum of $MAX_FAVORITES favorites is supported")
        }
        val ids = HashSet<String>()
        val contents = HashSet<String>()
        var totalBytes = 0
        return items.map { item ->
            val content = validate(item.content)
            if (item.id.isBlank() || !ids.add(item.id)) {
                throw FavoriteValidationException("Favorites contain duplicate or missing identifiers")
            }
            if (!contents.add(content)) {
                throw FavoriteValidationException("Favorite contents must be unique")
            }
            totalBytes += utf8Size(content)
            if (totalBytes > MAX_TOTAL_CONTENT_BYTES) {
                throw FavoriteValidationException("Favorite content exceeds the 10 MB total limit")
            }
            item.copy(content = content)
        }
    }

    fun preview(content: String): String {
        val firstLine = normalizeContent(content)
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?: "Empty favorite"
        return truncateCodePoints(firstLine, PREVIEW_CODE_POINTS)
    }

    fun normalizeContent(content: String): String =
        content.replace("\r\n", "\n").replace('\r', '\n')

    fun utf8Size(content: String): Int = content.toByteArray(StandardCharsets.UTF_8).size

    private fun truncateCodePoints(value: String, limit: Int): String {
        val count = value.codePointCount(0, value.length)
        if (count <= limit) return value
        return value.substring(0, value.offsetByCodePoints(0, limit - 1)).trimEnd() + "…"
    }

    private const val PREVIEW_CODE_POINTS = 80
}
