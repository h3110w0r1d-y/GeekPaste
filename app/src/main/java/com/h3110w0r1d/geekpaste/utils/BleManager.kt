package com.h3110w0r1d.geekpaste.utils

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Context.CLIPBOARD_SERVICE
import android.content.Context.COMPANION_DEVICE_SERVICE
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresPermission
import com.h3110w0r1d.geekpaste.data.config.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json.Default.decodeFromString
import kotlinx.serialization.json.Json.Default.encodeToString
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import java.util.UUID
import kotlin.collections.set
import kotlin.math.min

// JSON数据模型
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
data class BleData(
    val type: String,
    val data: String,
)

// 文件信息数据模型（用于files类型）
@Serializable
data class FileInfoData(
    val endpoint: String,
    val fileName: String,
    val fileSize: Long,
)

// 文件分享数据模型（用于files类型的data字段）
@Serializable
data class FilesShareData(
    val ips: List<String>,
    val port: Int,
    val pubKey: String,
    val files: List<FileInfoData>,
)

class BleManager(
    private val context: Context,
    private val configManager: ConfigManager,
    private val certManager: CertManager,
    private val downloadManager: DownloadManager,
) {
    /**
     * 获取下载任务的StateFlow，用于监听下载状态
     */
    val downloadTasks = downloadManager.downloadTasks

    /**
     * 取消指定的下载任务
     */
    fun cancelDownloadTask(taskId: String) {
        downloadManager.cancelTask(taskId)
    }

    /**
     * 重试失败的下载任务（OkHttp版本支持断点续传）
     */
    suspend fun retryDownloadTask(
        taskId: String,
        filesShareData: FilesShareData,
    ) {
        val serverPublicKey = parsePublicKeyFromBase64(filesShareData.pubKey)
        downloadManager.retryTask(taskId, serverPublicKey)
    }

    /**
     * 获取下载进度百分比
     */
    fun getDownloadProgress(taskId: String): Float = downloadManager.getProgress(taskId)

    /**
     * 清除已完成的下载任务
     */
    fun clearCompletedDownloads() {
        downloadManager.clearCompletedTasks()
    }

    /**
     * 清除所有下载任务
     */
    fun clearAllDownloads() {
        downloadManager.clearAllTasks()
    }

    companion object {
        // UUID 常量
        private val SERVICE_UUID = UUID.fromString("91106bcd-4f0f-4519-8a99-b09fff8c13ba")
        private val DATA_CHARACTERISTIC_UUID = UUID.fromString("10d7925f-be10-455c-b7bc-5cb663ae9630")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 重试配置
        private const val MAX_RETRY_ATTEMPTS = 10 // 最大重试次数
        private const val RETRY_DELAY_MS = 100L // 重试间隔（毫秒）

        // MTU 配置
        private const val MAX_MTU_SIZE = 517 // 最大MTU大小
        private const val DEFAULT_PAYLOAD_SIZE = 20 // 默认MTU大小
        private const val MAX_PAYLOAD_SIZE = 512 // 最大载荷大小

        private const val LOG_TAG = "BleManager"
    }

    private var lastReceivedText = ""
    private val clipboardManager = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    private var lastTimestamp: Long = 0
    private val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
    private val bleFilter = BluetoothLeDeviceFilter.Builder().setScanFilter(scanFilter).build()
    private val pairingRequest = AssociationRequest.Builder().addDeviceFilter(bleFilter).build()
    val deviceManager = context.getSystemService(COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
    private val bluetoothAdapter = (context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val _connectedGattMap = MutableStateFlow<MutableMap<String, BluetoothGatt>>(mutableMapOf())
    val connectedGattMap: StateFlow<MutableMap<String, BluetoothGatt>> = _connectedGattMap
    private val _connectedDevices = MutableStateFlow<MutableList<BluetoothDevice>>(mutableListOf())
    val connectedDevices: StateFlow<MutableList<BluetoothDevice>> = _connectedDevices
    private val devicePayloadSizeMap = mutableMapOf<String, Int>()
    private val fragmentHandler = BleDataFragmentHandler()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    init {
        clipboardManager.addPrimaryClipChangedListener {
            if (checkBlePermission()) {
                onClipboardChanged()
            }
        }
    }

    /**
     * BluetoothGatt 兼容性扩展函数：写入特征值
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun BluetoothGatt.writeCharacteristicCompat(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            writeCharacteristic(characteristic)
        }

    /**
     * BluetoothGatt 兼容性扩展函数：写入描述符
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun BluetoothGatt.writeDescriptorCompat(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            writeDescriptor(descriptor)
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun handleBluetoothBond(intent: Intent) {
        val device =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
        val gatt = _connectedGattMap.value[device.address] ?: return
        val bondState =
            intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.BOND_NONE,
            )
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> gatt.discoverServices()
            BluetoothDevice.BOND_NONE -> {}
            BluetoothDevice.BOND_BONDING -> {}
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun reconnectAll() {
        _connectedGattMap.value.keys.forEach { address ->
            val gatt = _connectedGattMap.value[address] ?: return@forEach
            gatt.disconnect()
            gatt.close()
            val device = bluetoothAdapter.getRemoteDevice(address)
            connectToDevice(device, true)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectDevice(address: String) {
        val gatt = _connectedGattMap.value[address]
        if (gatt != null) {
            gatt.disconnect()
            gatt.close()
            _connectedGattMap.value =
                _connectedGattMap.value
                    .filter {
                        it.key != address
                    }.toMutableMap()
            _connectedDevices.value =
                _connectedDevices.value
                    .filter {
                        it.address != address
                    }.toMutableList()
            Log.i(LOG_TAG, "已断开设备连接: $address")
        }
    }

    fun checkBlePermission(): Boolean {
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
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        return missingPermissions.isEmpty()
    }

    fun missingPermissions(): Array<String> {
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
            permissions
                .filter {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }.toTypedArray()
        return missingPermissions
    }

    /**
     * 获取剪贴板数据特征
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun BluetoothGatt.getDataCharacteristic(): BluetoothGattCharacteristic? =
        getService(SERVICE_UUID)?.getCharacteristic(DATA_CHARACTERISTIC_UUID)

    /**
     * 从Base64编码的公钥解析公钥对象
     */
    private fun parsePublicKeyFromBase64(base64PubKey: String): java.security.PublicKey {
        try {
            // 移除可能存在的空格、换行符等，并处理JSON转义字符
            val cleanedKey =
                base64PubKey
                    .trim()
                    .replace("\\s+".toRegex(), "")
                    .replace("\\/", "/") // 处理JSON转义的斜杠

            Log.d(LOG_TAG, "原始公钥长度: ${base64PubKey.length}")
            Log.d(LOG_TAG, "清理后公钥长度: ${cleanedKey.length}")
            Log.d(LOG_TAG, "公钥前20字符: ${cleanedKey.take(20)}")
            Log.d(LOG_TAG, "公钥后20字符: ${cleanedKey.takeLast(20)}")

            // 解码Base64（使用NO_WRAP标志，避免自动添加换行符）
            val keyBytes = android.util.Base64.decode(cleanedKey, android.util.Base64.NO_WRAP)
            Log.d(LOG_TAG, "解码后字节数组长度: ${keyBytes.size}")
            Log.d(LOG_TAG, "字节数组前8字节: ${keyBytes.take(8).joinToString(" ") { "%02X".format(it) }}")

            // 尝试使用 X.509 格式解析公钥
            val keySpec = java.security.spec.X509EncodedKeySpec(keyBytes)
            val keyFactory = java.security.KeyFactory.getInstance("RSA")
            return keyFactory.generatePublic(keySpec)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "解析公钥失败: ${e.message}", e)
            throw e
        }
    }

    /**
     * 处理接收到的完整数据
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processReceivedData(
        mergedData: ByteArray,
        deviceAddress: String,
    ) {
        try {
            val jsonString = String(mergedData, Charsets.UTF_8)
            val bleData = decodeFromString<BleData>(jsonString)

            when (bleData.type) {
                "text" -> {
                    lastReceivedText = bleData.data
                    val clip = ClipData.newPlainText("GeekPaste", bleData.data)
                    clipboardManager.setPrimaryClip(clip)
                    Log.i(LOG_TAG, "已将接收到的文本设置到剪贴板: ${bleData.data}")
                }
                "get_cert" -> {
                    Log.i(LOG_TAG, "收到 get_cert 请求，开始生成证书")
                    coroutineScope.launch {
                        try {
                            val tempCert = certManager.generateTempCertificate()
                            val certJson = encodeToString(tempCert)
                            val responseData = BleData(type = "set_cert", data = certJson)
                            val data = encodeToString(responseData).toByteArray(Charsets.UTF_8)
                            val device = _connectedDevices.value.find { it.address == deviceAddress }
                            if (device != null) {
                                sendDataToDevice(device, data)
                                Log.i(LOG_TAG, "成功发送证书到设备 ${device.address}")
                            }
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "生成或发送证书失败: ${e.message}", e)
                        }
                    }
                }
                "files" -> {
                    Log.i(LOG_TAG, "收到 files 类型数据，开始处理文件下载")
                    coroutineScope.launch {
                        try {
                            val filesShareData = decodeFromString<FilesShareData>(bleData.data)
                            Log.i(
                                LOG_TAG,
                                "收到 ${filesShareData.files.size} 个文件，端口: ${filesShareData.port}",
                            )

                            // 解析服务器公钥（从Base64）
                            val serverPublicKey = parsePublicKeyFromBase64(filesShareData.pubKey)

                            // 添加下载任务
                            downloadManager.addDownloadTasks(filesShareData, serverPublicKey)
                            Log.i(LOG_TAG, "文件下载任务已添加")
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "处理文件下载失败: ${e.message}", e)
                        }
                    }
                }
                else -> {
                    Log.w(LOG_TAG, "未知的数据类型: ${bleData.type}")
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "解析JSON失败: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun onClipboardChanged() {
        val clipData = clipboardManager.primaryClip ?: return
        val timestamp = clipData.description.timestamp

        // 检查时间戳，避免重复处理
        if (timestamp == lastTimestamp) return

        lastTimestamp = timestamp

        val text = clipData.getItemAt(0)?.text?.toString() ?: return
        if (text.isEmpty() || text == lastReceivedText) return

        // 创建JSON数据并发送
        val bleData = BleData(type = "text", data = text)
        val data = encodeToString(bleData).toByteArray(Charsets.UTF_8)

        // 向所有已连接的设备发送数据
        val devices = _connectedDevices.value
        if (devices.isEmpty()) return

        devices.forEach { device ->
            val gatt = _connectedGattMap.value[device.address] ?: return@forEach
            val mtu = devicePayloadSizeMap[device.address] ?: DEFAULT_PAYLOAD_SIZE
            val characteristic = gatt.getDataCharacteristic() ?: return@forEach

            try {
                fragmentHandler.calculateFragmentInfo(data.size, mtu) // 仅用于验证
                coroutineScope.launch {
                    try {
                        fragmentHandler.sendFragmentedData(
                            gatt,
                            characteristic,
                            data,
                            mtu,
                        ) { g, c, d -> writeCharacteristic(g, c, d, 0) }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "发送分片失败到 ${device.address}: ${e.message}", e)
                    }
                }
            } catch (_: IllegalArgumentException) {
                // 数据太大，跳过该设备
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun writeCharacteristic(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        data: ByteArray,
        retryCount: Int = 0,
    ) {
        val writeSuccess = gatt.writeCharacteristicCompat(char, data)
        if (writeSuccess) {
            return
        }
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            delay(RETRY_DELAY_MS)
            writeCharacteristic(gatt, char, data, retryCount + 1)
        } else {
            throw Exception("发送失败: 已达到最大重试次数")
        }
    }

    /**
     * 发送数据到指定设备（通用方法）
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun sendDataToDevice(
        device: BluetoothDevice,
        data: ByteArray,
    ) {
        val gatt = _connectedGattMap.value[device.address] ?: return
        val mtu = devicePayloadSizeMap[device.address] ?: DEFAULT_PAYLOAD_SIZE
        val characteristic = gatt.getDataCharacteristic()
        if (characteristic == null) {
            _connectedDevices.value =
                _connectedDevices.value
                    .filter {
                        it.address != device.address
                    }.toMutableList()
            return
        }

        try {
            fragmentHandler.sendFragmentedData(gatt, characteristic, data, mtu) { g, c, d ->
                writeCharacteristic(g, c, d, 0)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "发送数据失败: ${e.message}", e)
            throw e
        }
    }

    /**
     * 发送文本到指定设备
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendTextToDevice(
        device: BluetoothDevice,
        text: String,
    ) {
        val bleData = BleData(type = "text", data = text)
        val data = encodeToString(bleData).toByteArray(Charsets.UTF_8)
        sendDataToDevice(device, data)
        Log.i(LOG_TAG, "成功发送文本到设备 ${device.address}")
    }

    /**
     * 发送文件信息到指定设备
     * @param device 目标设备
     * @param ips 本机的所有IP地址列表
     * @param port WebServer的端口
     * @param pubKey WebServer的公钥（Base64编码）
     * @param files 文件列表，包含endpoint、fileName、fileSize
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendFilesToDevice(
        device: BluetoothDevice,
        ips: List<String>,
        port: Int,
        pubKey: String,
        files: List<FileInfoData>,
    ) {
        if (!_connectedGattMap.value.contains(device.address)) {
            throw Exception("设备未连接: ${device.address}")
        }

        // 构造文件分享数据
        val filesShareData =
            FilesShareData(
                ips = ips,
                port = port,
                pubKey = pubKey,
                files = files,
            )

        // 将文件分享数据序列化为JSON字符串
        val dataJson = encodeToString(filesShareData)

        // 创建ClipboardData，类型为"files"
        val bleData = BleData(type = "files", data = dataJson)
        val data = encodeToString(bleData).toByteArray(Charsets.UTF_8)
        sendDataToDevice(device, data)
        Log.i(LOG_TAG, "成功发送文件信息到设备 ${device.address}")
    }

    fun startScan(scanCallback: ActivityResultLauncher<IntentSenderRequest>) {
        deviceManager.associate(
            pairingRequest,
            object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(intentSender: IntentSender) {
                    val request = IntentSenderRequest.Builder(intentSender).build()
                    scanCallback.launch(request)
                }

                override fun onFailure(error: CharSequence?) {
                    // To handle the failure.
                }
            },
            null,
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connectToDevice(
        device: BluetoothDevice,
        force: Boolean = false,
    ) {
        if (_connectedGattMap.value[device.address] != null && !force) {
            return
        }
        Log.i(LOG_TAG, "连接设备: ${device.address}")
        configManager.addDevice(device.address)
        val gatt =
            device.connectGatt(
                context,
                true,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
        _connectedGattMap.value =
            _connectedGattMap.value.toMutableMap().also {
                it[device.address] = gatt
            }
    }

    private val gattCallback =
        object : BluetoothGattCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                handleConnectionStateChange(gatt, newState)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServiceChanged(gatt: BluetoothGatt) {
                gatt.discoverServices()
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                handleMtuChanged(gatt, mtu, status)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                handleDescriptorWrite(gatt, descriptor, status)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                handleServicesDiscovered(gatt, status)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleCharacteristicChanged(gatt, value)
            }
        }

    /**
     * 处理连接状态变化
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleConnectionStateChange(
        gatt: BluetoothGatt,
        state: Int,
    ) {
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(LOG_TAG, "GATT Connected: ${gatt.device.address}")
                val deviceAddress = gatt.device.address
                // 如果设备还没有MTU值，设置默认值
                if (!devicePayloadSizeMap.containsKey(deviceAddress)) {
                    devicePayloadSizeMap[deviceAddress] = DEFAULT_PAYLOAD_SIZE
                }
                gatt.requestMtu(MAX_MTU_SIZE)
                when (gatt.device.bondState) {
                    BluetoothDevice.BOND_BONDED -> gatt.discoverServices()
                    BluetoothDevice.BOND_NONE -> gatt.device.createBond()
                    BluetoothDevice.BOND_BONDING -> {}
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(LOG_TAG, "GATT Disconnected: ${gatt.device.address}")
                val deviceAddress = gatt.device.address
                _connectedDevices.value =
                    _connectedDevices.value
                        .filter {
                            it != gatt.device
                        }.toMutableList()
                devicePayloadSizeMap.remove(deviceAddress)
                fragmentHandler.clearDeviceFragments(deviceAddress)
            }
        }
    }

    /**
     * 处理 MTU 变化
     */
    private fun handleMtuChanged(
        gatt: BluetoothGatt,
        mtu: Int,
        status: Int,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val deviceAddress = gatt.device.address
            val payloadSize = min(mtu - 3, MAX_PAYLOAD_SIZE)
            devicePayloadSizeMap[deviceAddress] = payloadSize
            Log.i(LOG_TAG, "设备 $deviceAddress MTU 更新为 $mtu, payloadSize: $payloadSize")
        }
    }

    /**
     * 处理描述符写入
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (descriptor.uuid == CCCD_UUID) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(LOG_TAG, "CCCD写入成功，等待指示数据")
                }
                BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
                BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
                -> {
                    Log.i(LOG_TAG, "需要配对/加密，发起配对后重试")
                    gatt.device.createBond()
                }
                else -> {
                    Log.w(LOG_TAG, "CCCD写入失败，status=$status")
                }
            }
        }
    }

    /**
     * 处理服务发现
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleServicesDiscovered(
        gatt: BluetoothGatt,
        status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) return

        val dataChar = gatt.getDataCharacteristic() ?: return

        gatt.setCharacteristicNotification(dataChar, true)

        val descriptor = dataChar.getDescriptor(CCCD_UUID) ?: return
        val writeResult =
            gatt.writeDescriptorCompat(
                descriptor,
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
            )
        if (writeResult) {
            Log.i(LOG_TAG, "已订阅")
            // 优化：检查是否存在，避免不必要的列表创建
            val currentDevices = _connectedDevices.value
            if (currentDevices.none { it.address == gatt.device.address }) {
                val newDevices = currentDevices.toMutableList()
                newDevices.add(gatt.device)
                _connectedDevices.value = newDevices
            }
        }
    }

    /**
     * 处理特征值变化
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleCharacteristicChanged(
        gatt: BluetoothGatt,
        value: ByteArray,
    ) {
        val deviceAddress = gatt.device.address

        // 使用分片处理器处理接收到的分片
        val mergedData = fragmentHandler.processReceivedFragment(deviceAddress, value)

        // 如果接收完整，处理完整数据
        if (mergedData != null) {
            processReceivedData(mergedData, deviceAddress)
        }
    }
}
