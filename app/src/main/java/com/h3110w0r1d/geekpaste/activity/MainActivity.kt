package com.h3110w0r1d.geekpaste.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.h3110w0r1d.geekpaste.data.config.ConfigManager.Companion.LocalGlobalAppConfig
import com.h3110w0r1d.geekpaste.model.AppViewModel
import com.h3110w0r1d.geekpaste.model.AppViewModel.Companion.LocalGlobalAppViewModel
import com.h3110w0r1d.geekpaste.ui.AppNavigation
import com.h3110w0r1d.geekpaste.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var appViewModel: AppViewModel

    companion object {
        val LocalGlobalScanCallback =
            staticCompositionLocalOf<ActivityResultLauncher<IntentSenderRequest>> {
                error("ActivityResultLauncher not provided!")
            }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            // 在这里处理权限请求的结果
            appViewModel.handlePermissionResult(permissions)
        }

    @SuppressLint("MissingPermission")
    private val scanCallback =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            appViewModel.handleScanResult(result)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 收集所有需要请求的权限
        val permissionsToRequest = mutableListOf<String>()
        
        // 检查蓝牙权限
        if (!appViewModel.checkBlePermission()) {
            permissionsToRequest.addAll(appViewModel.missingPermissions())
        }
        
        // 检查通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // 如果有需要请求的权限，则请求
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
        
        setContent {
            val appConfig by appViewModel.appConfig.collectAsState()
            // 只有在配置初始化完成后才显示主界面，防止配置未加载完成时闪现引导界面
            if (!appConfig.isConfigInitialized) {
                return@setContent
            }
            Log.i("MainActivity", "onCreate: $appConfig")
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
                CompositionLocalProvider(
                    LocalGlobalAppViewModel provides appViewModel,
                    LocalGlobalAppConfig provides appConfig,
                    LocalGlobalScanCallback provides scanCallback,
                ) {
                    AppNavigation()
                }
            }
        }
    }
}
