package io.legado.app.help

import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.source.getSourceType
import io.legado.app.model.Debug
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.association.OpenUrlConfirmActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isMainThread
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.mapAsync
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.use
import splitties.init.appCtx
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.net.URL
import java.nio.charset.Charset
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import androidx.core.net.toUri

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/**
 * js扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 * 所有对于文件的读写删操作都是相对路径,只能操作阅读缓存内的文件
 * /android/data/{package}/cache/...
 */
internal data class RustJsHttpResponse(
    val url: String = "",
    val body: String? = null,
    val code: Int = 200,
    val message: String = "OK",
    val headers: Map<String, String> = emptyMap(),
    val headersList: List<List<String>> = emptyList(),
    val contentType: String? = null
) {
    fun toStrResponse(): StrResponse {
        if (url.isBlank()) {
            throw NoStackTraceException("Rust response object returned blank URL")
        }
        return StrResponse(url, body, code, message)
    }
}

enum class RustConnectionMethod {
    GET,
    HEAD,
    POST,
    PUT,
    DELETE,
    PATCH,
    OPTIONS
}

class RustConnectionResponse(
    private var responseUrl: URL,
    private val responseBody: String,
    private val responseCode: Int,
    private val responseMessage: String,
    private var responseMethod: RustConnectionMethod,
    private val responseContentType: String?,
    headers: Map<String, String>,
    headersList: List<List<String>> = emptyList()
) {
    private val responseHeaders = linkedMapOf<String, MutableList<String>>()
    private val responseCookies = linkedMapOf<String, String>()

    init {
        headersList.forEach { entry ->
            if (entry.size >= 2) {
                addResponseHeader(entry[0], entry[1])
            }
        }
        headers.forEach { (name, value) ->
            if (!hasHeader(name)) {
                addResponseHeader(name, value)
            }
        }
    }

    fun statusCode(): Int = responseCode
    fun statusMessage(): String = responseMessage
    private var responseCharset: String = parseCharset(contentType()) ?: "UTF-8"
    fun charset(): String = responseCharset
    fun charset(charset: String): RustConnectionResponse {
        if (charset.isNotBlank()) responseCharset = charset
        return this
    }
    fun contentType(): String = responseContentType ?: header("Content-Type") ?: "text/plain; charset=UTF-8"
    fun parse(): Nothing {
        throw NoStackTraceException(
            "Rust HTTP response parse cannot expose an Android DOM Document. " +
                    "Use Rust JS host DOM parse compatibility inside analyzer scripts, " +
                    "or RustAnalyzerBridge HTML document APIs for Android callers. url=$responseUrl"
        )
    }
    fun body(): String = responseBody
    fun bodyAsBytes(): ByteArray = responseBody.toByteArray(
        runCatching { Charset.forName(responseCharset) }.getOrDefault(Charsets.UTF_8)
    )
    fun bufferUp(): RustConnectionResponse = this
    fun bodyStream(): BufferedInputStream = ByteArrayInputStream(bodyAsBytes()).buffered()
    fun url(): URL = responseUrl
    fun url(url: URL): RustConnectionResponse {
        responseUrl = url
        return this
    }
    fun method(): RustConnectionMethod = responseMethod
    fun method(method: RustConnectionMethod): RustConnectionResponse {
        responseMethod = method
        return this
    }
    fun header(name: String): String? = responseHeaders.entries.firstOrNull {
        it.key.equals(name, ignoreCase = true)
    }?.value?.firstOrNull()

    fun headers(name: String): MutableList<String> = responseHeaders.entries.firstOrNull {
        it.key.equals(name, ignoreCase = true)
    }?.value?.toMutableList() ?: mutableListOf()
    fun header(name: String, value: String): RustConnectionResponse {
        if (name.isNotBlank()) responseHeaders[name] = mutableListOf(value)
        return this
    }
    fun addHeader(name: String, value: String): RustConnectionResponse {
        if (name.isNotBlank()) responseHeaders.getOrPut(name) { mutableListOf() }.add(value)
        return this
    }
    fun hasHeader(name: String): Boolean = responseHeaders.keys.any { it.equals(name, ignoreCase = true) }
    fun hasHeaderWithValue(name: String, value: String): Boolean = headers(name).contains(value)
    fun removeHeader(name: String): RustConnectionResponse {
        responseHeaders.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let {
            responseHeaders.remove(it)
        }
        return this
    }
    fun headers(): MutableMap<String, String> = responseHeaders.mapValues { it.value.firstOrNull().orEmpty() }.toMutableMap()
    fun multiHeaders(): MutableMap<String, MutableList<String>> = responseHeaders.mapValues { it.value.toMutableList() }.toMutableMap()
    fun cookie(name: String): String? = responseCookies[name]
    fun cookie(name: String, value: String): RustConnectionResponse {
        if (name.isNotBlank()) responseCookies[name] = value
        return this
    }
    fun hasCookie(name: String): Boolean = responseCookies.containsKey(name)
    fun removeCookie(name: String): RustConnectionResponse {
        responseCookies.remove(name)
        return this
    }
    fun cookies(): MutableMap<String, String> = responseCookies.toMutableMap()

    private fun addResponseHeader(name: String, value: String) {
        addHeader(name, value)
        if (name.equals("Set-Cookie", ignoreCase = true)) {
            val cookie = value.substringBefore(";").trim()
            val cookieName = cookie.substringBefore("=", "").trim()
            val cookieValue = cookie.substringAfter("=", "").trim()
            if (cookieName.isNotBlank()) {
                responseCookies[cookieName] = cookieValue
            }
        }
    }

    private fun parseCharset(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        return contentType
            .split(';')
            .firstNotNullOfOrNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size == 2 && pieces[0].trim().equals("charset", ignoreCase = true)) {
                    pieces[1].trim().trim('"', '\'', ' ').takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
    }
}

class RustFile(
    private val source: BaseSource,
    private val rustPath: String
) {
    val path: String
        get() = rustPath
    val absolutePath: String
        get() = rustPath
    val name: String
        get() = rustPath.split('/', '\\').lastOrNull { it.isNotBlank() }.orEmpty()

    fun exists(): Boolean = rustBool("java.fileExist(${GSON.toJson(rustPath)})", "exists")

    fun isFile(): Boolean = exists()

    fun isDirectory(): Boolean = false

    fun length(): Long = readBytes()?.size?.toLong() ?: 0L

    fun readBytes(): ByteArray? {
        return RustAnalyzerBridge.evalJsAny(
            source = source,
            script = "java.readFile(${GSON.toJson(rustPath)})",
            rulePath = "JsExtensions.getFile.readBytes"
        ) as? ByteArray
    }

    fun readText(): String = readText("UTF-8")

    fun readText(charsetName: String): String {
        return RustAnalyzerBridge.evalJs(
            source = source,
            script = "java.readTxtFile(${GSON.toJson(rustPath)}, ${GSON.toJson(charsetName)})",
            rulePath = "JsExtensions.getFile.readText"
        )
    }

    fun delete(): Boolean = rustBool("java.deleteFile(${GSON.toJson(rustPath)})", "delete")

    override fun toString(): String = rustPath

    private fun rustBool(script: String, action: String): Boolean {
        return (RustAnalyzerBridge.evalJsAny(
            source = source,
            script = script,
            rulePath = "JsExtensions.getFile.$action"
        ) as? Boolean) ?: false
    }
}

@Keep
@Suppress("unused")
interface JsExtensions : JsEncodeUtils {

    fun getSource(): BaseSource?
    fun getTag(): String?

    private val context: CoroutineContext
        get() = EmptyCoroutineContext

    /**
     * 访问网络,返回String
     */
    fun ajax(url: Any): String? {
        return ajax(url, null)
    }

    fun ajax(url: Any, callTimeout: Long?): String? {
        val source = rustSource("java.ajax")
        return RustAnalyzerBridge.evalJs(
            source = source,
            script = "java.ajax(${GSON.toJson(rustAjaxArgument(url))}, ${callTimeout ?: 0})",
            rulePath = "JsExtensions.ajax"
        )
    }

    /**
     * 并发访问网络
     */
    fun ajaxAll(urlList: Array<String>): Array<StrResponse> {
        return ajaxAll(urlList, false)
    }

    fun ajaxAll(urlList: Array<String>, skipRateLimit: Boolean): Array<StrResponse> {
        return rustHttpResponses(
            source = rustSource("java.ajaxAll"),
            script = rustResponsesScript(
                "java.ajaxAll(${GSON.toJson(urlList.toList())}, $skipRateLimit)"
            ),
            rulePath = "JsExtensions.ajaxAll"
        )
    }

    /**
     * 并发测试网络
     */
    fun ajaxTestAll(urlList: Array<String>, timeout: Int): Array<StrResponse> {
        return ajaxTestAll(urlList, timeout, false)
    }

    fun ajaxTestAll(urlList: Array<String>, timeout: Int, skipRateLimit: Boolean): Array<StrResponse> {
        return rustHttpResponses(
            source = rustSource("java.ajaxTestAll"),
            script = rustResponsesScript(
                "java.ajaxTestAll(${GSON.toJson(urlList.toList())}, $timeout, $skipRateLimit)"
            ),
            rulePath = "JsExtensions.ajaxTestAll"
        )
    }


    /**
     * 访问网络,返回Response<String>
     */
    fun connect(urlStr: String): StrResponse {
        return rustHttpResponse(
            source = rustSource("java.connect"),
            script = rustResponseScript("java.connect(${GSON.toJson(urlStr)})"),
            rulePath = "JsExtensions.connect"
        )
    }

    fun connect(urlStr: String, header: String?): StrResponse {
        return connect(urlStr, header, null)
    }

    fun connect(urlStr: String, header: String?, callTimeout: Long?): StrResponse {
        return rustHttpResponse(
            source = rustSource("java.connect"),
            script = rustResponseScript(
                "java.connect(${GSON.toJson(urlStr)}, ${GSON.toJson(header?.ifBlank { "{}" } ?: "{}")}, ${callTimeout ?: 0})"
            ),
            rulePath = "JsExtensions.connect"
        )
    }

    private fun rustSource(api: String): BaseSource {
        if (!RustAnalyzerBridge.isAvailable) {
            throw NoStackTraceException("Rust analyzer UniFFI binding is required for $api")
        }
        return getSource()
            ?: throw NoStackTraceException("Rust analyzer source context is required for $api")
    }

    private fun rustAjaxArgument(url: Any): Any {
        return if (url is List<*>) {
            url.map { it?.toString().orEmpty() }
        } else {
            url.toString()
        }
    }

    private fun rustResponseScript(call: String): String {
        return """
            (function() {
                var r = $call;
                return JSON.stringify({
                    url: r && r.url ? String(r.url()) : '',
                    body: r && r.body ? String(r.body()) : '',
                    code: r && r.code ? Number(r.code()) : 200,
                    message: r && r.message ? String(r.message()) : 'OK',
                    headers: r && r.headers ? r.headers() : {},
                    headersList: r && r.headersList ? r.headersList() : [],
                    contentType: r && r.contentType ? String(r.contentType()) : ''
                });
            })()
        """.trimIndent()
    }

    private fun rustResponsesScript(call: String): String {
        return """
            (function() {
                var rs = $call || [];
                return JSON.stringify(rs.map(function(r) {
                    return {
                        url: r && r.url ? String(r.url()) : '',
                        body: r && r.body ? String(r.body()) : '',
                        code: r && r.code ? Number(r.code()) : 200,
                        message: r && r.message ? String(r.message()) : 'OK',
                        headers: r && r.headers ? r.headers() : {},
                        headersList: r && r.headersList ? r.headersList() : [],
                        contentType: r && r.contentType ? String(r.contentType()) : ''
                    };
                }));
            })()
        """.trimIndent()
    }

    private fun rustJsHttpResponse(source: BaseSource, script: String, rulePath: String): RustJsHttpResponse {
        val json = RustAnalyzerBridge.evalJs(source = source, script = script, rulePath = rulePath)
        return GSON.fromJsonObject<RustJsHttpResponse>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid response JSON: ${it.localizedMessage}")
        }
    }

    private fun rustHttpResponse(source: BaseSource, script: String, rulePath: String): StrResponse {
        return rustJsHttpResponse(source, script, rulePath).toStrResponse()
    }

    private fun rustHttpResponses(source: BaseSource, script: String, rulePath: String): Array<StrResponse> {
        val json = RustAnalyzerBridge.evalJs(source = source, script = script, rulePath = rulePath)
        return GSON.fromJsonArray<RustJsHttpResponse>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid response JSON: ${it.localizedMessage}")
        }.map { it.toStrResponse() }.toTypedArray()
    }

    private fun rustConnectionResponse(
        method: String,
        urlStr: String,
        body: String,
        headers: Map<String, String>,
        timeout: Int?,
        rulePath: String
    ): RustConnectionResponse {
        val response = rustJsHttpResponse(
            source = rustSource("java.${method.lowercase()}"),
            script = rustResponseScript(
                "java.__httpResponse('${method}', ${GSON.toJson(urlStr)}, ${GSON.toJson(body)}, ${GSON.toJson(headers)}, ${timeout ?: 0})"
            ),
            rulePath = rulePath
        )
        val url = runCatching { URL(response.url) }.getOrElse {
            throw NoStackTraceException(
                "Rust $rulePath returned invalid response URL: ${response.url.ifBlank { "<blank>" }}"
            )
        }
        val connectionMethod = RustConnectionMethod.valueOf(method)
        return RustConnectionResponse(
            responseUrl = url,
            responseBody = response.body.orEmpty(),
            responseCode = response.code,
            responseMessage = response.message,
            responseMethod = connectionMethod,
            responseContentType = response.contentType,
            headers = response.headers,
            headersList = response.headersList
        )
    }

    private fun rustFetchText(url: String, rulePath: String): String {
        return connect(url).body()
            ?: throw NoStackTraceException("Rust $rulePath returned empty body for $url")
    }

    private fun rustHostEvalString(api: String, script: String): String {
        return RustAnalyzerBridge.evalJs(
            source = rustSource("java.$api"),
            script = script,
            rulePath = "JsExtensions.$api"
        )
    }

    private fun rustHostEvalAny(api: String, script: String): Any? {
        return RustAnalyzerBridge.evalJsAny(
            source = rustSource("java.$api"),
            script = script,
            rulePath = "JsExtensions.$api"
        )
    }

    private fun rustHostEvalMap(api: String, script: String): Map<String, Any?> {
        val value = rustHostEvalAny(api, script)
        return (value as? Map<*, *>)
            ?.mapKeys { it.key.toString() }
            ?: throw NoStackTraceException("Rust java.$api returned ${value?.javaClass?.name ?: "null"} instead of Map")
    }

    fun webView(html: String?, url: String?, js: String?): String? {
        return webView(html, url, js, false)
    }

    /**
     * 使用webView访问网络
     * @param html 直接用webView载入的html, 如果html为空直接访问url
     * @param url html内如果有相对路径的资源不传入url访问不了
     * @param js 用来取返回值的js语句, 没有就返回整个源代码
     * @param cacheFirst 优先使用缓存,为true能提高访问速度
     * @return 返回js获取的内容
     */
    fun webView(html: String?, url: String?, js: String?, cacheFirst: Boolean): String? {
        if (isMainThread) {
            error("webView must be called on a background thread")
        }
        return runBlocking(context) {
            BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                source = getSource(),
                cacheFirst = cacheFirst
            ).getStrResponse().body
        }
    }

    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String): String? {
        return webViewGetSource(html, url, js, sourceRegex, false, 0)
    }
    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String, cacheFirst: Boolean): String? {
        return webViewGetSource(html, url, js, sourceRegex, cacheFirst, 0)
    }

    /**
     * 使用webView获取资源url
     */
    fun webViewGetSource(
        html: String?,
        url: String?,
        js: String?,
        sourceRegex: String,
        cacheFirst: Boolean,
        delayTime:Long
    ): String? {
        if (isMainThread) {
            error("webViewGetSource must be called on a background thread")
        }
        return runBlocking(context) {
            BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                source = getSource(),
                sourceRegex = sourceRegex,
                cacheFirst = cacheFirst,
                delayTime = delayTime
            ).getStrResponse().body
        }
    }

    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String): String? {
        return webViewGetOverrideUrl(html, url, js, overrideUrlRegex, false, 0)
    }
    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String, cacheFirst: Boolean): String? {
        return webViewGetOverrideUrl(html, url, js, overrideUrlRegex, cacheFirst, 0)
    }

    /**
     * 使用webView获取跳转url
     */
    fun webViewGetOverrideUrl(
        html: String?,
        url: String?,
        js: String?,
        overrideUrlRegex: String,
        cacheFirst: Boolean,
        delayTime:Long
    ): String? {
        if (isMainThread) {
            error("webViewGetOverrideUrl must be called on a background thread")
        }
        return runBlocking(context) {
            BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                source = getSource(),
                overrideUrlRegex = overrideUrlRegex,
                cacheFirst = cacheFirst,
                delayTime = delayTime
            ).getStrResponse().body
        }
    }

    @JavascriptInterface
    fun openVideoPlayer(url: String, title: String) {
        openVideoPlayer(url, title, false)
    }

    /**
     * 打开内置视频播放器
     * @param url 视频播放链接
     * @param title 视频的标题
     * @param isFloat 是否悬浮窗打开
     */
    @JavascriptInterface
    fun openVideoPlayer(url: String, title: String, isFloat: Boolean) {
        SourceHelp.openVideoPlayer(getSource(), url, title, isFloat)
    }

    /**
     * 使用内置浏览器打开链接，手动验证网站防爬
     * @param url 要打开的链接
     * @param title 浏览器页面的标题
     */
    fun startBrowser(url: String, title: String) {
        return startBrowser(url, title, null)
    }

    fun startBrowser(url: String, title: String, html: String?) {
        Unit
        SourceVerificationHelp.startBrowser(getSource(), url, title, html=html)
    }

    /**
     * 使用内置浏览器打开链接，并等待网页结果
     */
    fun startBrowserAwait(url: String, title: String): StrResponse {
        return startBrowserAwait(url, title, true, null)
    }

    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): StrResponse {
        return startBrowserAwait(url, title, refetchAfterSuccess, null)
    }

    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean, html: String?): StrResponse {
        Unit
        val pair = SourceVerificationHelp.getVerificationResult(
            getSource(), url, title, true, refetchAfterSuccess, html
        )
        val (url2, body) = pair
        return StrResponse(url2.ifEmpty { url }, body)
    }

    /**
     * 打开图片验证码对话框，等待返回验证结果
     */
    fun getVerificationCode(imageUrl: String): String {
        Unit
        return SourceVerificationHelp.getVerificationResult(getSource(), imageUrl, "", false).second
    }

    /**
     * 可从网络，本地文件(阅读私有数据目录相对路径)导入JavaScript脚本
     */
    @JavascriptInterface
    fun importScript(path: String): String {
        return rustHostEvalString(
            api = "importScript",
            script = "java.importScript(${GSON.toJson(path)})"
        )
    }

    /**
     * 缓存以文本方式保存的文件 如.js .txt等
     * @param urlStr 网络文件的链接
     * @return 返回缓存后的文件内容
     */
    @JavascriptInterface
    fun cacheFile(urlStr: String): String {
        return cacheFile(urlStr, 0)
    }

    /**
     * 缓存以文本方式保存的文件 如.js .txt等
     * @param saveTime 缓存时间，单位：秒
     */
    @JavascriptInterface
    fun cacheFile(urlStr: String, saveTime: Int): String {
        return rustHostEvalString(
            api = "cacheFile",
            script = "java.cacheFile(${GSON.toJson(urlStr)}, $saveTime)"
        )
    }

    /**
     *js实现读取cookie
     */
    @JavascriptInterface
    fun getCookie(tag: String): String {
        return getCookie(tag, null)
    }

    @JavascriptInterface
    fun getCookie(tag: String, key: String?): String {
        val script = if (key != null) {
            "java.getCookie(${GSON.toJson(tag)}, ${GSON.toJson(key)})"
        } else {
            "java.getCookie(${GSON.toJson(tag)})"
        }
        return rustHostEvalString(api = "getCookie", script = script)
    }

    /**
     * 下载文件
     * @param url 下载地址:可带参数type
     * @return 下载的文件相对路径
     */
    @JavascriptInterface
    fun downloadFile(url: String): String {
        return rustHostEvalString(
            api = "downloadFile",
            script = "java.downloadFile(${GSON.toJson(url)})"
        )
    }


    /**
     * 实现16进制字符串转文件
     * @param content 需要转成文件的16进制字符串
     * @param url 通过url里的参数来判断文件类型
     * @return 相对路径
     */
    @Deprecated("Deprecated")
    @JavascriptInterface
    fun downloadFile(content: String, url: String): String {
        return rustHostEvalString(
            api = "downloadFile",
            script = "java.__downloadHexFile(${GSON.toJson(content)}, ${GSON.toJson(url)})"
        )
    }

    /**
     * js实现重定向拦截,网络访问get
     */
    fun get(urlStr: String, headers: Map<String, String>): RustConnectionResponse {
        return get(urlStr, headers, null)
    }

    fun get(urlStr: String, headers: Map<String, String>, timeout: Int?): RustConnectionResponse {
        return rustConnectionResponse(
            method = "GET",
            urlStr = urlStr,
            body = "",
            headers = headers,
            timeout = timeout,
            rulePath = "JsExtensions.get"
        )
    }

    /**
     * js实现重定向拦截,网络访问head,不返回Response Body更省流量
     */
    fun head(urlStr: String, headers: Map<String, String>): RustConnectionResponse {
        return head(urlStr, headers, null)
    }

    fun head(urlStr: String, headers: Map<String, String>, timeout: Int?): RustConnectionResponse {
        return rustConnectionResponse(
            method = "HEAD",
            urlStr = urlStr,
            body = "",
            headers = headers,
            timeout = timeout,
            rulePath = "JsExtensions.head"
        )
    }

    /**
     * 网络访问post
     */
    fun post(urlStr: String, body: String, headers: Map<String, String>): RustConnectionResponse {
        return post(urlStr, body, headers, null)
    }

    fun post(urlStr: String, body: String, headers: Map<String, String>, timeout: Int?): RustConnectionResponse {
        return rustConnectionResponse(
            method = "POST",
            urlStr = urlStr,
            body = body,
            headers = headers,
            timeout = timeout,
            rulePath = "JsExtensions.post"
        )
    }

    /* Str转ByteArray */
    fun strToBytes(str: String): ByteArray {
        return rustHostEvalAny(
            api = "strToBytes",
            script = "java.strToBytes(${GSON.toJson(str)})"
        ) as? ByteArray ?: byteArrayOf()
    }

    fun strToBytes(str: String, charset: String): ByteArray {
        return rustHostEvalAny(
            api = "strToBytes",
            script = "java.strToBytes(${GSON.toJson(str)}, ${GSON.toJson(charset)})"
        ) as? ByteArray ?: byteArrayOf()
    }

    /* ByteArray转Str */
    fun bytesToStr(bytes: ByteArray): String {
        return bytesToStr(bytes, "UTF-8")
    }

    fun bytesToStr(bytes: ByteArray, charset: String): String {
        return rustHostEvalString(
            api = "bytesToStr",
            script = "java.bytesToStr({__javaBytesHex:${GSON.toJson(bytes.toHexString())}}, ${GSON.toJson(charset)})"
        )
    }

    /**
     * js实现base64解码,不能删
     */
    @JavascriptInterface
    fun base64Decode(str: String?): String {
        return rustHostEvalString(
            api = "base64Decode",
            script = "java.base64Decode(${GSON.toJson(str)})"
        )
    }

    @JavascriptInterface
    fun base64Decode(str: String?, charset: String): String {
        return rustHostEvalString(
            api = "base64Decode",
            script = "java.base64Decode(${GSON.toJson(str)}, ${GSON.toJson(charset)})"
        )
    }

    @JavascriptInterface
    fun base64Decode(str: String, flags: Int): String {
        return rustHostEvalString(
            api = "base64Decode",
            script = "java.base64Decode(${GSON.toJson(str)}, $flags)"
        )
    }

    fun base64DecodeToByteArray(str: String?): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return rustHostEvalAny(
            api = "base64DecodeToByteArray",
            script = "java.base64DecodeToByteArray(${GSON.toJson(str)}, 0)"
        ) as? ByteArray
    }

    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return rustHostEvalAny(
            api = "base64DecodeToByteArray",
            script = "java.base64DecodeToByteArray(${GSON.toJson(str)}, $flags)"
        ) as? ByteArray
    }

    @JavascriptInterface
    fun base64Encode(str: String): String? {
        return rustHostEvalString(
            api = "base64Encode",
            script = "java.base64Encode(${GSON.toJson(str)}, 2)"
        )
    }

    @JavascriptInterface
    fun base64Encode(str: String, flags: Int): String? {
        return rustHostEvalString(
            api = "base64Encode",
            script = "java.base64Encode(${GSON.toJson(str)}, $flags)"
        )
    }

    /* HexString 解码为字节数组 */
    fun hexDecodeToByteArray(hex: String): ByteArray? {
        return rustHostEvalAny(
            api = "hexDecodeToByteArray",
            script = "java.hexDecodeToByteArray(${GSON.toJson(hex)})"
        ) as? ByteArray
    }

    /* hexString 解码为utf8String*/
    @JavascriptInterface
    fun hexDecodeToString(hex: String): String? {
        return rustHostEvalString(
            api = "hexDecodeToString",
            script = "java.hexDecodeToString(${GSON.toJson(hex)})"
        )
    }

    /* utf8 编码为hexString */
    @JavascriptInterface
    fun hexEncodeToString(utf8: String): String? {
        return rustHostEvalString(
            api = "hexEncodeToString",
            script = "java.hexEncodeToString(${GSON.toJson(utf8)})"
        )
    }

    /**
     * 格式化时间
     */
    @JavascriptInterface
    fun timeFormatUTC(time: Long, format: String, sh: Int): String? {
        return rustHostEvalString(
            api = "timeFormatUTC",
            script = "java.timeFormatUTC($time, ${GSON.toJson(format)}, $sh)"
        )
    }

    /**
     * 时间格式化
     */
    @JavascriptInterface
    fun timeFormat(time: Long): String {
        return rustHostEvalString(
            api = "timeFormat",
            script = "java.timeFormat($time)"
        )
    }

    @JavascriptInterface
    fun encodeURI(str: String): String {
        return rustHostEvalString(
            api = "encodeURI",
            script = "java.encodeURI(${GSON.toJson(str)})"
        )
    }

    @JavascriptInterface
    fun encodeURI(str: String, enc: String): String {
        return rustHostEvalString(
            api = "encodeURI",
            script = "java.encodeURI(${GSON.toJson(str)}, ${GSON.toJson(enc)})"
        )
    }

    @JavascriptInterface
    fun htmlFormat(str: String): String {
        return rustHostEvalString(
            api = "htmlFormat",
            script = "java.htmlFormat(${GSON.toJson(str)})"
        )
    }

    @JavascriptInterface
    fun t2s(text: String): String {
        return rustHostEvalString(
            api = "t2s",
            script = "java.t2s(${GSON.toJson(text)})"
        )
    }

    @JavascriptInterface
    fun s2t(text: String): String {
        return rustHostEvalString(
            api = "s2t",
            script = "java.s2t(${GSON.toJson(text)})"
        )
    }

    @JavascriptInterface
    fun getWebViewUA(): String {
        return rustHostEvalString(
            api = "getWebViewUA",
            script = "java.getWebViewUA()"
        )
    }

//****************文件操作******************//

    /**
     * 获取本地文件
     * @param path 相对路径
     * @return Rust-backed virtual file
     */
    fun getFile(path: String): RustFile {
        return RustFile(rustSource("java.getFile"), path)
    }

    fun readFile(path: String): ByteArray? {
        return RustAnalyzerBridge.evalJsAny(
            source = rustSource("java.readFile"),
            script = "java.readFile(${GSON.toJson(path)})",
            rulePath = "JsExtensions.readFile"
        ) as? ByteArray
    }

    @JavascriptInterface
    fun readTxtFile(path: String): String {
        return rustHostEvalString(
            api = "readTxtFile",
            script = "java.readTxtFile(${GSON.toJson(path)})"
        )
    }

    @JavascriptInterface
    fun readTxtFile(path: String, charsetName: String): String {
        return rustHostEvalString(
            api = "readTxtFile",
            script = "java.readTxtFile(${GSON.toJson(path)}, ${GSON.toJson(charsetName)})"
        )
    }

    /**
     * 删除本地文件
     */
    @JavascriptInterface
    fun deleteFile(path: String): Boolean {
        return (rustHostEvalAny(
            api = "deleteFile",
            script = "java.deleteFile(${GSON.toJson(path)})"
        ) as? Boolean) ?: false
    }

    /**
     * js实现Zip压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    @JavascriptInterface
    fun unzipFile(zipPath: String): String {
        return rustHostEvalString(
            api = "unzipFile",
            script = "java.unzipFile(${GSON.toJson(zipPath)})"
        )
    }

    /**
     * js实现7Zip压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    @JavascriptInterface
    fun un7zFile(zipPath: String): String {
        return rustHostEvalString(
            api = "un7zFile",
            script = "java.un7zFile(${GSON.toJson(zipPath)})"
        )
    }

    /**
     * js实现Rar压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    @JavascriptInterface
    fun unrarFile(zipPath: String): String {
        return rustHostEvalString(
            api = "unrarFile",
            script = "java.unrarFile(${GSON.toJson(zipPath)})"
        )
    }

    /**
     * js实现压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    @JavascriptInterface
    fun unArchiveFile(zipPath: String): String {
        return rustHostEvalString(
            api = "unArchiveFile",
            script = "java.unArchiveFile(${GSON.toJson(zipPath)})"
        )
    }

    /**
     * js实现文件夹内所有文本文件读取
     * @param path 文件夹相对路径
     * @return 所有文件字符串换行连接
     */
    @JavascriptInterface
    fun getTxtInFolder(path: String): String {
        return rustHostEvalString(
            api = "getTxtInFolder",
            script = "java.getTxtInFolder(${GSON.toJson(path)})"
        )
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    @JavascriptInterface
    fun getZipStringContent(url: String, path: String): String {
        return rustHostEvalString(
            api = "getZipStringContent",
            script = "java.getZipStringContent(${GSON.toJson(url)}, ${GSON.toJson(path)})"
        )
    }

    @JavascriptInterface
    fun getZipStringContent(url: String, path: String, charsetName: String): String {
        return rustHostEvalString(
            api = "getZipStringContent",
            script = "java.getZipStringContent(${GSON.toJson(url)}, ${GSON.toJson(path)}, ${GSON.toJson(charsetName)})"
        )
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    @JavascriptInterface
    fun getRarStringContent(url: String, path: String): String {
        return rustHostEvalString(
            api = "getRarStringContent",
            script = "java.getRarStringContent(${GSON.toJson(url)}, ${GSON.toJson(path)})"
        )
    }

    @JavascriptInterface
    fun getRarStringContent(url: String, path: String, charsetName: String): String {
        return rustHostEvalString(
            api = "getRarStringContent",
            script = "java.getRarStringContent(${GSON.toJson(url)}, ${GSON.toJson(path)}, ${GSON.toJson(charsetName)})"
        )
    }

    /**
     * 获取网络7zip文件里面的数据
     * @param url 7zip文件的链接或十六进制字符串
     * @param path 所需获取文件在7zip内的路径
     * @return zip指定文件的数据
     */
    @JavascriptInterface
    fun get7zStringContent(url: String, path: String): String {
        return rustHostEvalString(
            api = "get7zStringContent",
            script = "java.get7zStringContent(${GSON.toJson(url)}, ${GSON.toJson(path)})"
        )
    }

    @JavascriptInterface
    fun get7zStringContent(url: String, path: String, charsetName: String): String {
        return rustHostEvalString(
            api = "get7zStringContent",
            script = "java.get7zStringContent(${GSON.toJson(url)}, ${GSON.toJson(path)}, ${GSON.toJson(charsetName)})"
        )
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    fun getZipByteArrayContent(url: String, path: String): ByteArray? {
        return RustAnalyzerBridge.evalJsAny(
            source = rustSource("java.getZipByteArrayContent"),
            script = "java.getZipByteArrayContent(${GSON.toJson(url)}, ${GSON.toJson(path)})",
            rulePath = "JsExtensions.getZipByteArrayContent"
        ) as? ByteArray ?: run {
            log("getZipContent 未发现内容")
            null
        }
    }

    /**
     * 获取网络Rar文件里面的数据
     * @param url Rar文件的链接或十六进制字符串
     * @param path 所需获取文件在Rar内的路径
     * @return Rar指定文件的数据
     */
    fun getRarByteArrayContent(url: String, path: String): ByteArray? {
        return RustAnalyzerBridge.evalJsAny(
            source = rustSource("java.getRarByteArrayContent"),
            script = "java.getRarByteArrayContent(${GSON.toJson(url)}, ${GSON.toJson(path)})",
            rulePath = "JsExtensions.getRarByteArrayContent"
        ) as? ByteArray ?: run {
            log("getRarContent 未发现内容")
            null
        }
    }

    /**
     * 获取网络7zip文件里面的数据
     * @param url 7zip文件的链接或十六进制字符串
     * @param path 所需获取文件在7zip内的路径
     * @return 7zip指定文件的数据
     */
    fun get7zByteArrayContent(url: String, path: String): ByteArray? {
        return RustAnalyzerBridge.evalJsAny(
            source = rustSource("java.get7zByteArrayContent"),
            script = "java.get7zByteArrayContent(${GSON.toJson(url)}, ${GSON.toJson(path)})",
            rulePath = "JsExtensions.get7zByteArrayContent"
        ) as? ByteArray ?: run {
            log("get7zContent 未发现内容")
            null
        }
    }


//******************文件操作************************//

    /**
     * 解析字体Base64数据,返回字体解析类
     */
    @Deprecated("Deprecated")
    fun queryBase64TTF(data: String?): Any? {
        return rustHostEvalAny(
            api = "queryBase64TTF",
            script = "java.queryBase64TTF(${GSON.toJson(data)})"
        )
    }

    /**
     * 返回字体解析类
     * @param data 支持url,本地文件,base64,ByteArray,自动判断,自动缓存
     * @param useCache 可选开关缓存,不传入该值默认开启缓存
     */
    fun queryTTF(data: Any?, useCache: Boolean): Any? {
        val script = when (data) {
            is ByteArray -> "java.queryTTF(__javaBytes(${GSON.toJson(data.toHexString())}), $useCache)"
            else -> "java.queryTTF(${GSON.toJson(data?.toString())}, $useCache)"
        }
        return rustHostEvalAny(
            api = "queryTTF",
            script = script
        )
    }

    fun queryTTF(data: Any?): Any? {
        return queryTTF(data, true)
    }

    /**
     * @param text 包含错误字体的内容
     * @param errorFont 错误的字体
     * @param correctFont 正确的字体
     * @param filter 删除错误字体中不存在的字符
     */
    fun replaceFont(
        text: String,
        errorFont: Any?,
        correctFont: Any?,
        filter: Boolean
    ): String {
        return rustHostEvalString(
            api = "replaceFont",
            script = "java.replaceFont(${GSON.toJson(text)}, ${GSON.toJson(errorFont)}, ${GSON.toJson(correctFont)}, $filter)"
        )
    }

    /**
     * @param text 包含错误字体的内容
     * @param errorFont 错误的字体
     * @param correctFont 正确的字体
     */
    fun replaceFont(
        text: String,
        errorFont: Any?,
        correctFont: Any?
    ): String {
        return replaceFont(text, errorFont, correctFont, false)
    }


    /**
     * 章节数转数字
     */
    @JavascriptInterface
    fun toNumChapter(s: String?): String? {
        s ?: return null
        return rustHostEvalString(
            api = "toNumChapter",
            script = "java.toNumChapter(${GSON.toJson(s)})"
        )
    }


    fun toURL(urlStr: String): Any? {
        return rustHostEvalAny(
            api = "toURL",
            script = "java.toURL(${GSON.toJson(urlStr)})"
        )
    }

    fun toURL(url: String, baseUrl: String? = null): Any? {
        return rustHostEvalAny(
            api = "toURL",
            script = "java.toURL(${GSON.toJson(url)}, ${GSON.toJson(baseUrl)})"
        )
    }

    /**
     * 弹窗提示
     */
    fun toast(msg: Any?) {
        Unit
        appCtx.toastOnUi("${getTag()}: ${msg.toString()}")
    }

    /**
     * 弹窗提示 停留时间较长
     */
    fun longToast(msg: Any?) {
        Unit
        appCtx.longToastOnUi("${getTag()}: ${msg.toString()}")
    }

    /**
     * 输出调试日志
     */
    fun log(msg: Any?): Any? {
        Unit
        getSource()?.let {
            Debug.log(it.getKey(), msg.toString())
        } ?: Debug.log(msg.toString())
        AppLog.putDebug("${getTag() ?: "源"}调试输出: $msg")
        return msg
    }

    /**
     * 输出对象类型
     */
    fun logType(any: Any?) {
        if (any == null) {
            log("null")
        } else {
            log(any.javaClass.name)
        }
    }

    /**
     * 生成UUID
     */
    @JavascriptInterface
    fun randomUUID(): String {
        return rustHostEvalString(
            api = "randomUUID",
            script = "java.randomUUID()"
        )
    }

    @JavascriptInterface
    fun androidId(): String {
        return rustHostEvalString(
            api = "androidId",
            script = "java.androidId()"
        )
    }

    /**
     * 获取应用版本名
     */
    @JavascriptInterface
    fun getAppVersionName(): String {
        return rustHostEvalString(
            api = "getAppVersionName",
            script = "java.getAppVersionName()"
        )
    }

    /**
     * 获取应用版本号
     */
    @JavascriptInterface
    fun getAppVersionCode(): Long {
        return rustHostEvalString(
            api = "getAppVersionCode",
            script = "java.getAppVersionCode()"
        ).toLong()
    }

    /**
     * 获取应用版本变体
     */
    @JavascriptInterface
    fun getAppVariant(): String {
        return rustHostEvalString(
            api = "getAppVariant",
            script = "java.getAppVariant()"
        )
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        openUrl(url, null)
    }

    /**
     * 打开应用跳转或者网页
     * @param mimeType 指定应用类型
     */
    @JavascriptInterface
    fun openUrl(url: String, mimeType: String? = null) {
        require(url.length < 64 * 1024) { "openUrl parameter url too long" }
        Unit
        if (url.startsWith("legado://") || url.startsWith("yuedu://")) {
            appCtx.startActivity<OnLineImportActivity> {
                data = url.toUri()
            }
            return
        }
        val source = getSource() ?: throw NoStackTraceException("openUrl source cannot be null")
        appCtx.startActivity<OpenUrlConfirmActivity> {
            putExtra("uri", url)
            putExtra("mimeType", mimeType)
            putExtra("sourceOrigin", source.getKey())
            putExtra("sourceName", source.getTag())
            putExtra("sourceType", source.getSourceType())
        }
    }

    /**
     * 获取阅读配置
     */
    @JavascriptInterface
    fun getReadBookConfig(): String {
        return rustHostEvalString(
            api = "getReadBookConfig",
            script = "java.getReadBookConfig()"
        )
    }

    fun getReadBookConfigMap(): Map<String, Any> {
        return rustHostEvalMap(
            api = "getReadBookConfigMap",
            script = "java.getReadBookConfigMap()"
        ).mapValues { it.value ?: "" }
    }

    /**
     * 获取主题模式
     */
    @JavascriptInterface
    fun getThemeMode(): String {
        return rustHostEvalString(
            api = "getThemeMode",
            script = "java.getThemeMode()"
        )
    }

    /**
     * 获取主题配置
     */
    @JavascriptInterface
    fun getThemeConfig(): String {
        return rustHostEvalString(
            api = "getThemeConfig",
            script = "java.getThemeConfig()"
        )
    }

    fun getThemeConfigMap(): Map<String, Any?> {
        return rustHostEvalMap(
            api = "getThemeConfigMap",
            script = "java.getThemeConfigMap()"
        )
    }

}
