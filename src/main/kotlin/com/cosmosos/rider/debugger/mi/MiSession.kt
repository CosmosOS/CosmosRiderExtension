package com.cosmosos.rider.debugger.mi

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MiException(message: String) : Exception(message)

/**
 * A running gdb in MI mode. Commands are tagged with a token and answered
 * through a future; async records (stops, thread events) and stream output
 * go to [listener] on the reader thread.
 */
class MiSession(private val process: Process, private val listener: Listener) {

    interface Listener {
        fun onAsync(record: MiAsyncRecord)
        fun onStream(record: MiStreamRecord)
        fun onExit(exitCode: Int)
    }

    private val nextToken = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<MiResultRecord>>()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val closed = AtomicBoolean(false)

    val isAlive: Boolean
        get() = process.isAlive && !closed.get()

    fun start() {
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(::dispatch)
            } catch (_: IOException) {
                // stream closed with the process
            }
            val code = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            failPending("gdb exited")
            listener.onExit(code)
        }, "Cosmos gdb/MI reader").apply { isDaemon = true }.start()

        Thread({
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine {
                    listener.onStream(MiStreamRecord('&', it + "\n"))
                }
            } catch (_: IOException) {
            }
        }, "Cosmos gdb/MI stderr").apply { isDaemon = true }.start()
    }

    private fun dispatch(line: String) {
        when (val record = MiParser.parse(line)) {
            is MiResultRecord -> {
                val future = record.token?.let { pending.remove(it) }
                if (future != null) {
                    val error = record.errorMessage
                    if (error != null) future.completeExceptionally(MiException(error)) else future.complete(record)
                } else if (record.errorMessage != null) {
                    listener.onStream(MiStreamRecord('&', record.errorMessage + "\n"))
                }
            }
            is MiAsyncRecord -> listener.onAsync(record)
            is MiStreamRecord -> listener.onStream(record)
            MiPrompt -> {}
        }
    }

    /** Sends one MI command (with its leading dash) and answers its result record. */
    fun send(command: String): CompletableFuture<MiResultRecord> {
        val future = CompletableFuture<MiResultRecord>()
        if (!isAlive) {
            future.completeExceptionally(MiException("gdb is not running"))
            return future
        }
        val token = nextToken.getAndIncrement()
        pending[token] = future
        try {
            synchronized(writer) {
                writer.write("$token$command\n")
                writer.flush()
            }
        } catch (e: IOException) {
            pending.remove(token)
            future.completeExceptionally(MiException("gdb is not running: ${e.message}"))
        }
        return future
    }

    /** Sends a command and ignores its failure. */
    fun sendQuietly(command: String): CompletableFuture<MiResultRecord?> =
        send(command).handle { record, _ -> record }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            synchronized(writer) {
                writer.write("-gdb-exit\n")
                writer.flush()
            }
        } catch (_: IOException) {
        }
        try {
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        } catch (_: InterruptedException) {
            process.destroyForcibly()
        }
        failPending("gdb closed")
    }

    private fun failPending(reason: String) {
        for (token in pending.keys.toList()) {
            pending.remove(token)?.completeExceptionally(MiException(reason))
        }
    }
}
