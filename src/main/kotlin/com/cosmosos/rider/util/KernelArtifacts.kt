package com.cosmosos.rider.util

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

object KernelArtifacts {

    // First ISO under <projectDir>/output-<arch>, or null when the kernel
    // hasn't been built for that architecture.
    fun findIso(projectDir: String, arch: String): File? {
        val outputDir = File(projectDir, "output-$arch")
        return outputDir.listFiles()?.filter { it.isFile && it.name.endsWith(".iso") }?.minByOrNull { it.name }
    }

    fun outputDirExists(projectDir: String, arch: String) = File(projectDir, "output-$arch").isDirectory

    // Resolves the kernel ELF. Cosmos.Sdk routes outputs to a repo-level
    // artifacts directory (<root>/artifacts/bin/<proj>/debug_linux-<arch>/),
    // but older project layouts still drop the binary under
    // <projectDir>/bin/Debug/net10.0/linux-<arch>/. Modern path first.
    fun resolveKernelElf(rootDir: String?, projectDir: String, projectName: String, arch: String): File? {
        val candidates = listOfNotNull(
            rootDir?.let { File(it, "artifacts/bin/$projectName/debug_linux-$arch") },
            File(projectDir, "artifacts/bin/$projectName/debug_linux-$arch"),
            File(projectDir, "bin/Debug/net10.0/linux-$arch")
        ).distinct()

        for (dir in candidates) {
            if (!dir.isDirectory) continue
            // Prefer "<projectName>.elf", otherwise the first ELF.
            val named = File(dir, "$projectName.elf")
            if (named.isFile) return named
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".elf") }?.minByOrNull { it.name }?.let { return it }
        }
        return null
    }
}

object PortUtil {

    // True if something is listening on `port` on the loopback interface: a
    // successful connect means busy, a refused one means free.
    fun isPortInUse(port: Int, host: String = "127.0.0.1"): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 500) }
            true
        } catch (_: Exception) {
            false
        }
    }

    // Polls until the port accepts connections, instead of a fixed sleep.
    fun waitForPort(port: Int, timeoutMs: Long, isCancelled: () -> Boolean = { false }): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !isCancelled()) {
            if (isPortInUse(port)) return true
            Thread.sleep(100)
        }
        return false
    }
}
