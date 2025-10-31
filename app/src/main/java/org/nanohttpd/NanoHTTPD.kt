package org.nanohttpd

import org.nanohttpd.response.Response
import org.nanohttpd.response.Status
import org.nanohttpd.threading.DefaultAsyncRunner
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.Socket
import java.net.URLDecoder
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory

abstract class NanoHTTPD(
    val hostname: String,
    val myPort: Int,
) {
    companion object {
        /**
         * 在 Socket.getInputStream().read() 上等待的最大时间（毫秒）
         * 这是必需的，因为 Keep-Alive HTTP 连接会永远阻塞 socket 读取线程
         * （或只要浏览器打开）。
         */
        const val SOCKET_READ_TIMEOUT = 5000

        /**
         * 动态内容的常见 MIME 类型：纯文本
         */
        const val MIME_PLAINTEXT = "text/plain"

        /**
         * 动态内容的常见 MIME 类型：html
         */
        const val MIME_HTML = "text/html"

        /**
         * 用于在参数映射中存储实际查询字符串的伪参数，以便稍后重新处理。
         */
        private const val QUERY_STRING_PARAMETER = "NanoHttpd.QUERY_STRING"

        /**
         * 用于记录的 logger
         */
        val LOG: Logger = Logger.getLogger(NanoHTTPD::class.java.name)

        /**
         * 映射 (String)FILENAME_EXTENSION -> (String)MIME_TYPE 的哈希表
         */
        @Volatile
        private var MIME_TYPES: Map<String, String> =
            hashMapOf(
                "css" to "text/css",
                "htm" to "text/html",
                "html" to "text/html",
                "xml" to "text/xml",
                "java" to "text/x-java-source, text/java",
                "md" to "text/plain",
                "txt" to "text/plain",
                "asc" to "text/plain",
                "gif" to "image/gif",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "png" to "image/png",
                "svg" to "image/svg+xml",
                "mp3" to "audio/mpeg",
                "m3u" to "audio/mpeg-url",
                "mp4" to "video/mp4",
                "ogv" to "video/ogg",
                "flv" to "video/x-flv",
                "mov" to "video/quicktime",
                "swf" to "application/x-shockwave-flash",
                "js" to "application/javascript",
                "pdf" to "application/pdf",
                "doc" to "application/msword",
                "ogg" to "application/x-ogg",
                "zip" to "application/octet-stream",
                "exe" to "application/octet-stream",
                "class" to "application/octet-stream",
                "m3u8" to "application/vnd.apple.mpegurl",
                "ts" to "video/mp2t",
            )

        fun mimeTypes(): Map<String, String> = MIME_TYPES

        /**
         * 从文件名扩展名获取 MIME 类型（如果可能）
         *
         * @param uri 表示文件的字符串
         * @return 连接的 mime/type
         */
        fun getMimeTypeForFile(uri: String): String {
            val dot = uri.lastIndexOf('.')
            return if (dot >= 0) {
                mimeTypes()[uri.substring(dot + 1).lowercase()] ?: "application/octet-stream"
            } else {
                "application/octet-stream"
            }
        }

        /**
         * 安全关闭可关闭对象
         */
        fun safeClose(closeable: Any?) {
            try {
                when (closeable) {
                    is Closeable -> closeable.close()
                    null -> return
                    else -> throw IllegalArgumentException("未知的关闭对象: ${closeable::class.java.name}")
                }
            } catch (e: IOException) {
                LOG.log(Level.SEVERE, "无法关闭", e)
            }
        }

        /**
         * 从 URL 解码参数，处理单个参数名称可能被提供多次的情况，
         * 通过返回值列表。通常这些列表将包含单个元素。
         *
         * @param parms 原始 NanoHTTPD 参数值，传递给 serve() 方法
         * @return String（参数名）到 List<String>（提供的值列表）的映射
         */
        protected fun decodeParameters(parms: Map<String, String>): Map<String, List<String>> =
            decodeParameters(parms[QUERY_STRING_PARAMETER])

        /**
         * 从 URL 解码参数，处理单个参数名称可能被提供多次的情况，
         * 通过返回值列表。通常这些列表将包含单个元素。
         *
         * @param queryString 从 URL 提取的查询字符串
         * @return String（参数名）到 List<String>（提供的值列表）的映射
         */
        protected fun decodeParameters(queryString: String?): Map<String, List<String>> {
            val parms = mutableMapOf<String, MutableList<String>>()
            queryString?.split("&")?.forEach { e ->
                val sep = e.indexOf('=')
                val propertyName =
                    if (sep >= 0) {
                        decodePercent(e.substring(0, sep)).trim()
                    } else {
                        decodePercent(e).trim()
                    }
                val propertyValue = if (sep >= 0) decodePercent(e.substring(sep + 1)) else null
                parms.getOrPut(propertyName) { mutableListOf() }.let { list ->
                    propertyValue?.let { list.add(it) }
                }
            }
            return parms
        }

        /**
         * 解码百分比编码的 String 值。
         *
         * @param str 百分比编码的 String
         * @return 输入的扩展形式，例如 "foo%20bar" 变为 "foo bar"
         */
        fun decodePercent(str: String): String =
            try {
                URLDecoder.decode(str, "UTF8")
            } catch (e: UnsupportedEncodingException) {
                LOG.log(Level.WARNING, "不支持编码，已忽略", e)
                str
            }
    }

    private var sslServerSocketFactory: SSLServerSocketFactory? = null
    private var sslProtocols: Array<String>? = null

    /**
     * 响应异常类
     */
    class ResponseException(
        val status: Status,
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    @Volatile
    private var myServerSocket: SSLServerSocket? = null

    fun getMyServerSocket(): SSLServerSocket? = myServerSocket

    private var myThread: Thread? = null

    /**
     * 异步执行请求的可插入策略。
     */
    var asyncRunner = DefaultAsyncRunner()

    /**
     * 强制关闭所有打开的连接。
     */
    @Synchronized
    fun closeAllConnections() {
        stop()
    }

    /**
     * 创建客户端处理程序的实例，子类可以返回 ClientHandler 的子类。
     */
    open fun createClientHandler(
        finalAccept: Socket,
        inputStream: InputStream,
    ): ClientHandler = ClientHandler(this, inputStream, finalAccept)

    /**
     * 实例化服务器 runnable，可以由子类覆盖以提供 ServerRunnable 的子类。
     */
    protected open fun createServerRunnable(timeout: Int): ServerRunnable = ServerRunnable(this, timeout)

    fun getListeningPort(): Int = myServerSocket?.localPort ?: -1

    fun isAlive(): Boolean = wasStarted() && myServerSocket?.isClosed == false && myThread?.isAlive == true

    /**
     * 在 start() 之前调用以通过 HTTPS 而不是 HTTP 提供服务
     */
    fun makeSecure(
        sslServerSocketFactory: SSLServerSocketFactory,
        sslProtocols: Array<String>?,
    ) {
        this.sslServerSocketFactory = sslServerSocketFactory
        this.sslProtocols = sslProtocols
    }

    /**
     * 覆盖此方法以自定义服务器。
     * （默认情况下，这返回 404 "Not Found" 纯文本错误响应。）
     */
    open fun serve(session: HTTPSession): Response =
        Response.Companion.newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")

    /**
     * 启动服务器。
     *
     * @throws IOException 如果 socket 正在使用中
     */
    @Throws(IOException::class)
    open fun start() {
        start(SOCKET_READ_TIMEOUT, true)
    }

    /**
     * 启动服务器。
     *
     * @param timeout 用于 socket 连接的超时时间
     * @param daemon 是否以守护线程模式启动线程
     * @throws IOException 如果 socket 正在使用中
     */
    @Throws(IOException::class)
    fun start(
        timeout: Int,
        daemon: Boolean,
    ) {
        myServerSocket = sslServerSocketFactory?.createServerSocket() as SSLServerSocket
        myServerSocket?.apply {
            enabledProtocols = sslProtocols ?: supportedProtocols
            useClientMode = false
            wantClientAuth = false
            needClientAuth = false
            reuseAddress = true
        }

        val serverRunnable = createServerRunnable(timeout)
        myThread =
            Thread(serverRunnable).apply {
                isDaemon = daemon
                name = "NanoHttpd Main Listener"
                start()
            }

        while (!serverRunnable.hasBinded() && serverRunnable.getBindException() == null) {
            try {
                Thread.sleep(10L)
            } catch (e: Throwable) {
                // 在 Android 上这可能不被允许，这就是为什么我们捕获 Throwable
                // 等待应该很短，因为我们只是在等待 socket 的绑定
            }
        }

        serverRunnable.getBindException()?.let { throw it }
    }

    /**
     * 停止服务器。
     */
    open fun stop() {
        try {
            safeClose(myServerSocket)
            asyncRunner.closeAll()
            myThread?.join()
        } catch (e: Exception) {
            LOG.log(Level.SEVERE, "无法停止所有连接", e)
        }
    }

    fun wasStarted(): Boolean = myServerSocket != null && myThread != null
}
