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
import com.h3110w0r1d.geekpaste.data.AppConfig
import com.h3110w0r1d.geekpaste.data.ConfigManager
import com.h3110w0r1d.geekpaste.utils.BleManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppViewModel(
    @ApplicationContext
    private val context: Context,
    private val bleManager: BleManager,
    private val configManager: ConfigManager,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val appConfig: StateFlow<AppConfig> = configManager.appConfig

    val connectedDevices = bleManager.connectedDevices
    val connectedGattMap = bleManager.connectedGattMap

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

            if (!appConfig.value.isConfigInitialized) {
                appConfig.drop(1).first()
            }
            connectSavedDevices()
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
}
