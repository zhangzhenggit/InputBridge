package com.tools.inputbridge.favorites

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FavoritesJsonCodecTest {
    @Test
    fun `round trips unicode titles and content in version 3 format`() {
        val original = listOf(
            FavoriteRules.create("中文标题", "第一行\nSecond line"),
            FavoriteRules.create("Emoji", "Text 😀"),
        )

        val encoded = FavoritesJsonCodec.encode(original)
        val decoded = FavoritesJsonCodec.decode(encoded)

        assertTrue(encoded.contains("\"version\": 3"))
        assertTrue(encoded.contains("\"title\": \"中文标题\""))
        assertEquals(original.map(FavoriteItem::title), decoded.map(FavoriteItem::title))
        assertEquals(original.map(FavoriteItem::content), decoded.map(FavoriteItem::content))
    }

    @Test
    fun `imports legacy files with generated titles`() {
        val decoded = FavoritesJsonCodec.decode(
            """{"version":1,"favorites":[{"name":"Legacy title","content":"Legacy content"}]}""",
        )
        val version2 = FavoritesJsonCodec.decode(
            """{"version":2,"favorites":[{"content":"Second legacy"}]}""",
        )

        assertEquals(listOf("Legacy content"), decoded.map(FavoriteItem::title))
        assertEquals(listOf("Legacy content"), decoded.map(FavoriteItem::content))
        assertEquals(listOf("Second legacy"), version2.map(FavoriteItem::title))
    }

    @Test
    fun `merge skips duplicate content and disambiguates titles`() {
        val existing = listOf(FavoriteRules.create("Shared", "existing"))
        val imported = listOf(
            FavoriteRules.create("Shared", "new"),
            FavoriteRules.create("Duplicate", "existing"),
        )

        val merged = FavoritesJsonCodec.merge(existing, imported)

        assertEquals(1, merged.added)
        assertEquals(1, merged.skipped)
        assertEquals(listOf("Shared", "Shared (2)"), merged.favorites.map(FavoriteItem::title))
        assertEquals(listOf("existing", "new"), merged.favorites.map(FavoriteItem::content))
    }

    @Test
    fun `rejects unsupported or incomplete files`() {
        assertFailsWith<FavoriteValidationException> {
            FavoritesJsonCodec.decode("""{"version":4,"favorites":[]}""")
        }
        assertFailsWith<FavoriteValidationException> {
            FavoritesJsonCodec.decode("""{"version":3}""")
        }
    }

    @Test
    fun `import limit accommodates worst case JSON escaping`() {
        assertTrue(
            FavoritesJsonCodec.MAX_IMPORT_FILE_BYTES >
                FavoriteRules.MAX_TOTAL_CONTENT_BYTES.toLong() * 6,
        )
    }
}
