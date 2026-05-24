package io.legado.app.utils

import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.semicolonRegex
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.RustAnalyzerBridge
import java.net.URL
import java.net.URLDecoder

object UrlUtil {

    // 有时候文件名在query里，截取path会截到其他内容
    // https://www.example.com/download.php?filename=文件.txt
    // https://www.example.com/txt/文件.txt?token=123456
    private val unExpectFileSuffixs = arrayOf(
        "php", "html"
    )

    fun replaceReservedChar(text: String): String {
        return text.replace("%", "%25")
            .replace(" ", "%20")
            .replace("\"", "%22")
            .replace("#", "%23")
            .replace("&", "%26")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace("+", "%2B")
            .replace(",", "%2C")
            .replace("/", "%2F")
            .replace(":", "%3A")
            .replace(";", "%3B")
            .replace("<", "%3C")
            .replace("=", "%3D")
            .replace(">", "%3E")
            .replace("?", "%3F")
            .replace("@", "%40")
            .replace("\\", "%5C")
            .replace("|", "%7C")
    }


    /**
     * 根据网络url获取文件信息 文件名
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun getFileName(fileUrl: String, headerMap: Map<String, String>? = null): String? {
        return try {
            val url = URL(fileUrl)
            var fileName: String? = getFileNameFromPath(url)
            if (fileName == null) {
                fileName = getFileNameFromResponseHeader(url, headerMap)
            }
            fileName
        } catch (e: Exception) {
            throw NoStackTraceException(
                "UrlUtil file name resolution failed through Rust fetch: $fileUrl; ${e.localizedMessage}"
            )
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    private fun getFileNameFromResponseHeader(
        url: URL,
        headerMap: Map<String, String>? = null
    ): String? {
        val requestOptions = linkedMapOf<String, Any>("method" to "HEAD")
        headerMap?.takeIf { it.isNotEmpty() }?.let {
            requestOptions["headers"] = it
        }
        val response = RustAnalyzerBridge.fetchRawUrl(
            "${url},${GSON.toJson(requestOptions)}",
            "UrlUtil.getFileName"
        )

        if (AppConfig.recordLog || BuildConfig.DEBUG) {
            val headersString = buildString {
                response.headersList.forEach { pair ->
                    append(pair.getOrNull(0).orEmpty())
                    append(": ")
                    append(pair.getOrNull(1).orEmpty())
                    append("\n")
                }
            }
            AppLog.put("$url response header:\n$headersString")
        }

        // val fileSize = conn.getContentLengthLong() / 1024
        /** Content-Disposition 存在三种情况 文件名应该用引号 有些用空格
         * filename="filename"
         * filename*="charset''filename"
         */
        val raw: String? = response.headerValue("Content-Disposition")
        // Location跳转到实际链接
        val redirectUrl: String? = response.headerValue("Location")

        return if (raw != null) {
            val fileNames = raw.split(semicolonRegex).filter { it.contains("filename") }
            val names = hashSetOf<String>()
            fileNames.forEach {
                val fileName = it.substringAfter("=")
                    .trim()
                    .replace("^\"".toRegex(), "")
                    .replace("\"$".toRegex(), "")
                if (it.contains("filename*")) {
                    val data = fileName.split("''")
                    names.add(URLDecoder.decode(data[1], data[0]))
                } else {
                    names.add(fileName)
                    /* 好像不用这样
                    names.add(
                            String(
                            fileName.toByteArray(StandardCharsets.ISO_8859_1),
                            StandardCharsets.UTF_8
                        )
                    )
                    */
                }
           }
           names.firstOrNull()
        } else if (redirectUrl != null) {
            val newUrl= URL(URLDecoder.decode(redirectUrl, "UTF-8"))
            getFileNameFromPath(newUrl)
        } else {
            AppLog.put("Cannot obtain URL file name, enable recordLog for response header")
            null
        }
    }

    private fun io.legado.app.model.webBook.RustRawFetchResult.headerValue(name: String): String? {
        return headersList.firstNotNullOfOrNull { pair ->
            val key = pair.getOrNull(0)
            val value = pair.getOrNull(1)
            if (key.equals(name, ignoreCase = true)) value else null
        } ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
    
    private fun getFileNameFromPath(fileUrl: URL): String? {
        val path = fileUrl.path ?: return null
        val suffix = getSuffix(path, "")
        return if (
           suffix != "" && !unExpectFileSuffixs.contains(suffix)
        ) {
            path.substringAfterLast("/")
        } else {
            AppLog.put("getFileNameFromPath: Unexpected file suffix: $suffix")
            null
        }
    }

    private val fileSuffixRegex = Regex("^[a-z\\d]+$", RegexOption.IGNORE_CASE)

    /* 获取合法的文件后缀 */
    fun getSuffix(str: String, default: String? = null): String {
        val suffix = CustomUrl(str).getUrl()
            .substringAfterLast("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringAfterLast(".", "")
        //检查截取的后缀字符是否合法 [a-zA-Z0-9]
        return if (suffix.length > 5 || !suffix.matches(fileSuffixRegex)) {
            if (default == null) {
                AppLog.put("Cannot find legal suffix:\n target: $str\n suffix: $suffix")
            }
            default ?: "ext"
        } else {
            suffix
        }
    }

}
