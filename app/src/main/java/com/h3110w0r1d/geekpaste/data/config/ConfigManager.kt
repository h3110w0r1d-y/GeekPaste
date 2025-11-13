package com.h3110w0r1d.geekpaste.data.config

import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/**
 * 配置键管理
 */
private object ConfigKeys {
    // 主题
    val isUseSystemColor = booleanPreferencesKey("is_use_system_color")
    val themeColor = stringPreferencesKey("theme_color")
    val nightModeFollowSystem = booleanPreferencesKey("night_mode_follow_system")
    val nightModeEnabled = booleanPreferencesKey("night_mode_enabled")
    val pureBlackDarkTheme = booleanPreferencesKey("pure_black_dark_theme")
    val savedDevices = stringPreferencesKey("saved_devices")
}

class ConfigManager(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        val LocalGlobalAppConfig =
            staticCompositionLocalOf<AppConfig> {
                error("AppConfig not provided!")
            }
    }

    val appConfig: StateFlow<AppConfig> =
        dataStore.data
            .map { preferences ->
                Log.i("ConfigManager", "Loading appConfig...")
                AppConfig(
                    isUseSystemColor = preferences[ConfigKeys.isUseSystemColor] ?: true,
                    themeColor = preferences[ConfigKeys.themeColor] ?: "blue",
                    nightModeFollowSystem = preferences[ConfigKeys.nightModeFollowSystem] ?: true,
                    nightModeEnabled = preferences[ConfigKeys.nightModeEnabled] ?: false,
                    pureBlackDarkTheme = preferences[ConfigKeys.pureBlackDarkTheme] ?: false,
                    savedDevices =
                        Json.decodeFromString<List<DeviceInfo>>(
                            preferences[ConfigKeys.savedDevices] ?: "[]",
                        ),
                    isConfigInitialized = true,
                )
            }.stateIn(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                started = SharingStarted.Eagerly,
                initialValue = AppConfig(),
            )

    suspend fun addDevice(address: String) {
        println("addDevice2: $address")
        val device = appConfig.value.savedDevices.firstOrNull { it.address == address }
        println("addDevice3: $device")
        if (device != null) {
            return
        }
        println("addDevice3: $address")
        val newDevicesConfig =
            appConfig.value.savedDevices.toMutableList().apply {
                add(DeviceInfo(address = address, name = address))
            }
        updateDevicesConfig(newDevicesConfig)
    }

    suspend fun updateDeviceName(
        address: String,
        name: String,
    ) {
        val newDevicesConfig =
            appConfig.value.savedDevices.toMutableList().map {
                if (it.address == address) {
                    it.copy(name = name)
                } else {
                    it
                }
            }
        updateDevicesConfig(newDevicesConfig)
    }

    suspend fun removeDevice(address: String) {
        val newDevicesConfig =
            appConfig.value.savedDevices.toMutableList().filter {
                it.address != address
            }
        updateDevicesConfig(newDevicesConfig)
    }

    suspend fun updateDevicesConfig(devicesConfig: List<DeviceInfo>) {
        println(Json.encodeToString(devicesConfig))
        dataStore.edit { preferences ->
            preferences[ConfigKeys.savedDevices] = Json.encodeToString(devicesConfig)
        }
    }

    suspend fun updateAppConfig(appConfig: AppConfig) {
        dataStore.edit { preferences ->
            preferences[ConfigKeys.isUseSystemColor] = appConfig.isUseSystemColor
            preferences[ConfigKeys.themeColor] = appConfig.themeColor
            preferences[ConfigKeys.nightModeFollowSystem] = appConfig.nightModeFollowSystem
            preferences[ConfigKeys.nightModeEnabled] = appConfig.nightModeEnabled
            preferences[ConfigKeys.pureBlackDarkTheme] = appConfig.pureBlackDarkTheme
        }
    }
}
