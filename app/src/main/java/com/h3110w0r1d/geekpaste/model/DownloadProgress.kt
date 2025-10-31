package com.h3110w0r1d.geekpaste.model

/**
 * 下载状态
 */
enum class DownloadStatus {
    /** 未开始 */
    NOT_STARTED,
    /** 正在下载 */
    DOWNLOADING,
    /** 下载完成 */
    COMPLETED,
    /** 下载失败 */
    FAILED
}

/**
 * 文件下载进度
 * @param endpoint 文件的endpoint路径
 * @param status 下载状态
 * @param downloadedBytes 已下载的字节数
 */
data class FileDownloadProgress(
    val endpoint: String,
    val status: DownloadStatus = DownloadStatus.NOT_STARTED,
    val downloadedBytes: Long = 0L,
) {
    /**
     * 获取文件名（从endpoint提取）
     */
    val fileName: String
        get() = endpoint.substringAfterLast('/')
}
