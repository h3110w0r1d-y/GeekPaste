package com.h3110w0r1d.geekpaste

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.h3110w0r1d.geekpaste.data.config.LocalGlobalAppConfig
import com.h3110w0r1d.geekpaste.model.AppViewModel
import com.h3110w0r1d.geekpaste.model.LocalGlobalViewModel
import com.h3110w0r1d.geekpaste.model.ShareContent
import com.h3110w0r1d.geekpaste.ui.screen.ShareScreen
import com.h3110w0r1d.geekpaste.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    // 追踪在当前 Activity 中创建的 endpoint
    private val addedEndpoints = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 监听生命周期，在销毁时清理 endpoint
        lifecycle.addObserver(
            object : LifecycleEventObserver {
                override fun onStateChanged(
                    source: LifecycleOwner,
                    event: Lifecycle.Event,
                ) {
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        cleanupEndpoints()
                    }
                }
            },
        )

        setContent {
            val appConfig by appViewModel.appConfig.collectAsState()
            // 只有在配置初始化完成后才显示界面
            if (!appConfig.isConfigInitialized) return@setContent

            // 使用remember保存分享内容状态
            var currentShareContent by remember { mutableStateOf<ShareContent?>(null) }
            // 保存文件到endpoint的映射，用于显示下载进度
            var fileToEndpointMap by remember { mutableStateOf<Map<ShareContent.FileInfo, String>>(emptyMap()) }
            val scope = rememberCoroutineScope()

            // 解析分享内容
            LaunchedEffect(intent) {
                handleShareIntent(intent, scope) { content ->
                    currentShareContent = content
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
                    LocalGlobalViewModel provides appViewModel,
                    LocalGlobalAppConfig provides appConfig,
                ) {
                    ShareScreen(
                        shareContent = currentShareContent,
                        fileToEndpointMap = fileToEndpointMap,
                        onShareToDevice = { device, content ->
                            if (appViewModel.checkBlePermission()) {
                                Log.i("ShareActivity", "onShareToDevice")
                                // 处理分享到设备
                                handleShareToDevice(device, content) { map ->
                                    fileToEndpointMap = map
                                }
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
     * 只解析内容，不调用appViewModel的方法
     */
    private fun handleShareIntent(
        intent: Intent?,
        scope: kotlinx.coroutines.CoroutineScope,
        onContentParsed: (ShareContent) -> Unit,
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
                            onContentParsed(ShareContent.Text(sharedText))
                        }
                    }
                    intent.hasExtra(Intent.EXTRA_STREAM) -> {
                        // 分享单个文件
                        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        if (uri != null) {
                            scope.launch {
                                // 在IO线程执行文件信息解析（涉及数据库查询）
                                val fileInfo = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    parseFileInfo(uri)
                                }
                                // 在主线程更新UI状态
                                if (fileInfo != null) {
                                    onContentParsed(ShareContent.Files(listOf(fileInfo)))
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
                        val fileInfos = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            uris.mapNotNull { parseFileInfo(it) }
                        }
                        // 在主线程更新UI状态
                        if (fileInfos.isNotEmpty()) {
                            onContentParsed(ShareContent.Files(fileInfos))
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
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex) ?: "shared_file"
                    }
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }

            ShareContent.FileInfo(uri, fileName, fileSize)
        } catch (e: Exception) {
            android.util.Log.e("ShareActivity", "解析文件信息失败", e)
            null
        }

    /**
     * 处理分享到设备
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleShareToDevice(
        device: BluetoothDevice,
        content: ShareContent,
        onEndpointsGenerated: (Map<ShareContent.FileInfo, String>) -> Unit,
    ) {
        when (content) {
            is ShareContent.Text -> {
                // 对于文本，发送到指定设备
                appViewModel.handleSharedTextToDevice(device, content.text)
            }
            is ShareContent.Files -> {
                // 对于文件，添加到WebServer并发送文件信息
                // 生成并追踪 endpoint
                Log.i("ShareActivity", "handleShareToDevice: ${content.files}")
                val endpoints =
                    content.files.map { fileInfo ->
                        "/share/${System.currentTimeMillis()}_${fileInfo.name}"
                    }
                addedEndpoints.addAll(endpoints)
                
                // 创建文件到endpoint的映射
                val map = content.files.zip(endpoints).toMap()
                onEndpointsGenerated(map)
                
                Log.i("ShareActivity", "handleShareToDevice2: ${content.files}")

                appViewModel.handleSharedFilesToDevice(device, content.files, endpoints)
            }
        }
    }

    /**
     * 清理当前 Activity 创建的 endpoint
     */
    private fun cleanupEndpoints() {
        if (addedEndpoints.isNotEmpty()) {
            android.util.Log.d("ShareActivity", "清理 ${addedEndpoints.size} 个 endpoint")
            addedEndpoints.forEach { endpoint ->
                appViewModel.removeWebServerEndpoint(endpoint)
            }
            addedEndpoints.clear()
        }
    }
}
