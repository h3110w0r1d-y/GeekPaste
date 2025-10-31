package com.h3110w0r1d.geekpaste.model

import android.net.Uri

/**
 * 分享内容的数据模型
 */
sealed class ShareContent {
    /**
     * 文本内容
     */
    data class Text(val text: String) : ShareContent()

    /**
     * 文件列表
     */
    data class Files(val files: List<FileInfo>) : ShareContent()

    /**
     * 文件信息
     */
    data class FileInfo(
        val uri: Uri,
        val name: String,
        val size: Long,
    )
}

