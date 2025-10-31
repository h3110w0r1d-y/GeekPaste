package com.h3110w0r1d.geekpaste.utils

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.h3110w0r1d.geekpaste.model.DownloadStatus
import com.h3110w0r1d.geekpaste.model.FileDownloadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json.Default.decodeFromString
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import org.nanohttpd.HTTPSession
import org.nanohttpd.NanoHTTPD
import org.nanohttpd.response.Response
import org.nanohttpd.response.Response.Companion.newFixedLengthResponse
import org.nanohttpd.response.Status
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * 包装 FileInputStream 和 ParcelFileDescriptor，确保两者都能正确关闭
 * 当 InputStream 关闭时，同时关闭 ParcelFileDescriptor
 */
private class ParcelFileInputStream(
    private val pfd: ParcelFileDescriptor,
) : FileInputStream(pfd.fileDescriptor) {
    override fun close() {
        try {
            // 先关闭 FileInputStream（父类的 close）
            super.close()
        } finally {
            // 确保 ParcelFileDescriptor 也被关闭
            try {
                pfd.close()
            } catch (e: IOException) {
                // 忽略关闭时的异常，避免掩盖原始异常
                Log.w("WebServer", "关闭 ParcelFileDescriptor 失败", e)
            }
        }
    }
}

/**
 * 文件源抽象，支持物理文件和URI两种方式
 */
sealed class FileSource {
    abstract val fileName: String
    abstract val fileSize: Long

    abstract fun generateETag(): String

    /**
     * 物理文件
     */
    data class PhysicalFile(
        val file: File,
    ) : FileSource() {
        override val fileName: String get() = file.name
        override val fileSize: Long get() = file.length()

        override fun generateETag(): String {
            val etagString = "${file.absolutePath}${file.lastModified()}${file.length()}"
            return Integer.toHexString(etagString.hashCode())
        }
    }

    /**
     * URI文件（通过ContentProvider访问，无需创建临时文件）
     */
    data class UriFile(
        val uri: Uri,
        override val fileName: String,
        override val fileSize: Long,
    ) : FileSource() {
        override fun generateETag(): String {
            val etagString = "${uri}$fileSize"
            return Integer.toHexString(etagString.hashCode())
        }
    }
}

/**
 * 客户端上报下载进度的请求数据
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
private data class ProgressReportRequest(
    val endpoint: String,
    val downloadedBytes: Long = 0L,
)

/**
 * 端点信息，包含文件源和下载进度
 */
data class EndpointInfo(
    val endpoint: String,
    val fileSource: FileSource,
    val status: DownloadStatus = DownloadStatus.NOT_STARTED,
    val downloadedBytes: Long = 0L,
) {
    val fileName: String get() = fileSource.fileName
    val fileSize: Long get() = fileSource.fileSize

    /**
     * 根据下载字节数自动更新状态
     */
    fun updateProgress(newDownloadedBytes: Long): EndpointInfo {
        val newStatus =
            when {
                newDownloadedBytes >= fileSize && fileSize > 0 -> DownloadStatus.COMPLETED
                newDownloadedBytes > 0 -> DownloadStatus.DOWNLOADING
                else -> status // 保持当前状态
            }
        return copy(
            downloadedBytes = newDownloadedBytes.coerceIn(0, fileSize),
            status = newStatus,
        )
    }

    /**
     * 设置为下载中状态
     */
    fun setDownloading(): EndpointInfo = copy(status = DownloadStatus.DOWNLOADING)

    /**
     * 设置为失败状态
     */
    fun setFailed(): EndpointInfo = copy(status = DownloadStatus.FAILED)
}

class WebServer(
    port: Int,
    private val certManager: CertManager,
    private val context: Context,
) : NanoHTTPD("0.0.0.0", port) {
    // 存储每个endpoint的信息（包含文件源和下载进度）
    private val endpointInfoMap = ConcurrentHashMap<String, MutableStateFlow<EndpointInfo>>()

    /**
     * 添加一个 endpoint，用于传输指定的文件（兼容现有方式）
     * @param path endpoint 路径，例如 "/file"
     * @param file 要传输的文件
     */
    fun addEndpoint(
        path: String,
        file: File,
    ) {
        val normalizedPath = normalizePath(path)
        val fileSource = FileSource.PhysicalFile(file)
        val endpointInfo =
            EndpointInfo(
                endpoint = normalizedPath,
                fileSource = fileSource,
                status = DownloadStatus.NOT_STARTED,
            )
        endpointInfoMap[normalizedPath] = MutableStateFlow(endpointInfo)
        Log.d(TAG, "添加 endpoint (File): $normalizedPath -> ${file.absolutePath}")
    }

    /**
     * 添加一个 endpoint，从URI直接传输文件（无需创建临时文件）
     * @param path endpoint 路径，例如 "/file"
     * @param uri 文件的URI
     * @param fileName 文件名
     * @param fileSize 文件大小（字节）
     */
    fun addEndpointFromUri(
        path: String,
        uri: Uri,
        fileName: String,
        fileSize: Long,
    ) {
        val normalizedPath = normalizePath(path)
        val fileSource = FileSource.UriFile(uri, fileName, fileSize)
        val endpointInfo =
            EndpointInfo(
                endpoint = normalizedPath,
                fileSource = fileSource,
                status = DownloadStatus.NOT_STARTED,
            )
        endpointInfoMap[normalizedPath] = MutableStateFlow(endpointInfo)
        Log.d(TAG, "添加 endpoint (URI): $normalizedPath -> $uri ($fileName, $fileSize bytes)")
    }

    /**
     * 移除一个 endpoint
     * @param path endpoint 路径
     */
    fun removeEndpoint(path: String) {
        val normalizedPath = normalizePath(path)
        endpointInfoMap.remove(normalizedPath)
        Log.d(TAG, "移除 endpoint: $normalizedPath")
    }

    /**
     * 获取指定endpoint的下载进度StateFlow
     * @param endpoint endpoint路径
     * @return 下载进度的StateFlow（转换为FileDownloadProgress），如果endpoint不存在则返回null
     */
    fun getDownloadProgress(endpoint: String): StateFlow<FileDownloadProgress>? {
        val normalizedPath = normalizePath(endpoint)
        val endpointInfoFlow = endpointInfoMap[normalizedPath] ?: return null

        // 将 EndpointInfo 转换为 FileDownloadProgress
        return endpointInfoFlow
            .map { info ->
                FileDownloadProgress(
                    endpoint = info.endpoint,
                    status = info.status,
                    downloadedBytes = info.downloadedBytes,
                )
            }.stateIn(
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                started = SharingStarted.Eagerly,
                initialValue =
                    FileDownloadProgress(
                        endpoint = endpointInfoFlow.value.endpoint,
                        status = endpointInfoFlow.value.status,
                        downloadedBytes = endpointInfoFlow.value.downloadedBytes,
                    ),
            )
    }

    /**
     * 获取所有已注册的 endpoint
     */
    fun getEndpoints(): Set<String> = endpointInfoMap.keys.toSet()

    /**
     * 获取endpoint的文件源
     */
    private fun getFileSource(endpoint: String): FileSource? {
        val normalizedPath = normalizePath(endpoint)
        return endpointInfoMap[normalizedPath]?.value?.fileSource
    }

    /**
     * 启用 SSL 支持
     * 使用 certManager 生成或获取证书，并配置 SSL
     */
    fun enableSSL() {
        try {
            // 生成或获取证书（如果证书已存在且有效，会跳过生成）
            val keystoreFile = certManager.generateSelfSignedCertificate()

            // 加载 KeyStore（PKCS12 格式）
            val keyStore = KeyStore.getInstance("PKCS12")
            FileInputStream(keystoreFile).use { fis ->
                keyStore.load(fis, CertManager.DEFAULT_PASSWORD.toCharArray())
            }

            val keyManagerFactory =
                KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm(),
                )
            keyManagerFactory.init(keyStore, CertManager.DEFAULT_PASSWORD.toCharArray())

            val trustManagerFactory =
                TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm(),
                )
            trustManagerFactory.init(keyStore)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(
                keyManagerFactory.keyManagers,
                trustManagerFactory.trustManagers,
                null,
            )

            makeSecure(sslContext.serverSocketFactory, null)
            Log.d(TAG, "SSL 已启用，证书文件: ${keystoreFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "启用 SSL 失败", e)
            throw RuntimeException("启用 SSL 失败", e)
        }
    }

    override fun serve(session: HTTPSession): Response {
        val uri = session.getUri() ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error")
        val method = session.getMethod() ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error")

        Log.d(TAG, "收到请求: $method $uri")

        // 处理 /ping 端点
        if (uri == "/ping") {
            return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "ok")
        }

        // 处理 /progress 端点 - 客户端上报下载进度
        if (uri == "/progress" && method.name == "POST") {
            return handleProgressReport(session)
        }

        // 处理动态注册的 endpoint
        val normalizedUri = normalizePath(uri)
        val endpointInfoFlow = endpointInfoMap[normalizedUri]

        if (endpointInfoFlow != null) {
            val endpointInfo = endpointInfoFlow.value
            val fileSource = endpointInfo.fileSource

            // 在文件请求开始时，设置状态为下载中（如果不是 HEAD 请求）
            if (method.name != "HEAD" && endpointInfo.status == DownloadStatus.NOT_STARTED) {
                endpointInfoFlow.value = endpointInfo.setDownloading()
                Log.d(TAG, "文件请求开始，设置状态为下载中: ${endpointInfo.fileName}")
            }

            // 验证物理文件（如果是PhysicalFile）
            if (fileSource is FileSource.PhysicalFile) {
                val file = fileSource.file
                if (!file.exists()) {
                    Log.w(TAG, "文件不存在: ${file.absolutePath}，移除 endpoint")
                    removeEndpoint(normalizedUri)
                    return newFixedLengthResponse(
                        Status.NOT_FOUND,
                        MIME_PLAINTEXT,
                        "文件不存在",
                    )
                }

                if (!file.isFile) {
                    Log.w(TAG, "路径不是文件: ${file.absolutePath}，移除 endpoint")
                    removeEndpoint(normalizedUri)
                    return newFixedLengthResponse(
                        Status.BAD_REQUEST,
                        MIME_PLAINTEXT,
                        "路径不是文件",
                    )
                }
            }

            try {
                val mimeType = getMimeTypeForFile(fileSource.fileName)
                val fileLength = fileSource.fileSize
                val etag = fileSource.generateETag()

                var startFrom = 0L
                var endAt = -1L
                val rangeHeader = session.getHeaders()["range"]
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val range = rangeHeader.substring("bytes=".length)
                    val minus = range.indexOf('-')
                    try {
                        if (minus > 0) {
                            startFrom = range.substring(0, minus).toLong()
                            val endStr = range.substring(minus + 1)
                            if (endStr.isNotEmpty()) {
                                endAt = endStr.toLong()
                            }
                        }
                    } catch (_: NumberFormatException) {
                        // 忽略解析错误，保持默认值
                    }
                }

                // get if-range header. If present, it must match etag or else we
                // should ignore the range request
                val ifRangeHeader = session.getHeaders()["if-range"]
                val headerIfRangeMissingOrMatching =
                    ifRangeHeader == null || etag == ifRangeHeader

                val ifNoneMatchHeader = session.getHeaders()["if-none-match"]
                val headerIfNoneMatchPresentAndMatching =
                    ifNoneMatchHeader != null && ("*" == ifNoneMatchHeader || ifNoneMatchHeader == etag)

                // Change return code and add Content-Range header when skipping is
                // requested
                val response: Response

                if (headerIfRangeMissingOrMatching && rangeHeader != null && startFrom >= 0 && startFrom < fileLength) {
                    // range request that matches current etag
                    // and the startFrom of the range is satisfiable
                    if (headerIfNoneMatchPresentAndMatching) {
                        // range request that matches current etag
                        // and the startFrom of the range is satisfiable
                        // would return range from file
                        // respond with not-modified
                        response = newFixedLengthResponse(Status.NOT_MODIFIED, mimeType, "")
                        response.addHeader("ETag", etag)
                        Log.d(TAG, "返回 304 Not Modified (range + if-none-match): ${fileSource.fileName}")
                        return response
                    } else {
                        if (endAt < 0) {
                            endAt = fileLength - 1
                        }
                        var newLen = endAt - startFrom + 1
                        if (newLen < 0) {
                            newLen = 0
                        }

                        val fileInputStream = openInputStream(fileSource)
                        fileInputStream.skip(startFrom)

                        response =
                            newFixedLengthResponse(
                                Status.PARTIAL_CONTENT,
                                mimeType,
                                fileInputStream,
                                newLen,
                            )
                        response.addHeader("Accept-Ranges", "bytes")
                        response.addHeader("Content-Length", newLen.toString())
                        response.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLength")
                        response.addHeader("ETag", etag)
                        Log.d(
                            TAG,
                            "返回部分内容: ${fileSource.fileName} ($startFrom-$endAt/$fileLength, $newLen bytes)",
                        )
                        return response
                    }
                } else {
                    if (headerIfRangeMissingOrMatching && rangeHeader != null && startFrom >= fileLength) {
                        // return the size of the file
                        // 4xx responses are not trumped by if-none-match
                        response = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                        response.addHeader("Content-Range", "bytes */$fileLength")
                        response.addHeader("ETag", etag)
                        Log.d(TAG, "返回 416 Range Not Satisfiable: ${fileSource.fileName}")
                        return response
                    } else if (rangeHeader == null && headerIfNoneMatchPresentAndMatching) {
                        // full-file-fetch request
                        // would return entire file
                        // respond with not-modified
                        response = newFixedLengthResponse(Status.NOT_MODIFIED, mimeType, "")
                        response.addHeader("ETag", etag)
                        Log.d(TAG, "返回 304 Not Modified (no range): ${fileSource.fileName}")
                        return response
                    } else if (!headerIfRangeMissingOrMatching && headerIfNoneMatchPresentAndMatching) {
                        // range request that doesn't match current etag
                        // would return entire (different) file
                        // respond with not-modified
                        response = newFixedLengthResponse(Status.NOT_MODIFIED, mimeType, "")
                        response.addHeader("ETag", etag)
                        Log.d(TAG, "返回 304 Not Modified (if-range mismatch): ${fileSource.fileName}")
                        return response
                    } else {
                        // supply the file
                        val fileInputStream = openInputStream(fileSource)
                        response = newFixedLengthResponse(Status.OK, mimeType, fileInputStream, fileLength)
                        response.addHeader("Accept-Ranges", "bytes")
                        response.addHeader("Content-Length", fileLength.toString())
                        response.addHeader("ETag", etag)
                        response.addHeader("Content-Disposition", "attachment; filename=\"${fileSource.fileName}\"")
                        Log.d(TAG, "返回完整文件: ${fileSource.fileName} ($fileLength bytes)")
                        return response
                    }
                }
            } catch (e: Exception) {
                // 处理其他可能的异常（如 SecurityException 等）
                Log.e(TAG, "处理文件请求时发生异常: ${fileSource.fileName}，移除 endpoint", e)
                removeEndpoint(normalizedUri)
                return newFixedLengthResponse(
                    Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "处理文件请求失败: ${e.message}",
                )
            }
        }

        // 未找到匹配的 endpoint
        return newFixedLengthResponse(
            Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Not Found",
        )
    }

    /**
     * 打开文件输入流，支持物理文件和URI两种方式
     * 对于URI文件，使用包装类确保 ParcelFileDescriptor 也能被正确关闭
     */
    private fun openInputStream(fileSource: FileSource): FileInputStream =
        when (fileSource) {
            is FileSource.PhysicalFile -> {
                FileInputStream(fileSource.file)
            }
            is FileSource.UriFile -> {
                var pfd: ParcelFileDescriptor? = null
                try {
                    pfd = context.contentResolver.openFileDescriptor(fileSource.uri, "r")
                        ?: throw IOException("无法打开文件描述符: ${fileSource.uri}")
                    // 使用包装类，确保关闭时会同时关闭 ParcelFileDescriptor
                    ParcelFileInputStream(pfd).also {
                        // 将 pfd 设置为 null，避免在包装类中重复关闭
                        // 包装类会持有 pfd 的引用并在 close() 时关闭它
                        pfd = null
                    }
                } catch (e: Exception) {
                    // 如果发生异常，确保关闭已创建的 ParcelFileDescriptor
                    pfd?.close()
                    Log.e(TAG, "打开URI文件失败: ${fileSource.uri}", e)
                    throw IOException("打开URI文件失败: ${e.message}", e)
                }
            }
        }

    /**
     * 处理客户端上报的下载进度
     * 请求体格式：{"endpoint": "/share/xxx", "downloadedBytes": 1024}
     * 状态会自动根据 downloadedBytes 和文件大小判断（当 downloadedBytes >= fileSize 时自动设置为完成）
     */
    private fun handleProgressReport(session: HTTPSession): Response {
        try {
            // 读取请求体
            val contentLength = session.getBodySize()
            if (contentLength <= 0) {
                return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "缺少请求体")
            }

            val bodyStr = session.getBody()
            val request =
                try {
                    decodeFromString<ProgressReportRequest>(bodyStr)
                } catch (e: Exception) {
                    Log.e(TAG, "解析JSON失败: $bodyStr", e)
                    return newFixedLengthResponse(
                        Status.BAD_REQUEST,
                        MIME_PLAINTEXT,
                        "无效的JSON格式: ${e.message}",
                    )
                }

            val endpoint = request.endpoint
            val downloadedBytes = request.downloadedBytes

            // 更新进度
            val normalizedPath = normalizePath(endpoint)
            val endpointInfoFlow = endpointInfoMap[normalizedPath]

            if (endpointInfoFlow != null) {
                // 使用 updateProgress 方法自动判断状态
                val updatedInfo = endpointInfoFlow.value.updateProgress(downloadedBytes)
                endpointInfoFlow.value = updatedInfo
                Log.d(TAG, "更新下载进度: $normalizedPath, 状态: ${updatedInfo.status}, 已下载: $downloadedBytes/${updatedInfo.fileSize} bytes")
                return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "ok")
            } else {
                Log.w(TAG, "未找到endpoint的进度记录: $normalizedPath")
                return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "endpoint不存在")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理进度上报失败", e)
            return newFixedLengthResponse(
                Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "处理失败: ${e.message}",
            )
        }
    }

    /**
     * 规范化路径，确保以 / 开头
     */
    private fun normalizePath(path: String): String = if (path.startsWith("/")) path else "/$path"

    override fun start() {
        try {
            enableSSL()
            super.start()
            Log.d(TAG, "Web 服务器已启动，端口: ${getListeningPort()}")
        } catch (e: IOException) {
            Log.e(TAG, "启动 Web 服务器失败", e)
            throw e
        }
    }

    override fun stop() {
        super.stop()
        endpointInfoMap.clear()
        Log.d(TAG, "Web 服务器已停止")
    }

    companion object {
        private const val TAG = "WebServer"
    }
}
