package io.legado.app.utils

import android.util.Base64
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.RustAnalyzerBridge
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

object RustRemoteFetch {

    fun text(url: String, rulePath: String): String {
        val request = request(url)
        return RustAnalyzerBridge.fetchText(
            url = request.url,
            source = request.source,
            rulePath = rulePath
        ).body
    }

    fun bytes(url: String, rulePath: String): RustRemoteBytes {
        val request = request(url)
        val result = if (request.source != null) {
            RustAnalyzerBridge.fetchRawResponse(request.source, request.url, rulePath)
        } else {
            RustAnalyzerBridge.fetchRawUrl(request.url, rulePath)
        }
        return RustRemoteBytes(
            body = decompressZipFirstEntryIfNeeded(result.body, result.contentType),
            contentType = result.contentType,
            code = result.code,
            message = result.message,
            headers = result.headers,
            headersList = result.headersList
        )
    }

    fun requestBytes(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        rulePath: String
    ): RustRemoteBytes {
        val options = linkedMapOf<String, Any>(
            "method" to method,
            "headers" to headers
        )
        body?.let {
            options["bodyBase64"] = Base64.encodeToString(it, Base64.NO_WRAP)
        }
        return bytes("${url},${GSON.toJson(options)}", rulePath)
    }

    private fun request(url: String): RustRemoteRequest {
        if (!url.endsWith(REQUEST_WITHOUT_UA_SUFFIX)) {
            return RustRemoteRequest(url = url, source = null)
        }
        val cleanUrl = url.substringBeforeLast(REQUEST_WITHOUT_UA_SUFFIX)
        val source = BookSource(
            bookSourceUrl = cleanUrl,
            bookSourceName = "Rust remote fetch",
            bookSourceType = BookSourceType.default,
            header = GSON.toJson(mapOf(AppConst.UA_NAME to "null"))
        )
        return RustRemoteRequest(url = cleanUrl, source = source)
    }

    private const val REQUEST_WITHOUT_UA_SUFFIX = "#requestWithoutUA"

    internal fun decompressZipFirstEntryIfNeeded(body: ByteArray, contentType: String?): ByteArray {
        if (contentType?.substringBefore(';')?.trim()?.equals("application/zip", true) != true) {
            return body
        }
        ZipInputStream(ByteArrayInputStream(body)).use { zip ->
            zip.nextEntry ?: return ByteArray(0)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}

data class RustRemoteBytes(
    val body: ByteArray,
    val contentType: String?,
    val code: Int = 200,
    val message: String = "OK",
    val headers: Map<String, String> = emptyMap(),
    val headersList: List<List<String>> = emptyList()
)

private data class RustRemoteRequest(
    val url: String,
    val source: BookSource?
)
