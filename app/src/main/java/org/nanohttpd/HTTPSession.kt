package org.nanohttpd

import org.nanohttpd.request.Method
import org.nanohttpd.response.Response
import org.nanohttpd.response.Status
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.logging.Level
import javax.net.ssl.SSLException

class HTTPSession(
    private val httpd: NanoHTTPD,
    inputStream: InputStream,
    private val outputStream: OutputStream,
    inetAddress: InetAddress? = null,
) {
    companion object {
        const val BUFSIZE = 8192
    }

    private val inputStream = BufferedInputStream(inputStream, BUFSIZE)

    private var splitbyte = 0
    private var rlen = 0
    private var uri: String? = null
    private var method: Method? = null
    private var parms = mutableMapOf<String, MutableList<String>>()
    private var headers = mutableMapOf<String, String>()
    private var queryParameterString: String? = null
    private val remoteIp: String? =
        inetAddress?.let {
            if (it.isLoopbackAddress || it.isAnyLocalAddress) "127.0.0.1" else it.hostAddress
        }
    private var protocolVersion = "HTTP/1.1"

    init {
        if (inetAddress != null) {
            headers = mutableMapOf()
        }
    }

    /**
     * 解码发送的 header 并将数据加载到键值对中
     */
    private fun decodeHeader(
        bufin: BufferedReader,
        pre: MutableMap<String, String>,
        parms: MutableMap<String, MutableList<String>>,
        headers: MutableMap<String, String>,
    ) {
        try {
            // 读取请求行
            val inLine = bufin.readLine() ?: return

            val tokens = inLine.split("\\s+".toRegex())
            if (tokens.isEmpty()) {
                throw NanoHTTPD.ResponseException(
                    Status.BAD_REQUEST,
                    "BAD REQUEST: 语法错误。用法: GET /example/file.html",
                )
            }

            pre["method"] = tokens[0]

            if (tokens.size < 2) {
                throw NanoHTTPD.ResponseException(
                    Status.BAD_REQUEST,
                    "BAD REQUEST: 缺少 URI。用法: GET /example/file.html",
                )
            }

            var uri = tokens[1]

            // 从 URI 解码参数
            val qmi = uri.indexOf('?')
            if (qmi >= 0) {
                decodeParms(uri.substring(qmi + 1), parms)
                uri = NanoHTTPD.decodePercent(uri.substring(0, qmi))
            } else {
                uri = NanoHTTPD.decodePercent(uri)
            }

            // 如果有另一个 token，它是协议版本，然后是 HTTP header
            protocolVersion = tokens.getOrNull(2) ?: run {
                NanoHTTPD.LOG.log(Level.FINE, "未指定协议版本，奇怪。假设为 HTTP/1.1。")
                "HTTP/1.1"
            }

            var line = bufin.readLine()
            while (line != null && line.trim().isNotEmpty()) {
                val p = line.indexOf(':')
                if (p >= 0) {
                    headers[line.substring(0, p).trim().lowercase(Locale.US)] =
                        line.substring(p + 1).trim()
                }
                line = bufin.readLine()
            }

            pre["uri"] = uri
        } catch (ioe: IOException) {
            throw NanoHTTPD.ResponseException(
                Status.INTERNAL_ERROR,
                "服务器内部错误: IOException: ${ioe.message}",
                ioe,
            )
        }
    }

    /**
     * 解码百分比编码的 URI 格式参数（例如 "name=Jack%20Daniels&pass=Single%20Malt"）
     * 并将其添加到给定的 Map 中
     */
    private fun decodeParms(
        parms: String?,
        p: MutableMap<String, MutableList<String>>,
    ) {
        if (parms == null) {
            queryParameterString = ""
            return
        }

        queryParameterString = parms
        parms.split("&").forEach { e ->
            val sep = e.indexOf('=')
            val (key, value) =
                if (sep >= 0) {
                    NanoHTTPD.decodePercent(e.substring(0, sep)).trim() to
                        NanoHTTPD.decodePercent(e.substring(sep + 1))
                } else {
                    NanoHTTPD.decodePercent(e).trim() to ""
                }

            p.getOrPut(key) { mutableListOf() }.add(value)
        }
    }

    fun execute() {
        var r: Response? = null
        try {
            // 读取前 8192 字节
            // 完整的 header 应该适合这里
            // Apache 的默认 header 限制是 8KB
            // 不要假设一次读取就能获取整个 header！
            val buf = ByteArray(BUFSIZE)
            splitbyte = 0
            rlen = 0

            inputStream.mark(BUFSIZE)
            var read =
                try {
                    inputStream.read(buf, 0, BUFSIZE)
                } catch (e: SSLException) {
                    throw e
                } catch (_: IOException) {
                    NanoHTTPD.safeClose(inputStream)
                    NanoHTTPD.safeClose(outputStream)
                    throw SocketException("NanoHttpd Shutdown")
                }

            if (read == -1) {
                // socket 已关闭
                NanoHTTPD.safeClose(inputStream)
                NanoHTTPD.safeClose(outputStream)
                throw SocketException("NanoHttpd Shutdown")
            }

            while (read > 0) {
                rlen += read
                splitbyte = findHeaderEnd(buf, rlen)
                if (splitbyte > 0) {
                    break
                }
                read = inputStream.read(buf, rlen, BUFSIZE - rlen)
            }

            if (splitbyte < rlen) {
                inputStream.reset()
                inputStream.skip(splitbyte.toLong())
            }
            parms = mutableMapOf()
            if (headers.isEmpty()) {
                headers = mutableMapOf()
            } else {
                headers.clear()
            }

            // 创建 BufferedReader 用于解析 header
            val hin = BufferedReader(InputStreamReader(ByteArrayInputStream(buf, 0, rlen)))

            // 将 header 解码为 parms 和 header
            val pre = mutableMapOf<String, String>()
            decodeHeader(hin, pre, parms, headers)

            remoteIp?.let {
                headers["remote-addr"] = it
                headers["http-client-ip"] = it
            }

            method = Method.Companion.lookup(pre["method"])
            if (method == null) {
                throw NanoHTTPD.ResponseException(
                    Status.BAD_REQUEST,
                    "BAD REQUEST: 语法错误。HTTP 动词 ${pre["method"]} 未处理。",
                )
            }

            uri = pre["uri"]

            val connection = headers["connection"]
            val keepAlive =
                protocolVersion == "HTTP/1.1" &&
                    (connection == null || !connection.matches("(?i).*close.*".toRegex()))

            // 现在执行 serve()
            r = httpd.serve(this)

            val acceptEncoding = headers["accept-encoding"]
            r.setRequestMethod(method)
            if (acceptEncoding == null || !acceptEncoding.contains("gzip")) {
                r.setUseGzip(false)
            }
            r.setKeepAlive(keepAlive)
            r.send(outputStream)

            if (!keepAlive || r.isCloseConnection()) {
                throw SocketException("NanoHttpd Shutdown")
            }
        } catch (e: SocketException) {
            // 抛出以关闭 socket 对象
            throw e
        } catch (ste: SocketTimeoutException) {
            // 将 socket 超时视为 socket 异常
            // 即通过向上抛出异常来关闭流和 socket 对象
            throw ste
        } catch (_: SSLException) {
            NanoHTTPD.safeClose(outputStream)
        } catch (ioe: IOException) {
            val resp =
                Response.Companion.newFixedLengthResponse(
                    Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "服务器内部错误: IOException: ${ioe.message}",
                )
            resp.send(outputStream)
            NanoHTTPD.safeClose(outputStream)
        } catch (re: NanoHTTPD.ResponseException) {
            val resp =
                Response.Companion.newFixedLengthResponse(
                    re.status,
                    NanoHTTPD.MIME_PLAINTEXT,
                    re.message ?: "",
                )
            resp.send(outputStream)
            NanoHTTPD.safeClose(outputStream)
        } finally {
            NanoHTTPD.safeClose(r)
        }
    }

    /**
     * 查找分隔 header 和 body 的字节索引。它必须是前两个连续换行符的最后一个字节。
     */
    private fun findHeaderEnd(
        buf: ByteArray,
        rlen: Int,
    ): Int {
        var splitbyte = 0
        while (splitbyte + 1 < rlen) {
            // RFC2616
            if (buf[splitbyte] == '\r'.code.toByte() &&
                buf[splitbyte + 1] == '\n'.code.toByte() &&
                splitbyte + 3 < rlen &&
                buf[splitbyte + 2] == '\r'.code.toByte() &&
                buf[splitbyte + 3] == '\n'.code.toByte()
            ) {
                return splitbyte + 4
            }

            // 容错
            if (buf[splitbyte] == '\n'.code.toByte() && buf[splitbyte + 1] == '\n'.code.toByte()) {
                return splitbyte + 2
            }
            splitbyte++
        }
        return 0
    }

    fun getHeaders(): Map<String, String> = headers

    fun getBodySize(): Int {
        if (this.headers.containsKey("content-length")) {
            return this.headers["content-length"]?.toInt() ?: 0
        } else if (this.splitbyte < this.rlen) {
            return this.rlen - this.splitbyte
        }
        return 0
    }

    fun getBody(): String {
        try {
            var size = getBodySize()
            if (size > 1024) {
                return ""
            }
            val buf = ByteArray(size)
            while (this.rlen >= 0 && size > 0) {
                this.rlen = this.inputStream.read(buf, 0, size)
                size -= this.rlen
            }
            return buf.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            return ""
        }
    }

    fun getInputStream(): InputStream = inputStream

    fun getMethod(): Method? = method

    fun getParms(): Map<String, String> = parms.mapValues { it.value.firstOrNull() ?: "" }

    fun getParameters(): Map<String, List<String>> = parms

    fun getQueryParameterString(): String? = queryParameterString

    fun getUri(): String? = uri

    fun getRemoteIpAddress(): String? = remoteIp
}
