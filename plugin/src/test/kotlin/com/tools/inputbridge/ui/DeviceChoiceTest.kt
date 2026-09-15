package com.tools.inputbridge.ui

import com.tools.inputbridge.core.ConnectionState
import com.tools.inputbridge.core.DeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceChoiceTest {
    private val tablet = DeviceInfo("95e42ea7", "device", "TB522FU")
    private val phone = DeviceInfo("7d8663c", "device", "TB323FU")
    private val unauthorized = DeviceInfo("0a1b", "unauthorized", null)

    @Test
    fun `keeps an absent target selectable`() {
        val entries = DeviceChoice.entries(listOf(phone), tablet.serial, "TB522FU")

        assertEquals(listOf(phone, DeviceInfo(tablet.serial, DeviceInfo.DETACHED_STATE, "TB522FU")), entries)
        assertEquals(tablet.serial, DeviceChoice.selection(entries, tablet.serial, phone.serial)?.serial)
    }

    @Test
    fun `selects the first online device when an unavailable one is listed first`() {
        assertEquals(tablet, DeviceChoice.selection(listOf(unauthorized, tablet), null, null))
    }

    @Test
    fun `keeps the user's online selection`() {
        assertEquals(phone, DeviceChoice.selection(listOf(tablet, phone), null, phone.serial))
    }

    @Test
    fun `connects an idle dialog when a device comes online`() {
        assertEquals(
            tablet.serial,
            DeviceChoice.autoConnectTarget(listOf(unauthorized, tablet), ConnectionState.DISCONNECTED, null, null, false),
        )
    }

    @Test
    fun `does not reconnect after the user disconnects`() {
        assertNull(DeviceChoice.autoConnectTarget(listOf(tablet, phone), ConnectionState.DISCONNECTED, null, tablet.serial, true))
    }

    @Test
    fun `switches to another online device when the session's device is detached`() {
        assertEquals(
            phone.serial,
            DeviceChoice.autoConnectTarget(listOf(phone), ConnectionState.WAITING, tablet.serial, tablet.serial, false),
        )
    }

    @Test
    fun `switches away from a device that is listed but offline`() {
        val offlineTablet = tablet.copy(state = "offline")
        assertEquals(
            phone.serial,
            DeviceChoice.autoConnectTarget(listOf(offlineTablet, phone), ConnectionState.RECONNECTING, tablet.serial, null, false),
        )
    }

    @Test
    fun `waits for the session's device when no other device is online`() {
        assertNull(DeviceChoice.autoConnectTarget(listOf(unauthorized), ConnectionState.WAITING, tablet.serial, null, false))
    }

    @Test
    fun `keeps retrying an online device instead of switching`() {
        assertNull(DeviceChoice.autoConnectTarget(listOf(tablet, phone), ConnectionState.RECONNECTING, tablet.serial, null, false))
    }

    @Test
    fun `leaves a ready session alone until it reports the loss`() {
        assertNull(DeviceChoice.autoConnectTarget(listOf(phone), ConnectionState.READY, tablet.serial, tablet.serial, false))
    }
}
