package com.tools.inputbridge.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.tools.inputbridge.adb.AdbClient
import com.tools.inputbridge.adb.AdbLocator
import com.tools.inputbridge.core.ClipboardUpdate
import com.tools.inputbridge.core.ConnectionState
import com.tools.inputbridge.core.ConnectionStatus
import com.tools.inputbridge.core.DeviceInfo
import com.tools.inputbridge.core.InputResult
import com.tools.inputbridge.history.InputHistoryService
import com.tools.inputbridge.history.PendingInputHistory
import com.tools.inputbridge.session.DeviceServerLease
import com.tools.inputbridge.session.InputBridgeProtocol
import com.tools.inputbridge.session.InputBridgeSession
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

@Service(Service.Level.PROJECT)
class InputBridgeProjectService(private val project: Project) : Disposable {
    interface Listener {
        fun onConnectionChanged(status: ConnectionStatus) {}
        fun onClipboardChanged(update: ClipboardUpdate) {}
        fun onClipboardCleared(serial: String?) {}
        fun onInputResult(result: InputResult) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val latestClipboardBySerial = ConcurrentHashMap<String, ClipboardUpdate>()
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "InputBridge-Project-${project.name}").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private val generation = AtomicInteger()
    private val runtimeGeneration = AtomicInteger()
    private val deviceRefreshGeneration = AtomicInteger()
    private val adb = AdbClient(AdbLocator.locate(project.basePath))
    private val historyService = ApplicationManager.getApplication().service<InputHistoryService>()
    private val pendingInputHistory = PendingInputHistory()

    @Volatile private var desiredSerial: String? = null
    @Volatile private var connectionStatus = ConnectionStatus(ConnectionState.DISCONNECTED)
    @Volatile private var windowVisible = false

    // Access to runtime resources below is confined to executor.
    private var serverLease: DeviceServerLease? = null
    private var session: InputBridgeSession? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var suspendFuture: ScheduledFuture<*>? = null
    private var reconnectAttempts = 0

    fun addListener(listener: Listener, parentDisposable: Disposable) {
        listeners += listener
        Disposer.register(parentDisposable) { listeners -= listener }
        dispatch {
            listener.onConnectionChanged(connectionStatus)
            val serial = connectionStatus.serial
            val cached = serial?.let(latestClipboardBySerial::get)
            if (cached != null) listener.onClipboardChanged(cached) else listener.onClipboardCleared(serial)
        }
    }

    fun setWindowVisible(visible: Boolean) {
        if (windowVisible == visible) return
        windowVisible = visible
        executeSafely {
            suspendFuture?.cancel(false)
            suspendFuture = null
            if (visible) {
                desiredSerial?.let { resumeRuntime(it, generation.get()) }
            } else {
                suspendFuture = executor.schedule({
                    if (!windowVisible) suspendRuntime()
                }, HIDDEN_GRACE_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    fun refreshDevices(onComplete: (Result<List<DeviceInfo>>) -> Unit) {
        val requestGeneration = deviceRefreshGeneration.incrementAndGet()
        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { adb.listDevices() }
            result.onSuccess {
                LOG.info("ADB device discovery completed: ${it.size} device(s)")
            }.onFailure {
                LOG.warn("ADB device discovery failed", it)
            }
            dispatch {
                if (deviceRefreshGeneration.get() == requestGeneration) {
                    onComplete(result)
                }
            }
        }
    }

    fun connect(serial: String) {
        if (desiredSerial == serial) {
            val requestGeneration = generation.get()
            executeSafely { resumeRuntime(serial, requestGeneration) }
            return
        }

        desiredSerial = serial
        val requestGeneration = generation.incrementAndGet()
        dispatchClipboard(serial)
        executeSafely { selectDevice(serial, requestGeneration) }
    }

    fun disconnect() {
        desiredSerial = null
        generation.incrementAndGet()
        executeSafely {
            cancelScheduledWork()
            closeRuntime()
            releaseServerLease()
            updateStatus(ConnectionState.DISCONNECTED, message = "Disconnected")
        }
    }

    fun sendText(text: String, appendEnter: Boolean, replaceExisting: Boolean = false) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        when {
            bytes.isEmpty() -> notifyInputResult(InputResult(0, false, "Input text is empty"))
            bytes.size > InputBridgeProtocol.MAX_INPUT_BYTES ->
                notifyInputResult(InputResult(0, false, "Input text exceeds 256 KB"))
            else -> executeSafely {
                runCatching {
                    val requestId = session?.sendText(text, appendEnter, replaceExisting)
                        ?: error("Device session is not ready")
                    pendingInputHistory.register(requestId, text)
                }.onFailure { notifyInputResult(InputResult(0, false, it.message ?: "Unable to send text")) }
            }
        }
    }

    fun requestClipboardSnapshot() {
        executeSafely {
            if (connectionStatus.state == ConnectionState.READY) {
                runCatching { session?.requestClipboardSnapshot() }
            }
        }
    }

    fun currentStatus(): ConnectionStatus = connectionStatus

    override fun dispose() {
        windowVisible = false
        desiredSerial = null
        generation.incrementAndGet()
        deviceRefreshGeneration.incrementAndGet()
        executeSafely {
            cancelScheduledWork()
            closeRuntime()
            releaseServerLease()
        }
        executor.shutdown()
    }

    private fun selectDevice(serial: String, requestGeneration: Int) {
        if (!isCurrent(serial, requestGeneration)) return
        cancelScheduledWork()
        closeRuntime()
        releaseServerLease()
        reconnectAttempts = 0

        runCatching { serverLease = DeviceServerLease.create(adb, serial) }
            .onFailure { error ->
                updateStatus(ConnectionState.ERROR, serial, error.message ?: "Unable to prepare the device server")
                return
            }

        if (windowVisible) {
            connectInternal(serial, requestGeneration, reconnecting = false)
        } else {
            updateSuspendedStatus(serial)
        }
    }

    private fun resumeRuntime(serial: String, requestGeneration: Int) {
        if (!windowVisible || !isCurrent(serial, requestGeneration)) return
        suspendFuture?.cancel(false)
        suspendFuture = null
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        if (session != null && connectionStatus.state == ConnectionState.READY) return
        connectInternal(serial, requestGeneration, reconnecting = connectionStatus.state != ConnectionState.DISCONNECTED)
    }

    private fun connectInternal(serial: String, requestGeneration: Int, reconnecting: Boolean) {
        if (!windowVisible || !isCurrent(serial, requestGeneration)) return
        reconnectFuture = null
        closeRuntime()
        updateStatus(
            if (reconnecting) ConnectionState.RECONNECTING else ConnectionState.CONNECTING,
            serial,
            if (reconnecting) "Reconnecting to $serial…" else "Connecting to $serial…",
        )

        val lease = serverLease?.takeIf { it.serial == serial } ?: runCatching {
            DeviceServerLease.create(adb, serial).also { serverLease = it }
        }.getOrElse { error ->
            handleConnectFailure(serial, requestGeneration, error)
            return
        }
        val runtimeToken = runtimeGeneration.incrementAndGet()
        val candidate = InputBridgeSession(
            adb,
            serial,
            lease,
            sessionCallbacks(serial, requestGeneration, runtimeToken),
        )
        session = candidate
        runCatching { candidate.connect() }
            .onSuccess { info ->
                if (!isCurrent(serial, requestGeneration)) {
                    candidate.close()
                    if (session === candidate) session = null
                    return@onSuccess
                }
                reconnectAttempts = 0
                updateStatus(ConnectionState.READY, serial, "Connected to ${info.model} · Android API ${info.sdk}")
            }
            .onFailure { error ->
                if (session === candidate) session = null
                handleConnectFailure(serial, requestGeneration, error)
            }
    }

    private fun sessionCallbacks(
        serial: String,
        requestGeneration: Int,
        requestRuntimeGeneration: Int,
    ) = object : InputBridgeSession.Callbacks {
        override fun onClipboard(sequence: Long, text: String?) {
            if (!isActiveRuntime(serial, requestGeneration, requestRuntimeGeneration)) return
            val update = ClipboardUpdate(serial, sequence, text)
            latestClipboardBySerial[serial] = update
            dispatch { listeners.forEach { it.onClipboardChanged(update) } }
        }

        override fun onInputResult(result: InputResult) {
            executeSafely {
                if (!isActiveRuntime(serial, requestGeneration, requestRuntimeGeneration)) return@executeSafely
                pendingInputHistory.complete(result)?.let(historyService::recordSuccessfulInput)
                notifyInputResult(result)
            }
        }

        override fun onDisconnected(message: String) {
            executeSafely {
                if (!isActiveRuntime(serial, requestGeneration, requestRuntimeGeneration)) return@executeSafely
                session = null
                if (windowVisible) scheduleReconnect(serial, requestGeneration, message)
                else updateSuspendedStatus(serial)
            }
        }
    }

    private fun handleConnectFailure(serial: String, requestGeneration: Int, error: Throwable) {
        if (!isCurrent(serial, requestGeneration)) return
        if (!windowVisible) {
            updateSuspendedStatus(serial)
            return
        }
        val message = error.message ?: "Unable to connect"
        updateStatus(ConnectionState.ERROR, serial, message)
        scheduleReconnect(serial, requestGeneration, message)
    }

    private fun scheduleReconnect(serial: String, requestGeneration: Int, reason: String) {
        if (!windowVisible || !isCurrent(serial, requestGeneration)) return
        reconnectFuture?.cancel(false)
        reconnectAttempts++
        val delaySeconds = min(30L, 1L shl min(reconnectAttempts, 5))
        updateStatus(ConnectionState.RECONNECTING, serial, "$reason · retrying in ${delaySeconds}s")
        reconnectFuture = executor.schedule({
            if (windowVisible && isCurrent(serial, requestGeneration)) {
                connectInternal(serial, requestGeneration, reconnecting = true)
            }
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun suspendRuntime() {
        suspendFuture = null
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        closeRuntime()
        desiredSerial?.let(::updateSuspendedStatus)
    }

    private fun closeRuntime() {
        val current = session
        session = null
        pendingInputHistory.clear()
        if (current != null) runtimeGeneration.incrementAndGet()
        current?.close()
    }

    private fun releaseServerLease() {
        val current = serverLease
        serverLease = null
        current?.close()
    }

    private fun cancelScheduledWork() {
        suspendFuture?.cancel(false)
        suspendFuture = null
        reconnectFuture?.cancel(false)
        reconnectFuture = null
    }

    private fun updateSuspendedStatus(serial: String) {
        updateStatus(ConnectionState.SUSPENDED, serial, "Paused while InputBridge is hidden")
    }

    private fun dispatchClipboard(serial: String) {
        dispatch {
            val cached = latestClipboardBySerial[serial]
            if (cached != null) listeners.forEach { it.onClipboardChanged(cached) }
            else listeners.forEach { it.onClipboardCleared(serial) }
        }
    }

    private fun isCurrent(serial: String, requestGeneration: Int): Boolean =
        !project.isDisposed && desiredSerial == serial && generation.get() == requestGeneration

    private fun isActiveRuntime(
        serial: String,
        requestGeneration: Int,
        requestRuntimeGeneration: Int,
    ): Boolean = isCurrent(serial, requestGeneration) && runtimeGeneration.get() == requestRuntimeGeneration

    private fun updateStatus(state: ConnectionState, serial: String? = null, message: String) {
        val status = ConnectionStatus(state, serial, message)
        connectionStatus = status
        dispatch { listeners.forEach { it.onConnectionChanged(status) } }
    }

    private fun notifyInputResult(result: InputResult) {
        dispatch { listeners.forEach { it.onInputResult(result) } }
    }

    private fun executeSafely(action: () -> Unit) {
        try {
            executor.execute(action)
        } catch (_: RejectedExecutionException) {
            // The project service is already disposing.
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) action()
        }
    }

    private companion object {
        val LOG = Logger.getInstance(InputBridgeProjectService::class.java)
        const val HIDDEN_GRACE_SECONDS = 30L
    }
}
