package com.h3110w0r1d.geekpaste.model

import android.Manifest
import android.app.Activity.RESULT_OK
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.staticCompositionLocalOf
import com.h3110w0r1d.geekpaste.data.config.AppConfig
import com.h3110w0r1d.geekpaste.data.config.ConfigManager
import com.h3110w0r1d.geekpaste.utils.BleManager
import com.h3110w0r1d.geekpaste.utils.CertManager
import com.h3110w0r1d.geekpaste.utils.DownloadManager
import com.h3110w0r1d.geekpaste.utils.FileInfoData
import com.h3110w0r1d.geekpaste.utils.IpUtils
import com.h3110w0r1d.geekpaste.utils.WebServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class AppViewModel(
    @ApplicationContext
    private val context: Context,
    private val bleManager: BleManager,
    private val webServer: WebServer,
    private val configManager: ConfigManager,
    private val certManager: CertManager,
    private val downloadManager: DownloadManager,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val appConfig: StateFlow<AppConfig> = configManager.appConfig

    val connectedDevices = bleManager.connectedDevices
    val connectedGattMap = bleManager.connectedGattMap

    // 暴露 WebServer 中所有 endpoint 的信息
    val allEndpointInfo = webServer.allEndpointInfo

    companion object {
        val LocalGlobalAppViewModel =
            staticCompositionLocalOf<AppViewModel> {
                error("AppViewModel not provided!")
            }
    }

    private val bluetoothAdapter =
        (context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val bluetoothReceiver =
        object : BroadcastReceiver() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        bleManager.handleBluetoothBond(intent)
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                            BluetoothAdapter.STATE_ON -> {
                                Log.i("ViewModel", "蓝牙已开启")
                                scope.launch {
                                    bleManager.reconnectAll()
                                }
                            }
                            BluetoothAdapter.STATE_OFF -> {
                                Log.i("ViewModel", "蓝牙已关闭")
                            }
                        }
                    }
                }
            }
        }

    private val _isBluetoothPermissionGranted = MutableStateFlow(bleManager.checkBlePermission())
    val isBluetoothPermissionGranted: StateFlow<Boolean> = _isBluetoothPermissionGranted

    init {
        scope.launch {
            val filter = IntentFilter()
            filter.run {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            context.registerReceiver(bluetoothReceiver, filter)
            Log.i("ViewModel", "ViewModel init $webServer")

            if (!appConfig.value.isConfigInitialized) {
                appConfig.drop(1).first()
            }
            connectSavedDevices()
            if (!webServer.isAlive()) {
                webServer.start()
            }
        }
    }

    suspend fun connectSavedDevices() {
        if (!isBluetoothPermissionGranted.value && !bleManager.checkBlePermission()) {
            return
        }
        appConfig.value.savedDevices.forEach {
            val device = bluetoothAdapter.getRemoteDevice(it.address)
            connectToDevice(device)
        }
    }

    fun updateAppConfig(config: AppConfig) {
        scope.launch {
            configManager.updateAppConfig(config)
        }
    }

    fun updateDeviceName(
        address: String,
        name: String,
    ) {
        scope.launch {
            configManager.updateDeviceName(address, name)
        }
    }

    fun removeDevice(address: String) {
        scope.launch {
            configManager.removeDevice(address)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectDevice(address: String) {
        scope.launch {
            bleManager.disconnectDevice(address)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectToDevice(address: String) {
        scope.launch {
            val device = bluetoothAdapter.getRemoteDevice(address)
            connectToDevice(device)
        }
    }

    fun updateBluetoothPermission(isGranted: Boolean) {
        _isBluetoothPermissionGranted.value = isGranted
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connectToDevice(device: BluetoothDevice) {
        bleManager.connectToDevice(device)
    }

    fun handlePermissionResult(permissions: Map<String, Boolean>) {
        var allGranted = true
        val deniedPermissions = mutableListOf<String>()

        for (entry in permissions.entries) {
            if (!entry.value) {
                allGranted = false
                deniedPermissions.add(entry.key)
            }
        }
        if (allGranted) {
            updateBluetoothPermission(true)
            println("所有权限已被授予")
        } else {
            println("被拒绝的权限: $deniedPermissions")
        }
    }

    fun handleScanResult(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {
            if (!bleManager.checkBlePermission()) {
                return
            }
            val data = result.data ?: return
            var scanResult: ScanResult?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val associationInfo =
                    data.getParcelableExtra(
                        CompanionDeviceManager.EXTRA_ASSOCIATION,
                        AssociationInfo::class.java,
                    ) ?: return
                scanResult = associationInfo.associatedDevice?.bleDevice
            } else {
                @Suppress("DEPRECATION")
                scanResult = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
            }
            if (scanResult == null) {
                return
            }
            val device = scanResult.device
            if (device != null) {
                Log.i("ViewModel", "Found device: ${device.name ?: device.address}")
                scope.launch {
                    connectToDevice(device)
                }
            }
        } else {
            // 结果失败或被取消
        }
    }

    fun startScan(scanCallback: ActivityResultLauncher<IntentSenderRequest>) {
        bleManager.startScan(scanCallback)
    }

    fun checkBlePermission(): Boolean = bleManager.checkBlePermission()

    fun missingPermissions(): Array<String> = bleManager.missingPermissions()

    /**
     * 发送文本到指定设备
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun handleSharedTextToDevice(
        device: BluetoothDevice,
        text: String,
    ) {
        scope.launch {
            try {
                bleManager.sendTextToDevice(device, text)
                Log.i("Share", "成功发送文本到设备: ${device.name ?: device.address}")
            } catch (e: Exception) {
                Log.e("Share", "发送文本到设备失败", e)
            }
        }
    }

    /**
     * 将文件添加到 WebServer
     * @param fileInfo 文件信息
     * @param endpoint endpoint 路径
     */
    fun addFileToWebServer(
        fileInfo: ShareContent.FileInfo,
        endpoint: String,
    ) {
        // 直接使用URI添加到WebServer，无需创建临时文件
        webServer.addEndpoint(
            endpoint,
            fileInfo.uri,
            fileInfo.name,
            fileInfo.size,
        )
        Log.i("AppViewModel", "已添加文件到 WebServer: $endpoint")
    }

    /**
     * 发送文件信息到指定设备（文件已经在 WebServer 中）
     * @param device 目标设备
     * @param files 文件列表
     * @param endpoints endpoint 路径列表（与 files 一一对应）
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendFilesInfoToDevice(
        device: BluetoothDevice,
        files: List<ShareContent.FileInfo>,
        endpoints: List<String>,
    ) {
        scope.launch {
            try {
                // 1. 构造文件信息列表
                val fileInfoList =
                    files.mapIndexed { index, fileInfo ->
                        FileInfoData(
                            endpoint = endpoints[index],
                            fileName = fileInfo.name,
                            fileSize = fileInfo.size,
                        )
                    }

                if (fileInfoList.isEmpty()) {
                    Log.w("Share", "没有文件需要发送")
                    return@launch
                }

                // 2. 获取本机所有IP地址
                val ips = IpUtils.getAllIpAddresses()
                if (ips.isEmpty()) {
                    Log.e("Share", "无法获取本机IP地址")
                    throw Exception("无法获取本机IP地址")
                }
                Log.i("Share", "获取到本机IP地址: ${ips.joinToString(", ")}")

                // 3. 获取WebServer端口
                val port = webServer.getListeningPort()
                if (port <= 0) {
                    Log.e("Share", "WebServer未启动或端口未就绪")
                    throw Exception("WebServer未启动")
                }
                Log.i("Share", "WebServer端口: $port")

                // 4. 获取WebServer的公钥（Base64编码）
                val pubKey =
                    certManager.getPublicKeyBase64()
                        ?: throw Exception("无法获取WebServer公钥")
                Log.i("Share", "获取到公钥（$pubKey，长度: ${pubKey.length}）")

                // 5. 通过BLE发送文件信息
                bleManager.sendFilesToDevice(
                    device = device,
                    ips = ips,
                    port = port,
                    pubKey = pubKey,
                    files = fileInfoList,
                )
                Log.i("Share", "成功发送文件信息到设备: ${device.name ?: device.address}")
            } catch (e: Exception) {
                Log.e("Share", "发送文件到设备失败", e)
            }
        }
    }

    /**
     * 发送文件到指定设备（旧方法，保留以兼容其他地方的调用）
     * 文件会被添加到WebServer，然后通过BLE发送文件信息（IP、端口、公钥、文件列表）
     * @param device 目标设备
     * @param files 文件列表
     * @param endpoints 预生成的 endpoint 路径列表（与 files 一一对应）
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun handleSharedFilesToDevice(
        device: BluetoothDevice,
        files: List<ShareContent.FileInfo>,
        endpoints: List<String>,
    ) {
        Log.i("ShareActivity", "handleSharedFilesToDevice: $files")
        scope.launch {
            try {
                // 1. 将文件添加到WebServer
                files.forEachIndexed { index, fileInfo ->
                    val endpoint =
                        endpoints.getOrNull(index)
                            ?: UUID.randomUUID().toString()
                    addFileToWebServer(fileInfo, endpoint)
                }

                // 2. 发送文件信息到设备
                sendFilesInfoToDevice(device, files, endpoints)
            } catch (e: Exception) {
                Log.e("Share", "发送文件到设备失败", e)
            }
        }
    }

    /**
     * 移除 WebServer 中的 endpoint
     */
    fun removeWebServerEndpoint(endpoint: String) {
        webServer.removeEndpoint(endpoint)
        Log.d("AppViewModel", "移除 endpoint: $endpoint")
    }
}
