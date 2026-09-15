package com.tools.inputbridge.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbClientTest {
    @Test
    fun `parses online and unavailable devices`() {
        val output = """
            List of devices attached
            emulator-5554 device product:sdk_gphone model:Pixel_8 device:emu transport_id:1
            192.0.2.5:5555 offline transport_id:2
            ZX1G22 unauthorized usb:1-1

        """.trimIndent()

        val devices = AdbClient.parseDevices(output)

        assertEquals(3, devices.size)
        assertEquals("Pixel 8", devices[0].model)
        assertTrue(devices[0].online)
        assertFalse(devices[1].online)
        assertEquals("unauthorized", devices[2].state)
    }

    @Test
    fun `ignores daemon and malformed lines`() {
        val output = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            serial-only
            tablet device model:Demo_Tablet
        """.trimIndent()

        val devices = AdbClient.parseDevices(output)

        assertEquals(listOf("tablet"), devices.map { it.serial })
    }

    @Test
    fun `parses tracked device payloads without a header`() {
        val payload = "7d8663c                device product:TB323FU model:TB323FU device:TB323FU transport_id:3\n" +
            "95e42ea7               unauthorized usb:1-2 transport_id:2\n"

        val devices = AdbClient.parseTrackedDevices(payload)

        assertEquals(listOf("7d8663c", "95e42ea7"), devices.map { it.serial })
        assertEquals("TB323FU", devices[0].model)
        assertTrue(devices[0].online)
        assertEquals("unauthorized", devices[1].state)
    }

    @Test
    fun `treats an empty tracked payload as no devices`() {
        assertEquals(emptyList(), AdbClient.parseTrackedDevices(""))
    }
}
