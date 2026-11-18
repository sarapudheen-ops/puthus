package com.bod3.app.errors

import com.bod3.app.obd.elm.ElmError
import com.bod3.app.obd.elm.ElmResponse
import com.bod3.app.obd.session.ObdRequestException
import com.bod3.app.obd.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorExplainerTest {

    private fun title(message: String) = ErrorExplainer.explain(message)?.title

    @Test
    fun everyAdapterErrorHasAnExplanation() {
        ElmError.entries.forEach { e ->
            val x = ErrorExplainer.explain(e)
            assertTrue(e.name, x.cause.isNotBlank() && x.fix.isNotBlank())
            assertEquals("description of ${e.name} maps back", x, ErrorExplainer.explain(e.description))
        }
    }

    @Test
    fun adapterReplies() {
        assertTrue(ErrorExplainer.explain("UNABLE TO CONNECT")!!.fix.contains("ignition on"))
        assertTrue(ErrorExplainer.explain("No protocol answered (tried 15)")!!.cause.contains("OBD2"))
        assertEquals(ErrorExplainer.explain(ElmError.CAN_ERROR), ErrorExplainer.explain("CAN ERROR"))
        assertEquals(ErrorExplainer.explain(ElmError.BUS_INIT_ERROR), ErrorExplainer.explain("BUS INIT: ...ERROR"))
        assertTrue(ErrorExplainer.explain("LV RESET")!!.fix.contains("charger"))
        assertEquals(ErrorExplainer.explain(ElmError.NO_DATA), ErrorExplainer.explain("NO DATA"))
        assertTrue(title("Timed out after 5000 ms")!!.contains("did not answer in time"))
    }

    @Test
    fun bluetoothProblems() {
        assertTrue(title("Could not connect to 00:11:22:33:44:55: read failed, socket might closed or timeout")!!.contains("could not be reached"))
        assertTrue(title("Connection lost (Read failed: Broken pipe) and 4 reconnect attempts failed.")!!.contains("dropped"))
        assertTrue(title("Bluetooth permission missing")!!.contains("permission"))
        val fromThrowable = ErrorExplainer.explain(TransportException("Connection to adapter closed", RuntimeException("x")))
        assertTrue(fromThrowable!!.title.contains("dropped"))
    }

    @Test
    fun negativeResponses() {
        assertTrue(title("Could not read 0600: refused: NRC 0x22 (conditions not correct)")!!.contains("Conditions not correct"))
        assertTrue(ErrorExplainer.explain("Security access failed: NRC 0x35 (invalid key)")!!.fix.contains("lock"))
        assertTrue(title("7F 2E 31")!!.contains("out of range"))
        assertTrue(ErrorExplainer.explain("NRC 0x7F (service not supported in active session)")!!.cause.contains("extended"))
        assertTrue(title("NRC 0x36")!!.contains("locked"))
    }

    @Test
    fun obdRequestExceptionUsesItsErrorCode() {
        val e = ObdRequestException(ElmError.BUFFER_FULL, ElmResponse("0100", "BUFFER FULL", listOf("BUFFER FULL"), ElmError.BUFFER_FULL))
        assertEquals(ErrorExplainer.explain(ElmError.BUFFER_FULL), ErrorExplainer.explain(e))
    }

    @Test
    fun fileProblems() {
        assertTrue(title("Not valid JSON: Unexpected 'x' at 12")!!.contains("not valid JSON"))
        assertTrue(title("formatVersion: 3 is newer than this app supports (1); update the app")!!.contains("newer version"))
    }

    @Test
    fun unknownMessagesAreLeftAlone() {
        assertNull(ErrorExplainer.explain("Something odd"))
        assertNull(ErrorExplainer.explain(""))
        assertNull(ErrorExplainer.explain(null as String?))
        assertEquals("Something odd", ErrorExplainer.describe("Something odd"))
        val described = ErrorExplainer.describe("CAN ERROR")
        assertTrue(described.startsWith("CAN ERROR\n"))
        assertNotNull(described.lines().firstOrNull { it.startsWith("Likely cause:") })
    }
}
