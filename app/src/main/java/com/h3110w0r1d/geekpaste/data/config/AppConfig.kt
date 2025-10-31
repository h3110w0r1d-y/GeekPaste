package com.h3110w0r1d.geekpaste.data.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/**
 * 配置类
 */
data class AppConfig(
    val isUseSystemColor: Boolean = true,
    val themeColor: String = "blue",
    val nightModeFollowSystem: Boolean = true,
    val nightModeEnabled: Boolean = false,
    val pureBlackDarkTheme: Boolean = false,
    val isConfigInitialized: Boolean = false,
    val savedDevices: List<DeviceInfo> = listOf(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
data class DeviceInfo(
    val address: String,
    val name: String,
)
