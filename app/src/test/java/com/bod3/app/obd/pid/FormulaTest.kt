package com.bod3.app.obd.pid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaTest {

    private fun eval(formula: String, vararg bytes: Int) = Formula.parse(formula).evaluate(bytes.toList())

    @Test
    fun arithmeticAndPrecedence() {
        assertEquals(1726.0, eval("(A*256+B)/4", 0x1A, 0xF8)!!, 1e-9)
        assertEquals(14.0, eval("2+3*4")!!, 1e-9)
        assertEquals(20.0, eval("(2+3)*4")!!, 1e-9)
        assertEquals(-5.0, eval("-A+5", 10)!!, 1e-9)
        assertEquals(1.0, eval("7 % 3")!!, 1e-9)
        assertEquals(83.0, eval("A - 40", 0x7B)!!, 1e-9)
        assertEquals(0.5, eval("0x10 / 32")!!, 1e-9)
        assertEquals(0x1234.toDouble(), eval("A<<8 | B", 0x12, 0x34)!!, 1e-9)
        assertEquals(3.0, eval("A >> 4 & 0x3", 0xF5)!!, 1e-9)
    }

    @Test
    fun functions() {
        assertEquals(-2.0, eval("signed8(A)", 0xFE)!!, 1e-9)
        assertEquals(-100.0, eval("signed16(A*256+B)", 0xFF, 0x9C)!!, 1e-9)
        assertEquals(1.0, eval("bit(A, 7)", 0x80)!!, 1e-9)
        assertEquals(0.0, eval("bit(A, 0)", 0x80)!!, 1e-9)
        assertEquals(3.0, eval("max(min(A, 3), 1)", 9)!!, 1e-9)
        assertEquals(4.0, eval("round(abs(-3.6))")!!, 1e-9)
    }

    @Test
    fun bytesNeededAndShortReplies() {
        assertEquals(3, Formula.parse("A + C").bytesNeeded)
        assertEquals(0, Formula.parse("42").bytesNeeded)
        assertNull(eval("A*256+B", 0x12)) // reply too short
        assertNull(eval("A / B", 1, 0)) // division by zero
    }

    @Test
    fun errorsPointAtTheProblem() {
        val unknown = assertThrows(FormulaException::class.java) { Formula.parse("A + foo") }
        assertTrue(unknown.message!!, unknown.message!!.contains("Unknown name 'foo'"))
        assertEquals(4, unknown.position)
        assertTrue(assertThrows(FormulaException::class.java) { Formula.parse("(A+1") }.message!!.contains("Missing ')'"))
        assertTrue(assertThrows(FormulaException::class.java) { Formula.parse("A +") }.message!!.contains("ends unexpectedly"))
        assertTrue(assertThrows(FormulaException::class.java) { Formula.parse("min(A)") }.message!!.contains("needs 2 argument"))
        assertTrue(assertThrows(FormulaException::class.java) { Formula.parse("A B") }.message!!.contains("Unexpected 'B'"))
        assertTrue(assertThrows(FormulaException::class.java) { Formula.parse("  ") }.message!!.contains("Empty"))
        assertNull(Formula.parseOrNull("a+1")) // lower-case variables are not bytes
    }
}
