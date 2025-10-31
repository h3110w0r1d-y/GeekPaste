package org.nanohttpd.response

/**
 * 一些 HTTP 响应状态码
 */
enum class Status(
    val requestStatus: Int,
    val description: String,
) {
    SWITCH_PROTOCOL(101, "Switching Protocols"),
    OK(200, "OK"),
    CREATED(201, "Created"),
    ACCEPTED(202, "Accepted"),
    NO_CONTENT(204, "No Content"),
    PARTIAL_CONTENT(206, "Partial Content"),
    MULTI_STATUS(207, "Multi-Status"),

    REDIRECT(301, "Moved Permanently"),

    /**
     * 许多用户代理错误地处理 302，违反了 RFC1945 规范（例如，将 POST 重定向到 GET）。
     * 303 和 307 在 RFC2616 中添加以解决此问题。除非调用的用户代理不支持 303 和 307 功能，
     * 否则应优先使用 303 和 307。
     */
    @Deprecated("使用 REDIRECT_SEE_OTHER 或 TEMPORARY_REDIRECT 代替")
    FOUND(302, "Found"),
    REDIRECT_SEE_OTHER(303, "See Other"),
    NOT_MODIFIED(304, "Not Modified"),
    TEMPORARY_REDIRECT(307, "Temporary Redirect"),

    BAD_REQUEST(400, "Bad Request"),
    UNAUTHORIZED(401, "Unauthorized"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
    NOT_ACCEPTABLE(406, "Not Acceptable"),
    REQUEST_TIMEOUT(408, "Request Timeout"),
    CONFLICT(409, "Conflict"),
    GONE(410, "Gone"),
    LENGTH_REQUIRED(411, "Length Required"),
    PRECONDITION_FAILED(412, "Precondition Failed"),
    PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type"),
    RANGE_NOT_SATISFIABLE(416, "Requested Range Not Satisfiable"),
    EXPECTATION_FAILED(417, "Expectation Failed"),
    TOO_MANY_REQUESTS(429, "Too Many Requests"),

    INTERNAL_ERROR(500, "Internal Server Error"),
    NOT_IMPLEMENTED(501, "Not Implemented"),
    SERVICE_UNAVAILABLE(503, "Service Unavailable"),
    UNSUPPORTED_HTTP_VERSION(505, "HTTP Version Not Supported"), ;

    fun lookup(requestStatus: Int): Status? = entries.find { it.requestStatus == requestStatus }
}
