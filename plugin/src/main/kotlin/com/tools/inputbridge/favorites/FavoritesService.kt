package com.tools.inputbridge.favorites

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.UUID

internal data class FavoritesSnapshot(
    val revision: Long,
    val favorites: List<FavoriteItem>,
)

internal enum class FavoritesUpdateStatus {
    UPDATED,
    STALE,
}

@Service(Service.Level.APP)
@State(
    name = "com.tools.inputbridge.favorites",
    storages = [Storage(value = "InputBridgeFavorites.xml", roamingType = RoamingType.DISABLED)],
)
class FavoritesService : PersistentStateComponent<FavoritesService.StoredState> {
    private var storedState = StoredState()
    private var revision = 0L

    @Synchronized
    override fun getState(): StoredState = storedState.deepCopy()

    @Synchronized
    override fun loadState(state: StoredState) {
        storedState = sanitize(state)
        revision++
    }

    @Synchronized
    fun favorites(): List<FavoriteItem> = storedState.favorites.map(StoredFavorite::toFavorite)

    @Synchronized
    internal fun snapshot(): FavoritesSnapshot = FavoritesSnapshot(revision, favorites())

    @Synchronized
    internal fun evaluateAddition(content: String): FavoriteAddition =
        FavoriteRules.evaluateAddition(content, favorites())

    @Synchronized
    internal fun suggestedTitle(content: String): String =
        FavoriteRules.suggestedTitle(content, favorites().map(FavoriteItem::title))

    @Synchronized
    internal fun titleValidationError(title: String, excludingId: String? = null): String? {
        val normalized = try {
            FavoriteRules.validateTitle(title)
        } catch (error: FavoriteValidationException) {
            return error.message
        }
        val duplicate = favorites().any {
            it.id != excludingId && FavoriteRules.titleKey(it.title) == FavoriteRules.titleKey(normalized)
        }
        return if (duplicate) "A favorite with this title already exists" else null
    }

    @Synchronized
    internal fun tryAdd(title: String, content: String): FavoriteAddStatus {
        val current = favorites()
        val addition = FavoriteRules.evaluateAddition(content, current)
        if (addition.status != FavoriteAddStatus.READY) return addition.status
        val normalizedTitle = FavoriteRules.validateTitle(title)
        if (current.any { FavoriteRules.titleKey(it.title) == FavoriteRules.titleKey(normalizedTitle) }) {
            throw FavoriteValidationException("A favorite with this title already exists")
        }
        replaceAllInternal(
            current + FavoriteRules.create(normalizedTitle, requireNotNull(addition.content)),
        )
        return FavoriteAddStatus.READY
    }

    @Synchronized
    internal fun replaceAll(items: List<FavoriteItem>, expectedRevision: Long): FavoritesUpdateStatus {
        if (revision != expectedRevision) return FavoritesUpdateStatus.STALE
        replaceAllInternal(items)
        return FavoritesUpdateStatus.UPDATED
    }

    private fun replaceAllInternal(items: List<FavoriteItem>) {
        val currentById = favorites().associateBy(FavoriteItem::id)
        val now = System.currentTimeMillis()
        val normalized = FavoriteRules.validateCollection(items).map { item ->
            val previous = currentById[item.id]
            item.copy(
                createdAtMillis = previous?.createdAtMillis ?: item.createdAtMillis.takeIf { it > 0 } ?: now,
                updatedAtMillis = if (
                    previous == null ||
                    previous.title != item.title ||
                    previous.content != item.content
                ) {
                    now
                } else {
                    previous.updatedAtMillis
                },
            )
        }
        storedState = StoredState().apply {
            version = CURRENT_VERSION
            favorites = normalized.mapTo(mutableListOf(), StoredFavorite::fromFavorite)
        }
        revision++
    }

    private fun sanitize(state: StoredState): StoredState {
        val restored = mutableListOf<FavoriteItem>()
        val ids = HashSet<String>()
        val retainedTitles = mutableListOf<String>()
        val titleKeys = HashSet<String>()
        val contents = HashSet<String>()
        var totalBytes = 0
        val now = System.currentTimeMillis()
        state.favorites.take(FavoriteRules.MAX_FAVORITES).forEach { stored ->
            val content = runCatching { FavoriteRules.validate(stored.content) }.getOrNull() ?: return@forEach
            if (!contents.add(content)) return@forEach
            val candidateTotal = totalBytes + FavoriteRules.utf8Size(content)
            if (candidateTotal > FavoriteRules.MAX_TOTAL_CONTENT_BYTES) return@forEach
            totalBytes = candidateTotal
            val id = stored.id.takeIf { it.isNotBlank() && ids.add(it) }
                ?: UUID.randomUUID().toString().also(ids::add)
            val title = stored.title
                .takeIf { state.version >= TITLED_VERSION }
                ?.let { candidate -> runCatching { FavoriteRules.validateTitle(candidate) }.getOrNull() }
                ?.takeIf { FavoriteRules.titleKey(it) !in titleKeys }
                ?: FavoriteRules.suggestedTitle(content, retainedTitles)
            retainedTitles += title
            titleKeys += FavoriteRules.titleKey(title)
            restored += FavoriteItem(
                id = id,
                title = title,
                content = content,
                createdAtMillis = stored.createdAtMillis.takeIf { it > 0 } ?: now,
                updatedAtMillis = stored.updatedAtMillis.takeIf { it > 0 } ?: now,
            )
        }
        return StoredState().apply {
            version = CURRENT_VERSION
            favorites = restored.mapTo(mutableListOf(), StoredFavorite::fromFavorite)
        }
    }

    class StoredState {
        var version: Int = CURRENT_VERSION
        var favorites: MutableList<StoredFavorite> = mutableListOf()

        fun deepCopy(): StoredState = StoredState().also { copy ->
            copy.version = version
            copy.favorites = favorites.mapTo(mutableListOf(), StoredFavorite::copy)
        }
    }

    class StoredFavorite {
        var id: String = ""
        var title: String = ""
        var content: String = ""
        var createdAtMillis: Long = 0
        var updatedAtMillis: Long = 0

        fun copy(): StoredFavorite = StoredFavorite().also { copy ->
            copy.id = id
            copy.title = title
            copy.content = content
            copy.createdAtMillis = createdAtMillis
            copy.updatedAtMillis = updatedAtMillis
        }

        fun toFavorite(): FavoriteItem = FavoriteItem(id, title, content, createdAtMillis, updatedAtMillis)

        companion object {
            fun fromFavorite(favorite: FavoriteItem): StoredFavorite = StoredFavorite().also {
                it.id = favorite.id
                it.title = favorite.title
                it.content = favorite.content
                it.createdAtMillis = favorite.createdAtMillis
                it.updatedAtMillis = favorite.updatedAtMillis
            }
        }
    }

    private companion object {
        const val TITLED_VERSION = 3
        const val CURRENT_VERSION = TITLED_VERSION
    }
}
