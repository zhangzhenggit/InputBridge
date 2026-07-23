package com.tools.inputbridge.session

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

class ServerArtifact private constructor(
    val localFile: File,
    val remotePath: String,
) : AutoCloseable {
    override fun close() {
        runCatching { Files.deleteIfExists(localFile.toPath()) }
    }

    companion object {
        private const val RESOURCE_PATH = "/device/inputbridge-server.jar"

        fun extract(): ServerArtifact {
            val stream = ServerArtifact::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: error("Bundled InputBridge device server is missing")
            val file = Files.createTempFile("inputbridge-server-", ".jar").toFile()
            stream.use { input -> file.outputStream().use(input::copyTo) }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            val instance = UUID.randomUUID().toString().replace("-", "").take(8)
            return ServerArtifact(file, "/data/local/tmp/inputbridge-server-${digest.take(12)}-$instance.jar")
        }
    }
}
