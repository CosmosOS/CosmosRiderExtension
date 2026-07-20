package com.cosmosos.rider.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Path

object PlatformUtil {

    val dotnetToolsDir: String
        get() = Path.of(System.getProperty("user.home"), ".dotnet", "tools").toString()

    val cosmosExecutable: String
        get() = if (SystemInfo.isWindows) "cosmos.exe" else "cosmos"

    val cosmosToolsPath: String?
        get() {
            val toolsDir = File(dotnetToolsDir)
            val names = if (SystemInfo.isWindows) listOf("cosmos.exe", "cosmos") else listOf("cosmos")
            for (name in names) {
                val file = File(toolsDir, name)
                if (file.exists()) return file.absolutePath
            }
            return null
        }

    val isCosmosToolsInstalled: Boolean
        get() = cosmosToolsPath != null

    val qemuDisplay: String
        get() = when {
            SystemInfo.isWindows -> "sdl"
            SystemInfo.isMac -> "cocoa"
            else -> "gtk"
        }

    val platformName: String
        get() = when {
            SystemInfo.isWindows -> "Windows"
            SystemInfo.isMac -> "macOS"
            else -> "Linux"
        }

    fun getEnvWithDotnetTools(): Map<String, String> {
        val env = System.getenv().toMutableMap()
        val separator = if (SystemInfo.isWindows) ";" else ":"
        val currentPath = env["PATH"] ?: ""
        env["PATH"] = "$dotnetToolsDir$separator$currentPath"
        return env
    }

    fun createCommandLine(executable: String, vararg args: String): GeneralCommandLine {
        return GeneralCommandLine(executable, *args).apply {
            environment.putAll(getEnvWithDotnetTools())
        }
    }

    fun findCommand(name: String): String? {
        return try {
            val cmd = if (SystemInfo.isWindows) "where $name" else "which $name"
            val process = ProcessBuilder(if (SystemInfo.isWindows) listOf("cmd.exe", "/c", cmd) else listOf("/bin/sh", "-c", cmd))
                .apply { environment().putAll(getEnvWithDotnetTools()) }
                .start()
            val output = process.inputStream.bufferedReader().readLine()?.trim()
            if (process.waitFor() == 0 && !output.isNullOrBlank()) output else null
        } catch (_: Exception) {
            null
        }
    }

    fun execCommand(command: String, workDir: String? = null, timeoutMs: Long = 5000): String? {
        return try {
            val shell = if (SystemInfo.isWindows) listOf("cmd.exe", "/c") else listOf("/bin/sh", "-c")
            val pb = ProcessBuilder(shell + command)
                .apply {
                    environment().putAll(getEnvWithDotnetTools())
                    if (workDir != null) directory(File(workDir))
                    redirectErrorStream(true)
                }
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (process.exitValue() == 0) output else null
        } catch (_: Exception) {
            null
        }
    }

    data class PlatformInfo(
        val platform: String,
        val platformName: String,
        val arch: String,
        val qemuDisplay: String,
        val gdbCommand: String,
        val arm64UefiBios: String?
    )

    private var cachedPlatformInfo: PlatformInfo? = null

    fun getPlatformInfo(): PlatformInfo {
        cachedPlatformInfo?.let { return it }

        if (isCosmosToolsInstalled) {
            try {
                val result = execCommand("cosmos info --json")
                if (result != null) {
                    val json = com.google.gson.JsonParser.parseString(result).asJsonObject
                    val info = PlatformInfo(
                        platform = json.get("platform")?.asString ?: platformName.lowercase(),
                        platformName = json.get("platformName")?.asString ?: platformName,
                        arch = json.get("arch")?.asString ?: System.getProperty("os.arch"),
                        qemuDisplay = json.get("qemuDisplay")?.asString ?: qemuDisplay,
                        gdbCommand = json.get("gdbCommand")?.asString ?: "gdb",
                        arm64UefiBios = json.get("arm64UefiBios")?.asString
                    )
                    cachedPlatformInfo = info
                    return info
                }
            } catch (_: Exception) {
                // Fall through
            }
        }

        val info = PlatformInfo(
            platform = platformName.lowercase(),
            platformName = platformName,
            arch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64",
            qemuDisplay = qemuDisplay,
            gdbCommand = "gdb",
            arm64UefiBios = null
        )
        cachedPlatformInfo = info
        return info
    }
}
