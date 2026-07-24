package com.tools.inputbridge.favorites

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoritesJsonCodecTest {
    @Test
    fun `round trips unicode content in version 2 format`() {
        val original = listOf(
            FavoriteRules.create("第一行\nSecond line"),
            FavoriteRules.create("Text 😀"),
        )

        val encoded = FavoritesJsonCodec.encode(original)
        val decoded = FavoritesJsonCodec.decode(encoded)

        assertTrue(encoded.contains("\"version\": 2"))
        assertFalse(encoded.contains("\"name\""))
        assertEquals(original.map(FavoriteItem::content), decoded.map(FavoriteItem::content))
    }

    @Test
    fun `imports version 1 files without retaining names`() {
        val decoded = FavoritesJsonCodec.decode(
            """{"version":1,"favorites":[{"name":"Legacy title","content":"Legacy content"}]}""",
        )

        assertEquals(listOf("Legacy content"), decoded.map(FavoriteItem::content))
    }

    @Test
    fun `merge skips duplicate content`() {
        val existing = listOf(FavoriteRules.create("existing"))
        val imported = listOf(
            FavoriteRules.create("new"),
            FavoriteRules.create("existing"),
        )

        val merged = FavoritesJsonCodec.merge(existing, imported)

        assertEquals(1, merged.added)
        assertEquals(1, merged.skipped)
        assertEquals(listOf("existing", "new"), merged.favorites.map(FavoriteItem::content))
    }

    @Test
    fun `rejects unsupported or incomplete files`() {
        assertFailsWith<FavoriteValidationException> {
            FavoritesJsonCodec.decode("""{"version":3,"favorites":[]}""")
        }
        assertFailsWith<FavoriteValidationException> {
            FavoritesJsonCodec.decode("""{"version":2}""")
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
