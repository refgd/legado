package io.legado.app.utils

import androidx.annotation.Keep
import io.legado.app.exception.NoStackTraceException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object UrlOptions {
    val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")
    val customIp: ConcurrentHashMap<String, String> by lazy { ConcurrentHashMap() }

    fun parseStringMap(value: String, context: String): Map<String, String> {
        return GSON.fromJsonObject<Map<String, String>>(value).getOrElse {
            throw NoStackTraceException(
                "$context URL option JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }
    }

    fun parseAnyMap(value: String, context: String): Map<String, Any> {
        return GSON.fromJsonObject<Map<String, Any>>(value).getOrElse {
            throw NoStackTraceException(
                "$context URL option object JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }
    }

    fun parseAnyMapArray(value: String, context: String): List<Map<String, Any>> {
        return GSON.fromJsonArray<Map<String, Any>>(value).getOrElse {
            throw NoStackTraceException(
                "$context URL option array JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }
    }
}

data class ConcurrentRecord(
    var time: Long,
    var accessLimit: Int,
    var interval: Int,
    var frequency: Int
)

@Keep
data class UrlOption(
    private var method: String? = null,
    private var charset: String? = null,
    private var headers: Any? = null,
    private var body: Any? = null,
    private var origin: String? = null,
    private var retry: Int? = null,
    private var type: String? = null,
    private var webView: Any? = null,
    private var webJs: String? = null,
    private var dnsIp: String? = null,
    private var js: String? = null,
    private var bodyJs: String? = null,
    private var serverID: Long? = null,
    private var webViewDelayTime: Long? = null,
) {
    fun setMethod(value: String?) {
        method = if (value.isNullOrBlank()) null else value
    }

    fun getMethod(): String? = method

    fun setCharset(value: String?) {
        charset = if (value.isNullOrBlank()) null else value
    }

    fun getCharset(): String? = charset

    fun setOrigin(value: String?) {
        origin = if (value.isNullOrBlank()) null else value
    }

    fun getOrigin(): String? = origin

    fun setRetry(value: String?) {
        retry = if (value.isNullOrEmpty()) null else value.toIntOrNull()
    }

    fun getRetry(): Int = retry ?: 0

    fun setType(value: String?) {
        type = if (value.isNullOrBlank()) null else value
    }

    fun getType(): String? = type

    fun useWebView(): Boolean {
        return when (webView) {
            null, "", false, "false" -> false
            else -> true
        }
    }

    fun useWebView(boolean: Boolean) {
        webView = if (boolean) true else null
    }

    fun setHeaders(value: String?) {
        headers = if (value.isNullOrBlank()) {
            null
        } else {
            UrlOptions.parseAnyMap(value, "UrlOption.headers")
        }
    }

    fun getHeaderMap(): Map<*, *>? {
        return when (val value = headers) {
            is Map<*, *> -> value
            is String -> UrlOptions.parseAnyMap(value, "UrlOption.headers")
            else -> null
        }
    }

    fun setBody(value: String?) {
        body = when {
            value.isNullOrBlank() -> null
            value.isJsonObject() -> UrlOptions.parseAnyMap(value, "UrlOption.body")
            value.isJsonArray() -> UrlOptions.parseAnyMapArray(value, "UrlOption.body")
            else -> value
        }
    }

    fun getBody(): String? {
        return body?.let {
            it as? String ?: GSON.toJson(it)
        }
    }

    fun setWebJs(value: String?) {
        webJs = if (value.isNullOrBlank()) null else value
    }

    fun getWebJs(): String? = webJs

    fun setDnsIp(value: String?) {
        dnsIp = if (value.isNullOrBlank()) null else value
    }

    fun getDnsIp(): String? = dnsIp

    fun setJs(value: String?) {
        js = if (value.isNullOrBlank()) null else value
    }

    fun getJs(): String? = js

    fun setBodyJs(value: String?) {
        bodyJs = if (value.isNullOrBlank()) null else value
    }

    fun getBodyJs(): String? = bodyJs

    fun setServerID(value: String?) {
        serverID = if (value.isNullOrBlank()) null else value.toLong()
    }

    fun getServerID(): Long? = serverID

    fun setWebViewDelayTime(value: String?) {
        webViewDelayTime = if (value.isNullOrBlank()) null else value.toLong()
    }

    fun getWebViewDelayTime(): Long? = webViewDelayTime
}
