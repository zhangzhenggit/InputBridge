package com.tools.inputbridge.history

import com.tools.inputbridge.core.InputResult

internal class PendingInputHistory {
    private val contentByRequestId = HashMap<Long, String>()

    fun register(requestId: Long, content: String) {
        require(requestId > 0) { "A pending input requires a positive request identifier" }
        contentByRequestId[requestId] = content
    }

    fun complete(result: InputResult): String? =
        contentByRequestId.remove(result.requestId)?.takeIf { result.success }

    fun clear() {
        contentByRequestId.clear()
    }
}
