package com.cosmosos.rider.util

/**
 * Cleans raw command output before it reaches a console: strips ANSI escapes
 * and, when [joinWrappedLines] is set, re-joins lines the tool hard-wrapped at
 * 80 columns while keeping its real line breaks.
 */
class LogProcessor(
    private val joinWrappedLines: Boolean,
    private val sink: (String) -> Unit
) {
    private val buffer = StringBuilder()

    @Synchronized
    fun append(data: String) {
        buffer.append(data.replace(ANSI, ""))
        processBuffer()
    }

    private fun processBuffer() {
        while (true) {
            val match = NEWLINES.find(buffer) ?: break
            val index = match.range.first
            val nextCharIndex = match.range.last + 1

            // A newline at the very end needs the next chunk to tell a wrap
            // from a real break.
            if (joinWrappedLines && nextCharIndex >= buffer.length) break

            if (!joinWrappedLines) {
                flushTo(nextCharIndex)
                continue
            }

            // Cosmos build logs indent real lines with spaces; hard-wrapped
            // continuations (paths, commands) start at column 0.
            val nextChar = buffer[nextCharIndex]
            val isWrappedLine = nextChar != ' ' && nextChar != '\r' && nextChar != '\n'
            if (isWrappedLine) {
                sink(buffer.substring(0, index))
                buffer.delete(0, nextCharIndex)
            } else {
                flushTo(nextCharIndex)
            }
        }
    }

    private fun flushTo(end: Int) {
        sink(buffer.substring(0, end))
        buffer.delete(0, end)
    }

    @Synchronized
    fun flush() {
        if (buffer.isNotEmpty()) {
            sink(buffer.toString())
            buffer.setLength(0)
        }
    }

    companion object {
        private val ANSI = Regex("\u001b\\[[0-9;]*[A-Za-z]")
        private val NEWLINES = Regex("[\r\n]+")

        fun stripAnsi(s: String) = s.replace(ANSI, "")
    }
}
