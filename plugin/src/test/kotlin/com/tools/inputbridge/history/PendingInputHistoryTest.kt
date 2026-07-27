package com.tools.inputbridge.history

import com.tools.inputbridge.core.InputResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingInputHistoryTest {
    @Test
    fun `returns text only for a successful acknowledgement`() {
        val pending = PendingInputHistory()
        pending.register(1, "successful")
        pending.register(2, "failed")

        assertEquals("successful", pending.complete(InputResult(1, true, "Delivered")))
        assertNull(pending.complete(InputResult(2, false, "Rejected")))
    }

    @Test
    fun `completion consumes a request and clear drops outstanding text`() {
        val pending = PendingInputHistory()
        pending.register(1, "once")

        assertEquals("once", pending.complete(InputResult(1, true, "Delivered")))
        assertNull(pending.complete(InputResult(1, true, "Delivered")))

        pending.register(2, "cleared")
        pending.clear()
        assertNull(pending.complete(InputResult(2, true, "Delivered")))
    }
}
