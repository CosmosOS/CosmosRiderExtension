package com.cosmosos.rider.debugger

import com.cosmosos.rider.CosmosIcons
import com.cosmosos.rider.debugger.mi.MiAsyncRecord
import com.cosmosos.rider.debugger.mi.MiParser
import com.cosmosos.rider.debugger.mi.MiResultRecord
import com.cosmosos.rider.debugger.mi.MiSession
import com.cosmosos.rider.debugger.mi.MiStreamRecord
import com.cosmosos.rider.debugger.mi.MiTuple
import com.cosmosos.rider.kernelviews.KernelGcPanel
import com.cosmosos.rider.kernelviews.KernelLiveViews
import com.cosmosos.rider.kernelviews.KernelMemoryPanel
import com.cosmosos.rider.kernelviews.KernelThreadsPanel
import com.cosmosos.rider.kernelviews.LiveReader
import com.cosmosos.rider.run.CosmosLaunchSpec
import com.cosmosos.rider.util.PlatformUtil
import com.cosmosos.rider.util.PortUtil
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.ui.XDebugTabLayouter
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A Cosmos kernel debug session: `cosmos run --debug` boots the kernel with
 * QEMU's gdbstub waiting on port 1234, gdb attaches to it over MI, and Rider's
 * C# line breakpoints become gdb breakpoints (NativeAOT emits DWARF that maps
 * the kernel back to its .cs sources). A QMP socket alongside feeds the live
 * kernel views without pausing the guest.
 */
class CosmosDebugProcess(
    session: XDebugSession,
    private val spec: CosmosLaunchSpec,
    private val elf: File,
    private val cosmosHandler: ProcessHandler,
    private val qmpPort: Int?
) : XDebugProcess(session) {

    companion object {
        const val GDB_PORT = 1234
        const val QMP_PORT = 4444
        private const val MAX_CHILDREN = 500
        private const val DOTNET_LINE_BREAKPOINT_TYPE = "com.jetbrains.rider.debugger.breakpoint.DotNetLineBreakpointType"
        private val ACCESS_SPECIFIERS = setOf("public", "private", "protected")
        private val LOG = logger<CosmosDebugProcess>()
    }

    private val target = spec.target
    private val stopped = AtomicBoolean(false)

    @Volatile
    private var gdb: MiSession? = null

    @Volatile
    private var qmp: QmpClient? = null

    // Set once breakpoints are in and the guest has been let go; stops before
    // that belong to the attach itself.
    @Volatile
    private var started = false

    @Volatile
    private var running = false

    // An interrupt we sent ourselves to change breakpoints while the guest
    // runs; its stop is resumed instead of shown.
    @Volatile
    private var internalInterrupt = false

    // Completed by the stop at the kernel entry during start-up.
    @Volatile
    private var bootStop: CompletableFuture<MiTuple>? = null

    // The last few gdb log lines, to explain an aborted resume.
    private val recentLog = ArrayDeque<String>()

    private val bpLock = Any()
    private val breakpoints = ConcurrentHashMap.newKeySet<XLineBreakpoint<*>>()
    private val gdbNumbers = ConcurrentHashMap<XLineBreakpoint<*>, Int>()
    private val byNumber = ConcurrentHashMap<Int, XLineBreakpoint<*>>()
    private val pendingOps = ConcurrentLinkedQueue<() -> CompletableFuture<*>>()
    private val varRoots = ConcurrentLinkedQueue<String>()

    val liveViews = KernelLiveViews(::log)

    private val breakpointHandlers: Array<XBreakpointHandler<*>> = XBreakpointType.EXTENSION_POINT_NAME.extensionList
        .filter { it is XLineBreakpointType<*> && it.javaClass.name == DOTNET_LINE_BREAKPOINT_TYPE }
        .map { LineBreakpointHandler(it.javaClass) }
        .toTypedArray()

    @Suppress("UNCHECKED_CAST")
    private inner class LineBreakpointHandler(type: Class<*>) :
        XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<*>>>(
            type as Class<out XBreakpointType<XLineBreakpoint<XBreakpointProperties<*>>, *>>
        ) {
        override fun registerBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) = addBreakpoint(breakpoint)

        override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>, temporary: Boolean) =
            removeBreakpoint(breakpoint)
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = GdbEditorsProvider

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = breakpointHandlers

    override fun doGetProcessHandler(): ProcessHandler = cosmosHandler

    fun log(message: String) {
        cosmosHandler.notifyTextAvailable("[cosmos-debug] $message\n", ProcessOutputTypes.SYSTEM)
    }

    // ---- startup -------------------------------------------------------

    override fun sessionInitialized() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                startup()
            } catch (e: Exception) {
                if (!stopped.get()) {
                    LOG.info("Cosmos debug startup failed", e)
                    val message = "Failed to start cosmos debug: ${e.cause?.message ?: e.message}"
                    log(message)
                    session.reportError(message)
                    session.stop()
                }
            }
        }
    }

    private fun startup() {
        log("Debugging ${target.name} (${target.arch})")
        log("ELF: ${elf.absolutePath}")
        log("> ${spec.commandLine.commandLineString}")
        spec.notes.forEach(::log)
        if (breakpointHandlers.isEmpty()) {
            log("Rider's C# line breakpoint type was not found; breakpoints won't reach gdb.")
        }

        // Attach as soon as QEMU's gdbstub listens.
        if (!PortUtil.waitForPort(GDB_PORT, 5000) { stopped.get() || cosmosHandler.isProcessTerminated }) {
            if (stopped.get()) return
            throw IOException("QEMU gdbstub on port $GDB_PORT did not come up within 5000ms")
        }

        if (qmpPort != null) {
            connectQmp(qmpPort)
        } else {
            log("QMP port ${QMP_PORT} in use — live kernel views disabled this session.")
            liveViews.start(null)
        }

        val located = GdbLocator.locate(target.arch) ?: throw IOException(
            if (target.arch == "arm64") {
                "No gdb that can target aarch64 was found. Install a multiarch gdb (`cosmos install --auto --tools`, or `sudo apt install gdb-multiarch`)."
            } else {
                "gdb not found. Run `cosmos install --auto --tools`."
            }
        )
        log("GDB: ${located.path}${if (located.hasPython) "" else " (no Python — pretty-printers disabled)"}")

        val process = PlatformUtil.createCommandLine(located.path, "-q", "--interpreter=mi2", elf.absolutePath)
            .withWorkDirectory(target.projectDir)
            .createProcess()
        val mi = MiSession(process, MiListener())
        gdb = mi
        mi.start()
        if (stopped.get()) {
            mi.close()
            return
        }

        // Everything up to target-select runs before gdb talks to QEMU.
        // `osabi none` stops gdb asking the bare-metal stub for a TIB address,
        // which QEMU rejects.
        val setup = mutableListOf<CompletableFuture<*>>(
            mi.sendQuietly("-gdb-set mi-async on"),
            mi.sendQuietly("-environment-directory ${MiParser.quote(target.projectDir)}"),
            mi.sendQuietly("-enable-pretty-printing"),
            mi.send("-gdb-set osabi none"),
            if (target.arch == "arm64") mi.send("-gdb-set architecture aarch64")
            else mi.sendQuietly("-gdb-set disassembly-flavor intel")
        )
        if (located.hasPython) {
            prettyPrinterScript()?.let { script ->
                setup += mi.sendQuietly("-interpreter-exec console ${MiParser.quote("source $script")}")
            }
        }
        CompletableFuture.allOf(*setup.toTypedArray()).get(30, TimeUnit.SECONDS)
        mi.send("-target-select remote localhost:$GDB_PORT").get(30, TimeUnit.SECONDS)
        runToKernelEntry(mi)

        // The guest is halted: put every breakpoint in, then let it go.
        val inserted = HashSet<XLineBreakpoint<*>>()
        while (true) {
            val batch = synchronized(bpLock) {
                val todo = breakpoints.filter { it !in inserted }
                if (todo.isEmpty()) started = true
                todo
            }
            if (batch.isEmpty()) break
            inserted += batch
            CompletableFuture.allOf(*batch.map { insertBreakpoint(it).handle { _, _ -> null } }.toTypedArray())
                .get(30, TimeUnit.SECONDS)
        }
        if (stopped.get()) return
        running = true
        mi.send("-exec-continue")
    }

    /**
     * Software breakpoints are written into guest memory, and at the reset
     * vector the kernel's higher-half addresses aren't mapped yet: under KVM
     * the write fails and gdb aborts the resume. So boot to the kernel entry
     * on a one-shot hardware breakpoint first (Limine enters it with paging
     * on) and insert the real breakpoints from there.
     */
    private fun runToKernelEntry(mi: MiSession) {
        val entry = try {
            ElfSymbols.entryPoint(elf)
        } catch (_: Exception) {
            null
        } ?: return
        val address = "0x${java.lang.Long.toHexString(entry)}"
        try {
            mi.send("-break-insert -t -h *$address").get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log("No hardware breakpoint at the kernel entry ($address): ${e.cause?.message ?: e.message}; inserting breakpoints at reset.")
            return
        }
        val stop = CompletableFuture<MiTuple>()
        bootStop = stop
        try {
            running = true
            mi.send("-exec-continue").get(10, TimeUnit.SECONDS)
            stop.get(120, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            throw IOException("The kernel didn't reach its entry point ($address) within 120s")
        } catch (e: ExecutionException) {
            if (stopped.get()) return
            // The resume was aborted (e.g. no hardware breakpoint slot): the
            // guest is still at reset. Drop the entry breakpoint, the only one
            // so far, and insert the real ones from here.
            log("Could not run to the kernel entry (${e.cause?.message ?: e.message}); inserting breakpoints at reset.")
            mi.sendQuietly("-break-delete").get(10, TimeUnit.SECONDS)
        } finally {
            bootStop = null
        }
    }

    private fun connectQmp(port: Int) {
        log("QEMU launched with -qmp tcp:127.0.0.1:$port,server,nowait")
        try {
            if (!PortUtil.waitForPort(port, 5000) { stopped.get() }) throw IOException("QMP port $port did not come up")
            val client = QmpClient("127.0.0.1", port)
            client.connect(5000)
            qmp = client
            log("QMP connected on 127.0.0.1:$port")

            // Resolve the snapshot statics up front so the views never need a
            // gdb infcall.
            val symbols = try {
                ElfSymbols.resolve(elf, listOf(LiveReader.THREADS_SYMBOL, LiveReader.GC_SYMBOL, LiveReader.MEMORY_SYMBOL))
            } catch (e: Exception) {
                log("Could not read ELF symbols: ${e.message}")
                emptyMap()
            }
            fun report(label: String, symbol: String, missing: String) {
                val addr = symbols[symbol]
                log(if (addr != null) "$label statics at 0x${java.lang.Long.toHexString(addr)}" else "$label symbol not found — $missing")
            }
            report("DebugLiveSnapshot", LiveReader.THREADS_SYMBOL, "falling back to gdb infcall.")
            report("DebugLiveGCSnapshot", LiveReader.GC_SYMBOL, "GC live view will be empty.")
            report("DebugLiveMemorySnapshot", LiveReader.MEMORY_SYMBOL, "memory live view will be empty.")

            liveViews.start(
                LiveReader(client, symbols[LiveReader.THREADS_SYMBOL], symbols[LiveReader.GC_SYMBOL], symbols[LiveReader.MEMORY_SYMBOL])
            )
        } catch (e: Exception) {
            log("QMP unavailable: ${e.message}")
            qmp?.close()
            qmp = null
            liveViews.start(null)
        }
    }

    // The NativeAOT pretty-printers ship inside the plugin; gdb needs a file.
    private fun prettyPrinterScript(): String? {
        return try {
            val dir = File(PathManager.getTempPath(), "cosmos-gdb").apply { mkdirs() }
            val file = File(dir, "cosmos_prettyprint.py")
            val resource = CosmosDebugProcess::class.java.getResourceAsStream("/gdb/cosmos_prettyprint.py") ?: return null
            resource.use { input -> file.outputStream().use { input.copyTo(it) } }
            file.absolutePath.replace('\\', '/')
        } catch (e: Exception) {
            log("Could not extract pretty-printers: ${e.message}")
            null
        }
    }

    // ---- gdb events ----------------------------------------------------

    private inner class MiListener : MiSession.Listener {
        override fun onAsync(record: MiAsyncRecord) {
            if (record.kind != '*') return
            when (record.asyncClass) {
                "running" -> running = true
                "stopped" -> onStopped(record)
            }
        }

        override fun onStream(record: MiStreamRecord) {
            if (record.kind == '&') onLogLine(record.text.trim())
            // Console and target output are worth showing; the log stream
            // just echoes our own commands, so only its errors get through.
            val show = when (record.kind) {
                '~', '@' -> true
                '&' -> record.text.contains("error", ignoreCase = true) || record.text.contains("warning", ignoreCase = true)
                else -> false
            }
            if (show && record.text.isNotBlank()) {
                cosmosHandler.notifyTextAvailable(record.text.let { if (it.endsWith("\n")) it else it + "\n" }, ProcessOutputTypes.SYSTEM)
            }
        }

        override fun onExit(exitCode: Int) {
            if (!stopped.get()) {
                log("gdb exited with code $exitCode")
                session.stop()
            }
        }
    }

    private fun onStopped(record: MiAsyncRecord) {
        running = false
        if (!started) {
            bootStop?.complete(record.results)
            return
        }
        if (stopped.get()) return
        val results = record.results
        val reason = results.string("reason")

        if (reason == "exited" || reason == "exited-normally" || reason == "exited-signalled") {
            session.stop()
            return
        }

        val drained = drainPendingOps()
        if (internalInterrupt) {
            internalInterrupt = false
            // Our own interrupt: apply the breakpoint changes, then carry on.
            if (reason == null || reason == "signal-received") {
                drained.whenComplete { _, _ -> resumeQuietly() }
                return
            }
        }

        val threadId = results.int("thread-id") ?: 1
        val top = results.tuple("frame")?.let { GdbStackFrame.from(this, threadId, it) }
        val context = GdbSuspendContext(this, threadId, top)
        liveViews.onStopped { expression -> evaluate(expression, threadId) }

        if (reason == "breakpoint-hit") {
            val breakpoint = results.int("bkptno")?.let { byNumber[it] }
            if (breakpoint != null) {
                // False when the breakpoint doesn't suspend (log-only).
                if (!session.breakpointReached(breakpoint, null, context)) resume(context)
                return
            }
        }
        session.positionReached(context)
    }

    // gdb reports a resume it couldn't carry out (typically a breakpoint it
    // couldn't write) only as log text ending in "Command aborted.", after
    // already answering ^running. The guest never left its stop.
    private fun onLogLine(line: String) {
        if (line.isEmpty()) return
        val detail = synchronized(recentLog) {
            if (line != "Command aborted.") {
                recentLog.addLast(line)
                while (recentLog.size > 6) recentLog.removeFirst()
                return
            }
            recentLog.joinToString(" ").also { recentLog.clear() }
        }
        running = false
        val message = "gdb could not resume the kernel: ${detail.ifEmpty { "command aborted" }}"
        log(message)
        val boot = bootStop
        if (!started && boot != null) {
            boot.completeExceptionally(IOException(message))
            return
        }
        if (!started || stopped.get()) return
        session.reportError(message)
        // Show where it is still stopped.
        mi("-thread-info").whenComplete { record, _ ->
            val results = record?.results ?: return@whenComplete
            val threadId = results.int("current-thread-id") ?: 1
            val frame = results.list("threads")?.tuples()?.firstOrNull { it.int("id") == threadId }?.tuple("frame")
            session.positionReached(GdbSuspendContext(this, threadId, frame?.let { GdbStackFrame.from(this, threadId, it) }))
        }
    }

    // ---- breakpoints ---------------------------------------------------

    private fun addBreakpoint(breakpoint: XLineBreakpoint<*>) {
        synchronized(bpLock) {
            breakpoints.add(breakpoint)
            // Before start-up finishes, the start-up loop picks it up.
            if (!started) return
        }
        whenHalted { insertBreakpoint(breakpoint) }
    }

    private fun removeBreakpoint(breakpoint: XLineBreakpoint<*>) {
        synchronized(bpLock) { breakpoints.remove(breakpoint) }
        val number = gdbNumbers.remove(breakpoint) ?: return
        byNumber.remove(number)
        whenHalted { mi("-break-delete $number") }
    }

    private fun insertBreakpoint(breakpoint: XLineBreakpoint<*>): CompletableFuture<*> {
        val path = breakpoint.sourcePosition?.file?.path ?: VfsUtilCore.urlToPath(breakpoint.fileUrl)
        val location = "${FileUtil.toSystemDependentName(path)}:${breakpoint.line + 1}"
        val condition = breakpoint.conditionExpression?.expression?.takeIf { it.isNotBlank() }
        val command = buildString {
            append("-break-insert -f ")
            if (condition != null) append("-c ${MiParser.quote(condition)} ")
            append(MiParser.quote(location))
        }
        return mi(command).whenComplete { record, error ->
            val bkpt = record?.results?.tuple("bkpt")
            val number = bkpt?.int("number")
            if (error != null || number == null) {
                session.setBreakpointInvalid(breakpoint, error?.message ?: "gdb did not accept the breakpoint")
                return@whenComplete
            }
            if (breakpoint !in breakpoints) {
                // Removed while gdb was answering.
                mi("-break-delete $number")
                return@whenComplete
            }
            gdbNumbers[breakpoint] = number
            byNumber[number] = breakpoint
            if (bkpt.string("pending") != null) {
                session.setBreakpointInvalid(breakpoint, "No code for this line in ${elf.name}")
            } else {
                session.setBreakpointVerified(breakpoint)
            }
        }
    }

    // Breakpoint changes need the guest halted: QEMU's stub can't patch
    // memory while it runs. If it's running, interrupt it, apply the change
    // on the resulting stop and resume.
    private fun whenHalted(op: () -> CompletableFuture<*>) {
        if (!running) {
            op()
            return
        }
        pendingOps.add(op)
        synchronized(bpLock) {
            if (!internalInterrupt) {
                internalInterrupt = true
                gdb?.sendQuietly("-exec-interrupt")
            }
        }
    }

    private fun drainPendingOps(): CompletableFuture<*> {
        val futures = mutableListOf<CompletableFuture<*>>()
        while (true) {
            val op = pendingOps.poll() ?: break
            futures += op().handle { _, _ -> null }
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    // ---- run control ---------------------------------------------------

    override fun startStepOver(context: XSuspendContext?) = exec("-exec-next", context)

    override fun startStepInto(context: XSuspendContext?) = exec("-exec-step", context)

    override fun startStepOut(context: XSuspendContext?) = exec("-exec-finish", context)

    override fun resume(context: XSuspendContext?) = exec("-exec-continue", null)

    override fun startPausing() {
        // A user pause wins over a pending internal one: show the stop.
        internalInterrupt = false
        gdb?.sendQuietly("-exec-interrupt")
    }

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        val location = "${FileUtil.toSystemDependentName(position.file.path)}:${position.line + 1}"
        mi("-break-insert -t ${MiParser.quote(location)}").whenComplete { _, error ->
            if (error != null) {
                session.reportError("Cannot run to cursor: ${error.message}")
                context?.let { session.positionReached(it) }
            } else {
                exec("-exec-continue", context)
            }
        }
    }

    private fun exec(command: String, context: XSuspendContext?) {
        val mi = gdb ?: return
        clearVarRoots()
        running = true
        val thread = (context as? GdbSuspendContext)?.threadId
        val full = if (thread != null) "$command --thread $thread" else command
        mi.send(full).whenComplete { _, error ->
            if (error != null) {
                running = false
                session.reportError("${command.removePrefix("-exec-")} failed: ${error.message}")
                // Put the session back in its suspended state.
                context?.let { session.positionReached(it) }
            }
        }
    }

    private fun resumeQuietly() {
        running = true
        gdb?.sendQuietly("-exec-continue")
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        bootStop?.completeExceptionally(IOException("Debug session stopped"))
        ApplicationManager.getApplication().executeOnPooledThread {
            log("shutdown")
            liveViews.dispose()
            qmp?.close()
            gdb?.close()
            if (!cosmosHandler.isProcessTerminated) cosmosHandler.destroyProcess()
        }
    }

    // ---- gdb helpers for the frame model ---------------------------------

    fun mi(command: String): CompletableFuture<MiResultRecord> =
        gdb?.send(command) ?: CompletableFuture.failedFuture(IOException("gdb is not running"))

    fun createVarObj(expression: String, threadId: Int, frameLevel: Int): CompletableFuture<VarObj> =
        mi("-var-create --thread $threadId --frame $frameLevel - * ${MiParser.quote(expression)}").thenApply { record ->
            VarObj.from(record.results).also { varRoots.add(it.name) }
        }

    // Children of a varobj, with C++-style access-specifier pseudo children
    // ("public"/"private") flattened into their members.
    fun listChildren(varName: String): CompletableFuture<List<Pair<String, VarObj>>> =
        mi("-var-list-children --all-values ${MiParser.quote(varName)} 0 $MAX_CHILDREN").thenCompose { record ->
            val parts = record.results.list("children")?.tuples().orEmpty().map { child ->
                val v = VarObj.from(child)
                val exp = child.string("exp") ?: v.name.substringAfterLast('.')
                if (exp in ACCESS_SPECIFIERS && child.string("type") == null) {
                    listChildren(v.name)
                } else {
                    CompletableFuture.completedFuture(listOf(exp to v))
                }
            }
            CompletableFuture.allOf(*parts.toTypedArray()).thenApply { parts.flatMap { it.join() } }
        }

    private fun evaluate(expression: String, threadId: Int): CompletableFuture<String> =
        mi("-data-evaluate-expression --thread $threadId --frame 0 ${MiParser.quote(expression)}")
            .thenApply { it.results.string("value").orEmpty() }

    // Varobjs live in gdb until deleted; roots are dropped on every resume.
    private fun clearVarRoots() {
        val mi = gdb ?: return
        while (true) {
            val name = varRoots.poll() ?: break
            mi.sendQuietly("-var-delete ${MiParser.quote(name)}")
        }
    }

    // ---- UI ----------------------------------------------------------------

    override fun createTabLayouter(): XDebugTabLayouter = object : XDebugTabLayouter() {
        override fun registerAdditionalContent(ui: RunnerLayoutUi) {
            val project = session.project
            val tabs = listOf(
                Triple("CosmosKernelThreads", "Kernel Threads", KernelThreadsPanel(project, liveViews)),
                Triple("CosmosKernelGC", "Kernel GC", KernelGcPanel(project, liveViews)),
                Triple("CosmosKernelMemory", "Kernel Memory", KernelMemoryPanel(project, liveViews))
            )
            for ((id, title, panel) in tabs) {
                val content = ui.createContent(id, panel, title, CosmosIcons.Cosmos, null)
                content.isCloseable = false
                ui.addContent(content, 0, PlaceInGrid.center, false)
            }
        }
    }
}
