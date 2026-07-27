package com.tools.inputbridge.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputHistoryServiceTest {
    @Test
    fun `records successful input newest first and promotes duplicates`() {
        val service = InputHistoryService()

        assertTrue(service.recordSuccessfulInput("first", 10))
        assertTrue(service.recordSuccessfulInput("second", 20))
        assertTrue(service.recordSuccessfulInput("first", 30))

        assertEquals(listOf("first", "second"), service.history().map(InputHistoryItem::content))
        assertEquals(30L, service.history().first().lastUsedAtMillis)
    }

    @Test
    fun `disabled history does not record input`() {
        val service = InputHistoryService()
        service.updatePreferences(enabled = false, maxEntries = 50)

        assertFalse(service.recordSuccessfulInput("private text"))
        assertTrue(service.history().isEmpty())
    }

    @Test
    fun `changing the entry limit removes the oldest records`() {
        val service = InputHistoryService()
        repeat(25) { index ->
            service.recordSuccessfulInput("entry-$index", index.toLong())
        }

        service.updatePreferences(enabled = true, maxEntries = 20)

        assertEquals(20, service.history().size)
        assertEquals("entry-24", service.history().first().content)
        assertEquals("entry-5", service.history().last().content)
    }

    @Test
    fun `state loading normalizes content and removes duplicates`() {
        val state = InputHistoryService.StoredState().apply {
            maxEntries = 50
            entries += stored("same\r\ntext", 20)
            entries += stored("same\ntext", 10)
            entries += stored(" ", 5)
        }
        val service = InputHistoryService()

        service.loadState(state)

        assertEquals(listOf("same\ntext"), service.history().map(InputHistoryItem::content))
    }

    @Test
    fun `clear removes entries without changing preferences`() {
        val service = InputHistoryService()
        service.updatePreferences(enabled = true, maxEntries = 100)
        service.recordSuccessfulInput("text")

        service.clear()

        assertTrue(service.history().isEmpty())
        assertEquals(InputHistoryPreferences(true, 100), service.preferences())
    }

    private fun stored(content: String, timestamp: Long): InputHistoryService.StoredHistoryItem =
        InputHistoryService.StoredHistoryItem().apply {
            this.content = content
            lastUsedAtMillis = timestamp
        }
}
