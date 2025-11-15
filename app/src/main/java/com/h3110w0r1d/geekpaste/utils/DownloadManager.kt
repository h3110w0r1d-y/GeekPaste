package com.h3110w0r1d.geekpaste.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.h3110w0r1d.geekpaste.R
import com.h3110w0r1d.geekpaste.data.config.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    val serverBaseUrl: String? = null, // 服务器基础 URL (用于回报进度)
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

        // 通知相关常量
        private const val NOTIFICATION_CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID_BASE = 1000
    }

    private val appConfig = configManager.appConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloadTasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val downloadTasks: StateFlow<Map<String, DownloadTask>> = _downloadTasks

    // 保存每个任务的 OkHttp Call，用于取消
    private val activeCalls = mutableMapOf<String, Call>()
    private val activeJobs = mutableMapOf<String, Job>()

    // 通知管理器
    private val notificationManager = NotificationManagerCompat.from(context)

    // 用于存储每个任务的通知ID
    private val notificationIds = mutableMapOf<String, Int>()
    private var nextNotificationId = NOTIFICATION_ID_BASE

    // 用于限流通知更新（记录上次更新时间）
    private val lastNotificationUpdateTime = mutableMapOf<String, Long>()
    private val notificationUpdateInterval = 500L // 500ms 更新一次通知

    // 用于限流进度回报（记录上次回报时间）
    private val lastProgressReportTime = mutableMapOf<String, Long>()
    private val progressReportInterval = 500L // 0.5秒回报一次进度

    // JSON 序列化器
    private val json = Json { ignoreUnknownKeys = true }

    init {
        createNotificationChannel()
    }

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
            val url = "https://$ip:${filesShareData.port}/share/${fileInfo.endpoint}"
            val serverBaseUrl = "https://$ip:${filesShareData.port}"

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
                    serverBaseUrl = serverBaseUrl,
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
            updateTaskProgress(task.id, downloadedBytes, serverPublicKey)

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
                        updateTaskProgress(task.id, currentBytes, serverPublicKey)
                    }
                }
            }

            // 下载完成，保存到最终位置
            val savedPath = saveToFinalLocation(task.fileName, tempFile)
            updateTaskCompleted(task.id, savedPath, serverPublicKey)
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
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        val name = context.getString(R.string.download_notification_channel_name)
        val descriptionText = context.getString(R.string.download_notification_channel_description)
        val importance = NotificationManager.IMPORTANCE_DEFAULT // 普通通知
        val channel =
            NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                // 启用声音和振动
                enableVibration(true)
                enableLights(true)
            }

        val systemNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        systemNotificationManager.createNotificationChannel(channel)
        Log.d(LOG_TAG, "通知渠道创建成功")
    }

    /**
     * 获取或创建任务的通知ID
     */
    private fun getNotificationId(taskId: String): Int =
        notificationIds.getOrPut(taskId) {
            nextNotificationId++
        }

    /**
     * 显示/更新下载进度通知
     */
    private fun showProgressNotification(
        taskId: String,
        fileName: String,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        try {
            val notificationId = getNotificationId(taskId)

            val notification =
                NotificationCompat
                    .Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_notification_title, fileName))
                    .setContentText("${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)}")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(100, progress, false)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()

            notificationManager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "无通知权限: ${e.message}")
        }
    }

    /**
     * 显示下载完成通知
     */
    private fun showCompletedNotification(
        taskId: String,
        fileName: String,
    ) {
        try {
            val notificationId = getNotificationId(taskId)
            val task = _downloadTasks.value[taskId]
            val uriString = task?.savedPath

            if (uriString == null) {
                Log.e(LOG_TAG, "文件路径为空，无法创建通知")
                return
            }

            // 创建打开文件的 Intent 和 PendingIntent
            val openFileIntent = createOpenFileIntent(uriString)
            val openFilePendingIntent =
                openFileIntent?.let {
                    PendingIntent.getActivity(
                        context,
                        notificationId,
                        it,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }

            // 创建打开文件夹的 Intent 和 PendingIntent
            val openFolderIntent = createOpenFolderIntent(uriString)
            val openFolderPendingIntent =
                openFolderIntent?.let {
                    PendingIntent.getActivity(
                        context,
                        notificationId + 1000, // 使用不同的请求码
                        it,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }

            // 创建操作按钮
            val builder =
                NotificationCompat
                    .Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_notification_completed_title))
                    .setContentText(context.getString(R.string.download_notification_completed_text, fileName))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)

            // 点击通知默认打开文件
            if (openFilePendingIntent != null) {
                builder.setContentIntent(openFilePendingIntent)
            }

            // 添加"打开文件"按钮
            if (openFilePendingIntent != null) {
                builder.addAction(
                    android.R.drawable.ic_menu_view,
                    context.getString(R.string.download_notification_action_open_file),
                    openFilePendingIntent,
                )
            }

            // 添加"打开文件夹"按钮
            if (openFolderPendingIntent != null) {
                builder.addAction(
                    android.R.drawable.ic_menu_directions,
                    context.getString(R.string.download_notification_action_open_folder),
                    openFolderPendingIntent,
                )
            }

            notificationManager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "无通知权限: ${e.message}")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "显示完成通知失败: ${e.message}", e)
        }
    }

    /**
     * 创建打开文件的 Intent
     */
    private fun createOpenFileIntent(uriString: String): Intent? =
        try {
            val uri = Uri.parse(uriString)
            val mimeType = getMimeType(uri.lastPathSegment ?: "")

            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "创建打开文件 Intent 失败: ${e.message}")
            null
        }

    /**
     * 创建打开文件夹的 Intent
     */
    private fun createOpenFolderIntent(uriString: String): Intent? =
        try {
            val uri = Uri.parse(uriString)

            // 根据 URI 的 authority 判断类型
            val folderUri =
                when (uri.authority) {
                    // MediaStore Downloads URI
                    "media" -> {
                        // 打开 Downloads 文件夹
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    }
                    // SAF Document URI
                    "com.android.externalstorage.documents",
                    "com.android.providers.downloads.documents",
                    -> {
                        try {
                            // 尝试获取父目录 URI
                            Log.i("DownloadManager", "尝试获取父目录 URI: $uri")

                            var uriString = uriString
                            Log.i("DownloadManager", "uriString: $uriString")
                            val docIndex = uriString.indexOf("/document")
                            if (docIndex != -1) {
                                uriString = uriString.take(docIndex)
                            }
                            uriString.toUri()
                        } catch (e: Exception) {
                            Log.w(LOG_TAG, "无法解析父目录，使用文件 URI: ${e.message}")
                            uri
                        }
                    }
                    else -> {
                        // 其他类型，尝试打开 Downloads 文件夹
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    }
                }

            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "创建打开文件夹 Intent 失败: ${e.message}", e)
            // 降级方案：尝试打开文件管理器
            try {
                Intent(Intent.ACTION_VIEW).apply {
                    type = DocumentsContract.Document.MIME_TYPE_DIR
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } catch (e2: Exception) {
                Log.e(LOG_TAG, "降级方案也失败: ${e2.message}")
                null
            }
        }

    /**
     * 显示下载失败通知
     */
    private fun showFailedNotification(
        taskId: String,
        fileName: String,
        error: String,
    ) {
        try {
            val notificationId = getNotificationId(taskId)

            val notification =
                NotificationCompat
                    .Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_notification_failed_title))
                    .setContentText(context.getString(R.string.download_notification_failed_text, fileName, error))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()

            notificationManager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "无通知权限: ${e.message}")
        }
    }

    /**
     * 取消通知
     */
    private fun cancelNotification(taskId: String) {
        val notificationId = notificationIds[taskId] ?: return
        notificationManager.cancel(notificationId)
        notificationIds.remove(taskId)
        lastNotificationUpdateTime.remove(taskId)
        lastProgressReportTime.remove(taskId)
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }

    /**
     * 异步回报下载进度到服务器
     * 使用协程异步执行，不阻塞下载和通知更新
     * @param force 是否强制回报，忽略限流（用于下载完成时）
     */
    private fun reportProgressToServer(
        taskId: String,
        downloadedBytes: Long,
        serverBaseUrl: String,
        serverPublicKey: PublicKey,
        force: Boolean = false,
    ) {
        // 限流：避免过于频繁地发送请求（除非强制回报）
        if (!force) {
            val currentTime = System.currentTimeMillis()
            val lastReportTime = lastProgressReportTime[taskId] ?: 0L

            if (currentTime - lastReportTime < progressReportInterval) {
                return // 跳过本次回报
            }

            // 更新上次回报时间
            lastProgressReportTime[taskId] = currentTime
        }

        // 使用协程异步执行，不阻塞主流程
        scope.launch {
            try {
                val progressUrl = "$serverBaseUrl/progress"

                // 构造请求体
                val request =
                    ProgressReportRequest(
                        endpoint = taskId,
                        downloadedBytes = downloadedBytes,
                    )
                val jsonBody = json.encodeToString(request)
                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

                // 创建 OkHttpClient（复用已有的SSL配置）
                val client = createOkHttpClient(serverPublicKey)

                // 构造 HTTP 请求
                val httpRequest =
                    Request
                        .Builder()
                        .url(progressUrl)
                        .post(requestBody)
                        .build()

                // 执行请求
                val response = client.newCall(httpRequest).execute()

                if (response.isSuccessful) {
                    val forceTag = if (force) " (强制)" else ""
                    Log.d(LOG_TAG, "成功回报进度$forceTag: $taskId, $downloadedBytes bytes")
                } else {
                    Log.w(LOG_TAG, "回报进度失败: HTTP ${response.code}")
                }

                response.close()
            } catch (e: Exception) {
                // 回报失败不影响下载，只记录日志
                Log.w(LOG_TAG, "回报进度异常: ${e.message}")
            }
        }
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
        serverPublicKey: PublicKey? = null,
    ) {
        _downloadTasks.value =
            _downloadTasks.value.toMutableMap().apply {
                this[taskId]?.downloadedBytes = downloadedBytes
            }

        // 限流更新进度通知（避免过于频繁）
        val currentTime = System.currentTimeMillis()
        val lastUpdateTime = lastNotificationUpdateTime[taskId] ?: 0L
        val task = _downloadTasks.value[taskId]

        if (task != null && task.fileSize > 0 && (currentTime - lastUpdateTime >= notificationUpdateInterval)) {
            val progress = ((downloadedBytes.toFloat() / task.fileSize.toFloat()) * 100).toInt()
            showProgressNotification(taskId, task.fileName, progress, downloadedBytes, task.fileSize)
            lastNotificationUpdateTime[taskId] = currentTime
        }

        // 异步回报进度到服务器
        if (task != null && task.serverBaseUrl != null && serverPublicKey != null) {
            reportProgressToServer(taskId, downloadedBytes, task.serverBaseUrl, serverPublicKey)
        }
    }

    /**
     * 更新任务为完成状态
     */
    private fun updateTaskCompleted(
        taskId: String,
        savedPath: String,
        serverPublicKey: PublicKey? = null,
    ) {
        _downloadTasks.value =
            _downloadTasks.value.toMutableMap().apply {
                this[taskId]?.let {
                    it.status = DownloadStatus.COMPLETED
                    it.savedPath = savedPath
                }
            }

        val task = _downloadTasks.value[taskId]

        // 强制回报最终进度（忽略限流，确保服务器端显示100%完成）
        if (task != null && task.serverBaseUrl != null && serverPublicKey != null) {
            reportProgressToServer(
                taskId = taskId,
                downloadedBytes = task.fileSize, // 回报完整的文件大小
                serverBaseUrl = task.serverBaseUrl,
                serverPublicKey = serverPublicKey,
                force = true, // 强制回报，忽略限流
            )
        }

        // 显示完成通知
        if (task != null) {
            showCompletedNotification(taskId, task.fileName)
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

        // 显示失败通知
        val task = _downloadTasks.value[taskId]
        if (task != null) {
            showFailedNotification(taskId, task.fileName, error)
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

        // 取消通知
        cancelNotification(taskId)

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
        // 取消已完成任务的通知
        _downloadTasks.value
            .filter { it.value.status == DownloadStatus.COMPLETED }
            .forEach { (taskId, _) ->
                cancelNotification(taskId)
            }

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

        // 取消所有通知
        notificationIds.keys.toList().forEach { taskId ->
            cancelNotification(taskId)
        }

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
