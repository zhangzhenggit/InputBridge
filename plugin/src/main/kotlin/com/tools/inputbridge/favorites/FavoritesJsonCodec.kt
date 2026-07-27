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
                    ExportFavorite().apply {
                        title = it.title
                        content = it.content
                    }
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
        val decoded = mutableListOf<FavoriteItem>()
        val titles = mutableListOf<String>()
        exportedFavorites.filterNotNull().forEach { exported ->
            val content = FavoriteRules.validate(exported.content)
            val title = if (document.version >= TITLED_FORMAT_VERSION) {
                FavoriteRules.validateTitle(exported.title)
            } else {
                FavoriteRules.suggestedTitle(content, titles)
            }
            val uniqueTitle = FavoriteRules.uniqueTitle(title, titles)
            titles += uniqueTitle
            decoded += FavoriteRules.create(uniqueTitle, content, now)
        }
        return FavoriteRules.validateCollection(decoded)
    }

    fun merge(existing: List<FavoriteItem>, imported: List<FavoriteItem>): MergeResult {
        val merged = existing.toMutableList()
        val contents = existing.mapTo(HashSet(), FavoriteItem::content)
        val titles = existing.mapTo(mutableListOf(), FavoriteItem::title)
        var skipped = 0
        imported.forEach { item ->
            if (!contents.add(item.content)) {
                skipped++
            } else {
                val title = FavoriteRules.uniqueTitle(item.title, titles)
                titles += title
                merged += item.copy(id = UUID.randomUUID().toString(), title = title)
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
        var title: String = ""
        var content: String = ""
    }

    private val SUPPORTED_VERSIONS = setOf(1, 2, FORMAT_VERSION)
    private const val TITLED_FORMAT_VERSION = 3
    private const val FORMAT_VERSION = TITLED_FORMAT_VERSION
    private const val MAX_JSON_BYTES_PER_CONTENT_BYTE = 6
    private const val JSON_OVERHEAD_BYTES = 1024 * 1024L
}
