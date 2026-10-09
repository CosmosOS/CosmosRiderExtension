package com.cosmosos.rider.kernelviews

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

// Snapshot layouts — keep in sync with the kernel's DebugLiveSnapshot.cs,
// DebugLiveGCSnapshot.cs, DebugLiveMemorySnapshot.cs and PageType.cs. Every
// snapshot starts with a magic and a seqlock counter at offset 16: odd means a
// writer is mid-update, so the read is dropped and retried on the next poll.

data class KernelThreadInfo(
    val slot: Int,
    val id: Long,
    val state: String,
    val cpuId: Long,
    val flags: Long
) {
    val flagNames: List<String>
        get() = buildList {
            if (flags and 0x1L != 0L) add("Kernel")
            if (flags and 0x2L != 0L) add("Idle")
            if (flags and 0x4L != 0L) add("Pinned")
            if (flags and 0x8L != 0L) add("Managed")
        }

    // CPU ids are u32; 0xFFFFFFFF (-1 in the kernel) means "not on a CPU".
    val cpuLabel: String
        get() = if (cpuId in 0 until 0x7fffffff) " on CPU $cpuId" else ""
}

data class GcStats(
    val initialized: Boolean,
    val heapSizeBytes: Long,
    val fragmentedBytes: Long,
    val totalCommittedBytes: Long,
    val totalAllocatedBytes: Long,
    val pinnedObjectsCount: Long,
    val collectionCount: Long,
    val totalObjectsFreed: Long,
    val memoryLoadBytes: Long,
    val gcSegmentSize: Long,
    val lastGCPercentTimeInGC: Long,
    val lastGen0SizeBefore: Long,
    val lastGen0FragBefore: Long,
    val lastGen0SizeAfter: Long,
    val lastGen0FragAfter: Long
)

data class MemoryStats(
    val initialized: Boolean,
    val pageSize: Long,
    val ramStart: Long,
    val ramSize: Long,
    val totalPageCount: Long,
    val freePageCount: Long,
    val ratAddress: Long,
    val heapEnd: Long,
    val pagesEmpty: Long,
    val pagesGCHeap: Long,
    val pagesHeapSmall: Long,
    val pagesHeapMedium: Long,
    val pagesHeapLarge: Long,
    val pagesUnmanaged: Long,
    val pagesPageDirectory: Long,
    val pagesPageAllocator: Long,
    val pagesSMT: Long,
    val pagesExtension: Long,
    val pagesUnknown: Long
) {
    // Page count per PageType byte, for the memory map legend.
    val typeCounts: Map<Int, Long>
        get() = mapOf(
            PageTypes.EMPTY to pagesEmpty,
            PageTypes.GC_HEAP to pagesGCHeap,
            PageTypes.HEAP_SMALL to pagesHeapSmall,
            PageTypes.HEAP_MEDIUM to pagesHeapMedium,
            PageTypes.HEAP_LARGE to pagesHeapLarge,
            PageTypes.UNMANAGED to pagesUnmanaged,
            PageTypes.PAGE_DIRECTORY to pagesPageDirectory,
            PageTypes.PAGE_ALLOCATOR to pagesPageAllocator,
            PageTypes.SMT to pagesSMT,
            PageTypes.EXTENSION to pagesExtension
        )
}

// A run of pages owned by one PageType; Extension pages are folded into their owner.
data class PageExtent(val start: Int, val length: Int, val type: Int)

object PageTypes {
    const val EMPTY = 0
    const val GC_HEAP = 1
    const val HEAP_SMALL = 3
    const val HEAP_MEDIUM = 5
    const val HEAP_LARGE = 7
    const val UNMANAGED = 9
    const val PAGE_DIRECTORY = 11
    const val PAGE_ALLOCATOR = 32
    const val SMT = 64
    const val EXTENSION = 128

    val names = linkedMapOf(
        EMPTY to "Empty",
        GC_HEAP to "GCHeap",
        HEAP_SMALL to "HeapSmall",
        HEAP_MEDIUM to "HeapMedium",
        HEAP_LARGE to "HeapLarge",
        UNMANAGED to "Unmanaged",
        PAGE_DIRECTORY to "PageDirectory",
        PAGE_ALLOCATOR to "PageAllocator",
        SMT to "SMT",
        EXTENSION to "Extension"
    )
}

object KernelSnapshots {
    const val THREADS_MAGIC = 0xC05D0001L
    const val THREADS_HEADER_SIZE = 24
    const val THREADS_ENTRY_SIZE = 16
    const val THREADS_MAX_ENTRIES = 64
    const val THREADS_SIZE = THREADS_HEADER_SIZE + THREADS_MAX_ENTRIES * THREADS_ENTRY_SIZE

    const val GC_MAGIC = 0xC05D0002L
    const val GC_SIZE = 160

    const val MEMORY_MAGIC = 0xC05D0003L
    const val MEMORY_SIZE = 192

    private val STATE_NAMES = listOf("Created", "Ready", "Running", "Blocked", "Sleeping", "Dead")

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.u32(offset: Int): Long = getInt(offset).toLong() and 0xffffffffL

    private fun ByteBuffer.seqIsStable(): Boolean = getLong(16) and 1L == 0L

    fun readPointer(bytes: ByteArray): Long? = if (bytes.size >= 8) le(bytes).getLong(0) else null

    fun parseThreads(bytes: ByteArray): List<KernelThreadInfo>? {
        if (bytes.size < THREADS_HEADER_SIZE) return null
        val buf = le(bytes)
        if (buf.u32(0) != THREADS_MAGIC || !buf.seqIsStable()) return null
        val count = minOf(buf.u32(8), THREADS_MAX_ENTRIES.toLong()).toInt()
        return (0 until count).mapNotNull { i ->
            val off = THREADS_HEADER_SIZE + i * THREADS_ENTRY_SIZE
            if (off + THREADS_ENTRY_SIZE > bytes.size) return@mapNotNull null
            val state = buf.u32(off + 8).toInt()
            KernelThreadInfo(
                slot = i,
                id = buf.u32(off),
                state = STATE_NAMES.getOrElse(state) { "?$state" },
                cpuId = buf.u32(off + 4),
                flags = buf.u32(off + 12)
            )
        }
    }

    fun parseGc(bytes: ByteArray): GcStats? {
        if (bytes.size < 136) return null
        val buf = le(bytes)
        if (buf.u32(0) != GC_MAGIC || !buf.seqIsStable()) return null
        return GcStats(
            initialized = buf.u32(8) and 1L != 0L,
            heapSizeBytes = buf.getLong(24),
            fragmentedBytes = buf.getLong(32),
            totalCommittedBytes = buf.getLong(40),
            totalAllocatedBytes = buf.getLong(48),
            pinnedObjectsCount = buf.getLong(56),
            collectionCount = buf.u32(64),
            totalObjectsFreed = buf.u32(68),
            memoryLoadBytes = buf.getLong(72),
            gcSegmentSize = buf.getLong(80),
            lastGCPercentTimeInGC = buf.u32(96),
            lastGen0SizeBefore = buf.getLong(104),
            lastGen0FragBefore = buf.getLong(112),
            lastGen0SizeAfter = buf.getLong(120),
            lastGen0FragAfter = buf.getLong(128)
        )
    }

    fun parseMemory(bytes: ByteArray): MemoryStats? {
        if (bytes.size < 160) return null
        val buf = le(bytes)
        if (buf.u32(0) != MEMORY_MAGIC || !buf.seqIsStable()) return null
        return MemoryStats(
            initialized = buf.u32(8) and 1L != 0L,
            pageSize = buf.u32(12),
            ramStart = buf.getLong(24),
            ramSize = buf.getLong(32),
            totalPageCount = buf.getLong(40),
            freePageCount = buf.getLong(48),
            ratAddress = buf.getLong(56),
            heapEnd = buf.getLong(64),
            pagesEmpty = buf.getLong(72),
            pagesGCHeap = buf.getLong(80),
            pagesHeapSmall = buf.getLong(88),
            pagesHeapMedium = buf.getLong(96),
            pagesHeapLarge = buf.getLong(104),
            pagesUnmanaged = buf.getLong(112),
            pagesPageDirectory = buf.getLong(120),
            pagesPageAllocator = buf.getLong(128),
            pagesSMT = buf.getLong(136),
            pagesExtension = buf.getLong(144),
            pagesUnknown = buf.getLong(152)
        )
    }

    /**
     * Walks the raw RAT into coalesced extents. Each non-Extension byte starts
     * an extent and absorbs the Extension bytes after it; Empty pages coalesce
     * with neighbouring Empty pages. Orphan Extension runs get their own
     * extent so a corrupted RAT shows up instead of being dropped.
     */
    fun walkExtents(rat: ByteArray): List<PageExtent> {
        val out = mutableListOf<PageExtent>()
        val n = rat.size
        var i = 0
        while (i < n) {
            val t = rat[i].toInt() and 0xff
            val start = i
            if (t == PageTypes.EXTENSION) {
                while (i < n && (rat[i].toInt() and 0xff) == PageTypes.EXTENSION) i++
                out += PageExtent(start, i - start, PageTypes.EXTENSION)
                continue
            }
            i++
            val absorbed = if (t == PageTypes.EMPTY) PageTypes.EMPTY else PageTypes.EXTENSION
            while (i < n && (rat[i].toInt() and 0xff) == absorbed) i++
            out += PageExtent(start, i - start, t)
        }
        return out
    }

    fun formatBytes(n: Long): String {
        if (n in 0 until 1024) return "$n B"
        val kb = n.toULong().toDouble() / 1024
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KiB", kb)
        val mb = kb / 1024
        if (mb < 1024) return String.format(Locale.ROOT, "%.2f MiB", mb)
        return String.format(Locale.ROOT, "%.2f GiB", mb / 1024)
    }

    fun formatHex(n: Long): String = "0x" + java.lang.Long.toHexString(n).padStart(16, '0')
}
