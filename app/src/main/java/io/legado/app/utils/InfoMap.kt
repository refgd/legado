package io.legado.app.utils

import androidx.annotation.Keep
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.webBook.RustAnalyzerBridge

/**
 * 发现按钮信息
 */
@Keep
class InfoMap(val sourceUrl: String): MutableMap<String, String> {
    private val rustKey = "__legado_info_map"
    private var actualMap: MutableMap<String, String>
    var needSave = false
    private var saveTime = 0

    init {
        val cache = RustAnalyzerBridge.evalJs(
            source = rustSource(),
            script = "source.get(${GSON.toJson(rustKey)})",
            rulePath = "InfoMap.init"
        )
        actualMap = when {
            cache.isBlank() || cache == "null" -> mutableMapOf()
            else -> GSON.fromJsonObject<MutableMap<String, String>>(cache).getOrElse { error ->
                throw NoStackTraceException(
                    "InfoMap Rust source state JSON is invalid for $sourceUrl: " +
                            (error.localizedMessage ?: error::class.java.name)
                )
            }
        }
    }

    /**
     * time 保存时间 单位为秒
     */
    @JvmOverloads
    fun save(time: Int = 0, need: Boolean = true) {
        needSave = need
        saveTime = time
    }

    fun saveNow() {
        val json = GSON.toJson(actualMap)
        RustAnalyzerBridge.evalJs(
            source = rustSource(),
            script = "source.put(${GSON.toJson(rustKey)}, ${GSON.toJson(json)})",
            rulePath = "InfoMap.saveNow"
        )
        needSave = false
    }

    private fun rustSource(): BookSource = BookSource(
        bookSourceUrl = sourceUrl,
        bookSourceName = sourceUrl
    )

    fun get(): MutableMap<String, String> {
        return actualMap
    }

    fun set(value: Map<String, String>) {
        actualMap = value.toMutableMap()
    }

    override fun get(key: String) = actualMap[key]
    override fun put(key: String, value: String) = actualMap.put(key, value)
    override fun remove(key: String) = actualMap.remove(key)
    override fun putAll(from: Map<out String, String>) = actualMap.putAll(from)
    override fun containsKey(key: String) = actualMap.containsKey(key)
    override fun containsValue(value: String) = actualMap.containsValue(value)
    override val size get() = actualMap.size
    override val entries get() = actualMap.entries
    override val keys get() = actualMap.keys
    override val values get() = actualMap.values
    override fun isEmpty() = actualMap.isEmpty()
    override fun clear() = actualMap.clear()
}
