package com.h3110w0r1d.geekpaste.ui.screen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults.cardColors
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.h3110w0r1d.geekpaste.R
import com.h3110w0r1d.geekpaste.activity.MainActivity.Companion.LocalGlobalScanCallback
import com.h3110w0r1d.geekpaste.data.config.DeviceInfo
import com.h3110w0r1d.geekpaste.model.AppViewModel.Companion.LocalGlobalAppViewModel
import com.h3110w0r1d.geekpaste.ui.components.LargeFlexibleTopAppBar
import com.h3110w0r1d.geekpaste.utils.XposedUtil.getModuleVersion
import com.h3110w0r1d.geekpaste.utils.XposedUtil.isModuleEnabled

/**
 * 设备连接状态
 */
enum class DeviceConnectionState {
    DISCONNECTED, // 已断开：connectedGattMap中没有
    CONNECTING, // 连接中：connectedGattMap中有，connectedDevices中没有
    CONNECTED, // 已连接：connectedDevices中有
}

fun getAppIconBitmap(context: Context): Bitmap? =
    ResourcesCompat
        .getDrawable(
            context.resources,
            R.mipmap.ic_launcher,
            context.theme,
        )?.let { drawable ->
            createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight).apply {
                val canvas = Canvas(this)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }

fun withoutFontPadding(): TextStyle =
    Typography(
        titleSmall = TextStyle(),
    ).titleSmall.copy(
        platformStyle =
            PlatformTextStyle(
                includeFontPadding = false,
            ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val appViewModel = LocalGlobalAppViewModel.current
    val connectedDevices by appViewModel.connectedDevices.collectAsState()
    val connectedGattMap by appViewModel.connectedGattMap.collectAsState()
    val appConfig by appViewModel.appConfig.collectAsState()
    val scanCallback = LocalGlobalScanCallback.current
    val showAboutDialog = remember { mutableStateOf(false) }
    val isBluetoothPermissionGranted by appViewModel.isBluetoothPermissionGranted.collectAsState()

    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            rememberTopAppBarState(),
            snapAnimationSpec = null,
        )
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    getAppIconBitmap(context)?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .height(32.dp)
                                    .width(48.dp),
                        )
                    }
                },
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showAboutDialog.value = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.about),
                            tint = colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(innerPadding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ModuleCard()

            Button(
                onClick = { appViewModel.startScan(scanCallback) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("扫描并添加新设备")
            }

            Text(
                text = "已保存设备 (${appConfig.savedDevices.size})",
                style = typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (appConfig.savedDevices.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        cardColors(
                            containerColor = colorScheme.surfaceVariant,
                        ),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无已保存的设备",
                            style = typography.bodyLarge,
                            color = colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "点击上方按钮扫描并添加设备",
                            style = typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                appConfig.savedDevices.forEach { deviceInfo ->
                    // 计算设备连接状态
                    val connectionState =
                        when {
                            connectedDevices.any { it.address == deviceInfo.address } -> DeviceConnectionState.CONNECTED
                            connectedGattMap.containsKey(deviceInfo.address) -> DeviceConnectionState.CONNECTING
                            else -> DeviceConnectionState.DISCONNECTED
                        }

                    DeviceCard(
                        deviceInfo = deviceInfo,
                        connectionState = connectionState,
                        onEditName = { address, newName ->
                            appViewModel.updateDeviceName(address, newName)
                        },
                        onDelete = { address ->
                            appViewModel.removeDevice(address)
                        },
                        onConnect = { address ->
                            if (isBluetoothPermissionGranted && appViewModel.checkBlePermission()) {
                                appViewModel.connectToDevice(address)
                            }
                        },
                        onDisconnect = { address ->
                            if (isBluetoothPermissionGranted && appViewModel.checkBlePermission()) {
                                appViewModel.disconnectDevice(address)
                            }
                        },
                    )
                }
            }
        }

        if (showAboutDialog.value) {
            InfoDialog(onDismiss = { showAboutDialog.value = false })
        }
    }
}

@Composable
fun ModuleCard() {
    var moduleStatus = stringResource(R.string.module_inactivated)
    var cardBackground = colorScheme.error
    var textColor = colorScheme.onError
    var iconVector = Icons.Filled.AddCircle
    var deg = 45f
    if (isModuleEnabled()) {
        moduleStatus = stringResource(R.string.module_activated)
        cardBackground = colorScheme.primary
        textColor = colorScheme.onPrimary
        iconVector = Icons.Filled.CheckCircle
        deg = 0f
    }

    Card(
        colors = cardColors(containerColor = cardBackground),
        modifier = Modifier.fillMaxWidth(),
        onClick = { },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                modifier =
                    Modifier
                        .padding(26.dp, 32.dp)
                        .size(24.dp)
                        .rotate(deg),
                tint = textColor,
            )
            Column {
                Text(
                    text = moduleStatus,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    // 					style = withoutFontPadding(),
                )
                if (isModuleEnabled()) {
                    Text(
                        text = "Xposed API Version: " + getModuleVersion(),
                        fontSize = 12.sp,
                        color = textColor,
                        style = withoutFontPadding(),
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    deviceInfo: DeviceInfo,
    connectionState: DeviceConnectionState,
    onEditName: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // 根据连接状态设置卡片颜色
    val containerColor =
        when (connectionState) {
            DeviceConnectionState.CONNECTED -> colorScheme.primaryContainer
            DeviceConnectionState.CONNECTING -> colorScheme.tertiaryContainer
            DeviceConnectionState.DISCONNECTED -> colorScheme.surfaceVariant
        }

    val contentColor =
        when (connectionState) {
            DeviceConnectionState.CONNECTED -> colorScheme.onPrimaryContainer
            DeviceConnectionState.CONNECTING -> colorScheme.onTertiaryContainer
            DeviceConnectionState.DISCONNECTED -> colorScheme.onSurfaceVariant
        }

    // Badge颜色：绿色=已连接，黄色=连接中，红色=已断开
    val badgeColor =
        when (connectionState) {
            DeviceConnectionState.CONNECTED -> Color(0xFF4CAF50) // 绿色
            DeviceConnectionState.CONNECTING -> Color(0xFFFFC107) // 黄色
            DeviceConnectionState.DISCONNECTED -> Color(0xFFF44336) // 红色
        }

    // 蓝牙图标
    val bluetoothIcon =
        when (connectionState) {
            DeviceConnectionState.CONNECTED -> Icons.Filled.BluetoothConnected
            DeviceConnectionState.CONNECTING -> Icons.Filled.Bluetooth
            DeviceConnectionState.DISCONNECTED -> Icons.Filled.Bluetooth
        }

    // 图标颜色
    val iconTint =
        when (connectionState) {
            DeviceConnectionState.CONNECTED -> colorScheme.primary
            DeviceConnectionState.CONNECTING -> contentColor
            DeviceConnectionState.DISCONNECTED -> contentColor.copy(alpha = 0.6f)
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            cardColors(
                containerColor = containerColor,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                // 带 Badge 的蓝牙图标
                BadgedBox(
                    badge = {
                        Badge(
                            containerColor = badgeColor,
                        )
                    },
                ) {
                    Icon(
                        imageVector = bluetoothIcon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = iconTint,
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = deviceInfo.name,
                        style = typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = contentColor,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = deviceInfo.address,
                        style = typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }
            }

            // 下拉菜单按钮
            Box {
                IconButton(
                    onClick = { showMenu = true },
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = contentColor,
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    // 编辑菜单项
                    DropdownMenuItem(
                        text = { Text("编辑备注") },
                        onClick = {
                            showMenu = false
                            showEditDialog = true
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = null,
                            )
                        },
                    )

                    // 连接/断开连接菜单项
                    when (connectionState) {
                        DeviceConnectionState.DISCONNECTED -> {
                            // 已断开：显示"连接设备"
                            DropdownMenuItem(
                                text = { Text("连接设备") },
                                onClick = {
                                    showMenu = false
                                    onConnect(deviceInfo.address)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Link,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                        DeviceConnectionState.CONNECTING,
                        DeviceConnectionState.CONNECTED,
                        -> {
                            // 连接中或已连接：显示"断开连接"
                            DropdownMenuItem(
                                text = { Text("断开连接") },
                                onClick = {
                                    showMenu = false
                                    onDisconnect(deviceInfo.address)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.LinkOff,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                    }

                    // 删除菜单项（连接中或已连接时禁用）
                    DropdownMenuItem(
                        text = { Text("删除设备") },
                        onClick = {
                            showMenu = false
                            onDelete(deviceInfo.address)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                            )
                        },
                        enabled = connectionState == DeviceConnectionState.DISCONNECTED,
                    )
                }
            }
        }
    }

    // 编辑对话框
    if (showEditDialog) {
        EditDeviceNameDialog(
            currentName = deviceInfo.name,
            onDismiss = { showEditDialog = false },
            onConfirm = { newName ->
                onEditName(deviceInfo.address, newName)
                showEditDialog = false
            },
        )
    }
}

@Composable
fun EditDeviceNameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newName by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "修改设备备注")
        },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("设备名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isNotBlank()) {
                        onConfirm(newName)
                    }
                },
                enabled = newName.isNotBlank(),
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
fun InfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(24.dp)
                        .width(280.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                fun packageVersion(): String {
                    val manager = context.packageManager
                    var version = "1.0"
                    try {
                        val info = manager.getPackageInfo(context.packageName, 0)
                        version = info.versionName ?: "1.0"
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                    return version
                }
                // 应用 Logo
                getAppIconBitmap(context)?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = null)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 应用名称
                Text(
                    text = stringResource(R.string.app_name), // 替换你的应用名称资源
                    style = typography.bodyLarge,
                    color = colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(8.dp))
                // 版本信息
                Text(
                    text = "Version ${packageVersion()}",
                    style = typography.bodyMedium,
                    color = colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 开发者信息
                Text(
                    text = "Developed by",
                    style = typography.bodyMedium,
                    color = colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(4.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "@h3110w0r1d-y",
                        style = typography.bodyMedium,
                        color = colorScheme.primary,
                        modifier =
                            Modifier
                                .clickable {
                                    val intent =
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            "https://github.com/h3110w0r1d-y".toUri(),
                                        )
                                    context.startActivity(intent)
                                }.padding(4.dp),
                    )
                }
            }
        }
    }
}
