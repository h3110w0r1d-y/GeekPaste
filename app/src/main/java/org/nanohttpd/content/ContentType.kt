package org.nanohttpd.content

import java.util.regex.Pattern

/**
 * Content-Type 解析器
 */
class ContentType(
    val contentTypeHeader: String?,
) {
    companion object {
        private const val ASCII_ENCODING = "US-ASCII"
        private const val CONTENT_REGEX = "[ |\t]*([^/^ ^;^,]+/[^ ^;^,]+)"
        private val MIME_PATTERN = Pattern.compile(CONTENT_REGEX, Pattern.CASE_INSENSITIVE)
        private const val CHARSET_REGEX =
            "[ |\t]*(charset)[ |\t]*=[ |\t]*['|\"]?([^\"^'^;^,]*)['|\"]?"
        private val CHARSET_PATTERN = Pattern.compile(CHARSET_REGEX, Pattern.CASE_INSENSITIVE)
    }

    val contentType: String
    private val encoding: String

    init {
        if (contentTypeHeader != null) {
            contentType = getDetailFromContentHeader(contentTypeHeader, MIME_PATTERN, "", 1)
            encoding =
                getDetailFromContentHeader(contentTypeHeader, CHARSET_PATTERN, "UTF-8", 2)
        } else {
            contentType = ""
            encoding = "UTF-8"
        }
    }

    private fun getDetailFromContentHeader(
        contentTypeHeader: String,
        pattern: Pattern,
        defaultValue: String,
        group: Int,
    ): String {
        val matcher = pattern.matcher(contentTypeHeader)
        return if (matcher.find()) matcher.group(group) else defaultValue
    }

    fun getEncoding(): String = encoding.takeIf { it.isNotEmpty() } ?: ASCII_ENCODING

    fun tryUTF8(): ContentType =
        if (encoding.isEmpty()) {
            ContentType("$contentTypeHeader; charset=UTF-8")
        } else {
            this
        }
}
