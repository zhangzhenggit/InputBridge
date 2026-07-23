package com.tools.inputbridge.adb

import java.io.File
import java.util.Properties

object AdbLocator {
    private val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)
    private val executableName = if (isWindows) "adb.exe" else "adb"

    fun locate(projectBasePath: String?): String {
        val candidates = buildList {
            listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
                System.getenv(key)?.takeIf(String::isNotBlank)?.let { add(File(it, "platform-tools/$executableName")) }
            }
            readSdkDirectory(projectBasePath)?.let { add(File(it, "platform-tools/$executableName")) }
            if (isWindows) {
                add(File(System.getProperty("user.home"), "AppData/Local/Android/Sdk/platform-tools/$executableName"))
            }
        }
        return candidates.firstOrNull { it.isFile }?.absolutePath ?: executableName
    }

    private fun readSdkDirectory(projectBasePath: String?): String? {
        val propertiesFile = projectBasePath?.let { File(it, "local.properties") } ?: return null
        if (!propertiesFile.isFile) return null
        return runCatching {
            Properties().apply { propertiesFile.inputStream().use(::load) }.getProperty("sdk.dir")
        }.getOrNull()?.takeIf(String::isNotBlank)
    }
}
