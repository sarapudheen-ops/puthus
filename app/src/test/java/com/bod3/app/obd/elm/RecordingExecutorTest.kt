package com.bod3.app.obd.elm

import com.bod3.app.obd.testing.ScriptedExecutor
import com.bod3.app.profile.RawLogFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingExecutorTest {

    @Test
    fun recordsOnlyWhileRecordingAndTracksHeaders() = runBlocking<Unit> {
        val inner = ScriptedExecutor(
            mapOf("ATSH 7E0" to "OK", "3E00" to "7E8027E00", "ATCP 18" to "OK", "ATSH DA10F1" to "OK", "22F190" to "NO DATA", "ATI" to "ELM327 v1.5"),
        )
        var now = 1_000L
        val recorder = RecordingExecutor(inner, clock = { now.also { now += 5 } })

        recorder.execute("ATI", 1_000)
        recorder.startRecording()
        recorder.execute("ATSH 7E0", 1_000)
        recorder.execute("3E00", 1_000)
        recorder.execute("ATCP 18", 1_000)
        recorder.execute("ATSH DA10F1", 1_000)
        recorder.execute("22F190", 1_000)
        val log = recorder.stopRecording()
        recorder.execute("ATI", 1_000)

        assertEquals(5, log.size)
        assertEquals(listOf(1, 2, 3, 4, 5), log.map { it.sequence })
        assertEquals("7E0", log[1].header)
        assertEquals("7E8027E00", log[1].response)
        assertEquals("18DA10F1", log[4].header)
        assertEquals("NO DATA", log[4].response)
        assertEquals(5L, log[0].durationMs)
        assertEquals(5, recorder.snapshot().size)

        val text = RawLogFormatter.format(log, "bod3 raw log")
        assertTrue(text, text.startsWith("bod3 raw log\n#1 +5ms [7E0] > ATSH 7E0"))
        assertTrue(text.contains("[7E0] > 3E00\n  < 7E8027E00"))
        assertEquals(listOf("3E00"), RawLogFormatter.forModule(log, "7e0").map { it.command })
    }

    @Test
    fun recordsErrorsAndCapsSize() = runBlocking<Unit> {
        val failing = object : CommandExecutor {
            override suspend fun execute(command: String, timeoutMs: Long): ElmResponse =
                if (command == "BAD") throw CommandTimeoutException(command, timeoutMs) else ElmResponseParser.parse(command, "OK")
        }
        val recorder = RecordingExecutor(failing, maxEntries = 3)
        recorder.startRecording()
        repeat(4) { recorder.execute("AT$it", 100) }
        runCatching { recorder.execute("BAD", 100) }
        val log = recorder.stopRecording()
        assertEquals(3, log.size)
        assertEquals("BAD", log.last().command)
        assertEquals("No reply to 'BAD' within 100 ms", log.last().error)
        assertNull(log.last().header)
        assertTrue(RawLogFormatter.format(log).contains("  ! No reply to 'BAD' within 100 ms"))
    }
}
