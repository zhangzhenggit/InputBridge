package com.tools.inputbridge.adb

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class CommandResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    val success: Boolean
        get() = !timedOut && exitCode == 0

    val diagnostic: String
        get() = stderr.trim().ifBlank { stdout.trim() }
}

object ProcessRunner {
    fun run(command: List<String>, timeoutSeconds: Long = 15): CommandResult = runCatching {
        val process = ProcessBuilder(command).start()
        val stdout = StreamCollector(process.inputStream, "InputBridge-Stdout")
        val stderr = StreamCollector(process.errorStream, "InputBridge-Stderr")
        stdout.start()
        stderr.start()

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) process.destroyForcibly()
        stdout.join(2_000)
        stderr.join(2_000)
        CommandResult(
            exitCode = if (finished) process.exitValue() else null,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
        )
    }.getOrElse { error ->
        CommandResult(null, "", error.message ?: error.javaClass.simpleName, timedOut = false)
    }

    internal class StreamCollector(
        private val input: InputStream,
        threadName: String,
        private val limit: Int = 32 * 1024,
    ) {
        private val buffer = StringBuilder()
        private val thread = Thread({ collect() }, threadName).apply { isDaemon = true }

        fun start() = thread.start()

        fun join(timeoutMillis: Long) = thread.join(timeoutMillis)

        fun text(): String = synchronized(buffer) { buffer.toString() }

        private fun collect() {
            runCatching {
                input.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(buffer) {
                            if (buffer.length < limit) {
                                val remaining = limit - buffer.length
                                buffer.append(line.take(remaining)).append('\n')
                            }
                        }
                    }
                }
            }
        }
    }
}
