package org.nanohttpd

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.logging.Level

/**
 * 用于每个新客户端连接的 Runnable。
 */
class ClientHandler(
    private val httpd: NanoHTTPD,
    private val inputStream: InputStream,
    private val acceptSocket: Socket,
) : Runnable {
    fun close() {
        NanoHTTPD.safeClose(inputStream)
        NanoHTTPD.safeClose(acceptSocket)
    }

    override fun run() {
        var outputStream: OutputStream? = null
        try {
            outputStream = acceptSocket.getOutputStream()
            val session =
                HTTPSession(
                    httpd,
                    inputStream,
                    outputStream,
                    acceptSocket.inetAddress,
                )

            while (!acceptSocket.isClosed) {
                session.execute()
            }
        } catch (e: Exception) {
            // 当客户端关闭 socket 时，我们抛出自己的 SocketException
            // 以打破上面的 "keep alive" 循环。如果异常不是预期的 SocketException
            // 或 SocketTimeoutException，则打印堆栈跟踪
            if (!(e is SocketException && "NanoHttpd Shutdown" == e.message) &&
                !(e is SocketTimeoutException)
            ) {
                NanoHTTPD.LOG.log(Level.SEVERE, "与客户端的通信中断，或处理程序代码中存在错误", e)
            }
        } finally {
            NanoHTTPD.safeClose(outputStream)
            NanoHTTPD.safeClose(inputStream)
            NanoHTTPD.safeClose(acceptSocket)
            httpd.asyncRunner.closed(this)
        }
    }
}
