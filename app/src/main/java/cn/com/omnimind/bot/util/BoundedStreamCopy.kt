package cn.com.omnimind.bot.util

import java.io.InputStream
import java.io.OutputStream

internal class ContentSizeLimitExceededException : Exception("Content exceeds the allowed size")

internal object BoundedStreamCopy {
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return copied
            if (copied + read > maxBytes) {
                throw ContentSizeLimitExceededException()
            }
            output.write(buffer, 0, read)
            copied += read
        }
    }
}
