package com.bod3.app.packs

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.MalformedInputException
import org.junit.Assert.*
import org.junit.Test

class PackTextReaderTest {
    @Test fun readsUtf8AndLeavesTheBomForFormatDetection() {
        val text = "\uFEFF{\"format\":\"bod3-vehicle-catalog\",\"name\":\"Citroën\"}"
        assertEquals(text, PackTextReader.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
        assertTrue(PackFiles.detect(text) is Detection.Known)
    }

    @Test fun enforcesBytesNotCharactersIncludingTheExactLimit() {
        val bytes = "éé".toByteArray(Charsets.UTF_8)
        assertEquals("éé", PackTextReader.read(ByteArrayInputStream(bytes), maxBytes = 4))
        assertThrows(IllegalArgumentException::class.java) { PackTextReader.read(ByteArrayInputStream(bytes), maxBytes = 3) }
    }

    @Test fun doesNotKeepReadingAnUnboundedContentProviderAfterTheLimit() {
        var read = 0
        val input = object : InputStream() {
            override fun read(): Int { read++; return 'x'.code }
        }
        assertThrows(IllegalArgumentException::class.java) { PackTextReader.read(input, maxBytes = 10_000) }
        assertTrue("Only the allowed bytes plus one read buffer are consumed", read <= 10_000 + 8192)
    }

    @Test fun refusesMalformedOrTruncatedUtf8InsteadOfReplacingBytes() {
        for (bytes in listOf(byteArrayOf(0xC3.toByte()), byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0xFF.toByte()))) {
            assertThrows(MalformedInputException::class.java) { PackTextReader.read(ByteArrayInputStream(bytes)) }
        }
    }
}
