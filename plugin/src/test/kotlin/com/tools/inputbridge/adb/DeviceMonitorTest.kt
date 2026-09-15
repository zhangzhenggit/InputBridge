package com.tools.inputbridge.adb

import com.tools.inputbridge.core.DeviceInfo
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeviceMonitorTest {
    @Test
    fun `frames requests with a hex length`() {
        assertEquals("0014host:track-devices-l", String(DeviceMonitor.request("host:track-devices-l"), StandardCharsets.US_ASCII))
    }

    @Test
    fun `reads length prefixed payloads until the stream ends`() {
        val input = stream("0005hello0000")

        assertEquals("hello", DeviceMonitor.readPayload(input))
        assertEquals("", DeviceMonitor.readPayload(input))
        assertNull(DeviceMonitor.readPayload(input))
    }

    @Test
    fun `reports a rejected tracking request`() {
        val error = assertFailsWith<DeviceMonitor.ServiceRejectedException> {
            DeviceMonitor.readStatus(stream("FAIL000cunknown host"))
        }
        assertEquals("unknown host", error.message)
    }

    @Test
    fun `delivers device lists and tracking loss from the adb server`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val requests = LinkedBlockingQueue<String>()
            thread(isDaemon = true) {
                server.accept().use { client ->
                    val input = DataInputStream(client.getInputStream())
                    requests.add(DeviceMonitor.readPayload(input).orEmpty())
                    val output = client.getOutputStream()
                    output.write("OKAY".toByteArray(StandardCharsets.US_ASCII))
                    output.write(payload("tablet device model:Demo_Tablet\n"))
                    output.write(payload(""))
                    output.flush()
                }
            }
            val events = LinkedBlockingQueue<Any>()
            val monitor = DeviceMonitor(AdbClient("adb-unavailable", server.localPort), object : DeviceMonitor.Listener {
                override fun onDevices(devices: List<DeviceInfo>) {
                    events.add(devices)
                }

                override fun onTrackingLost() {
                    events.add(LOST)
                }
            })

            monitor.use {
                it.start()
                assertEquals("host:track-devices-l", requests.poll(5, TimeUnit.SECONDS))
                assertEquals(listOf(DeviceInfo("tablet", "device", "Demo Tablet")), events.poll(5, TimeUnit.SECONDS))
                assertEquals(emptyList<DeviceInfo>(), events.poll(5, TimeUnit.SECONDS))
                assertEquals(LOST, events.poll(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun stream(text: String) = DataInputStream(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))

    private fun payload(body: String): ByteArray = DeviceMonitor.request(body)

    private companion object {
        const val LOST = "tracking lost"
    }
}
