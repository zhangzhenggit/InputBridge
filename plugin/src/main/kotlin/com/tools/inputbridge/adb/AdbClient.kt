package com.tools.inputbridge.adb

import com.tools.inputbridge.core.DeviceInfo
import java.io.File

class AdbClient(private val adbPath: String) {
    fun listDevices(): List<DeviceInfo> {
        val result = ProcessRunner.run(listOf(adbPath, "devices", "-l"), timeoutSeconds = 10)
        if (!result.success) throw IllegalStateException(result.diagnostic.ifBlank { "Unable to list ADB devices" })
        return parseDevices(result.stdout)
    }

    fun ensureOnline(serial: String) {
        val result = ProcessRunner.run(listOf(adbPath, "-s", serial, "get-state"), timeoutSeconds = 5)
        if (!result.success || result.stdout.trim() != "device") {
            throw IllegalStateException(result.diagnostic.ifBlank { "Device is not online" })
        }
    }

    fun push(serial: String, localFile: File, remotePath: String) {
        val result = ProcessRunner.run(
            listOf(adbPath, "-s", serial, "push", localFile.absolutePath, remotePath),
            timeoutSeconds = 30,
        )
        if (!result.success) throw IllegalStateException(result.diagnostic.ifBlank { "Unable to upload the device server" })
    }

    fun createForward(serial: String, socketName: String): Int {
        val result = ProcessRunner.run(
            listOf(adbPath, "-s", serial, "forward", "tcp:0", "localabstract:$socketName"),
            timeoutSeconds = 10,
        )
        val port = result.stdout.lineSequence().map(String::trim).firstOrNull { it.all(Char::isDigit) }?.toIntOrNull()
        if (!result.success || port == null || port !in 1..65535) {
            throw IllegalStateException(result.diagnostic.ifBlank { "Unable to create the ADB tunnel" })
        }
        return port
    }

    fun removeForward(serial: String, port: Int) {
        ProcessRunner.run(listOf(adbPath, "-s", serial, "forward", "--remove", "tcp:$port"), timeoutSeconds = 5)
    }

    fun startServer(serial: String, socketName: String, remotePath: String): Process {
        val command = "CLASSPATH=$remotePath app_process / com.tools.inputbridge.server.Main $socketName"
        return ProcessBuilder(adbPath, "-s", serial, "shell", command).start()
    }

    fun removeRemoteServer(serial: String, remotePath: String) {
        ProcessRunner.run(listOf(adbPath, "-s", serial, "shell", "rm", "-f", remotePath), timeoutSeconds = 5)
    }

    fun remoteFileExists(serial: String, remotePath: String): Boolean =
        ProcessRunner.run(
            listOf(adbPath, "-s", serial, "shell", "test", "-f", remotePath),
            timeoutSeconds = 5,
        ).success

    companion object {
        internal fun parseDevices(output: String): List<DeviceInfo> {
            val lines = output.lineSequence().map(String::trim).toList()
            val headerIndex = lines.indexOfFirst { it.startsWith("List of devices attached") }
            if (headerIndex < 0) return emptyList()
            return lines.asSequence()
            .drop(headerIndex + 1)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val attributes = parts.drop(2).mapNotNull { token ->
                    token.substringBefore(':').takeIf { token.contains(':') }?.let { it to token.substringAfter(':') }
                }.toMap()
                DeviceInfo(serial = parts[0], state = parts[1], model = attributes["model"]?.replace('_', ' '))
            }
            .toList()
        }
    }
}
