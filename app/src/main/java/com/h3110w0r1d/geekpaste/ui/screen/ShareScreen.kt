package com.h3110w0r1d.geekpaste.ui.screen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.h3110w0r1d.geekpaste.model.AppViewModel.Companion.LocalGlobalAppViewModel
import com.h3110w0r1d.geekpaste.model.DownloadStatus
import com.h3110w0r1d.geekpaste.model.ShareContent
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    shareContent: ShareContent?,
    fileToEndpointMap: Map<ShareContent.FileInfo, String>,
    onShareToDevice: (BluetoothDevice, ShareContent) -> Unit,
    onFinish: () -> Unit,
) {
    val appViewModel = LocalGlobalAppViewModel.current
    val connectedDevices by appViewModel.connectedDevices.collectAsState()
    val allEndpointInfo by appViewModel.allEndpointInfo.collectAsState()

    // 控制是否显示退出确认对话框
    var showExitDialog by remember { mutableStateOf(false) }

    // 检查是否有文件正在传输
    val hasFileTransferring =
        fileToEndpointMap.values.any { endpoint ->
            allEndpointInfo[endpoint]?.status == DownloadStatus.DOWNLOADING
        }

    // 处理返回逻辑
    fun handleBack() {
        if (hasFileTransferring) {
            showExitDialog = true
        } else {
            onFinish()
        }
    }

    // 拦截系统返回键
    BackHandler(hasFileTransferring) {
        handleBack()
    }

    // 退出确认对话框
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("确认退出") },
            text = { Text("有文件正在传输，确定要退出吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        onFinish()
                    },
                ) {
                    Text("退出")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExitDialog = false },
                ) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分享内容") },
                navigationIcon = {
                    IconButton(onClick = {
                        handleBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 显示分享内容
            when (shareContent) {
                is ShareContent.Text -> {
                    Text(
                        text = "分享文本",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Text(
                            text = shareContent.text,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                is ShareContent.Files -> {
                    Text(
                        text = "分享文件 (${shareContent.files.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    shareContent.files.forEach { fileInfo ->
                        val endpoint = fileToEndpointMap[fileInfo]
                        val endpointInfo = endpoint?.let { allEndpointInfo[it] }
                        FileItemCard(
                            fileInfo = fileInfo,
                            endpointInfo = endpointInfo,
                        )
                    }
                }
                null -> {
                    Text(
                        text = "正在解析分享内容...",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            // 显示已连接的设备列表
            Text(
                text = "选择要分享的设备",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (connectedDevices.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                ) {
                    Text(
                        text = "没有已连接的设备\n请先在主界面连接设备",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                connectedDevices.forEach { device ->
                    DeviceItemCard(
                        device = device,
                        shareContent = shareContent,
                        onShareClick = { content ->
                            onShareToDevice(device, content)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun FileItemCard(
    fileInfo: ShareContent.FileInfo,
    endpointInfo: com.h3110w0r1d.geekpaste.utils.EndpointInfo?,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileInfo.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = formatFileSize(fileInfo.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 显示下载进度
            if (endpointInfo != null) {
                Spacer(modifier = Modifier.height(12.dp))
                when (endpointInfo.status) {
                    DownloadStatus.NOT_STARTED -> {
                        Text(
                            text = "等待下载...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DownloadStatus.DOWNLOADING -> {
                        val progress =
                            if (fileInfo.size > 0) {
                                (endpointInfo.downloadedBytes.toFloat() / fileInfo.size).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        Column {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${formatFileSize(
                                    endpointInfo.downloadedBytes,
                                )} / ${formatFileSize(fileInfo.size)} (${(progress * 100).toInt()}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        Text(
                            text = "✓ 下载完成",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    DownloadStatus.FAILED -> {
                        Text(
                            text = "✗ 下载失败",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceItemCard(
    device: BluetoothDevice,
    shareContent: ShareContent?,
    onShareClick: (ShareContent) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = shareContent != null) {
                    shareContent?.let { onShareClick(it) }
                }.padding(vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (shareContent != null) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "未知设备",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (shareContent == null) {
                Text(
                    text = "等待内容...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(size: Long): String {
    val df = DecimalFormat("#.##")
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${df.format(size / 1024.0)} KB"
        size < 1024 * 1024 * 1024 -> "${df.format(size / (1024.0 * 1024.0))} MB"
        else -> "${df.format(size / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
