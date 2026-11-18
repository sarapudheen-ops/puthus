package com.bod3.app.packs

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded, strict UTF-8 input shared by every Android pack picker. The caller owns the stream. */
object PackTextReader {
    fun read(input: InputStream, maxBytes: Int = PackFiles.MAX_BYTES): String {
        require(maxBytes > 0) { "File size limit must be positive" }
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= maxBytes - bytes.size()) { "The file is larger than $maxBytes bytes (${maxBytes / 1024 / 1024} MiB limit)" }
            bytes.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}
