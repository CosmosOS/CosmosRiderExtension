package com.cosmosos.rider.util

import java.io.File
import java.io.RandomAccessFile

object QemuOptions {

    // Parse memory strings like "512", "512M", "1G" into the integer MB that
    // `cosmos run -m` expects.
    fun parseMemoryMb(s: String): Int? {
        val m = Regex("^(\\d+)\\s*([MmGg]?)").find(s) ?: return null
        var n = m.groupValues[1].toIntOrNull() ?: return null
        if (m.groupValues[2].equals("G", ignoreCase = true)) n *= 1024
        return n
    }

    // Parse size strings like "256", "256M", "1G", "512K" into bytes. A bare
    // number, or the M suffix, means mebibytes.
    fun parseSizeBytes(s: String): Long? {
        val m = Regex("^(\\d+)\\s*([KkMmGg]?)").find(s.trim()) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        return when (m.groupValues[2].uppercase()) {
            "G" -> n * 1024 * 1024 * 1024
            "K" -> n * 1024
            else -> n * 1024 * 1024
        }
    }

    // `--cpu <model>`. Empty yields nothing, letting the launcher pick its
    // default (host under KVM, max under TCG on x64; cortex-a72 on arm64).
    fun buildCpuArgs(cpuModel: String?): List<String> {
        val model = cpuModel?.trim().orEmpty()
        return if (model.isEmpty()) emptyList() else listOf("--cpu", model)
    }

    // `--nic <model>`. 'none' is passed through so QEMU's default NIC is
    // disabled; empty keeps QEMU's default in place.
    fun buildNicArgs(networkCard: String?): List<String> {
        val card = networkCard?.trim().orEmpty()
        return if (card.isEmpty()) emptyList() else listOf("--nic", card)
    }

    // One `--hostfwd <rule>` per configured port forward (e.g. tcp::2323-:23).
    fun buildHostForwardArgs(portForwards: List<String>?): List<String> {
        return portForwards.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { listOf("--hostfwd", it) }
    }

    // `--keyboard/--mouse <model>`, passed as-is; the launcher treats
    // 'ps2'/'none' as "add nothing" and attaches only real device models.
    fun buildInputArgs(keyboard: String?, mouse: String?): List<String> {
        val args = mutableListOf<String>()
        keyboard?.trim()?.takeIf { it.isNotEmpty() }?.let { args += listOf("--keyboard", it) }
        mouse?.trim()?.takeIf { it.isNotEmpty() }?.let { args += listOf("--mouse", it) }
        return args
    }

    // `--audio <model>`. 'none' (the default) yields nothing, so a project
    // without audio launches exactly the command it did before.
    fun buildAudioArgs(audio: String?): List<String> {
        val model = audio?.trim().orEmpty()
        return if (model.isEmpty() || model == "none") emptyList() else listOf("--audio", model)
    }

    // Split the free-form Extra Arguments field into the argv entries that
    // follow `cosmos run --`: whitespace separates arguments, except inside
    // double quotes. The quotes stay in the entry, since cosmos run joins the
    // entries back into QEMU's command line, where they still group the
    // quoted text into one argument (e.g. -name "my vm").
    fun splitExtraArgs(extraArgs: String?): List<String> {
        return Regex("(?:[^\\s\"]+|\"[^\"]*\")+").findAll(extraArgs.orEmpty()).map { it.value }.toList()
    }

    // Turn the configured disks into `--disk <path>,<kind>` arguments,
    // creating any missing image as a sparse file of the requested size.
    // Paths resolve against the project directory; existing images are never
    // resized. Throws on a bad size or a failed creation.
    fun prepareDiskArgs(projectDir: String, disks: List<DiskConfig>?, log: (String) -> Unit = {}): List<String> {
        val args = mutableListOf<String>()
        for (disk in disks.orEmpty()) {
            if (disk.path.isBlank()) continue
            val kind = if (disk.type == "nvme") "nvme" else "ahci"
            val file = File(disk.path).let { if (it.isAbsolute) it else File(projectDir, disk.path) }

            if (!file.exists()) {
                val sizeStr = disk.size.trim().ifEmpty { "256M" }
                val bytes = parseSizeBytes(sizeStr)
                if (bytes == null || bytes <= 0) {
                    throw IllegalArgumentException("Invalid disk size \"$sizeStr\" for ${disk.path}")
                }
                file.absoluteFile.parentFile?.mkdirs()
                RandomAccessFile(file, "rw").use { it.setLength(bytes) }
                log("Created disk image ${file.absolutePath} ($sizeStr)")
            }

            args += listOf("--disk", "${file.absolutePath},$kind")
        }
        return args
    }
}
