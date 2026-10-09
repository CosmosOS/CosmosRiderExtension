package com.cosmosos.rider.testing

import java.io.File

data class TestKernel(
    val suiteName: String,
    val kernelName: String,
    val projectDir: String,
    val csprojPath: String
)

/** Finds the Cosmos repo's test kernels: tests/Kernels/Cosmos.Kernel.Tests.<Suite>/. */
object TestDiscovery {
    private const val KERNEL_NAME_PREFIX = "Cosmos.Kernel.Tests."

    fun findTestKernels(root: String?): List<TestKernel> {
        val kernelsRoot = root?.let { File(it, "tests/Kernels") } ?: return emptyList()
        val entries = kernelsRoot.listFiles() ?: return emptyList()
        return entries
            .filter { it.isDirectory && it.name.startsWith(KERNEL_NAME_PREFIX) }
            .mapNotNull { dir ->
                val csproj = File(dir, "${dir.name}.csproj")
                if (!csproj.isFile) return@mapNotNull null
                TestKernel(
                    suiteName = dir.name.substring(KERNEL_NAME_PREFIX.length),
                    kernelName = dir.name,
                    projectDir = dir.absolutePath,
                    csprojPath = csproj.absolutePath
                )
            }
            .sortedBy { it.suiteName }
    }

    fun locateTestRunnerDll(root: String?): String? {
        val dll = root?.let { File(it, "artifacts/bin/Cosmos.TestRunner.Engine/debug/Cosmos.TestRunner.Engine.dll") }
        return dll?.takeIf { it.isFile }?.absolutePath
    }

    fun locateTestRunnerProject(root: String?): String? {
        val csproj = root?.let { File(it, "tests/Cosmos.TestRunner.Engine/Cosmos.TestRunner.Engine.csproj") }
        return csproj?.takeIf { it.isFile }?.absolutePath
    }
}

/**
 * Per-suite default timeouts in seconds. ARM64 boots and runs ~1.5x slower
 * than x64 under TCG, so its timeouts are scaled accordingly.
 */
object TestTimeouts {
    private val x64Defaults = mapOf(
        "HelloWorld" to 60,
        "TypeCasting" to 60,
        "Memory" to 60,
        "Storage" to 90,
        "Timer" to 120,
        "Network" to 120,
        "Runtime" to 120,
        "Threading" to 120,
        "Graphic" to 120,
        "GarbageCollector" to 120,
        "Power" to 180,
        "Math" to 60
    )

    private val arm64Defaults = mapOf(
        "HelloWorld" to 90,
        "TypeCasting" to 90,
        "Memory" to 90,
        "Storage" to 180,
        "Timer" to 180,
        "Network" to 180,
        "Runtime" to 180,
        "Threading" to 180,
        "Graphic" to 180,
        "GarbageCollector" to 180,
        "Power" to 270,
        "Math" to 90
    )

    fun defaultTimeoutSeconds(suiteName: String, arch: String): Int {
        val table = if (arch == "arm64") arm64Defaults else x64Defaults
        return table[suiteName] ?: if (arch == "arm64") 180 else 120
    }
}
