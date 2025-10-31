package org.nanohttpd

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.logging.Level

/**
 * 用于主监听线程的 Runnable。
 */
class ServerRunnable(
    private val httpd: NanoHTTPD,
    private val timeout: Int,
) : Runnable {
    @Volatile
    private var bindException: IOException? = null

    @Volatile
    private var hasBinded = false

    override fun run() {
        try {
            httpd.getMyServerSocket()?.bind(
                InetSocketAddress(httpd.hostname, httpd.myPort),
            )
            hasBinded = true
        } catch (e: IOException) {
            bindException = e
            return
        }

        do {
            try {
                val finalAccept: Socket = httpd.getMyServerSocket()!!.accept()
                if (timeout > 0) {
                    finalAccept.soTimeout = timeout
                }
                val inputStream = finalAccept.getInputStream()
                httpd.asyncRunner.exec(httpd.createClientHandler(finalAccept, inputStream))
            } catch (e: IOException) {
                NanoHTTPD.LOG.log(Level.FINE, "与客户端的通信中断", e)
            }
        } while (!httpd.getMyServerSocket()!!.isClosed)
    }

    fun getBindException(): IOException? = bindException

    fun hasBinded(): Boolean = hasBinded
}
