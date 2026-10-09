package com.cosmosos.rider.debugger

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads symbol addresses straight out of a 64-bit little-endian ELF's
 * .symtab, so resolving the kernel's debug snapshot statics doesn't depend on
 * `nm` being installed (it usually isn't on Windows).
 */
object ElfSymbols {

    private const val SHT_SYMTAB = 2
    private const val SECTION_HEADER_SIZE = 64
    private const val SYMBOL_SIZE = 24

    /** The ELF header's entry point (e_entry), or null if [elf] isn't a 64-bit little-endian ELF. */
    fun entryPoint(elf: File): Long? {
        RandomAccessFile(elf, "r").use { raf ->
            if (raf.length() < 0x20) return null
            val header = ByteArray(0x20)
            raf.readFully(header)
            val buf = java.nio.ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val isElf64Le = header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[4] == 2.toByte() && header[5] == 1.toByte()
            return if (isElf64Le) buf.getLong(0x18).takeIf { it != 0L } else null
        }
    }

    /** Addresses of the [names] found in [elf]; missing names are absent from the map. */
    fun resolve(elf: File, names: Collection<String>): Map<String, Long> {
        if (names.isEmpty()) return emptyMap()
        RandomAccessFile(elf, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            buf.order(ByteOrder.LITTLE_ENDIAN)
            return resolve(buf, names)
        }
    }

    private fun resolve(buf: MappedByteBuffer, names: Collection<String>): Map<String, Long> {
        // \x7FELF, ELFCLASS64, ELFDATA2LSB
        if (buf.limit() < 64 ||
            buf.get(0) != 0x7f.toByte() || buf.get(1) != 'E'.code.toByte() ||
            buf.get(2) != 'L'.code.toByte() || buf.get(3) != 'F'.code.toByte() ||
            buf.get(4) != 2.toByte() || buf.get(5) != 1.toByte()
        ) {
            return emptyMap()
        }

        val shoff = buf.getLong(0x28)
        val shentsize = buf.getShort(0x3A).toInt() and 0xffff
        val shnum = buf.getShort(0x3C).toInt() and 0xffff
        if (shoff <= 0 || shentsize < SECTION_HEADER_SIZE) return emptyMap()

        val wanted = names.distinct().map { it.toByteArray(Charsets.US_ASCII) }
        val found = HashMap<String, Long>()

        for (s in 0 until shnum) {
            val sh = (shoff + s.toLong() * shentsize).toInt()
            if (buf.getInt(sh + 4) != SHT_SYMTAB) continue
            val symOffset = buf.getLong(sh + 0x18)
            val symSize = buf.getLong(sh + 0x20)
            val strIndex = buf.getInt(sh + 0x28)
            val entSize = buf.getLong(sh + 0x38).takeIf { it > 0 } ?: SYMBOL_SIZE.toLong()

            val strHeader = (shoff + strIndex.toLong() * shentsize).toInt()
            val strOffset = buf.getLong(strHeader + 0x18).toInt()
            val strSize = buf.getLong(strHeader + 0x20).toInt()

            val count = symSize / entSize
            for (k in 0 until count) {
                val sym = (symOffset + k * entSize).toInt()
                val nameOffset = buf.getInt(sym)
                if (nameOffset <= 0 || nameOffset >= strSize) continue
                val at = strOffset + nameOffset
                for (name in wanted) {
                    if (matches(buf, at, name)) {
                        found.putIfAbsent(String(name, Charsets.US_ASCII), buf.getLong(sym + 8))
                    }
                }
                if (found.size == wanted.size) return found
            }
        }
        return found
    }

    // Name at [at] equals [name] and is NUL-terminated right after it.
    private fun matches(buf: MappedByteBuffer, at: Int, name: ByteArray): Boolean {
        if (at + name.size >= buf.limit()) return false
        for (i in name.indices) {
            if (buf.get(at + i) != name[i]) return false
        }
        return buf.get(at + name.size) == 0.toByte()
    }
}
