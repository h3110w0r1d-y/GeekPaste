package com.h3110w0r1d.clipboardsync

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.h3110w0r1d.clipboardsync.model.AppViewModel
import com.h3110w0r1d.clipboardsync.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlin.getValue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions: Map<String, Boolean> ->
            // `permissions` 是一个 Map<权限名称, 是否被授予>

            // 检查是否所有请求的权限都被授予了
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                // 权限全部被授予！在这里执行你的蓝牙相关操作
                Log.d("BlePermission", "所有权限已授予")
            } else {
                // 至少有一个权限被拒绝了
                // 你应该向用户解释为什么需要这些权限
                Log.w("BlePermission", "部分或全部权限被拒绝")
                Toast.makeText(this, "需要蓝牙权限才能使用此功能", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bleManager = appViewModel.bleManager

        val scanCallback =
            registerForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    if (!checkBlePermission()) {
                        return@registerForActivityResult
                    }
                    val data = result.data ?: return@registerForActivityResult
                    var scanResult: ScanResult?
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val associationInfo =
                            data.getParcelableExtra(
                                CompanionDeviceManager.EXTRA_ASSOCIATION,
                                AssociationInfo::class.java,
                            ) ?: return@registerForActivityResult
                        scanResult = associationInfo.associatedDevice?.bleDevice
                    } else {
                        @Suppress("DEPRECATION")
                        scanResult = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
                    }
                    if (scanResult == null || !bleManager.isClipboardSyncDevice(scanResult)) {
                        return@registerForActivityResult
                    }
                    val device = scanResult.device
                    if (device != null) {
                        Log.i("BLE_TEST", "Found device: ${device.name ?: device.address}")
                        appViewModel.connectToDevice(device)
                    }
                } else {
                    // 结果失败或被取消
                }
            }

        if (checkBlePermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bleManager.deviceManager.myAssociations.forEach {
                    Log.i(
                        "BLE_TEST",
                        "Saved device: ${it.displayName} ${it.id} ${it.deviceMacAddress}",
                    )
                    val device =
                        bluetoothAdapter.getRemoteDevice(it.deviceMacAddress.toString().uppercase())
                    if (device != null) {
                        appViewModel.connectToDevice(device)
                    }
                }
            } else {
            }
        }
        setContent {
            val appConfig by appViewModel.appConfig.collectAsState()
            // 只有在配置初始化完成后才显示主界面，防止配置未加载完成时闪现引导界面
            if (!appConfig.isConfigInitialized) return@setContent

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
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val connectedDevices by appViewModel.bleManager.connectedDevices.collectAsState()
                    BLEServiceScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartScan = { bleManager.startScan(scanCallback) },
                        onBroadcastData = {
                            connectedDevices.forEach {
                                Log.i("BLE_TEST", "Broadcasting data to device: ${it.name ?: it.address}")
                            }
                        },
                        connectedDevices = connectedDevices,
                    )
                }
            }
        }
    }

    private fun checkBlePermission(): Boolean {
        val permissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                )
            }
        val missingPermissions =
            permissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 1)
            return false
        }
        return true
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
@Composable
fun BLEServiceScreen(
    modifier: Modifier = Modifier,
    onStartScan: () -> Unit,
    onBroadcastData: () -> Unit,
    connectedDevices: List<BluetoothDevice>,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text("BLE主设备服务", modifier = Modifier.padding(bottom = 16.dp))

        Button(onClick = onStartScan, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("开始扫描")
        }
        Button(onClick = onBroadcastData, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("发送广播数据")
        }

        Text("已连接设备: ${connectedDevices.size}")
        connectedDevices.forEach { device ->
            Text("设备: ${device.name ?: device.address}")
        }
    }
}
