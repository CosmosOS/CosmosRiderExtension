package com.cosmosos.rider.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

object PlatformUtil {

    val dotnetToolsDir: String
        get() = Path.of(System.getProperty("user.home"), ".dotnet", "tools").toString()

    // Where the Cosmos installer drops QEMU, GDB, LLVM and friends.
    private val cosmosInstallerToolsDir: String
        get() = if (SystemInfo.isWindows) {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
            Path.of(localAppData, "Cosmos", "Tools").toString()
        } else {
            Path.of(System.getProperty("user.home"), ".cosmos", "tools").toString()
        }

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

    val platformName: String
        get() = when {
            SystemInfo.isWindows -> "Windows"
            SystemInfo.isMac -> "macOS"
            else -> "Linux"
        }

    // PATH with the dotnet global tools and every Cosmos installer tool
    // directory prepended, so tools are found even when the shell that started
    // the IDE predates the install.
    val pathWithCosmosTools: String
        get() {
            val tools = cosmosInstallerToolsDir
            val extraPaths = listOf(
                dotnetToolsDir,
                Path.of(tools, "bin").toString(),
                Path.of(tools, "llvm-tools", "bin").toString(),
                Path.of(tools, "yasm").toString(),
                Path.of(tools, "xorriso").toString(),
                Path.of(tools, "lld").toString(),
                Path.of(tools, "x86_64-elf-tools", "bin").toString(),
                Path.of(tools, "aarch64-elf-tools", "bin").toString(),
                // The QEMU bundle keeps its executables in bin/ so QEMU's
                // <exec>/../share/qemu BIOS lookup resolves; the bare qemu
                // entry covers installs that predate that layout.
                Path.of(tools, "qemu", "bin").toString(),
                Path.of(tools, "qemu").toString(),
                // The gdb-multiarch zip extracts to gdb/bin, DLLs included.
                Path.of(tools, "gdb", "bin").toString()
            )
            val currentPath = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH") ?: ""
            return (extraPaths + currentPath).joinToString(File.pathSeparator)
        }

    fun getEnvWithDotnetTools(): Map<String, String> {
        val env = System.getenv().toMutableMap()
        env.keys.filter { it.equals("PATH", ignoreCase = true) }.forEach { env.remove(it) }
        env["PATH"] = pathWithCosmosTools
        return env
    }

    fun createCommandLine(executable: String, vararg args: String): GeneralCommandLine {
        return GeneralCommandLine(executable, *args)
            .withEnvironment("PATH", pathWithCosmosTools)
            .withCharset(Charsets.UTF_8)
    }

    fun findCommand(name: String): String? {
        val names = if (SystemInfo.isWindows) listOf("$name.exe", "$name.cmd", "$name.bat", name) else listOf(name)
        for (dir in pathWithCosmosTools.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            for (candidate in names) {
                val file = File(dir, candidate)
                if (file.isFile && file.canExecute()) return file.absolutePath
            }
        }
        return null
    }

    fun execCommand(command: String, workDir: String? = null, timeoutMs: Long = 5000): String? {
        return try {
            val shell = if (SystemInfo.isWindows) listOf("cmd.exe", "/c") else listOf("/bin/sh", "-c")
            val process = ProcessBuilder(shell + command)
                .apply {
                    environment().putAll(getEnvWithDotnetTools())
                    if (workDir != null) directory(File(workDir))
                    redirectErrorStream(true)
                }
                .start()
            process.outputStream.close()
            // Drain on a side thread so a chatty command can't fill the pipe
            // and stall past the timeout.
            val output = StringBuilder()
            val reader = Thread {
                runCatching { output.append(process.inputStream.bufferedReader().readText()) }
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return null
            }
            reader.join(1000)
            if (process.exitValue() == 0) output.toString().trim() else null
        } catch (_: Exception) {
            null
        }
    }

    // Cached `cosmos check --json` result. Filled lazily and by the Tools
    // panel's refresh; the debugger reads the gdb-multiarch path out of it.
    @Volatile
    private var cachedToolsCheck: JsonObject? = null

    fun getToolsCheck(): JsonObject? {
        cachedToolsCheck?.let { return it }
        return refreshToolsCheck()
    }

    fun refreshToolsCheck(): JsonObject? {
        cachedToolsCheck = null
        if (!isCosmosToolsInstalled) return null
        val result = execCommand("cosmos check --json", timeoutMs = 10000) ?: return null
        return try {
            JsonParser.parseString(result).asJsonObject.also { cachedToolsCheck = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun findToolPath(name: String): String? {
        val tools = getToolsCheck()?.getAsJsonArray("tools") ?: return null
        for (element in tools) {
            val tool = element.asJsonObject
            if (tool.get("name")?.asString == name && tool.get("found")?.asBoolean == true) {
                return tool.get("path")?.takeIf { !it.isJsonNull }?.asString
            }
        }
        return null
    }

    fun getGdbPath(): String? = findToolPath("gdb-multiarch")
}
