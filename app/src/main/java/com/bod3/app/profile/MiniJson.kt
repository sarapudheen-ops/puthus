package com.bod3.app.profile

/**
 * Minimal JSON parser (RFC 8259) returning Kotlin values: Map<String, Any?>, List<Any?>, String,
 * Double, Boolean or null. Enough for the vPIC API without pulling in a JSON library, and usable in
 * plain JVM unit tests (org.json is only a stub there).
 */
object MiniJson {

    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw ParseException("Unexpected trailing data at ${parser.position}")
        return value
    }

    private class Parser(private val text: String) {
        var position = 0

        fun atEnd() = position >= text.length

        fun skipWhitespace() {
            while (!atEnd() && text[position] in " \t\r\n") position++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw ParseException("Unexpected end of input")
            return when (val c = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw ParseException("Unexpected '$c' at $position")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            position++ // {
            skipWhitespace()
            if (peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw ParseException("Expected key at $position")
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> throw ParseException("Expected ',' or '}' at ${position - 1}")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = mutableListOf<Any?>()
            position++ // [
            skipWhitespace()
            if (peek() == ']') {
                position++
                return result
            }
            while (true) {
                result += readValue()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> throw ParseException("Expected ',' or ']' at ${position - 1}")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("Unterminated string")
                when (val c = text[position++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (atEnd()) throw ParseException("Unterminated escape")
                        when (val e = text[position++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw ParseException("Bad unicode escape")
                                val code = text.substring(position, position + 4).toIntOrNull(16)
                                    ?: throw ParseException("Bad unicode escape at $position")
                                builder.append(code.toChar())
                                position += 4
                            }
                            else -> throw ParseException("Bad escape '\\$e'")
                        }
                    }
                    else -> builder.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = position
            if (peek() == '-') position++
            while (!atEnd() && (text[position].isDigit() || text[position] in ".eE+-")) position++
            return text.substring(start, position).toDoubleOrNull() ?: throw ParseException("Bad number at $start")
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, position)) throw ParseException("Expected $word at $position")
            position += word.length
            return value
        }

        private fun peek(): Char? = if (atEnd()) null else text[position]

        private fun next(): Char? = if (atEnd()) null else text[position++]

        private fun expect(c: Char) {
            if (next() != c) throw ParseException("Expected '$c' at ${position - 1}")
        }
    }
}
