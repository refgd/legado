package io.legado.app.lib.webdav

import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.CustomUrl
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.model.webBook.RustRawFetchResult
import io.legado.app.utils.RustRemoteFetch
import io.legado.app.utils.readBytes
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL

typealias ProgressListener = (finished: Long, total: Long) -> Unit

@Suppress("unused", "MemberVisibilityCanBePrivate")
open class WebDav(
    val path: String,
    val authorization: Authorization
) {
    companion object {

        fun fromPath(path: String): WebDav {
            val id = RustAnalyzerBridge.resolveUrl(path, rulePath = "WebDav.fromPath").serverId
                ?: throw WebDavException("没有serverID")
            val authorization = Authorization(id)
            return WebDav(path, authorization)
        }

        // 指定返回哪些属性
        @Language("xml")
        private const val DIR =
            """<?xml version="1.0"?>
            <a:propfind xmlns:a="DAV:">
                <a:prop>
                    <a:displayname/>
                    <a:resourcetype/>
                    <a:getcontentlength/>
                    <a:creationdate/>
                    <a:getlastmodified/>
                    %s
                </a:prop>
            </a:propfind>"""

        @Language("xml")
        private const val EXISTS =
            """<?xml version="1.0"?>
            <propfind xmlns="DAV:">
               <prop>
                  <resourcetype />
               </prop>
            </propfind>"""

        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }


    private val url: URL = URL(CustomUrl(path).getUrl())
    private val httpUrl: String? by lazy {
        val raw = url.toString()
            .replace("davs://", "https://")
            .replace("dav://", "http://")
        return@lazy normalizeHttpUrl(raw)
    }
    private val host: String?
        get() = url.host?.let {
            if (it.startsWith("[")) {
                it.substring(1, it.lastIndex)
            } else {
                it
            }
        }

    /**
     * 获取当前url文件信息
     */
    @Throws(WebDavException::class)
    suspend fun getWebDavFile(): WebDavFile? {
        return propFindResponse(depth = 0)?.let {
            parseBody(it).firstOrNull()
        }
    }

    /**
     * 列出当前路径下的文件
     * @return 文件列表
     */
    @Throws(WebDavException::class)
    suspend fun listFiles(): List<WebDavFile> {
        propFindResponse()?.let { body ->
            return parseBody(body).filter {
                it.path != path
            }
        }
        return emptyList()
    }

    /**
     * @param propsList 指定列出文件的哪些属性
     */
    @Throws(WebDavException::class)
    private suspend fun propFindResponse(
        propsList: List<String> = emptyList(),
        depth: Int = 1
    ): String? {
        val requestProps = StringBuilder()
        for (p in propsList) {
            requestProps.append("<a:").append(p).append("/>\n")
        }
        val requestPropsStr: String = if (requestProps.toString().isEmpty()) {
            DIR.replace("%s", "")
        } else {
            String.format(DIR, requestProps.toString() + "\n")
        }
        val url = httpUrl ?: return null
        return rustRequest(
            method = "PROPFIND",
            headers = mapOf("Depth" to depth.toString(), "Content-Type" to "text/plain"),
            body = requestPropsStr.toByteArray(),
            rulePath = "WebDav.propFind"
        ).apply {
            checkResult(this)
        }.body.toString(Charsets.UTF_8)
    }

    /**
     * 解析webDav返回的xml
     */
    private fun parseBody(s: String): List<WebDavFile> {
        val urlStr = httpUrl ?: return emptyList()
        return RustAnalyzerBridge.parseWebDavListing(
            body = s,
            requestUrl = urlStr,
            originalPath = url.file,
            rulePath = "WebDav.parseBody"
        ).map {
            WebDavFile(
                it.url,
                authorization,
                displayName = it.displayName,
                urlName = it.urlName,
                size = it.size,
                contentType = it.contentType,
                resourceType = it.resourceType,
                lastModify = it.lastModify
            )
        }
    }

    /**
     * 文件是否存在
     */
    suspend fun exists(): Boolean {
        val url = httpUrl ?: return false
        return kotlin.runCatching {
            return rustRequest(
                method = "PROPFIND",
                headers = mapOf("Depth" to "0", "Content-Type" to "application/xml"),
                body = EXISTS.toByteArray(),
                rulePath = "WebDav.exists"
            ).isSuccessful
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }.getOrDefault(false)
    }

    /**
     * 检查用户名密码是否有效
     */
    suspend fun check(): Boolean {
        return kotlin.runCatching {
            rustRequest(
                method = "PROPFIND",
                headers = mapOf("Depth" to "0", "Content-Type" to "application/xml"),
                body = EXISTS.toByteArray(),
                rulePath = "WebDav.check"
            ).code != 401
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }.getOrDefault(true)
    }

    /**
     * 根据自己的URL，在远程处创建对应的文件夹
     * @return 是否创建成功
     */
    suspend fun makeAsDir(): Boolean {
        val url = httpUrl ?: return false
        //防止报错
        return kotlin.runCatching {
            if (!exists()) {
                rustRequest(method = "MKCOL", rulePath = "WebDav.makeAsDir")
                    .also { checkResult(it) }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav创建目录失败\n${it.localizedMessage}", it)
        }.isSuccess
    }

    /**
     * 下载到本地
     * @param savedPath       本地的完整路径，包括最后的文件名
     * @param replaceExisting 是否替换本地的同名文件
     */
    @Throws(WebDavException::class)
    suspend fun downloadTo(
        savedPath: String,
        replaceExisting: Boolean,
        onProgress: ProgressListener? = null
    ) {
        val file = File(savedPath)
        if (file.exists() && !replaceExisting) {
            return
        }
        if (onProgress != null) {
            val response = rustRequest(method = "GET", rulePath = "WebDav.downloadTo")
            checkResult(response)
            val total = response.contentLength()
            var finished = 0L
            onProgress(finished, total)
            ByteArrayInputStream(response.body).use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        finished += read
                        onProgress(finished, total)
                    }
                }
            }
            return
        }
        downloadInputStream().use { byteStream ->
            FileOutputStream(file).use {
                byteStream.copyTo(it)
            }
        }
    }

    /**
     * 下载文件,返回ByteArray
     */
    @Throws(WebDavException::class)
    suspend fun download(): ByteArray {
        return downloadInputStream().use {
            it.readBytes()
        }
    }

    /**
     * 上传文件
     */
    @Throws(WebDavException::class)
    suspend fun upload(
        localPath: String,
        contentType: String = DEFAULT_CONTENT_TYPE,
        onProgress: ProgressListener? = null
    ) {
        upload(File(localPath), contentType, onProgress)
    }

    @Throws(WebDavException::class)
    suspend fun upload(
        file: File,
        contentType: String = DEFAULT_CONTENT_TYPE,
        onProgress: ProgressListener? = null
    ) {
        kotlin.runCatching {
            withContext(IO) {
                if (!file.exists()) throw WebDavException("文件不存在")
                val url = httpUrl ?: throw WebDavException("url不能为空")
                val bytes = file.readBytes()
                onProgress?.invoke(0L, bytes.size.toLong())
                rustRequest(
                    method = "PUT",
                    url = url,
                    headers = mapOf("Content-Type" to contentType),
                    body = bytes,
                    rulePath = "WebDav.uploadFile"
                ).also {
                    checkResult(it)
                }
                onProgress?.invoke(bytes.size.toLong(), bytes.size.toLong())
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav上传失败\n${it.localizedMessage}", it)
            throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
        }
    }

    @Throws(WebDavException::class)
    suspend fun upload(byteArray: ByteArray, contentType: String = DEFAULT_CONTENT_TYPE) {
        kotlin.runCatching {
            withContext(IO) {
                val url = httpUrl ?: throw NoStackTraceException("url不能为空")
                rustRequest(
                    method = "PUT",
                    url = url,
                    headers = mapOf("Content-Type" to contentType),
                    body = byteArray,
                    rulePath = "WebDav.uploadBytes"
                ).also {
                    checkResult(it)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav上传失败\n${it.localizedMessage}", it)
            throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
        }
    }

    @Throws(WebDavException::class)
    suspend fun upload(uri: Uri, contentType: String = DEFAULT_CONTENT_TYPE) {
        kotlin.runCatching {
            withContext(IO) {
                val bytes = uri.readBytes(appCtx)
                val url = httpUrl ?: throw NoStackTraceException("url不能为空")
                rustRequest(
                    method = "PUT",
                    url = url,
                    headers = mapOf("Content-Type" to contentType),
                    body = bytes,
                    rulePath = "WebDav.uploadUri"
                ).also {
                    checkResult(it)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav上传失败\n${it.localizedMessage}", it)
            throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
        }
    }

    @Throws(WebDavException::class)
    suspend fun downloadInputStream(): InputStream {
        val response = rustRequest(method = "GET", rulePath = "WebDav.download")
        checkResult(response)
        return ByteArrayInputStream(response.body)
    }

    /**
     * 移除文件/文件夹
     */
    suspend fun delete(): Boolean {
        val url = httpUrl ?: return false
        //防止报错
        return kotlin.runCatching {
            rustRequest(method = "DELETE", url = url, rulePath = "WebDav.delete").also {
                checkResult(it)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav删除失败\n${it.localizedMessage}", it)
        }.isSuccess
    }

    /**
     * 检测返回结果是否正确
     */
    private suspend fun rustRequest(
        method: String,
        url: String = httpUrl ?: throw WebDavException("WebDav请求出错\nurl为空"),
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        rulePath: String
    ): RustRawFetchResult {
        val requestHeaders = if (hostMatches(url)) {
            linkedMapOf(authorization.name to authorization.data).apply {
                putAll(headers)
            }
        } else {
            headers
        }
        return RustRemoteFetch.requestBytes(
            url = url,
            method = method,
            headers = requestHeaders,
            body = body,
            rulePath = rulePath
        ).let {
            RustRawFetchResult(
                url = url,
                code = it.code,
                message = it.message,
                headers = it.headers,
                headersList = it.headersList,
                contentType = it.contentType,
                body = it.body
            )
        }
    }

    private fun hostMatches(requestUrl: String): Boolean {
        return kotlin.runCatching {
            URL(requestUrl).host.equals(host, true)
        }.getOrDefault(false)
    }

    private fun RustRawFetchResult.contentLength(): Long {
        return headersList
            .firstOrNull { it.size >= 2 && it[0].equals("Content-Length", ignoreCase = true) }
            ?.get(1)
            ?.toLongOrNull()
            ?: body.size.toLong()
    }

    private val RustRawFetchResult.isSuccessful: Boolean
        get() = code in 200..299

    private fun checkResult(response: RustRawFetchResult) {
        if (!response.isSuccessful) {
            val body = response.body.toString(Charsets.UTF_8)
            if (response.code == 401) {
                val headers = response.headersList
                    .filter { it.size >= 2 && it[0].equals("WWW-Authenticate", ignoreCase = true) }
                    .map { it[1] }
                val supportBasicAuth = headers.any {
                    it.startsWith("Basic", ignoreCase = true)
                }
                if (headers.isNotEmpty() && !supportBasicAuth) {
                    AppLog.put("服务器不支持BasicAuth认证")
                }
            }

            val statusMessage = response.message.takeUnless { it == "OK" }.orEmpty()
            if (statusMessage.isNotBlank() || body.isBlank()) {
                throw WebDavException("${response.url}\n${response.code}:$statusMessage")
            }
            val parsedError = RustAnalyzerBridge.parseWebDavError(body)
            val exception = parsedError.exception.takeIf { it.isNotBlank() }
            val serverMessage = parsedError.message.takeIf { it.isNotBlank() }
            if (exception == "ObjectNotFound") {
                throw ObjectNotFoundException(
                    serverMessage ?: "$path doesn't exist. code:${response.code}"
                )
            }
            throw WebDavException(serverMessage ?: "未知错误 code:${response.code}")
        }
    }

}

internal fun normalizeHttpUrl(raw: String): String? {
    return kotlin.runCatching {
        URL(raw).toURI().toASCIIString()
    }.getOrElse {
        kotlin.runCatching {
            val url = URL(raw)
            java.net.URI(
                url.protocol,
                url.userInfo,
                url.host,
                url.port,
                url.path,
                url.query,
                url.ref
            ).toASCIIString()
        }.getOrNull()
    }
}
