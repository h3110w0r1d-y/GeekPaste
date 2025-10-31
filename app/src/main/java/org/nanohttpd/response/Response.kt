package org.nanohttpd.response

import org.nanohttpd.NanoHTTPD
import org.nanohttpd.content.ContentType
import org.nanohttpd.request.Method
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.logging.Level
import java.util.zip.GZIPOutputStream

/**
 * HTTP 响应。从 serve() 返回其中一个。
 */
class Response(
    private var status: Status,
    private var mimeType: String?,
    private var data: InputStream?,
    private var contentLength: Long,
) : Closeable {
    private enum class GzipUsage {
        DEFAULT,
        ALWAYS,
        NEVER,
    }

    private val header = mutableMapOf<String, String>()
    private val lowerCaseHeader = mutableMapOf<String, String>()
    private var requestMethod: Method? = null
    private var chunkedTransfer: Boolean = false
    private var keepAlive: Boolean = false
    private var gzipUsage: GzipUsage = GzipUsage.DEFAULT

    init {
        if (data == null) {
            this.data = ByteArrayInputStream(ByteArray(0))
            this.contentLength = 0L
        }
        this.chunkedTransfer = this.contentLength < 0
    }

    /**
     * 添加 header 时自动更新小写映射
     */
    fun addHeader(
        name: String,
        value: String,
    ) {
        header[name] = value
        lowerCaseHeader[name.lowercase()] = value
    }

    /**
     * 指示在发送响应后关闭连接。
     */
    fun closeConnection(close: Boolean) {
        if (close) {
            header["connection"] = "close"
            lowerCaseHeader["connection"] = "close"
        } else {
            header.remove("connection")
            lowerCaseHeader.remove("connection")
        }
    }

    /**
     * @return 如果在此响应发送后要关闭连接，则返回 true
     */
    fun isCloseConnection(): Boolean = "close".equals(getHeader("connection"), ignoreCase = true)

    fun getData(): InputStream? = data

    fun getHeader(name: String): String? = lowerCaseHeader[name.lowercase()]

    fun getMimeType(): String? = mimeType

    fun getRequestMethod(): Method? = requestMethod

    fun getStatus(): Status = status

    fun setKeepAlive(useKeepAlive: Boolean) {
        keepAlive = useKeepAlive
    }

    fun setChunkedTransfer(chunkedTransfer: Boolean) {
        this.chunkedTransfer = chunkedTransfer
    }

    fun setData(data: InputStream?) {
        this.data = data
    }

    fun setMimeType(mimeType: String?) {
        this.mimeType = mimeType
    }

    fun setRequestMethod(requestMethod: Method?) {
        this.requestMethod = requestMethod
    }

    fun setStatus(status: Status) {
        this.status = status
    }

    fun setUseGzip(useGzip: Boolean): Response {
        gzipUsage = if (useGzip) GzipUsage.ALWAYS else GzipUsage.NEVER
        return this
    }

    /**
     * 如果强制使用 Gzip，则使用它。
     * 否则决定是否使用 Gzip。
     */
    fun useGzipWhenAccepted(): Boolean =
        when (gzipUsage) {
            GzipUsage.DEFAULT -> {
                mimeType?.lowercase()?.let {
                    it.contains("text/") || it.contains("/json")
                } ?: false
            }

            GzipUsage.ALWAYS -> true
            GzipUsage.NEVER -> false
        }

    /**
     * 将给定响应发送到 socket。
     */
    fun send(outputStream: OutputStream) {
        val gmtFrmt = SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        gmtFrmt.timeZone = TimeZone.getTimeZone("GMT")

        try {
            val contentType = ContentType(mimeType)
            val pw =
                PrintWriter(
                    BufferedWriter(
                        OutputStreamWriter(outputStream, contentType.getEncoding()),
                    ),
                    false,
                )

            pw.append("HTTP/1.1 ${status.requestStatus} ${status.description}\r\n")

            mimeType?.let { printHeader(pw, "Content-Type", it) }

            if (getHeader("date") == null) {
                printHeader(pw, "Date", gmtFrmt.format(Date()))
            }

            header.forEach { (key, value) -> printHeader(pw, key, value) }

            if (getHeader("connection") == null) {
                printHeader(pw, "Connection", if (keepAlive) "keep-alive" else "close")
            }

            if (getHeader("content-length") != null) {
                setUseGzip(false)
            }

            if (useGzipWhenAccepted()) {
                printHeader(pw, "Content-Encoding", "gzip")
                setChunkedTransfer(true)
            }

            var pending = data?.let { contentLength } ?: 0L

            if (requestMethod != Method.HEAD && chunkedTransfer) {
                printHeader(pw, "Transfer-Encoding", "chunked")
            } else if (!useGzipWhenAccepted()) {
                pending = sendContentLengthHeaderIfNotAlreadyPresent(pw, pending)
            }

            pw.append("\r\n")
            pw.flush()
            sendBodyWithCorrectTransferAndEncoding(outputStream, pending)
            outputStream.flush()
            NanoHTTPD.Companion.safeClose(data)
        } catch (ioe: IOException) {
//            NanoHTTPD.LOG.log(Level.SEVERE, "无法向客户端发送响应", ioe)
        }
    }

    private fun printHeader(
        pw: PrintWriter,
        key: String,
        value: String,
    ) {
        pw
            .append(key)
            .append(": ")
            .append(value)
            .append("\r\n")
    }

    private fun sendContentLengthHeaderIfNotAlreadyPresent(
        pw: PrintWriter,
        defaultSize: Long,
    ): Long {
        val contentLengthString = getHeader("content-length")
        val size = contentLengthString?.toLongOrNull() ?: defaultSize
        if (contentLengthString == null) {
            pw.print("Content-Length: $size\r\n")
        }
        return size
    }

    private fun sendBodyWithCorrectTransferAndEncoding(
        outputStream: OutputStream,
        pending: Long,
    ) {
        try {
            if (requestMethod != Method.HEAD && chunkedTransfer) {
                val chunkedOutputStream = ChunkedOutputStream(outputStream)
                sendBodyWithCorrectEncoding(chunkedOutputStream, -1)
                chunkedOutputStream.finish()
            } else {
                sendBodyWithCorrectEncoding(outputStream, pending)
            }
        } catch (_: Exception) {
            data?.close()
        }
    }

    private fun sendBodyWithCorrectEncoding(
        outputStream: OutputStream,
        pending: Long,
    ) {
        try {
            if (useGzipWhenAccepted()) {
                val gzipOutputStream = GZIPOutputStream(outputStream)
                sendBody(gzipOutputStream, -1)
                gzipOutputStream.finish()
            } else {
                sendBody(outputStream, pending)
            }
        } catch (_: Exception) {
            data?.close()
        }
    }

    /**
     * 将 body 发送到指定的 OutputStream。pending 参数限制发送的最大字节数，
     * 除非它是 -1，在这种情况下发送所有内容。
     */
    private fun sendBody(
        outputStream: OutputStream,
        pending: Long,
    ) {
        val BUFFER_SIZE = 16 * 1024L
        val buff = ByteArray(BUFFER_SIZE.toInt())
        val sendEverything = pending == -1L

        var remaining = pending
        while (remaining > 0 || sendEverything) {
            val bytesToRead = if (sendEverything) BUFFER_SIZE else minOf(remaining, BUFFER_SIZE)
            val read = data?.read(buff, 0, bytesToRead.toInt()) ?: 0

            if (read <= 0) break

            try {
                outputStream.write(buff, 0, read)
            } catch (_: Exception) {
                data?.close()
                return
            }

            if (!sendEverything) {
                remaining -= read
            }
        }
    }

    override fun close() {
        data?.close()
    }

    companion object {
        /**
         * 创建长度未知的响应（使用 HTTP 1.1 分块）。
         */
        fun newChunkedResponse(
            status: Status,
            mimeType: String?,
            data: InputStream?,
        ): Response = Response(status, mimeType, data, -1)

        fun newFixedLengthResponse(
            status: Status,
            mimeType: String?,
            data: ByteArray,
        ): Response =
            newFixedLengthResponse(
                status,
                mimeType,
                ByteArrayInputStream(data),
                data.size.toLong(),
            )

        /**
         * 创建已知长度的响应。
         */
        fun newFixedLengthResponse(
            status: Status,
            mimeType: String?,
            data: InputStream?,
            totalBytes: Long,
        ): Response = Response(status, mimeType, data, totalBytes)

        /**
         * 创建已知长度的文本响应。
         */
        fun newFixedLengthResponse(
            status: Status,
            mimeType: String?,
            txt: String?,
        ): Response {
            val contentType = ContentType(mimeType)
            if (txt == null) {
                return newFixedLengthResponse(
                    status,
                    mimeType,
                    ByteArrayInputStream(ByteArray(0)),
                    0,
                )
            }

            val bytes =
                try {
                    val encoder = Charset.forName(contentType.getEncoding()).newEncoder()
                    val finalContentType =
                        if (encoder.canEncode(txt)) contentType else contentType.tryUTF8()
                    txt.toByteArray(Charset.forName(finalContentType.getEncoding()))
                } catch (e: UnsupportedEncodingException) {
                    NanoHTTPD.Companion.LOG.log(Level.SEVERE, "编码问题，无响应", e)
                    ByteArray(0)
                }

            return newFixedLengthResponse(
                status,
                contentType.contentTypeHeader,
                ByteArrayInputStream(bytes),
                bytes.size.toLong(),
            )
        }

        /**
         * 创建已知长度的文本响应。
         */
        fun newFixedLengthResponse(msg: String): Response = newFixedLengthResponse(Status.OK, NanoHTTPD.Companion.MIME_HTML, msg)
    }
}
