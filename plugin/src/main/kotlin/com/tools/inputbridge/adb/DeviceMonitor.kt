package com.tools.inputbridge.adb

import com.intellij.openapi.diagnostic.Logger
import com.tools.inputbridge.core.DeviceInfo
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Follows device attach, detach, and state changes through the host ADB server's
 * `host:track-devices` service.
 *
 * The server socket is used instead of the `adb track-devices` command because the Windows adb
 * client rewrites line endings on stdout, which breaks the length-prefixed payload framing.
 */
class DeviceMonitor(
    private val adb: AdbClient,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        /** Receives the complete device list after every change reported by ADB. */
        fun onDevices(devices: List<DeviceInfo>)

        /** Reports that device changes are no longer followed until tracking resumes. */
        fun onTrackingLost()
    }

    private val running = AtomicBoolean(true)
    private val thread = Thread(::run, "InputBridge-DeviceMonitor").apply { isDaemon = true }
    private var longFormat = true

    @Volatile private var socket: Socket? = null

    fun start() = thread.start()

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        thread.interrupt()
    }

    private fun run() {
        var failures = 0
        while (running.get()) {
            val tracked = try {
                track { failures = 0 }
                null
            } catch (error: ServiceRejectedException) {
                // Servers older than the long listing format only offer the plain service.
                if (longFormat) {
                    longFormat = false
                    continue
                }
                error
            } catch (error: Exception) {
                // Any failure must reach onTrackingLost, or a session waiting for a device would never retry.
                error
            }
            if (!running.get()) return
            failures++
            if (failures == 1) {
                LOG.info("ADB device tracking stopped: ${tracked?.message ?: "server closed the connection"}")
                listener.onTrackingLost()
            }
            // A refused connection usually means no ADB server; retry once first in case one is restarting.
            if (tracked is ConnectException && failures >= 2) adb.startAdbServer()
            try {
                Thread.sleep(RETRY_BASE_MILLIS shl min(failures - 1, 3))
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun track(onTracking: () -> Unit) {
        Socket().use { candidate ->
            socket = candidate
            if (!running.get()) return
            candidate.connect(InetSocketAddress(LOCALHOST, adb.serverPort), CONNECT_TIMEOUT_MILLIS)
            val input = DataInputStream(BufferedInputStream(candidate.getInputStream()))
            candidate.getOutputStream().apply {
                write(request(if (longFormat) "host:track-devices-l" else "host:track-devices"))
                flush()
            }
            readStatus(input)
            onTracking()
            while (running.get()) {
                val payload = readPayload(input) ?: return
                listener.onDevices(AdbClient.parseTrackedDevices(payload))
            }
        }
    }

    internal class ServiceRejectedException(message: String) : IOException(message)

    companion object {
        private val LOG = Logger.getInstance(DeviceMonitor::class.java)
        private val LOCALHOST: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
        private const val CONNECT_TIMEOUT_MILLIS = 2_000
        private const val RETRY_BASE_MILLIS = 1_000L

        internal fun request(service: String): ByteArray {
            val body = service.toByteArray(StandardCharsets.UTF_8)
            return "%04x".format(body.size).toByteArray(StandardCharsets.US_ASCII) + body
        }

        /** Consumes the `OKAY` reply, or raises the server's `FAIL` message. */
        internal fun readStatus(input: DataInputStream) {
            val status = String(ByteArray(4).also(input::readFully), StandardCharsets.US_ASCII)
            when (status) {
                "OKAY" -> Unit
                "FAIL" -> throw ServiceRejectedException(readPayload(input) ?: "ADB server rejected device tracking")
                else -> throw IOException("Unexpected ADB server reply")
            }
        }

        /** Reads one hex-length-prefixed payload, or returns null when the server closes the stream. */
        internal fun readPayload(input: DataInputStream): String? {
            val header = ByteArray(4)
            try {
                input.readFully(header)
            } catch (_: EOFException) {
                return null
            }
            val length = String(header, StandardCharsets.US_ASCII).toIntOrNull(16)
                ?: throw IOException("Invalid ADB payload length")
            return String(ByteArray(length).also(input::readFully), StandardCharsets.UTF_8)
        }
    }
}
