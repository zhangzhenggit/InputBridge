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
        val first = FavoriteRules.create("First", "same")
        val duplicate = FavoriteRules.create("Second", "same")
        assertFailsWith<FavoriteValidationException> {
            FavoriteRules.validateCollection(listOf(first, duplicate))
        }
        assertFailsWith<FavoriteValidationException> {
            FavoriteRules.validateCollection(listOf(first, first.copy(title = "Second", content = "different")))
        }
    }

    @Test
    fun `titles are normalized and unique without regard to case`() {
        assertEquals("Device address", FavoriteRules.validateTitle(" Device \n address "))
        val first = FavoriteRules.create("Device", "first")
        val second = FavoriteRules.create("device", "second")

        assertFailsWith<FavoriteValidationException> {
            FavoriteRules.validateCollection(listOf(first, second))
        }
    }

    @Test
    fun `addition evaluation centralizes normalization and blocking reasons`() {
        val existing = listOf(FavoriteRules.create("Existing", "existing"))

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

    @Test
    fun `suggested titles are derived and disambiguated`() {
        assertEquals(
            "设备地址 (3)",
            FavoriteRules.suggestedTitle("\n设备地址\n正文", listOf("设备地址", "设备地址 (2)")),
        )
    }
}
