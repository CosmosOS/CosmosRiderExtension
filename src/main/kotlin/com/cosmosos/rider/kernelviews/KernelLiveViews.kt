package com.cosmosos.rider.kernelviews

import com.cosmosos.rider.debugger.QmpClient
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The session's live channel into guest memory: QMP reads, plus the addresses
 * of the kernel's snapshot statics resolved from the ELF. The first 8 bytes at
 * each statics address hold that snapshot's buffer pointer (`s_buffer`).
 */
class LiveReader(
    private val qmp: QmpClient,
    val threadsStaticsAddr: Long?,
    val gcStaticsAddr: Long?,
    val memoryStaticsAddr: Long?
) {
    fun readVirtual(vaddr: Long, length: Int): ByteArray = qmp.readVirtual(vaddr, length)

    companion object {
        const val THREADS_SYMBOL = "__NONGCSTATICSCosmos_Kernel_Core_Cosmos_Kernel_Core_Runtime_DebugLiveSnapshot"
        const val GC_SYMBOL = "__NONGCSTATICSCosmos_Kernel_Core_Cosmos_Kernel_Core_Runtime_DebugLiveGCSnapshot"
        const val MEMORY_SYMBOL = "__NONGCSTATICSCosmos_Kernel_Core_Cosmos_Kernel_Core_Runtime_DebugLiveMemorySnapshot"
    }
}

/** State behind one live view; listeners are called on the EDT. */
abstract class LiveModel(val tag: String) {
    @Volatile
    var message: String? = "Waiting for the kernel's live snapshot…"
        protected set

    @Volatile
    var snapshotAddr: Long? = null

    @Volatile
    var errorLogged = false

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    protected fun fire() {
        ApplicationManager.getApplication().invokeLater { listeners.forEach { it() } }
    }

    open fun showMessage(msg: String?) {
        message = msg
        clear()
        fire()
    }

    protected abstract fun clear()

    abstract fun staticsAddr(reader: LiveReader): Long?

    abstract val notInitializedMessage: String

    // Reads the snapshot's buffer pointer out of its statics block.
    open fun capture(reader: LiveReader, log: (String) -> Unit): Boolean {
        if (snapshotAddr != null) return true
        val statics = staticsAddr(reader) ?: return false
        val addr = KernelSnapshots.readPointer(reader.readVirtual(statics, 8)) ?: return false
        if (addr == 0L) {
            showMessage(notInitializedMessage)
            return false
        }
        snapshotAddr = addr
        log("[$tag] snapshot buffer at 0x${java.lang.Long.toHexString(addr)}")
        message = "Polling $tag snapshot…"
        fire()
        return true
    }

    abstract fun poll(reader: LiveReader)

    abstract fun serialize(): String
}

class ThreadsModel : LiveModel("kernel-threads") {
    @Volatile
    var threads: List<KernelThreadInfo> = emptyList()
        private set

    override val notInitializedMessage = "Live snapshot not initialized yet (scheduler not up)."

    override fun clear() {
        threads = emptyList()
    }

    override fun staticsAddr(reader: LiveReader) = reader.threadsStaticsAddr

    override fun poll(reader: LiveReader) {
        val addr = snapshotAddr ?: return
        val parsed = KernelSnapshots.parseThreads(reader.readVirtual(addr, KernelSnapshots.THREADS_SIZE))
        if (parsed == null) {
            message = "Snapshot buffer not yet populated (bad magic)."
            threads = emptyList()
        } else {
            message = null
            threads = parsed
        }
        fire()
    }

    // Fallback when the statics symbol is missing: a gdb infcall while the
    // guest is stopped hands back the buffer address.
    fun captureFromInfcall(result: String, log: (String) -> Unit) {
        val m = Regex("0x[0-9a-fA-F]+|\\d+").find(result)
        val addr = m?.value?.let { if (it.startsWith("0x")) it.substring(2).toULongOrNull(16)?.toLong() else it.toULongOrNull()?.toLong() }
        when {
            addr == null -> showMessage("Could not locate live snapshot buffer (parse failed).")
            addr == 0L -> showMessage("Live snapshot not initialized yet — continue or hit another breakpoint after the scheduler starts.")
            else -> {
                snapshotAddr = addr
                log("[$tag] snapshot buffer at 0x${java.lang.Long.toHexString(addr)} (via gdb)")
                message = "Polling kernel snapshot…"
                fire()
            }
        }
    }

    override fun serialize(): String = buildList {
        message?.let { add(it) }
        if (threads.isEmpty() && message == null) add("(empty)")
        for (t in threads) {
            add("slot ${t.slot}: #${t.id} [${t.state}]${t.cpuLabel} flags=0x${java.lang.Long.toHexString(t.flags)}")
        }
    }.joinToString("\n")
}

class GcModel : LiveModel("kernel-gc") {
    @Volatile
    var stats: GcStats? = null
        private set

    override val notInitializedMessage = "GC snapshot not initialized yet."

    override fun clear() {
        stats = null
    }

    override fun staticsAddr(reader: LiveReader) = reader.gcStaticsAddr

    override fun poll(reader: LiveReader) {
        val addr = snapshotAddr ?: return
        val parsed = KernelSnapshots.parseGc(reader.readVirtual(addr, KernelSnapshots.GC_SIZE))
        if (parsed == null) {
            message = "GC snapshot buffer not yet populated."
            stats = null
        } else {
            message = null
            stats = parsed
        }
        fire()
    }

    override fun serialize(): String {
        val s = stats ?: return message ?: "(no data)"
        val f = KernelSnapshots::formatBytes
        return listOfNotNull(
            message,
            "Initialized:           ${s.initialized}",
            "Heap size:             ${f(s.heapSizeBytes)}",
            "Fragmented:            ${f(s.fragmentedBytes)}",
            "Total committed:       ${f(s.totalCommittedBytes)}",
            "Total allocated:       ${f(s.totalAllocatedBytes)}",
            "Pinned objects:        ${s.pinnedObjectsCount}",
            "Collections:           ${s.collectionCount}",
            "Objects freed (total): ${s.totalObjectsFreed}",
            "Memory load:           ${f(s.memoryLoadBytes)}",
            "Segment size:          ${f(s.gcSegmentSize)}",
            "Last GC %time-in-GC:   ${s.lastGCPercentTimeInGC}%",
            "Last gen0 before:      size=${f(s.lastGen0SizeBefore)} frag=${f(s.lastGen0FragBefore)}",
            "Last gen0 after:       size=${f(s.lastGen0SizeAfter)} frag=${f(s.lastGen0FragAfter)}"
        ).joinToString("\n")
    }
}

class MemoryModel : LiveModel("kernel-memory") {
    @Volatile
    var stats: MemoryStats? = null
        private set

    @Volatile
    var extents: List<PageExtent>? = null
        private set

    @Volatile
    var extentsError: String? = null
        private set

    override val notInitializedMessage = "Memory snapshot not initialized yet."

    override fun clear() {
        stats = null
        extents = null
        extentsError = null
    }

    override fun staticsAddr(reader: LiveReader) = reader.memoryStaticsAddr

    override fun poll(reader: LiveReader) {
        val addr = snapshotAddr ?: return
        // The kernel writes from the timer tick, so a single read can land
        // mid-update; retry briefly before giving up on this poll.
        var parsed: MemoryStats? = null
        for (attempt in 0 until 3) {
            parsed = KernelSnapshots.parseMemory(reader.readVirtual(addr, KernelSnapshots.MEMORY_SIZE))
            if (parsed != null) break
            if (attempt < 2) Thread.sleep(30)
        }
        if (parsed == null) {
            // Keep the last good snapshot so the view doesn't flicker.
            if (stats == null) {
                message = "Memory snapshot buffer not yet populated."
                fire()
            }
            return
        }
        message = null
        stats = parsed
        if (parsed.initialized) refreshExtents(reader, parsed)
        fire()
    }

    private fun refreshExtents(reader: LiveReader, stats: MemoryStats) {
        val total = stats.totalPageCount
        if (stats.ratAddress == 0L || total <= 0 || total > Int.MAX_VALUE) return
        try {
            val rat = reader.readVirtual(stats.ratAddress, total.toInt())
            if (rat.size.toLong() != total) {
                extentsError = "Short RAT read: got ${rat.size}/$total bytes"
                return
            }
            extents = KernelSnapshots.walkExtents(rat)
            extentsError = null
        } catch (e: Exception) {
            extentsError = "RAT read failed: ${e.message}"
        }
    }

    override fun serialize(): String {
        val s = stats ?: return message ?: "(no data)"
        val f = KernelSnapshots::formatBytes
        val ps = s.pageSize
        val used = s.totalPageCount - s.freePageCount
        fun pages(n: Long) = "$n (${f(n * ps)})"
        return listOfNotNull(
            message,
            "Initialized:        ${s.initialized}",
            "Page size:          $ps B",
            "RAM start:          ${KernelSnapshots.formatHex(s.ramStart)}",
            "RAM size:           ${f(s.ramSize)}",
            "Heap end:           ${KernelSnapshots.formatHex(s.heapEnd)}",
            "RAT address:        ${KernelSnapshots.formatHex(s.ratAddress)}",
            "Total pages:        ${s.totalPageCount}",
            "Free pages:         ${pages(s.freePageCount)}",
            "Used pages:         ${pages(used)}",
            "Page composition:",
            "  Empty:            ${pages(s.pagesEmpty)}",
            "  GCHeap:           ${pages(s.pagesGCHeap)}",
            "  HeapSmall:        ${pages(s.pagesHeapSmall)}",
            "  HeapMedium:       ${pages(s.pagesHeapMedium)}",
            "  HeapLarge:        ${pages(s.pagesHeapLarge)}",
            "  Unmanaged:        ${pages(s.pagesUnmanaged)}",
            "  PageDirectory:    ${pages(s.pagesPageDirectory)}",
            "  PageAllocator:    ${pages(s.pagesPageAllocator)}",
            "  SMT:              ${pages(s.pagesSMT)}",
            "  Extension:        ${pages(s.pagesExtension)}",
            if (s.pagesUnknown > 0) "  Unknown:          ${s.pagesUnknown}" else null
        ).joinToString("\n")
    }
}

/**
 * Polls the kernel's live snapshots over QMP once a second, without pausing
 * the guest, for as long as the debug session lives.
 */
class KernelLiveViews(private val log: (String) -> Unit) : Disposable {
    val threads = ThreadsModel()
    val gc = GcModel()
    val memory = MemoryModel()
    private val models = listOf(threads, gc, memory)

    @Volatile
    private var reader: LiveReader? = null

    @Volatile
    private var disposed = false

    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("Cosmos kernel views", 1)
    private var ticker: ScheduledFuture<*>? = null

    fun start(reader: LiveReader?) {
        if (disposed) return
        this.reader = reader
        if (reader == null) {
            models.forEach { it.showMessage("Live kernel views need QEMU's QMP socket, which is unavailable this session.") }
            return
        }
        ticker = executor.scheduleWithFixedDelay(::tick, 1500, 1000, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        val r = reader ?: return
        for (model in models) {
            if (disposed) return
            try {
                if (model.snapshotAddr == null) model.capture(r, log) else model.poll(r)
                model.errorLogged = false
            } catch (e: Exception) {
                if (!model.errorLogged) {
                    log("[${model.tag}] poll error: ${e.message}")
                    model.errorLogged = true
                }
            }
        }
    }

    fun refreshNow(model: LiveModel) {
        val r = reader ?: return
        executor.execute {
            try {
                if (model.snapshotAddr == null) model.capture(r, log) else model.poll(r)
            } catch (e: Exception) {
                log("[${model.tag}] refresh error: ${e.message}")
            }
        }
    }

    /**
     * Called when the guest stops. If the threads statics symbol couldn't be
     * resolved, the stop is the one moment a gdb infcall can fetch the buffer
     * address instead.
     */
    fun onStopped(evaluate: (String) -> CompletableFuture<String>) {
        val r = reader ?: return
        if (threads.snapshotAddr != null || r.threadsStaticsAddr != null) return
        evaluate("(unsigned long long)CosmosDbg_GetSnapshotAddr()").whenComplete { result, error ->
            if (error != null) {
                log("[kernel-threads] capture failed: ${error.message}")
                threads.showMessage("Snapshot capture failed: ${error.message}")
            } else {
                threads.captureFromInfcall(result, log)
            }
        }
    }

    override fun dispose() {
        disposed = true
        ticker?.cancel(false)
        executor.shutdownNow()
        models.forEach { it.showMessage("Debug session ended.") }
    }
}
