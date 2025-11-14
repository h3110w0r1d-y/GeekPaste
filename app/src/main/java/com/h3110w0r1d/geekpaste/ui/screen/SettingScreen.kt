package com.h3110w0r1d.geekpaste.ui.screen

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import com.h3110w0r1d.geekpaste.BuildConfig
import com.h3110w0r1d.geekpaste.R
import com.h3110w0r1d.geekpaste.data.config.ConfigManager.Companion.LocalGlobalAppConfig
import com.h3110w0r1d.geekpaste.model.AppViewModel.Companion.LocalGlobalAppViewModel
import com.h3110w0r1d.geekpaste.ui.components.LargeFlexibleTopAppBar
import com.h3110w0r1d.geekpaste.ui.theme.getPrimaryColorMap
import kotlin.collections.toList
import kotlin.to

@SuppressLint("ShowToast")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen() {
    val viewModel = LocalGlobalAppViewModel.current
    val appConfig = LocalGlobalAppConfig.current
    val context = LocalContext.current

    val isDarkMode =
        if (appConfig.nightModeFollowSystem) {
            isSystemInDarkTheme()
        } else {
            appConfig.nightModeEnabled
        }
    var selectColorDialogOpened by remember { mutableStateOf(false) }

    // SAF 目录选择器
    val directoryPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            uri?.let {
                // 持久化URI权限
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    context.contentResolver.takePersistableUriPermission(it, takeFlags)
                    var uriString = it.toString()
                    Log.i("SettingScreen", "uriString: $uriString")
                    val docIndex = uriString.indexOf("/document")
                    if (docIndex != -1) {
                        uriString = uriString.take(docIndex)
                    }
                    viewModel.updateAppConfig(
                        appConfig.copy(
                            downloadPath = uriString,
                        ),
                    )
                } catch (_: Exception) {
                }
            }
        }

    val themeColorNamesMap =
        hashMapOf(
            "amber" to stringResource(R.string.amber_theme),
            "blue_grey" to stringResource(R.string.blue_grey_theme),
            "blue" to stringResource(R.string.blue_theme),
            "brown" to stringResource(R.string.brown_theme),
            "cyan" to stringResource(R.string.cyan_theme),
            "deep_orange" to stringResource(R.string.deep_orange_theme),
            "deep_purple" to stringResource(R.string.deep_purple_theme),
            "green" to stringResource(R.string.green_theme),
            "indigo" to stringResource(R.string.indigo_theme),
            "light_blue" to stringResource(R.string.light_blue_theme),
            "light_green" to stringResource(R.string.light_green_theme),
            "lime" to stringResource(R.string.lime_theme),
            "orange" to stringResource(R.string.orange_theme),
            "pink" to stringResource(R.string.pink_theme),
            "purple" to stringResource(R.string.purple_theme),
            "red" to stringResource(R.string.red_theme),
            "teal" to stringResource(R.string.teal_theme),
            "yellow" to stringResource(R.string.yellow_theme),
            "sakura" to stringResource(R.string.sakura_theme),
        )
    val themeColorKeys = themeColorNamesMap.keys.toList()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            rememberTopAppBarState(),
            snapAnimationSpec = null,
        )
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .padding(innerPadding)
                    .verticalScroll(scrollState),
        ) {
            SettingItemGroup(stringResource(R.string.download))

            SettingItem(
                imageVector = Icons.Outlined.Download,
                title = stringResource(R.string.download_path),
                trailingContent = {
                    if (appConfig.downloadPath != "") {
                        IconButton(
                            onClick = {
                                viewModel.updateAppConfig(
                                    appConfig.copy(
                                        downloadPath = "",
                                    ),
                                )
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SettingsBackupRestore,
                                contentDescription = null,
                            )
                        }
                    }
                },
                description =
                    if (appConfig.downloadPath.isEmpty()) {
                        stringResource(R.string.download_path_default)
                    } else {
                        try {
                            getReadablePathFromTreeUri(appConfig.downloadPath.toUri())
                        } catch (e: Exception) {
                            viewModel.updateAppConfig(
                                appConfig.copy(
                                    downloadPath = "",
                                ),
                            )
                            stringResource(R.string.download_path_default)
                        }
                    },
                onClick = {
                    directoryPickerLauncher.launch(null)
                },
            )

            SettingItemGroup(stringResource(R.string.appearance))

            SettingItem(
                imageVector = ImageVector.vectorResource(R.drawable.invert_colors_24px),
                title = stringResource(R.string.night_mode_follow_system),
                trailingContent = {
                    Switch(
                        checked = appConfig.nightModeFollowSystem,
                        onCheckedChange = null,
                    )
                },
                onClick = {
                    viewModel.updateAppConfig(
                        appConfig.copy(
                            nightModeFollowSystem = !appConfig.nightModeFollowSystem,
                        ),
                    )
                },
            )
            if (!appConfig.nightModeFollowSystem) {
                SettingItem(
                    imageVector = ImageVector.vectorResource(R.drawable.dark_mode_24px),
                    title = stringResource(R.string.night_mode_enabled),
                    trailingContent = {
                        Switch(
                            checked = appConfig.nightModeEnabled,
                            onCheckedChange = null,
                        )
                    },
                    onClick = {
                        viewModel.updateAppConfig(
                            appConfig.copy(
                                nightModeEnabled = !appConfig.nightModeEnabled,
                            ),
                        )
                    },
                )
            }

            SettingItem(
                imageVector = Icons.Outlined.Palette,
                title = stringResource(R.string.use_system_color),
                trailingContent = {
                    Switch(
                        checked = appConfig.isUseSystemColor,
                        onCheckedChange = null,
                    )
                },
                onClick = {
                    viewModel.updateAppConfig(
                        appConfig.copy(
                            isUseSystemColor = !appConfig.isUseSystemColor,
                        ),
                    )
                },
            )
            if (!appConfig.isUseSystemColor) {
                SettingItem(
                    imageVector = ImageVector.vectorResource(R.drawable.colors_24px),
                    title = stringResource(R.string.theme_color),
                    description = themeColorNamesMap.get(appConfig.themeColor),
                    onClick = {
                        selectColorDialogOpened = true
                    },
                )
            }

            SettingItem(
                imageVector = Icons.Outlined.InvertColors,
                title = stringResource(R.string.pure_black_dark_theme),
                description = stringResource(R.string.pure_black_dark_theme_summary),
                trailingContent = {
                    Switch(
                        checked = appConfig.pureBlackDarkTheme,
                        onCheckedChange = null,
                    )
                },
                onClick = {
                    viewModel.updateAppConfig(
                        appConfig.copy(
                            pureBlackDarkTheme = !appConfig.pureBlackDarkTheme,
                        ),
                    )
                },
            )

            SettingItemGroup(stringResource(R.string.about))

            SettingItem(
                imageVector = Icons.Outlined.Person,
                title = stringResource(R.string.author),
                description = "@h3110w0r1d-y",
                onClick = {
                    val uri = "https://github.com/h3110w0r1d-y".toUri()
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
            )

            SettingItem(
                imageVector = Icons.Outlined.Merge,
                title = stringResource(R.string.repository),
                description = BuildConfig.REPO_URL,
                onClick = {
                    val uri = BuildConfig.REPO_URL.toUri()
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
            )
            var clickCount by remember { mutableIntStateOf(0) }
            var mToast by remember { mutableStateOf<Toast?>(null) }
            var lastTimeStamp by remember { mutableLongStateOf(0L) }
            val maxClickCount = 7
            SettingItem(
                imageVector = Icons.Outlined.Info,
                title = stringResource(R.string.version),
                description = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onClick = {
                    if (mToast != null) {
                        mToast?.cancel()
                    }
                    if (System.currentTimeMillis() - lastTimeStamp < 500) {
                        clickCount++
                    } else {
                        clickCount = 1
                    }
                    lastTimeStamp = System.currentTimeMillis()
                    if (clickCount >= maxClickCount) {
                        mToast = Toast.makeText(context, "啥都木有", Toast.LENGTH_SHORT)
                        mToast?.show()
                    } else {
                        mToast =
                            Toast.makeText(
                                context,
                                "Click $clickCount times",
                                Toast.LENGTH_SHORT,
                            )
                        mToast?.show()
                    }
                },
            )
        }
    }
    if (selectColorDialogOpened) {
        Dialog(onDismissRequest = {
            selectColorDialogOpened = false
        }) {
            Card(
                colors =
                    CardDefaults.cardColors().copy(
                        containerColor = colorScheme.background,
                    ),
                modifier =
                    Modifier
                        .fillMaxHeight(.7f),
            ) {
                LazyColumn(
                    modifier =
                        Modifier
                            .padding(8.dp, 16.dp),
                ) {
                    items(themeColorKeys) {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    imageVector =
                                        if (appConfig.themeColor == it) {
                                            Icons.Filled.Palette
                                        } else {
                                            Icons.Outlined.Palette
                                        },
                                    contentDescription = null,
                                    tint = getPrimaryColorMap(isDarkMode, it),
                                )
                            },
                            headlineContent = {
                                Text(text = themeColorNamesMap.get(it) ?: "")
                            },
                            modifier =
                                Modifier.clickable(
                                    enabled = true,
                                    onClick = {
                                        viewModel.updateAppConfig(
                                            appConfig.copy(
                                                themeColor = it,
                                            ),
                                        )
                                        selectColorDialogOpened = false
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingItemGroup(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        color = colorScheme.primary,
        modifier =
            Modifier
                .padding(start = 16.dp, top = 16.dp)
                .padding(vertical = 4.dp),
    )
}

@Composable
fun SettingItem(
    imageVector: ImageVector,
    title: String,
    description: String? = null,
    onClick: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                modifier = if (description != null)Modifier.height(42.dp) else Modifier,
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { if (description != null) Text(description) },
        trailingContent = trailingContent,
        modifier =
            Modifier.clickable(
                onClick = onClick,
            ),
    )
}

fun getReadablePathFromTreeUri(uri: Uri): String {
    val docId = DocumentsContract.getTreeDocumentId(uri)
    Log.i("getReadablePathFromTreeUri", "docId: $docId")
    val parts = docId.split(":")

    if (parts.size == 1) {
        // 例如 primary
        if (parts[0] == "primary") {
            return "内部存储"
        }
        return "外部存储($docId)"
    }

    val volume = parts[0] // primary or 1234-5678(SD卡)
    val relativePath = parts[1] // Download / Documents ...

    val storageName =
        when (volume.lowercase()) {
            "primary" -> "内部存储"
            else -> "外部存储($volume)"
        }

    return "$storageName/$relativePath"
}
