package io.legado.app.help.webView

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import io.legado.app.data.entities.BaseSource
import io.legado.app.model.webBook.RustAnalyzerBridge
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

fun fetchModifiedContentWithRust(
    url: String,
    request: WebResourceRequest,
    source: BaseSource,
    cookie: String?,
    rulePath: String,
    setCookie: (String) -> Unit
): WebResourceResponse? {
    return runCatching {
        val rustUrl = url.withWebViewRequestOptions(request, cookie)
        val response = RustAnalyzerBridge.fetchRawResponse(source, rustUrl, rulePath)
        response.headersList.forEach { header ->
            val name = header.getOrNull(0) ?: return@forEach
            val value = header.getOrNull(1) ?: return@forEach
            if (name.equals("Set-Cookie", ignoreCase = true)) {
                setCookie(value)
            }
        }
        val contentType = response.contentType
        val mimeType = contentType?.substringBefore(";")?.trim()?.takeIf { it.isNotBlank() }
            ?: "text/html"
        val charset = contentType.charsetOrUtf8()
        val bodyText = String(response.body, charset).insertWebViewJsUrl()
        WebResourceResponse(
            mimeType,
            charset.name(),
            ByteArrayInputStream(bodyText.toByteArray(charset))
        )
    }.getOrNull()
}

private fun String.withWebViewRequestOptions(
    request: WebResourceRequest,
    cookie: String?
): String {
    val headers = JSONObject()
    if (!cookie.isNullOrEmpty()) {
        headers.put("Cookie", cookie)
    }
    request.requestHeaders?.forEach { (key, value) ->
        if (key.isNotBlank()) {
            headers.put(key, value)
        }
    }
    val options = JSONObject()
    if (!request.method.equals("GET", ignoreCase = true)) {
        options.put("method", request.method)
    }
    if (headers.length() > 0) {
        options.put("headers", headers)
    }
    return if (options.length() > 0) {
        "$this,${options}"
    } else {
        this
    }
}

private fun String?.charsetOrUtf8(): Charset {
    val raw = this ?: return Charsets.UTF_8
    val charsetName = raw.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotBlank() }
    return runCatching {
        charsetName?.let(Charset::forName)
    }.getOrNull() ?: Charsets.UTF_8
}

private fun String.insertWebViewJsUrl(): String {
    val headIndex = indexOf("<head", ignoreCase = true)
    if (headIndex < 0) return this
    val closingHeadIndex = indexOf('>', startIndex = headIndex)
    if (closingHeadIndex < 0) return this
    return StringBuilder(this).insert(closingHeadIndex + 1, WebJsExtensions.JS_URL).toString()
}
