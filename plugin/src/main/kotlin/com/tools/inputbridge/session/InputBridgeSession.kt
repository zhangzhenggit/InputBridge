package com.tools.inputbridge.session

import com.tools.inputbridge.adb.AdbClient
import com.tools.inputbridge.adb.ProcessRunner
import com.tools.inputbridge.core.InputResult
import com.tools.inputbridge.core.TextStyleRun
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class InputBridgeSession(
    private val adb: AdbClient,
    val serial: String,
    private val serverLease: DeviceServerLease,
    private val callbacks: Callbacks,
) : AutoCloseable {
    interface Callbacks {
        fun onClipboard(sequence: Long, text: String?, runs: List<TextStyleRun>)
        fun onInputResult(result: InputResult)
        fun onDisconnected(message: String)
    }

    data class ServerInfo(val sdk: Int, val model: String)

    private data class Connection(
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
        val hello: InputBridgeProtocol.ServerMessage.Hello,
    )

    private val closed = AtomicBoolean()
    private val nextRequestId = AtomicLong()
    private val pendingRequests = ConcurrentHashMap.newKeySet<Long>()
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "InputBridge-$serial-Heartbeat").apply { isDaemon = true }
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var serverProcess: Process? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var forwardPort: Int? = null
    @Volatile private var lastPongMillis = 0L
    private var stdoutCollector: ProcessRunner.StreamCollector? = null
    private var stderrCollector: ProcessRunner.StreamCollector? = null

    fun connect(): ServerInfo {
        check(!closed.get()) { "Session is closed" }
        try {
            serverLease.prepare()

            val socketName = "inputbridge_%08x".format(Random.nextInt().ushr(1))
            forwardPort = adb.createForward(serial, socketName)
            val process = adb.startServer(serial, socketName, serverLease.remotePath)
            serverProcess = process
            stdoutCollector = ProcessRunner.StreamCollector(process.inputStream, "InputBridge-$serial-ServerOut").also { it.start() }
            stderrCollector = ProcessRunner.StreamCollector(process.errorStream, "InputBridge-$serial-ServerErr").also { it.start() }

            val connection = connectAndHandshake(forwardPort!!, process)
            socket = connection.socket
            output = connection.output
            val hello = connection.hello
            require(hello.version == InputBridgeProtocol.VERSION) {
                "Protocol mismatch: plugin=${InputBridgeProtocol.VERSION}, server=${hello.version}"
            }
            connection.socket.soTimeout = 0
            lastPongMillis = System.currentTimeMillis()
            startReader(connection.input)
            startHeartbeat()
            return ServerInfo(hello.sdk, hello.model)
        } catch (error: Throwable) {
            terminate(expected = true, message = error.message ?: "Unable to start the device session")
            throw error
        }
    }

    fun sendText(text: String, appendEnter: Boolean, replaceExisting: Boolean = false): Long {
        check(!closed.get()) { "Device session is not connected" }
        val sessionOutput = output ?: error("Device session is not ready")
        val requestId = nextRequestId.incrementAndGet()
        pendingRequests += requestId
        try {
            InputBridgeProtocol.writePaste(sessionOutput, requestId, text, appendEnter, replaceExisting)
            heartbeat.schedule({
                if (pendingRequests.remove(requestId) && !closed.get()) {
                    callbacks.onInputResult(InputResult(requestId, false, "Device acknowledgement timed out"))
                }
            }, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            pendingRequests -= requestId
            throw error
        }
        return requestId
    }

    fun requestClipboardSnapshot() {
        check(!closed.get()) { "Device session is not connected" }
        val sessionOutput = output ?: error("Device session is not ready")
        InputBridgeProtocol.writeGetClipboard(sessionOutput)
    }

    override fun close() {
        terminate(expected = true, message = "Disconnected")
    }

    private fun connectAndHandshake(port: Int, process: Process): Connection {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var lastError: Throwable? = null
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                val detail = stderrCollector?.text()?.trim().orEmpty()
                error(detail.ifBlank { "Device server exited before accepting the connection" })
            }
            var candidate: Socket? = null
            try {
                candidate = Socket().apply {
                    connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                    tcpNoDelay = true
                    soTimeout = 2_000
                }
                val input = DataInputStream(BufferedInputStream(candidate.getInputStream()))
                val sessionOutput = DataOutputStream(BufferedOutputStream(candidate.getOutputStream()))
                val hello = InputBridgeProtocol.read(input) as? InputBridgeProtocol.ServerMessage.Hello
                if (hello != null) return Connection(candidate, input, sessionOutput, hello)
                lastError = IllegalStateException("Device server closed before its handshake")
            } catch (error: Throwable) {
                lastError = error
            }
            runCatching { candidate?.close() }
            Thread.sleep(100)
        }
        val detail = stderrCollector?.text()?.trim().orEmpty()
        throw SocketTimeoutException(detail.ifBlank { lastError?.message ?: "Timed out connecting to the device server" })
    }

    private fun startReader(input: DataInputStream) {
        Thread({
            try {
                while (!closed.get()) {
                    when (val message = InputBridgeProtocol.read(input) ?: break) {
                        is InputBridgeProtocol.ServerMessage.Clipboard ->
                            callbacks.onClipboard(message.sequence, message.text, message.runs)
                        is InputBridgeProtocol.ServerMessage.Ack -> {
                            pendingRequests -= message.requestId
                            callbacks.onInputResult(InputResult(message.requestId, message.success, message.message))
                        }
                        is InputBridgeProtocol.ServerMessage.Error -> callbacks.onInputResult(InputResult(0, false, message.message))
                        is InputBridgeProtocol.ServerMessage.Pong -> lastPongMillis = System.currentTimeMillis()
                        is InputBridgeProtocol.ServerMessage.Hello -> error("Unexpected duplicate handshake")
                    }
                }
                if (!closed.get()) terminate(expected = false, message = "Device server connection closed")
            } catch (error: Throwable) {
                if (!closed.get()) {
                    terminate(expected = false, message = error.message ?: "Device server connection failed")
                }
            }
        }, "InputBridge-$serial-Reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun startHeartbeat() {
        heartbeat.scheduleAtFixedRate({
            if (closed.get()) return@scheduleAtFixedRate
            val now = System.currentTimeMillis()
            if (now - lastPongMillis > HEARTBEAT_TIMEOUT_MILLIS) {
                terminate(expected = false, message = "Device server heartbeat timed out")
                return@scheduleAtFixedRate
            }
            runCatching { output?.let { InputBridgeProtocol.writePing(it, now) } }
                .onFailure { terminate(expected = false, message = "Unable to reach the device server") }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private fun terminate(expected: Boolean, message: String) {
        if (!closed.compareAndSet(false, true)) return
        heartbeat.shutdownNow()
        runCatching { output?.let(InputBridgeProtocol::writeClose) }
        runCatching { socket?.close() }
        serverProcess?.let { process ->
            runCatching { process.outputStream.close() }
            if (process.isAlive) process.destroy()
            runCatching {
                if (!process.waitFor(1, TimeUnit.SECONDS) && process.isAlive) process.destroyForcibly()
            }
        }
        forwardPort?.let { adb.removeForward(serial, it) }

        if (!expected) {
            pendingRequests.forEach { requestId ->
                callbacks.onInputResult(InputResult(requestId, false, "Connection lost before the device acknowledged the input"))
            }
            callbacks.onDisconnected(message)
        }
        pendingRequests.clear()
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_SECONDS = 15L
        private const val HEARTBEAT_TIMEOUT_MILLIS = 45_000L
        private const val REQUEST_TIMEOUT_SECONDS = 10L
    }
}
