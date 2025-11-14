package com.h3110w0r1d.geekpaste.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.h3110w0r1d.geekpaste.data.config.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.collections.plus

/**
 * 下载任务状态
 */
enum class DownloadStatus {
    PENDING, // 等待下载
    DOWNLOADING, // 下载中
    COMPLETED, // 下载完成
    FAILED, // 下载失败
    CANCELLED, // 已取消
}

/**
 * 下载任务信息
 */
data class DownloadTask(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val url: String,
    var status: DownloadStatus = DownloadStatus.PENDING,
    var downloadedBytes: Long = 0,
    var error: String? = null,
    var savedPath: String? = null,
)

/**
 * 使用 OkHttp 实现的下载管理器，支持断点续传和进度回调
 */
class DownloadManager(
    private val context: Context,
    private val configManager: ConfigManager,
) {
    companion object {
        private const val LOG_TAG = "DownloadManager"
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private const val TEMP_FILE_SUFFIX = ".tmp"
    }

    private val appConfig = configManager.appConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloadTasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val downloadTasks: StateFlow<Map<String, DownloadTask>> = _downloadTasks

    // 保存每个任务的 OkHttp Call，用于取消
    private val activeCalls = mutableMapOf<String, Call>()
    private val activeJobs = mutableMapOf<String, Job>()

    /**
     * 添加下载任务
     * @param filesShareData 文件分享数据
     * @param serverPublicKey 服务器公钥（用于SSL验证）
     */
    fun addDownloadTasks(
        filesShareData: FilesShareData,
        serverPublicKey: PublicKey,
    ) {
        val tasks = mutableMapOf<String, DownloadTask>()

        filesShareData.files.forEach { fileInfo ->
            // 选择第一个可用的IP地址
            val ip = filesShareData.ips.firstOrNull() ?: return@forEach
            val url = "https://$ip:${filesShareData.port}${fileInfo.endpoint}"

            // 跳过已存在的任务
            if (_downloadTasks.value.containsKey(fileInfo.endpoint)) {
                return@forEach
            }

            val task =
                DownloadTask(
                    id = fileInfo.endpoint,
                    fileName = fileInfo.fileName,
                    fileSize = fileInfo.fileSize,
                    url = url,
                )
            tasks[task.id] = task
        }

        _downloadTasks.value += tasks
        Log.i(LOG_TAG, "添加 ${tasks.size} 个下载任务")

        // 开始下载所有任务
        tasks.values.forEach { task ->
            startDownload(task, serverPublicKey)
        }
    }

    /**
     * 开始下载单个文件
     */
    private fun startDownload(
        task: DownloadTask,
        serverPublicKey: PublicKey,
    ) {
        val job =
            scope.launch {
                try {
                    downloadFile(task, serverPublicKey)
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "下载文件失败: ${task.fileName}, 错误: ${e.message}", e)
                    updateTaskFailed(task.id, e.message ?: "未知错误")
                } finally {
                    activeCalls.remove(task.id)
                    activeJobs.remove(task.id)
                }
            }
        activeJobs[task.id] = job
    }

    /**
     * 下载文件（支持断点续传）
     */
    private suspend fun downloadFile(
        task: DownloadTask,
        serverPublicKey: PublicKey,
    ) {
        withContext(Dispatchers.IO) {
            // 更新状态为下载中
            updateTaskStatus(task.id, DownloadStatus.DOWNLOADING)
            Log.i(LOG_TAG, "开始下载文件: ${task.fileName}")

            // 创建 OkHttpClient，配置 SSL
            val client = createOkHttpClient(serverPublicKey)

            // 检查临时文件是否存在（支持断点续传）
            val tempFile = getTempFile(task.fileName)
            val downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L
            updateTaskProgress(task.id, downloadedBytes)

            // 构建请求，添加 Range 头支持断点续传
            val requestBuilder =
                Request
                    .Builder()
                    .url(task.url)

            if (downloadedBytes > 0) {
                requestBuilder.addHeader("Range", "bytes=$downloadedBytes-")
                Log.i(LOG_TAG, "断点续传: 已下载 $downloadedBytes 字节")
            }

            val request = requestBuilder.build()
            val call = client.newCall(request)
            activeCalls[task.id] = call

            // 执行请求
            val response = call.execute()

            if (!response.isSuccessful && response.code != 206) {
                throw IOException("HTTP错误: ${response.code}")
            }

            val contentLength = response.body.contentLength()
            val totalBytes = if (response.code == 206) downloadedBytes + contentLength else contentLength
            Log.i(LOG_TAG, "文件总大小: $totalBytes 字节，本次需要下载: $contentLength 字节")

            // 下载到临时文件
            response.body.byteStream().use { inputStream ->
                FileOutputStream(tempFile, downloadedBytes > 0).use { outputStream ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var bytesRead: Int
                    var currentBytes = downloadedBytes

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        // 检查是否被取消
                        if (_downloadTasks.value[task.id]?.status == DownloadStatus.CANCELLED) {
                            Log.i(LOG_TAG, "下载已取消: ${task.fileName}")
                            return@withContext
                        }

                        outputStream.write(buffer, 0, bytesRead)
                        currentBytes += bytesRead
                        updateTaskProgress(task.id, currentBytes)
                    }
                }
            }

            // 下载完成，保存到最终位置
            val savedPath = saveToFinalLocation(task.fileName, tempFile)
            updateTaskCompleted(task.id, savedPath)
            Log.i(LOG_TAG, "文件下载完成: ${task.fileName}, 保存路径: $savedPath")

            // 删除临时文件
            tempFile.delete()
        }
    }

    /**
     * 创建配置了 SSL 的 OkHttpClient
     */
    private fun createOkHttpClient(expectedPublicKey: PublicKey): OkHttpClient {
        // 创建自定义 TrustManager
        val trustManager =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) {
                    // 不需要验证客户端
                }

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) {
                    if (chain.isNullOrEmpty()) {
                        throw java.security.cert.CertificateException("证书链为空")
                    }

                    val serverCert = chain[0]
                    val serverPublicKey = serverCert.publicKey

                    if (!serverPublicKey.encoded.contentEquals(expectedPublicKey.encoded)) {
                        throw java.security.cert.CertificateException("服务器公钥不匹配")
                    }

                    Log.d(LOG_TAG, "服务器公钥验证成功")
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)

        return OkHttpClient
            .Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // 跳过主机名验证（因为使用IP地址）
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 获取临时文件
     */
    private fun getTempFile(fileName: String): File {
        val cacheDir = context.cacheDir
        return File(cacheDir, "$fileName$TEMP_FILE_SUFFIX")
    }

    private fun saveToFinalLocation(
        fileName: String,
        tempFile: File,
    ): String {
        val customPath = appConfig.value.downloadPath

        // 有自定义路径 → 尝试 SAF 保存
        if (customPath.isNotEmpty()) {
            runCatching {
                return saveToCustomLocation(fileName, tempFile, customPath)
            }.onFailure {
                Log.e(LOG_TAG, "保存到自定义路径失败，回退到默认路径: ${it.message}")
            }
        }

        // 回退到默认 Downloads 目录
        return saveToMediaStore(fileName, tempFile)
    }

    /** 保存到 MediaStore Downloads */
    private fun saveToMediaStore(
        fileName: String,
        tempFile: File,
    ): String {
        val resolver = context.contentResolver

        val contentValues =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

        val uri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception("无法创建文件")

        writeTempFileToUri(uri, tempFile)
        return uri.toString()
    }

    /** 保存到指定 SAF 文件夹 */
    private fun saveToCustomLocation(
        fileName: String,
        tempFile: File,
        dirUriString: String,
    ): String {
        val resolver = context.contentResolver
        val treeUri = dirUriString.toUri()

        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

        val mimeType = getMimeType(fileName)

        val newFileUri =
            DocumentsContract.createDocument(resolver, dirUri, mimeType, fileName)
                ?: throw Exception("无法在自定义路径创建文件")

        writeTempFileToUri(newFileUri, tempFile)
        return newFileUri.toString()
    }

    /** 公共写文件方法 */
    private fun writeTempFileToUri(
        uri: Uri,
        tempFile: File,
    ) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri)?.use { output ->
            tempFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: throw Exception("无法打开输出流")
    }

    /**
     * 根据文件名获取 MIME 类型
     */
    private fun getMimeType(fileName: String): String =
        when {
            fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
            fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
            fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            else -> "application/octet-stream"
        }

    /**
     * 更新任务状态
     */
    private fun updateTaskStatus(
        taskId: String,
        status: DownloadStatus,
    ) {
        _downloadTasks.value =
            _downloadTasks.value.toMutableMap().apply {
                this[taskId]?.status = status
            }
    }

    /**
     * 更新任务进度
     */
    private fun updateTaskProgress(
        taskId: String,
        downloadedBytes: Long,
    ) {
        _downloadTasks.value =
            _downloadTasks.value.toMutableMap().apply {
                this[taskId]?.downloadedBytes = downloadedBytes
            }
    }

    /**
     * 更新任务为完成状态
     */
    private fun updateTaskCompleted(
        taskId: String,
        savedPath: String,
    ) {
        _downloadTasks.value =
            _downloadTasks.value.toMutableMap().apply {
                this[taskId]?.let {
                    it.status = DownloadStatus.COMPLETED
                    it.savedPath = savedPath
                }
            }
    }

    /**
     * 更新任务为失败状态
     */
    private fun updateTaskFailed(
        taskId: String,
        error: String,
    ) {
        _downloadTasks.value =
            _downloadTasks.value.toMutableMap().apply {
                this[taskId]?.let {
                    it.status = DownloadStatus.FAILED
                    it.error = error
                }
            }
    }

    /**
     * 取消下载任务
     */
    fun cancelTask(taskId: String) {
        // 取消 OkHttp 请求
        activeCalls[taskId]?.cancel()
        activeCalls.remove(taskId)

        // 取消协程
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)

        // 更新状态
        _downloadTasks.value =
            _downloadTasks.value.toMutableMap().apply {
                this[taskId]?.status = DownloadStatus.CANCELLED
            }
        Log.i(LOG_TAG, "取消下载任务: $taskId")
    }

    /**
     * 重试失败的任务
     */
    fun retryTask(
        taskId: String,
        serverPublicKey: PublicKey,
    ) {
        val task = _downloadTasks.value[taskId] ?: return
        if (task.status != DownloadStatus.FAILED) return

        // 重置任务状态
        _downloadTasks.value =
            _downloadTasks.value.toMutableMap().apply {
                this[taskId]?.let {
                    it.status = DownloadStatus.PENDING
                    it.error = null
                }
            }

        // 开始下载
        startDownload(task, serverPublicKey)
    }

    /**
     * 清除已完成的任务
     */
    fun clearCompletedTasks() {
        _downloadTasks.value =
            _downloadTasks.value
                .filterNot { it.value.status == DownloadStatus.COMPLETED }
        Log.i(LOG_TAG, "清除已完成的下载任务")
    }

    /**
     * 清除所有任务
     */
    fun clearAllTasks() {
        // 取消所有正在进行的下载
        activeCalls.values.forEach { it.cancel() }
        activeCalls.clear()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()

        _downloadTasks.value = emptyMap()
        Log.i(LOG_TAG, "清除所有下载任务")
    }

    /**
     * 获取下载进度百分比
     */
    fun getProgress(taskId: String): Float {
        val task = _downloadTasks.value[taskId] ?: return 0f
        if (task.fileSize == 0L) return 0f
        return (task.downloadedBytes.toFloat() / task.fileSize.toFloat() * 100f)
    }
}
