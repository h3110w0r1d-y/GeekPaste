package org.nanohttpd.request

/**
 * HTTP 请求方法，能够将字符串解码回其枚举值
 */
enum class Method {
    GET,
    PUT,
    POST,
    DELETE,
    HEAD,
    OPTIONS,
    TRACE,
    CONNECT,
    PATCH,
    ;

    companion object {
        fun lookup(method: String?): Method? =
            method?.let {
                try {
                    valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
    }
}
