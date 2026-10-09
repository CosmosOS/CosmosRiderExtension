package com.cosmosos.rider.debugger

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal QEMU Machine Protocol client: the handshake, and guest memory reads
 * through the HMP `x` command. QMP is independent of the gdbstub, so reads
 * don't pause the guest. Calls are serialized; each waits for its answer.
 */
class QmpClient(private val host: String, private val port: Int) : AutoCloseable {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    @Synchronized
    fun connect(timeoutMs: Int = 5000) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = 10_000
        socket = s
        reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
        writer = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
        // Greeting first, then capabilities negotiation leaves command mode.
        readMessage(acceptGreeting = true)
        execute(JsonObject().apply { addProperty("execute", "qmp_capabilities") })
    }

    @Synchronized
    fun execute(command: JsonObject): JsonObject {
        val out = writer ?: throw IOException("QMP not connected")
        out.write(command.toString())
        out.write("\n")
        out.flush()
        return readMessage(acceptGreeting = false)
    }

    private fun readMessage(acceptGreeting: Boolean): JsonObject {
        val input = reader ?: throw IOException("QMP not connected")
        while (true) {
            val line = input.readLine() ?: throw IOException("QMP socket closed")
            if (line.isBlank()) continue
            val msg = try {
                JsonParser.parseString(line).asJsonObject
            } catch (_: Exception) {
                continue
            }
            if (msg.has("QMP")) {
                if (acceptGreeting) return msg
                continue
            }
            if (msg.has("event")) continue
            msg.getAsJsonObject("error")?.let { error ->
                throw IOException("QMP error: ${error.get("desc")?.asString ?: error.toString()}")
            }
            return msg
        }
    }

    /** Reads [length] bytes of guest VIRTUAL memory at [vaddr] (through the current vCPU's MMU). */
    fun readVirtual(vaddr: Long, length: Int): ByteArray {
        val response = execute(JsonObject().apply {
            addProperty("execute", "human-monitor-command")
            add("arguments", JsonObject().apply {
                addProperty("command-line", "x /${length}bx 0x${java.lang.Long.toHexString(vaddr)}")
            })
        })
        val text = response.get("return")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IOException("x returned non-string: $response")
        return parseMonitorHexDump(text, length)
    }

    @Synchronized
    override fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        reader = null
        writer = null
    }

    companion object {
        // HMP `x` output is "<address>: 0x01 0x00 0x5d ..." per line; take the
        // bytes in order until [expected] are collected.
        fun parseMonitorHexDump(text: String, expected: Int): ByteArray {
            val out = ByteArray(expected)
            var written = 0
            for (line in text.split(Regex("\r?\n"))) {
                val colon = line.indexOf(':')
                if (colon < 0) continue
                for (token in line.substring(colon + 1).trim().split(Regex("\\s+"))) {
                    if (!token.startsWith("0x")) continue
                    val value = token.substring(2).toIntOrNull(16) ?: continue
                    if (written >= expected) return out
                    out[written++] = value.toByte()
                }
            }
            return if (written < expected) out.copyOf(written) else out
        }
    }
}
