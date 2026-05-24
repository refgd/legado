package io.legado.app.data.entities

import android.webkit.JavascriptInterface
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ConcurrentRateLimiter.Companion.updateConcurrentRate
import io.legado.app.help.JsExtensions
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.SharedJsScope.remove
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.has
import io.legado.app.utils.isMainThread
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language
import java.io.InputStream

/**
 * 可在js里调用,source.xxx()
 */
@Suppress("unused")
interface BaseSource : JsExtensions {
    /**
     * 并发率
     */
    var concurrentRate: String?

    /**
     * 登录地址
     */
    var loginUrl: String?

    /**
     * 登录UI
     */
    var loginUi: String?

    /**
     * 请求头
     */
    var header: String?

    /**
     * 启用cookieJar
     */
    var enabledCookieJar: Boolean?

    /**
     * js库
     */
    var jsLib: String?

    override fun getTag(): String

    fun getKey(): String

    override fun getSource(): BaseSource? {
        return this
    }

    fun getLoginJs(): String? {
        val loginJs = loginUrl
        return when {
            loginJs == null -> null
            loginJs.startsWith("@js:") -> loginJs.substring(4)
            loginJs.startsWith("<js>") -> loginJs.substring(4, loginJs.lastIndexOf("<"))
            else -> loginJs
        }
    }

    /**
     * 调用login函数 实现登录请求
     */
    @JavascriptInterface
    fun login() {
        val loginJs = getLoginJs()
        if (!loginJs.isNullOrBlank()) {
            @Language("js")
            val js = """$loginJs
                if(typeof login=='function'){
                    login.apply(this);
                } else {
                    throw('Function login not implements!!!')
                }
            """.trimIndent()
            evalJS(js)
        }
    }

    /**
     * 解析header规则
     */
    fun getHeaderMap(hasLoginHeader: Boolean = false) = HashMap<String, String>().apply {
        header?.let {
            val json = when {
                it.startsWith("@js:", true) -> evalJS(it.substring(4)).toString()
                it.startsWith("<js>", true) -> evalJS(
                    it.substring(4, it.lastIndexOf("<"))
                ).toString()

                else -> it
            }
            if (json.isBlank()) {
                return@let
            }
            putAll(parseHeaderRuleMap(json, "BaseSource.getHeaderMap"))
        }
        if (!has(AppConst.UA_NAME, true)) {
            put(AppConst.UA_NAME, AppConfig.userAgent)
        }
        if (hasLoginHeader) {
            getLoginHeaderMap()?.let {
                putAll(it)
            }
        }
    }

    /**
     * 获取用于登录的头部信息
     */
    @JavascriptInterface
    fun getLoginHeader(): String? {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.getLoginHeader")
        }
        return RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.getLoginHeader()",
            rulePath = "BaseSource.getLoginHeader"
        ).ifBlank { null }
    }

    fun getLoginHeaderMap(): Map<String, String>? {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.getLoginHeaderMap")
        }
        val json = RustAnalyzerBridge.evalJs(
            source = this,
            script = "JSON.stringify(source.getLoginHeaderMap() || {})",
            rulePath = "BaseSource.getLoginHeaderMap"
        )
        return parseHeaderRuleMap(json, "BaseSource.getLoginHeaderMap")
            .takeIf { it.isNotEmpty() }
    }

    private fun parseHeaderRuleMap(json: String, rulePath: String): Map<String, String> {
        return GSON.fromJsonObject<Map<String, String>>(json).getOrElse {
            throw NoStackTraceException(
                "$rulePath header JSON is invalid for Rust analyzer handoff " +
                    "for ${getTag()}(${getKey()}): ${json.take(300)}"
            )
        }
    }

    /**
     * 保存登录头部信息,map格式,访问时自动添加
     */
    fun putLoginHeader(header: String) {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.putLoginHeader")
        }
        RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.putLoginHeader(${GSON.toJson(header)})",
            rulePath = "BaseSource.putLoginHeader"
        )
    }

    fun removeLoginHeader() {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.removeLoginHeader")
        }
        RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.removeLoginHeader()",
            rulePath = "BaseSource.removeLoginHeader"
        )
    }

    /**
     * 获取用户信息,可以用来登录
     */
    @JavascriptInterface
    fun getLoginInfo(): String? {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.getLoginInfo")
        }
        val json = RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.getLoginInfo()",
            rulePath = "BaseSource.getLoginInfo"
        )
        return json.takeIf { it.isNotBlank() && it != "{}" }
    }

    private fun configureDefaultEvalBindings(): RustScriptBindings.() -> Unit = {
        put("result", mutableMapOf<String, String>())
        put("book", null)
        put("chapter", null)
    }

    fun getLoginInfoMap(): MutableMap<String, String> {
        val json = getLoginInfo() ?: if (loginUi.isNullOrBlank()) {
            return mutableMapOf()
        } else {
            val loginUiJson = loginUi?.let {
                when {
                    it.startsWith("@js:") -> evalJS(
                        "${getLoginJs() ?: ""}\n${it.substring(4)}",
                        configureDefaultEvalBindings()
                    ).toString()

                    it.startsWith("<js>") -> evalJS(
                        "${getLoginJs() ?: ""}\n${it.substring(4, it.lastIndexOf("<"))}",
                        configureDefaultEvalBindings()
                    ).toString()

                    else -> it
                }
            }
            val longinInfo = parseLoginUiRows(loginUiJson)
                ?.filter { it.type != "button" }
                ?.associate { it.name to (it.default ?: "") }
                ?.takeIf { it.isNotEmpty() }?.also {
                    putLoginInfo(GSON.toJson(it))
                }
            return longinInfo?.toMutableMap() ?: mutableMapOf()
        }
        return parseLoginInfoMap(json)
    }

    private fun parseLoginUiRows(json: String?): List<RowUi>? {
        if (json.isNullOrBlank()) {
            return null
        }
        return GSON.fromJsonArray<RowUi>(json).getOrElse {
            throw NoStackTraceException(
                "BaseSource.getLoginInfoMap loginUi must return a JSON array for ${getTag()}(${getKey()}): ${json.take(300)}"
            )
        }
    }

    private fun parseLoginInfoMap(json: String): MutableMap<String, String> {
        if (json.isBlank() || json == "{}") {
            return mutableMapOf()
        }
        return GSON.fromJsonObject<MutableMap<String, String>>(json).getOrElse {
            throw NoStackTraceException(
                "BaseSource.getLoginInfoMap login info must return a JSON object for ${getTag()}(${getKey()}): ${json.take(300)}"
            )
        }
    }

    /**
     * 保存用户信息
     */
    @JavascriptInterface
    fun putLoginInfo(info: String): Boolean {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.putLoginInfo")
        }
        RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.putLoginInfo(${GSON.toJson(info)})",
            rulePath = "BaseSource.putLoginInfo"
        )
        return true
    }

    @JavascriptInterface
    fun removeLoginInfo() {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.removeLoginInfo")
        }
        RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.removeLoginInfo()",
            rulePath = "BaseSource.removeLoginInfo"
        )
    }

    /**
     * 设置自定义变量
     * @param variable 变量内容
     */
    fun setVariable(variable: String?) {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.setVariable")
        }
        RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.setVariable(${GSON.toJson(variable ?: "")})",
            rulePath = "BaseSource.setVariable"
        )
    }

    /**
     * 设置自定义变量
     * 新,统一为put名称存变量
     */
    @JavascriptInterface
    fun putVariable(variable: String?) {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.putVariable")
        }
        RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.putVariable(${GSON.toJson(variable ?: "")})",
            rulePath = "BaseSource.putVariable"
        )
    }

    /**
     * 获取自定义变量
     */
    @JavascriptInterface
    fun getVariable(): String {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.getVariable")
        }
        return RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.getVariable()",
            rulePath = "BaseSource.getVariable"
        )
    }

    /**
     * 保存数据
     */
    @JavascriptInterface
    fun put(key: String, value: String): String {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.put")
        }
        return RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.put(${GSON.toJson(key)}, ${GSON.toJson(value)})",
            rulePath = "BaseSource.put"
        )
    }

    /**
     * 获取保存的数据
     */
    @JavascriptInterface
    fun get(key: String): String {
        if (!RustAnalyzerBridge.isAvailable) {
            error("Rust analyzer UniFFI binding is required for source.get")
        }
        return RustAnalyzerBridge.evalJs(
            source = this,
            script = "source.get(${GSON.toJson(key)})",
            rulePath = "BaseSource.get"
        )
    }

    /**
     * 刷新发现
     */
    fun refreshExplore() {
        if (isMainThread) {
            error("refreshExplore must be called on a background thread")
        }
        runBlocking {
            if (this@BaseSource is BookSource) {
                this@BaseSource.clearExploreKindsCache()
            }
        }
    }

    /**
     * 刷新JSLib
     */
    fun refreshJSLib() {
        if (isMainThread) {
            error("refreshJSLib must be called on a background thread")
        }
        runBlocking {
            remove(jsLib)
        }
    }

    /**
     * 设置并发率
     */
    fun putConcurrent(value: String) {
        updateConcurrentRate(getKey(),value)
    }

    /**
     * 执行JS
     */
    @Throws(Exception::class)
    fun evalJS(jsStr: String, bindingsConfig: RustScriptBindings.() -> Unit = {}): Any? {
        val bindings = RustScriptBindings().apply {
            put("java", this@BaseSource)
            put("source", this@BaseSource)
            put("baseUrl", getKey())
            bindingsConfig()
        }
        val bindingsJson = bindings.toRustBindingsJson()
        return RustAnalyzerBridge.evalJsAny(
            source = this,
            script = jsStr,
            result = bindings["result"],
            baseUrl = getKey(),
            rulePath = "BaseSource.evalJS",
            platformJava = bindings["java"],
            bindingsJson = bindingsJson
        )
    }
}

class RustScriptBindings {
    private val values = linkedMapOf<String, Any?>()

    operator fun set(key: String, value: Any?) {
        values[key] = value
    }

    operator fun get(key: String): Any? = values[key]

    fun put(key: String, value: Any?) {
        values[key] = value
    }

    fun toRustBindingsJson(): String {
        val reserved = setOf("java", "source", "cookie", "cache")
        return GSON.newBuilder()
            .serializeNulls()
            .create()
            .toJson(values.filterKeys { it !in reserved }.mapValues { encodeRustBindingValue(it.value) })
    }
}

private fun encodeRustBindingValue(value: Any?): Any? {
    return when (value) {
        is ByteArray -> mapOf("__javaBytesHex" to value.toHexString())
        is InputStream -> mapOf("__javaBytesHex" to value.readBytes().toHexString())
        else -> value
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
