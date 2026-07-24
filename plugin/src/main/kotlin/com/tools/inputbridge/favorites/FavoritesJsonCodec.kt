package com.tools.inputbridge.favorites

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import java.util.UUID

internal object FavoritesJsonCodec {
    val MAX_IMPORT_FILE_BYTES: Long =
        FavoriteRules.MAX_TOTAL_CONTENT_BYTES.toLong() * MAX_JSON_BYTES_PER_CONTENT_BYTE + JSON_OVERHEAD_BYTES

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    fun encode(items: List<FavoriteItem>): String {
        val validated = FavoriteRules.validateCollection(items)
        return gson.toJson(
            ExportDocument().apply {
                version = FORMAT_VERSION
                favorites = validated.mapTo(mutableListOf()) {
                    ExportFavorite().apply { content = it.content }
                }
            },
        )
    }

    fun decode(json: String): List<FavoriteItem> {
        val document = try {
            gson.fromJson(json, ExportDocument::class.java)
        } catch (error: JsonParseException) {
            throw FavoriteValidationException("The selected file is not valid InputBridge favorites JSON")
        } ?: throw FavoriteValidationException("The selected file is empty")
        if (document.version !in SUPPORTED_VERSIONS) {
            throw FavoriteValidationException("Unsupported favorites file version: ${document.version}")
        }
        val now = System.currentTimeMillis()
        val exportedFavorites = document.favorites
            ?: throw FavoriteValidationException("The selected file does not contain a favorites list")
        return exportedFavorites.filterNotNull().map { FavoriteRules.create(it.content, now) }
    }

    fun merge(existing: List<FavoriteItem>, imported: List<FavoriteItem>): MergeResult {
        val merged = existing.toMutableList()
        val contents = existing.mapTo(HashSet(), FavoriteItem::content)
        var skipped = 0
        imported.forEach { item ->
            if (!contents.add(item.content)) {
                skipped++
            } else {
                merged += item.copy(id = UUID.randomUUID().toString())
            }
        }
        return MergeResult(FavoriteRules.validateCollection(merged), imported.size - skipped, skipped)
    }

    data class MergeResult(
        val favorites: List<FavoriteItem>,
        val added: Int,
        val skipped: Int,
    )

    private class ExportDocument {
        var version: Int = 0
        var favorites: MutableList<ExportFavorite?>? = null
    }

    private class ExportFavorite {
        var content: String = ""
    }

    private val SUPPORTED_VERSIONS = setOf(1, FORMAT_VERSION)
    private const val FORMAT_VERSION = 2
    private const val MAX_JSON_BYTES_PER_CONTENT_BYTE = 6
    private const val JSON_OVERHEAD_BYTES = 1024 * 1024L
}
