package io.legado.app.help

import androidx.annotation.Keep
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import java.io.File
import splitties.init.appCtx

@Suppress("MemberVisibilityCanBePrivate")
object DirectLinkUpload {

    const val ruleFileName = "directLinkUploadRule.json"

    @Throws(NoStackTraceException::class)
    suspend fun upLoad(
        fileName: String,
        file: Any,
        contentType: String,
        rule: Rule = getRule()
    ): String {
        val url = rule.uploadUrl
        if (url.isBlank()) {
            throw NoStackTraceException("上传url未配置")
        }
        val downloadUrlRule = rule.downloadUrlRule
        if (downloadUrlRule.isBlank()) {
            throw NoStackTraceException("下载地址规则未配置")
        }
        val uploadBytes = when (val uploadFile = file) {
            is File -> uploadFile.readBytes()
            is ByteArray -> uploadFile
            is String -> uploadFile.toByteArray()
            else -> GSON.toJson(uploadFile).toByteArray()
        }
        val downloadUrl = RustAnalyzerBridge.directLinkUpload(
            uploadUrl = url,
            downloadUrlRule = downloadUrlRule,
            fileName = fileName,
            contentType = contentType,
            body = uploadBytes,
            compress = rule.compress && contentType != "application/zip"
        )
        if (downloadUrl.isBlank()) {
            throw NoStackTraceException("上传失败")
        }
        return downloadUrl
    }

    val defaultRules: List<Rule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}directLinkUpload.json")
                .readBytes()
        )
        GSON.fromJsonArray<Rule>(json).getOrThrow()
    }

    fun getRule(): Rule {
        return getConfig() ?: defaultRules[0]
    }

    fun getConfig(): Rule? {
        val json = ACache.get(cacheDir = false).getAsString(ruleFileName)
        if (json.isNullOrBlank()) {
            return null
        }
        return GSON.fromJsonObject<Rule>(json).getOrElse {
            throw NoStackTraceException(
                "DirectLinkUpload rule JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }
    }

    fun putConfig(rule: Rule) {
        ACache.get(cacheDir = false).put(ruleFileName, GSON.toJson(rule))
    }

    fun delConfig() {
        ACache.get(cacheDir = false).remove(ruleFileName)
    }

    fun getSummary(): String {
        return getRule().summary
    }

    @Keep
    data class Rule(
        var uploadUrl: String, //创建分享链接
        var downloadUrlRule: String, //下载链接规则
        var summary: String, //注释
        var compress: Boolean = false, //是否压缩
    ) {

        override fun toString(): String {
            return summary
        }

    }

}
