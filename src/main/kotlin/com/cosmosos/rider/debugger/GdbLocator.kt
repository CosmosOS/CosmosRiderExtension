package com.cosmosos.rider.debugger

import com.cosmosos.rider.util.PlatformUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object GdbLocator {

    data class Gdb(val path: String, val hasPython: Boolean)

    private val pythonCache = ConcurrentHashMap<String, Boolean>()
    private val archCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Picks the gdb to debug an [arch] guest with. Architecture support gates
     * the candidates first: a single-arch host gdb can't attach to an aarch64
     * guest even if it has Python. Among the rest, one with Python wins so the
     * NativeAOT pretty-printers load (Cosmos's bundled gdb has none).
     */
    fun locate(arch: String): Gdb? {
        val candidates = listOfNotNull(
            PlatformUtil.getGdbPath(),
            PlatformUtil.findCommand("gdb-multiarch"),
            PlatformUtil.findCommand("gdb")
        ).distinct().filter { supportsArch(it, arch) }
        if (candidates.isEmpty()) return null
        val withPython = candidates.firstOrNull(::hasPython)
        return Gdb(withPython ?: candidates.first(), withPython != null)
    }

    // True if gdb was built with Python AND its `gdb` module loads; Python
    // compiled in without the data directory can't run the printers either.
    private fun hasPython(gdbPath: String): Boolean = pythonCache.getOrPut(gdbPath) {
        val (code, out) = probe(gdbPath, "-batch", "-ex", "python import gdb; print(\"ok\")") ?: return@getOrPut false
        code == 0 &&
            Regex("\\bok\\b").containsMatchIn(out) &&
            !out.contains("Python scripting is not supported", ignoreCase = true) &&
            !out.contains("No module named 'gdb'", ignoreCase = true)
    }

    // x64 guests attach from any host gdb; only arm64 needs a probe. A gdb
    // without the target prints 'Undefined item: "aarch64".'
    private fun supportsArch(gdbPath: String, arch: String): Boolean {
        if (arch != "arm64") return true
        return archCache.getOrPut("$arch $gdbPath") {
            val (_, out) = probe(gdbPath, "-batch", "-ex", "set architecture aarch64") ?: return@getOrPut false
            !Regex("Undefined item|Undefined architecture|not a recognized", RegexOption.IGNORE_CASE).containsMatchIn(out)
        }
    }

    private fun probe(gdbPath: String, vararg args: String): Pair<Int, String>? {
        return try {
            val process = ProcessBuilder(listOf(gdbPath) + args)
                .redirectErrorStream(true)
                .apply { environment().putAll(PlatformUtil.getEnvWithDotnetTools()) }
                .start()
            process.outputStream.close()
            val output = StringBuilder()
            val reader = Thread {
                runCatching { output.append(process.inputStream.bufferedReader().readText()) }
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            reader.join(1000)
            process.exitValue() to output.toString()
        } catch (_: Exception) {
            null
        }
    }
}
