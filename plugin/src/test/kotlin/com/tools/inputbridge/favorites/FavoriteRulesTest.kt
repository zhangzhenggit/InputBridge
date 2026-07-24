package com.tools.inputbridge.favorites

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FavoriteRulesTest {
    @Test
    fun `uses the first non-empty line as the preview`() {
        assertEquals("设备测试内容", FavoriteRules.preview("\r\n  设备测试内容  \r\n第二行"))
    }

    @Test
    fun `normalizes line endings and rejects empty content`() {
        assertEquals("first\nsecond\nthird", FavoriteRules.validate("first\r\nsecond\rthird"))
        assertFailsWith<FavoriteValidationException> {
            FavoriteRules.validate(" \n ")
        }
    }

    @Test
    fun `truncates long previews with an ellipsis`() {
        val preview = FavoriteRules.preview("A".repeat(100))
        assertEquals(80, preview.codePointCount(0, preview.length))
        assertEquals("…", preview.takeLast(1))
    }

    @Test
    fun `collection requires unique identifiers and content`() {
        val first = FavoriteRules.create("same")
        val duplicate = FavoriteRules.create("same")
        assertFailsWith<FavoriteValidationException> {
            FavoriteRules.validateCollection(listOf(first, duplicate))
        }
        assertFailsWith<FavoriteValidationException> {
            FavoriteRules.validateCollection(listOf(first, first.copy(content = "different")))
        }
    }

    @Test
    fun `addition evaluation centralizes normalization and blocking reasons`() {
        val existing = listOf(FavoriteRules.create("existing"))

        val ready = FavoriteRules.evaluateAddition("first\r\nsecond", existing)

        assertEquals(FavoriteAddStatus.READY, ready.status)
        assertEquals("first\nsecond", ready.content)
        assertEquals(FavoriteAddStatus.EMPTY, FavoriteRules.evaluateAddition(" \n ", existing).status)
        assertEquals(FavoriteAddStatus.DUPLICATE, FavoriteRules.evaluateAddition("existing", existing).status)
        assertEquals(
            FavoriteAddStatus.ITEM_TOO_LARGE,
            FavoriteRules.evaluateAddition("x".repeat(FavoriteRules.MAX_CONTENT_BYTES + 1), existing).status,
        )
    }
}
