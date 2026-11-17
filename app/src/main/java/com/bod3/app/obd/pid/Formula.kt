package com.bod3.app.obd.pid

/**
 * Arithmetic formulas for custom PIDs, e.g. `(A*256+B)/4` or `signed16(A*256+B)*0.1`.
 *
 * - Variables `A`..`Z` are the data bytes after the positive response echo (`A` = first data byte).
 * - Numbers: decimal (`0.25`) or hex (`0x1F`).
 * - Operators, by precedence: unary `-`, then `* / %`, `+ -`, `<< >>`, `&`, `^`, `|`. Parentheses group.
 * - Functions: `min(x,y)`, `max(x,y)`, `abs(x)`, `round(x)`, `floor(x)`, `signed8(x)`, `signed16(x)`,
 *   `signed32(x)`, `bit(x,n)` (0 or 1).
 *
 * [parse] validates the formula once and reports the position of the first error; [evaluate] returns
 * null when the reply has fewer bytes than the formula uses or the result is not a finite number.
 */
class Formula private constructor(val source: String, private val root: Node) {

    /** Number of data bytes the formula needs (highest variable index + 1). */
    val bytesNeeded: Int = root.maxVariable() + 1

    fun evaluate(bytes: List<Int>): Double? {
        if (bytes.size < bytesNeeded) return null
        val value = try {
            root.eval(bytes)
        } catch (e: ArithmeticException) {
            return null
        }
        return value.takeIf { it.isFinite() }
    }

    override fun toString(): String = source

    private sealed interface Node {
        fun eval(b: List<Int>): Double
        fun maxVariable(): Int
    }

    private class Num(val value: Double) : Node {
        override fun eval(b: List<Int>) = value
        override fun maxVariable() = -1
    }

    private class Var(val index: Int) : Node {
        override fun eval(b: List<Int>) = b[index].toDouble()
        override fun maxVariable() = index
    }

    private class Unary(val operand: Node) : Node {
        override fun eval(b: List<Int>) = -operand.eval(b)
        override fun maxVariable() = operand.maxVariable()
    }

    private class Binary(val op: String, val left: Node, val right: Node) : Node {
        override fun eval(b: List<Int>): Double {
            val l = left.eval(b)
            val r = right.eval(b)
            return when (op) {
                "+" -> l + r
                "-" -> l - r
                "*" -> l * r
                "/" -> if (r == 0.0) throw ArithmeticException("division by zero") else l / r
                "%" -> if (r == 0.0) throw ArithmeticException("division by zero") else l % r
                "<<" -> (l.toLong() shl r.toInt()).toDouble()
                ">>" -> (l.toLong() shr r.toInt()).toDouble()
                "&" -> (l.toLong() and r.toLong()).toDouble()
                "^" -> (l.toLong() xor r.toLong()).toDouble()
                "|" -> (l.toLong() or r.toLong()).toDouble()
                else -> error("unknown operator $op")
            }
        }

        override fun maxVariable() = maxOf(left.maxVariable(), right.maxVariable())
    }

    private class Call(val name: String, val args: List<Node>) : Node {
        override fun eval(b: List<Int>): Double {
            val v = args.map { it.eval(b) }
            return when (name) {
                "min" -> minOf(v[0], v[1])
                "max" -> maxOf(v[0], v[1])
                "abs" -> kotlin.math.abs(v[0])
                "round" -> kotlin.math.round(v[0])
                "floor" -> kotlin.math.floor(v[0])
                "signed8" -> v[0].toLong().toByte().toDouble()
                "signed16" -> v[0].toLong().toShort().toDouble()
                "signed32" -> v[0].toLong().toInt().toDouble()
                "bit" -> ((v[0].toLong() shr v[1].toInt()) and 1L).toDouble()
                else -> error("unknown function $name")
            }
        }

        override fun maxVariable() = args.maxOfOrNull { it.maxVariable() } ?: -1
    }

    private class Parser(val text: String) {
        var pos = 0

        fun fail(message: String): Nothing = throw FormulaException("$message at position ${pos + 1}", pos)

        fun skip() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun peek(token: String): Boolean {
            skip()
            return text.startsWith(token, pos)
        }

        fun accept(token: String): Boolean = peek(token).also { if (it) pos += token.length }

        fun parseAll(): Node {
            skip()
            if (pos >= text.length) fail("Empty formula")
            val node = binary(0)
            skip()
            if (pos < text.length) fail("Unexpected '${text[pos]}'")
            return node
        }

        // Lowest to highest precedence.
        val levels = listOf(listOf("|"), listOf("^"), listOf("&"), listOf("<<", ">>"), listOf("+", "-"), listOf("*", "/", "%"))

        fun binary(level: Int): Node {
            if (level == levels.size) return unary()
            var left = binary(level + 1)
            while (true) {
                val op = levels[level].firstOrNull { accept(it) } ?: return left
                left = Binary(op, left, binary(level + 1))
            }
        }

        fun unary(): Node = if (accept("-")) Unary(unary()) else if (accept("+")) unary() else primary()

        fun primary(): Node {
            skip()
            if (pos >= text.length) fail("Formula ends unexpectedly")
            val c = text[pos]
            return when {
                c == '(' -> {
                    pos++
                    val inner = binary(0)
                    if (!accept(")")) fail("Missing ')'")
                    inner
                }
                c.isDigit() || c == '.' -> number()
                c.isLetter() -> identifier()
                else -> fail("Unexpected '$c'")
            }
        }

        fun number(): Node {
            val start = pos
            if (text.startsWith("0x", pos) || text.startsWith("0X", pos)) {
                pos += 2
                val digits = start + 2
                while (pos < text.length && (text[pos].isDigit() || text[pos].uppercaseChar() in 'A'..'F')) pos++
                if (pos == digits) fail("Hex number without digits")
                return Num(text.substring(digits, pos).toLong(16).toDouble())
            }
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            val value = text.substring(start, pos).toDoubleOrNull() ?: run { pos = start; fail("Invalid number") }
            return Num(value)
        }

        fun identifier(): Node {
            val start = pos
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
            val name = text.substring(start, pos)
            if (name.length == 1 && name[0] in 'A'..'Z') return Var(name[0] - 'A')
            val arity = FUNCTIONS[name.lowercase()] ?: run { pos = start; fail("Unknown name '$name' (use A-Z for data bytes)") }
            if (!accept("(")) fail("Expected '(' after $name")
            val args = mutableListOf<Node>()
            if (!peek(")")) {
                do args += binary(0) while (accept(","))
            }
            if (!accept(")")) fail("Missing ')' after arguments of $name")
            if (args.size != arity) fail("$name needs $arity argument(s), got ${args.size}")
            return Call(name.lowercase(), args)
        }
    }

    companion object {
        private val FUNCTIONS = mapOf(
            "min" to 2, "max" to 2, "abs" to 1, "round" to 1, "floor" to 1,
            "signed8" to 1, "signed16" to 1, "signed32" to 1, "bit" to 2,
        )

        fun parse(source: String): Formula = Formula(source.trim(), Parser(source).parseAll())

        fun parseOrNull(source: String): Formula? = try {
            parse(source)
        } catch (e: FormulaException) {
            null
        }
    }
}

class FormulaException(message: String, val position: Int) : IllegalArgumentException(message)
