package com.tools.inputbridge.ui

import com.tools.inputbridge.core.ConnectionState
import com.tools.inputbridge.core.DeviceInfo

/** Decides what the device selector shows and when a device list should start a connection. */
internal object DeviceChoice {
    /** Keeps the device the session targets selectable while ADB does not list it. */
    fun entries(devices: List<DeviceInfo>, targetSerial: String?, targetModel: String?): List<DeviceInfo> =
        if (targetSerial == null || devices.any { it.serial == targetSerial }) devices
        else devices + DeviceInfo(targetSerial, DeviceInfo.DETACHED_STATE, targetModel)

    fun selection(entries: List<DeviceInfo>, targetSerial: String?, selectedSerial: String?): DeviceInfo? =
        entries.firstOrNull { it.serial == targetSerial }
            ?: firstOnline(entries, selectedSerial)
            ?: entries.firstOrNull { it.serial == selectedSerial }
            ?: entries.firstOrNull()

    /**
     * Returns the device to connect for the current device list and connection, or null to leave it alone.
     *
     * An idle dialog connects to the first usable device, including one that only now came online.
     * When the session's device is no longer online, another online device takes over; the session
     * waits for its own device only when no other device is available. A ready session is left alone
     * until it notices the loss itself, so a stale device list cannot end a working connection.
     */
    fun autoConnectTarget(
        devices: List<DeviceInfo>,
        state: ConnectionState,
        targetSerial: String?,
        selectedSerial: String?,
        userDisconnected: Boolean,
    ): String? {
        if (userDisconnected) return null
        val candidate = firstOnline(devices, selectedSerial) ?: return null
        return when {
            state == ConnectionState.DISCONNECTED -> candidate.serial
            targetSerial != null && state != ConnectionState.READY &&
                devices.none { it.serial == targetSerial && it.online } -> candidate.serial
            else -> null
        }
    }

    private fun firstOnline(devices: List<DeviceInfo>, preferredSerial: String?): DeviceInfo? =
        devices.firstOrNull { it.serial == preferredSerial && it.online } ?: devices.firstOrNull(DeviceInfo::online)
}
