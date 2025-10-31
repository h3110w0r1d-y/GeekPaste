package com.h3110w0r1d.clipboardsync.model

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.h3110w0r1d.clipboardsync.data.config.AppConfig
import com.h3110w0r1d.clipboardsync.data.config.AppConfigManager
import com.h3110w0r1d.clipboardsync.utils.BleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        val bleManager: BleManager,
        private val configManager: AppConfigManager,
    ) : ViewModel() {
        val appConfig: StateFlow<AppConfig> = configManager.appConfig

        // 蓝牙权限是否被授予
        private val _isBluetoothPermissionGranted = MutableStateFlow<Boolean?>(null)
        val isBluetoothPermissionGranted: StateFlow<Boolean?> = _isBluetoothPermissionGranted

        fun updateAppConfig(config: AppConfig) {
            viewModelScope.launch {
                configManager.updateAppConfig(config)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun connectToDevice(device: BluetoothDevice) {
            viewModelScope.launch {
                bleManager.connectToDevice(device)
            }
        }
    }

val LocalGlobalViewModel =
    staticCompositionLocalOf<AppViewModel> {
        error("AppViewModel not provided!")
    }
