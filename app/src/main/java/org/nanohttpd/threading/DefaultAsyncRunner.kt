package org.nanohttpd.threading

import org.nanohttpd.ClientHandler
import java.util.concurrent.CopyOnWriteArrayList

/**
 * NanoHTTPD 的默认线程策略。
 *
 * 默认情况下，服务器为每个传入请求生成一个新线程。
 * 这些线程设置为守护线程状态，并根据请求编号命名。名称在分析应用程序时很有用。
 */
class DefaultAsyncRunner {
    @Volatile
    private var requestCount: Long = 0

    private val running = CopyOnWriteArrayList<ClientHandler>()

    /**
     * @return 当前正在运行的客户端列表。
     */
    fun getRunning(): List<ClientHandler> = running.toList()

    fun closeAll() {
        // 并发时复制列表
        ArrayList(running).forEach { it.close() }
    }

    fun closed(clientHandler: ClientHandler) {
        running.remove(clientHandler)
    }

    fun exec(clientHandler: ClientHandler) {
        requestCount++
        running.add(clientHandler)
        createThread(clientHandler).start()
    }

    private fun createThread(clientHandler: ClientHandler): Thread =
        Thread(clientHandler).apply {
            isDaemon = true
            name = "NanoHttpd Request Processor (#$requestCount)"
        }
}
