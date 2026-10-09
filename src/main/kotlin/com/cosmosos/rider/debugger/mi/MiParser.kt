package com.cosmosos.rider.debugger.mi

import java.io.ByteArrayOutputStream

// GDB/MI output model: https://sourceware.org/gdb/current/onlinedocs/gdb.html/GDB_002fMI-Output-Syntax.html

sealed interface MiValue

data class MiConst(val text: String) : MiValue

class MiTuple(val entries: List<Pair<String, MiValue>>) : MiValue {
    operator fun get(key: String): MiValue? = entries.firstOrNull { it.first == key }?.second

    fun string(key: String): String? = (get(key) as? MiConst)?.text

    fun int(key: String): Int? = string(key)?.toIntOrNull()

    fun tuple(key: String): MiTuple? = get(key) as? MiTuple

    fun list(key: String): MiList? = get(key) as? MiList

    // Unnamed tuples trailing a result, as gdb's mi2 emits for a breakpoint
    // with several locations: bkpt={...},{...},{...}
    fun unnamed(): List<MiTuple> = entries.filter { it.first.isEmpty() }.mapNotNull { it.second as? MiTuple }

    override fun toString() = entries.joinToString(",", "{", "}") { "${it.first}=${it.second}" }

    companion object {
        val EMPTY = MiTuple(emptyList())
    }
}

// Lists of results ("[frame={..},frame={..}]") keep only the values: the
// names are always the same word repeated.
class MiList(val items: List<MiValue>) : MiValue {
    fun tuples(): List<MiTuple> = items.filterIsInstance<MiTuple>()

    fun strings(): List<String> = items.filterIsInstance<MiConst>().map { it.text }

    override fun toString() = items.joinToString(",", "[", "]")
}

sealed interface MiRecord

// ^done / ^running / ^connected / ^error / ^exit
data class MiResultRecord(val token: Int?, val resultClass: String, val results: MiTuple) : MiRecord {
    val errorMessage: String?
        get() = if (resultClass == "error") results.string("msg") ?: "Unknown gdb error" else null
}

// *exec, +status, =notify
data class MiAsyncRecord(val token: Int?, val kind: Char, val asyncClass: String, val results: MiTuple) : MiRecord

// ~console, @target, &log; '?' marks a line that isn't MI at all.
data class MiStreamRecord(val kind: Char, val text: String) : MiRecord

object MiPrompt : MiRecord

object MiParser {

    fun parse(line: String): MiRecord {
        val trimmed = line.trimEnd('\r', '\n')
        if (trimmed.trim() == "(gdb)") return MiPrompt
        return try {
            Cursor(trimmed).parseRecord()
        } catch (_: Exception) {
            MiStreamRecord('?', trimmed)
        }
    }

    // Quotes a string as an MI c-string argument.
    fun quote(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private class Cursor(private val s: String) {
        private var i = 0

        fun parseRecord(): MiRecord {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            val token = if (i > start) s.substring(start, i).toIntOrNull() else null
            if (i >= s.length) return MiStreamRecord('?', s)

            return when (val kind = s[i]) {
                '^' -> {
                    i++
                    MiResultRecord(token, readIdentifier(), readResults())
                }
                '*', '+', '=' -> {
                    i++
                    MiAsyncRecord(token, kind, readIdentifier(), readResults())
                }
                '~', '@', '&' -> {
                    i++
                    MiStreamRecord(kind, readCString())
                }
                else -> MiStreamRecord('?', s)
            }
        }

        private fun readIdentifier(): String {
            val start = i
            while (i < s.length && s[i] != ',' && s[i] != '=' && s[i] != '{' && s[i] != '[' && s[i] != '}' && s[i] != ']') i++
            return s.substring(start, i)
        }

        // ("," result)* up to the end of the line
        private fun readResults(): MiTuple {
            val entries = mutableListOf<Pair<String, MiValue>>()
            while (i < s.length && s[i] == ',') {
                i++
                entries += readResult()
            }
            return MiTuple(entries)
        }

        // name=value, or a bare value where gdb omits the name
        private fun readResult(): Pair<String, MiValue> {
            if (i < s.length && (s[i] == '{' || s[i] == '[' || s[i] == '"')) {
                return "" to readValue()
            }
            val name = readIdentifier()
            expect('=')
            return name to readValue()
        }

        private fun readValue(): MiValue {
            check(i < s.length) { "value expected" }
            return when (s[i]) {
                '"' -> MiConst(readCString())
                '{' -> readTuple()
                '[' -> readList()
                else -> error("unexpected '${s[i]}' at $i")
            }
        }

        private fun readTuple(): MiTuple {
            expect('{')
            val entries = mutableListOf<Pair<String, MiValue>>()
            if (peek() == '}') {
                i++
                return MiTuple(entries)
            }
            while (true) {
                entries += readResult()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return MiTuple(entries)
                    }
                    else -> error("',' or '}' expected at $i")
                }
            }
        }

        private fun readList(): MiList {
            expect('[')
            val items = mutableListOf<MiValue>()
            if (peek() == ']') {
                i++
                return MiList(items)
            }
            while (true) {
                items += readResult().second
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return MiList(items)
                    }
                    else -> error("',' or ']' expected at $i")
                }
            }
        }

        // C string with escapes. Octal escapes are raw bytes (gdb escapes
        // non-ASCII that way), so everything is gathered as UTF-8 bytes and
        // decoded once at the end.
        fun readCString(): String {
            expect('"')
            val out = ByteArrayOutputStream()
            while (i < s.length) {
                // Plain runs are encoded whole so surrogate pairs survive.
                val runStart = i
                while (i < s.length && s[i] != '"' && s[i] != '\\') i++
                if (i > runStart) out.write(s.substring(runStart, i).toByteArray(Charsets.UTF_8))
                if (i >= s.length) break

                when (s[i++]) {
                    '"' -> return out.toString(Charsets.UTF_8)
                    '\\' -> {
                        check(i < s.length) { "dangling escape" }
                        when (val e = s[i++]) {
                            'n' -> out.write('\n'.code)
                            't' -> out.write('\t'.code)
                            'r' -> out.write('\r'.code)
                            'f' -> out.write(0x0c)
                            'b' -> out.write(0x08)
                            'v' -> out.write(0x0b)
                            'a' -> out.write(0x07)
                            'e' -> out.write(0x1b)
                            in '0'..'7' -> {
                                var value = e - '0'
                                var digits = 1
                                while (digits < 3 && i < s.length && s[i] in '0'..'7') {
                                    value = value * 8 + (s[i++] - '0')
                                    digits++
                                }
                                out.write(value and 0xff)
                            }
                            else -> out.write(e.toString().toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
            error("unterminated string")
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun expect(c: Char) {
            check(i < s.length && s[i] == c) { "'$c' expected at $i" }
            i++
        }
    }
}
