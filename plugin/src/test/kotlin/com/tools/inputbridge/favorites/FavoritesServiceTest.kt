package com.tools.inputbridge.favorites

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FavoritesServiceTest {
    @Test
    fun `persists content through a copied state`() {
        val service = FavoritesService()
        assertEquals(FavoriteAddStatus.READY, service.tryAdd("你好\nHello"))

        val restored = FavoritesService()
        restored.loadState(service.state)

        assertEquals(listOf("你好\nHello"), restored.favorites().map(FavoriteItem::content))
        assertEquals(2, restored.state.version)
    }

    @Test
    fun `migrates version 1 state and discards legacy names`() {
        val legacyState = FavoritesService.StoredState().apply {
            version = 1
            favorites += FavoritesService.StoredFavorite().apply {
                id = "legacy-id"
                content = "Legacy content"
                createdAtMillis = 10
                updatedAtMillis = 20
            }
        }

        val serialized = XmlSerializer.serialize(legacyState)
        val serializedFavorite = serialized.findDescendant("StoredFavorite")
            ?: error("Serialized state does not contain a favorite")
        serializedFavorite.addContent(Element("name").setAttribute("name", "name").setAttribute("value", "Legacy title"))
        val restoredState = XmlSerializer.deserialize(serialized, FavoritesService.StoredState::class.java)

        val service = FavoritesService()
        service.loadState(restoredState)

        assertEquals(listOf("Legacy content"), service.favorites().map(FavoriteItem::content))
        assertEquals(2, service.state.version)
    }

    @Test
    fun `quick add ignores duplicate content`() {
        val service = FavoritesService()

        assertEquals(FavoriteAddStatus.READY, service.tryAdd("same text"))
        assertEquals(FavoriteAddStatus.DUPLICATE, service.tryAdd("same text"))
        assertEquals(1, service.favorites().size)
    }

    @Test
    fun `stale manager snapshot cannot overwrite newer favorites`() {
        val service = FavoritesService()
        val snapshot = service.snapshot()
        assertEquals(FavoriteAddStatus.READY, service.tryAdd("added elsewhere"))

        val result = service.replaceAll(snapshot.favorites, snapshot.revision)

        assertEquals(FavoritesUpdateStatus.STALE, result)
        assertEquals(listOf("added elsewhere"), service.favorites().map(FavoriteItem::content))
    }

    @Test
    fun `sanitizing skips an overflowing item but keeps later content that fits`() {
        val state = FavoritesService.StoredState().apply {
            repeat(39) { index ->
                favorites += storedFavorite("full-$index", sizedContent("$index:", FavoriteRules.MAX_CONTENT_BYTES))
            }
            favorites += storedFavorite("partial", sizedContent("partial:", FavoriteRules.MAX_CONTENT_BYTES / 2))
            favorites += storedFavorite("overflow", sizedContent("overflow:", FavoriteRules.MAX_CONTENT_BYTES))
            favorites += storedFavorite("tail", "tail")
        }

        val service = FavoritesService()
        service.loadState(state)

        val restored = service.favorites()
        assertEquals(41, restored.size)
        assertTrue(restored.any { it.id == "tail" })
        assertTrue(restored.none { it.id == "overflow" })
    }

    private fun storedFavorite(id: String, content: String): FavoritesService.StoredFavorite =
        FavoritesService.StoredFavorite().apply {
            this.id = id
            this.content = content
        }

    private fun sizedContent(prefix: String, bytes: Int): String =
        prefix + "x".repeat(bytes - prefix.length)

    private fun Element.findDescendant(elementName: String): Element? {
        if (name == elementName) return this
        return children.firstNotNullOfOrNull { it.findDescendant(elementName) }
    }
}
