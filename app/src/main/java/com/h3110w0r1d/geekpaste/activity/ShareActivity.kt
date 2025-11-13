package com.h3110w0r1d.geekpaste.activity

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.h3110w0r1d.geekpaste.data.config.ConfigManager.Companion.LocalGlobalAppConfig
import com.h3110w0r1d.geekpaste.model.AppViewModel
import com.h3110w0r1d.geekpaste.model.AppViewModel.Companion.LocalGlobalAppViewModel
import com.h3110w0r1d.geekpaste.model.ShareContent
import com.h3110w0r1d.geekpaste.ui.screen.ShareScreen
import com.h3110w0r1d.geekpaste.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject
    lateinit var appViewModel: AppViewModel

    private val addedEndpoints = mutableSetOf<String>()

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ShareActivity", "onDestroy")
        cleanupEndpoints()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cleanupEndpoints()

        setContent {
            val appConfig by appViewModel.appConfig.collectAsState()
            // 只有在配置初始化完成后才显示界面
            if (!appConfig.isConfigInitialized) return@setContent

            // 使用remember保存分享内容状态
            var currentShareContent by remember { mutableStateOf<ShareContent?>(null) }
            // 保存文件到endpoint的映射，用于显示下载进度
            var fileToEndpointMap by remember { mutableStateOf<Map<ShareContent.FileInfo, String>>(emptyMap()) }
            val scope = rememberCoroutineScope()

            // 解析分享内容并自动添加到 WebServer
            LaunchedEffect(intent) {
                handleShareIntent(intent, scope) { content, endpointMap ->
                    currentShareContent = content
                    fileToEndpointMap = endpointMap
                }
            }

            val isDarkMode =
                if (appConfig.nightModeFollowSystem) {
                    isSystemInDarkTheme()
                } else {
                    appConfig.nightModeEnabled
                }
            enableEdgeToEdge(
                statusBarStyle =
                    SystemBarStyle.auto(
                        Color.Transparent.toArgb(),
                        Color.Transparent.toArgb(),
                    ) { isDarkMode },
            )
            AppTheme(
                darkTheme = isDarkMode,
                dynamicColor = appConfig.isUseSystemColor,
                pureBlackDarkTheme = appConfig.pureBlackDarkTheme,
                customColorScheme = appConfig.themeColor,
            ) {
                CompositionLocalProvider(
                    LocalGlobalAppViewModel provides appViewModel,
                    LocalGlobalAppConfig provides appConfig,
                ) {
                    ShareScreen(
                        shareContent = currentShareContent,
                        fileToEndpointMap = fileToEndpointMap,
                        onShareToDevice = { device, content ->
                            if (appViewModel.checkBlePermission()) {
                                // 直接发送到设备，文件已经在 WebServer 中
                                handleShareToDevice(device, content, fileToEndpointMap)
                            } else {
                                Toast
                                    .makeText(
                                        this,
                                        "请打开蓝牙权限",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        },
                        onFinish = { finish() },
                    )
                }
            }
        }
    }

    /**
     * 处理分享Intent
     * 支持分享文本、单个文件和多个文件
     * 解析内容后自动将文件添加到 WebServer
     */
    private fun handleShareIntent(
        intent: Intent?,
        scope: CoroutineScope,
        onContentParsed: (ShareContent, Map<ShareContent.FileInfo, String>) -> Unit,
    ) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                // 处理单个分享
                when {
                    intent.type?.startsWith("text/") == true -> {
                        // 分享文本
                        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                        if (sharedText != null) {
                            onContentParsed(ShareContent.Text(sharedText), emptyMap())
                        }
                    }
                    intent.hasExtra(Intent.EXTRA_STREAM) -> {
                        // 分享单个文件
                        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        if (uri != null) {
                            scope.launch {
                                // 在IO线程执行文件信息解析（涉及数据库查询）
                                val fileInfo =
                                    withContext(Dispatchers.IO) {
                                        parseFileInfo(uri)
                                    }
                                // 在主线程更新UI状态
                                if (fileInfo != null) {
                                    val files = listOf(fileInfo)
                                    val endpointMap = addFilesToWebServer(files)
                                    onContentParsed(ShareContent.Files(files), endpointMap)
                                }
                            }
                        }
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // 处理多个文件分享
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (uris != null && uris.isNotEmpty()) {
                    scope.launch {
                        // 在IO线程执行文件信息解析（涉及数据库查询）
                        val fileInfos =
                            withContext(Dispatchers.IO) {
                                uris.mapNotNull { parseFileInfo(it) }
                            }
                        // 在主线程更新UI状态
                        if (fileInfos.isNotEmpty()) {
                            val endpointMap = addFilesToWebServer(fileInfos)
                            onContentParsed(ShareContent.Files(fileInfos), endpointMap)
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析文件信息
     */
    private fun parseFileInfo(uri: Uri): ShareContent.FileInfo? =
        try {
            var fileName = "shared_file"
            var fileSize = 0L

            // 获取文件名和大小
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex) ?: "shared_file"
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }

            ShareContent.FileInfo(uri, fileName, fileSize)
        } catch (e: Exception) {
            Log.e("ShareActivity", "解析文件信息失败", e)
            null
        }

    /**
     * 将文件添加到 WebServer
     * @param files 文件列表
     * @return 文件到 endpoint 的映射
     */
    private fun addFilesToWebServer(files: List<ShareContent.FileInfo>): Map<ShareContent.FileInfo, String> {
        val endpointMap = mutableMapOf<ShareContent.FileInfo, String>()

        files.forEach { fileInfo ->
            // 生成唯一的 endpoint（只使用 UUID，不包含 "/share/" 前缀）
            val endpoint = UUID.randomUUID().toString()
            addedEndpoints.add(endpoint)

            // 添加到 WebServer
            appViewModel.addFileToWebServer(fileInfo, endpoint)
            endpointMap[fileInfo] = endpoint

            Log.i("ShareActivity", "已添加文件到 WebServer: $endpoint")
        }

        return endpointMap
    }

    /**
     * 处理分享到设备
     * 文件已经在 WebServer 中，直接发送 BLE 消息
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleShareToDevice(
        device: BluetoothDevice,
        content: ShareContent,
        fileToEndpointMap: Map<ShareContent.FileInfo, String>,
    ) {
        when (content) {
            is ShareContent.Text -> {
                // 对于文本，发送到指定设备
                appViewModel.handleSharedTextToDevice(device, content.text)
            }
            is ShareContent.Files -> {
                // 对于文件，直接使用已经在 WebServer 中的 endpoint 发送 BLE 消息
                val endpoints = content.files.mapNotNull { fileToEndpointMap[it] }
                if (endpoints.size == content.files.size) {
                    Log.i("ShareActivity", "发送文件信息到设备: ${device.name}")
                    appViewModel.sendFilesInfoToDevice(device, content.files, endpoints)
                } else {
                    Log.e("ShareActivity", "部分文件的 endpoint 未找到")
                    Toast.makeText(this, "文件准备失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 清理当前 Activity 创建的 endpoint
     */
    private fun cleanupEndpoints() {
        if (addedEndpoints.isNotEmpty()) {
            Log.d("ShareActivity", "清理 ${addedEndpoints.size} 个 endpoint")
            addedEndpoints.forEach { endpoint ->
                appViewModel.removeWebServerEndpoint(endpoint)
            }
            addedEndpoints.clear()
        }
    }
}
