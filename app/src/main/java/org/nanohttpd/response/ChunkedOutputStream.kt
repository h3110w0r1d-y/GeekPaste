package org.nanohttpd.response

import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * 自动按照分块传输将每次写入发送到包装的 OutputStream 的输出流：
 * http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6.1
 */
class ChunkedOutputStream(
    out: OutputStream,
) : FilterOutputStream(out) {
    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        if (len == 0) return
        out.write("%x\r\n".format(len).toByteArray())
        out.write(b, off, len)
        out.write("\r\n".toByteArray())
    }

    fun finish() {
        out.write("0\r\n\r\n".toByteArray())
    }
}
