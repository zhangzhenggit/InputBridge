package com.tools.inputbridge.core

data class DeviceInfo(
    val serial: String,
    val state: String,
    val model: String?,
) {
    val online: Boolean
        get() = state == "device"

    override fun toString(): String = when {
        !model.isNullOrBlank() -> "$model ($serial)"
        else -> serial
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    READY,
    RECONNECTING,
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
    val receivedAtMillis: Long = System.currentTimeMillis(),
)

data class InputResult(
    val requestId: Long,
    val success: Boolean,
    val message: String,
)
