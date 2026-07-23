package com.tools.inputbridge.session

import com.tools.inputbridge.adb.AdbClient
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the temporary device-server artifact for one logical device selection. */
class DeviceServerLease private constructor(
    private val adb: AdbClient,
    val serial: String,
    private val artifact: ServerArtifact,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private var uploaded = false

    val remotePath: String
        get() = artifact.remotePath

    fun prepare() {
        check(!closed.get()) { "Device server lease is closed" }
        adb.ensureOnline(serial)
        if (!uploaded || !adb.remoteFileExists(serial, remotePath)) {
            adb.push(serial, artifact.localFile, remotePath)
            uploaded = true
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        adb.removeRemoteServer(serial, remotePath)
        artifact.close()
    }

    companion object {
        fun create(adb: AdbClient, serial: String): DeviceServerLease =
            DeviceServerLease(adb, serial, ServerArtifact.extract())
    }
}
