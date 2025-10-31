package com.h3110w0r1d.clipboardsync.utils

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Context.COMPANION_DEVICE_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat.startIntentSenderForResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.collections.set
import kotlin.math.min

class BleManager(
    private val context: Context,
) {
    companion object {
        // 自定义剪贴板同步服务UUID
        private val CLIPBOARD_SERVICE_UUID = UUID.fromString("91106bcd-4f0f-4519-8a99-b09fff8c13ba")
        private val CLIPBOARD_DATA_CHARACTERISTIC_UUID = UUID.fromString("10d7925f-be10-455c-b7bc-5cb663ae9630")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var payloadSize = 20
    private val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CLIPBOARD_SERVICE_UUID)).build()
    private val bleFilter = BluetoothLeDeviceFilter.Builder().setScanFilter(scanFilter).build()
    private val pairingRequest = AssociationRequest.Builder().addDeviceFilter(bleFilter).build()
    val deviceManager = context.getSystemService(COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
    private val bluetoothAdapter = (context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val gattMap = mutableMapOf<String, BluetoothGatt>()

    private val _connectedDevices = MutableStateFlow<MutableList<BluetoothDevice>>(mutableListOf())
    val connectedDevices: StateFlow<MutableList<BluetoothDevice>> = _connectedDevices

    // 1) 监听配对状态变化
    private val bondReceiver =
        object : BroadcastReceiver() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) {
                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED != intent.action) return
                val device =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                val gatt = gattMap[device.address] ?: return

                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                when (bondState) {
                    BluetoothDevice.BOND_BONDING -> {
                        // 正在配对
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        // 2) 配对完成：若已连接，重新发现服务以便后续订阅
                        gatt.discoverServices()
                    }
                    BluetoothDevice.BOND_NONE -> {
                        // 配对失败/取消
                    }
                }
            }
        }

    init {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(bondReceiver, filter)
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
    fun connectToDevice(device: BluetoothDevice) {
        if (gattMap[device.address] != null) {
            return
        }
        val gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gattMap[device.address] = gatt
    }

    private val gattCallback =
        object : BluetoothGattCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                Log.i("BLE_TEST", "onConnectionStateChange: $newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        gatt.requestMtu(517)
                        when (gatt.device.bondState) {
                            BluetoothDevice.BOND_BONDED -> {
                                Log.i("BLE_TEST", "设备已配对，开始发现服务...")
                                gatt.discoverServices()
                            }

                            BluetoothDevice.BOND_NONE -> {
                                Log.i("BLE_TEST", "设备未配对，开始配对 (createBond)...")
                                gatt.device.createBond()
                                // 后续操作将由 bondStateReceiver 在 BOND_BONDED 状态时触发
                            }

                            BluetoothDevice.BOND_BONDING -> {
                                Log.i("BLE_TEST", "设备正在配对中，等待结果...")
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _connectedDevices.value = _connectedDevices.value.filter { it != gatt.device }.toMutableList()
                        gattMap.remove(gatt.device.address)
                    }
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServiceChanged(gatt: BluetoothGatt) {
                super.onServiceChanged(gatt)
                gatt.discoverServices()
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    payloadSize = min(mtu - 3, 512)
                    Log.i("BLE_TEST", "MTU 更新为 $mtu")
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (descriptor.uuid == CCCD_UUID) {
                    when (status) {
                        BluetoothGatt.GATT_SUCCESS -> {
                            // 仅表示CCCD写入成功，下一步以接收数据确认真正生效
                            println("CCCD写入成功，等待指示数据")
                            // 可先标记“已写入成功”，真正启用成功以后再置位
                        }
                        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
                        BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
                        -> {
                            println("需要配对/加密，发起配对后重试")
                            gatt.device.createBond()
                        }
                        else -> {
                            println("CCCD写入失败，status=$status")
                        }
                    }
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                Log.i("BLE_TEST", "onServicesDiscovered: $status")
                if (status != BluetoothGatt.GATT_SUCCESS) return

                val service = gatt.getService(CLIPBOARD_SERVICE_UUID)
                if (service == null) {
                    return
                }
                println("确认设备提供剪贴板同步服务: ${gatt.device.name ?: gatt.device.address}")

                // 设置数据特征通知
                val dataChar = service.getCharacteristic(CLIPBOARD_DATA_CHARACTERISTIC_UUID)
                if (dataChar == null) {
                    println("警告: 设备缺少必需的剪贴板同步特征")
                    return
                }
                gatt.setCharacteristicNotification(dataChar, true)
                // 写入特征描述符来启用通知
                val descriptor =
                    dataChar.getDescriptor(CCCD_UUID)
                if (descriptor == null) {
                    println("警告: 设备缺少必需的特征描述符")
                    return
                }
                val value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                println("已启用剪贴板数据特征通知")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                Log.i("BLE", "onCharacteristicChanged: ${String(value, Charsets.UTF_8)}")
            }
        }

    /**
     * 验证设备是否提供剪贴板同步服务
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isClipboardSyncDevice(result: ScanResult): Boolean {
        Log.i("BLE_TEST", result.device.address)
        val scanRecord = result.scanRecord ?: return false
        Log.i("BLE_TEST", scanRecord.serviceUuids.toString())
        // 方法1: 检查广播数据中的服务UUID
        val serviceUuids = scanRecord.serviceUuids
        if (serviceUuids != null) {
            val clipboardServiceParcelUuid = ParcelUuid(CLIPBOARD_SERVICE_UUID)
            if (serviceUuids.contains(clipboardServiceParcelUuid)) {
                Log.i("BLE_TEST", "Found clipboard sync device")
                return true
            }
        }

        return false
    }
}
