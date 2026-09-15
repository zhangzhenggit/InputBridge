package com.tools.inputbridge.core

data class DeviceInfo(
    val serial: String,
    val state: String,
    val model: String?,
) {
    val online: Boolean
        get() = state == ONLINE_STATE

    override fun toString(): String {
        val name = if (!model.isNullOrBlank()) "$model ($serial)" else serial
        return if (online) name else "$name · $state"
    }

    companion object {
        const val ONLINE_STATE = "device"
        const val UNAUTHORIZED_STATE = "unauthorized"

        /** Stands in for a selected device that ADB no longer lists. */
        const val DETACHED_STATE = "disconnected"
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    READY,
    RECONNECTING,

    /** The selected device is not online; the next connection attempt waits for ADB to report it. */
    WAITING,
    SUSPENDED,
    ERROR,
}

data class ConnectionStatus(
    val state: ConnectionState,
    val serial: String? = null,
    val message: String = "Disconnected",
)

data class ClipboardUpdate(
    val serial: String,
    val sequence: Long,
    val text: String?,
    val runs: List<TextStyleRun> = emptyList(),
    val receivedAtMillis: Long = System.currentTimeMillis(),
)

data class InputResult(
    val requestId: Long,
    val success: Boolean,
    val message: String,
)
