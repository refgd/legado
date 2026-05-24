package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.model.webBook.RustAnalyzerBridge

/**
 * 字典规则
 */
@Entity(tableName = "dictRules")
data class DictRule(
    @PrimaryKey
    var name: String = "",
    var urlRule: String = "",
    var showRule: String = "",
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    var sortNumber: Int = 0
) {

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is DictRule) {
            return name == other.name
        }
        return false
    }

    /**
     * 搜索字典
     */
    suspend fun search(word: String): String {
        return RustAnalyzerBridge.dictSearch(name, urlRule, showRule, word)
    }

    suspend fun buttonClick(name: String, click: String) {
        RustAnalyzerBridge.evalJsRaw(
            script = click,
            result = name,
            baseUrl = "legado://dict/${this.name.ifBlank { "anonymous" }}",
            rulePath = "DictRule.${this.name}.buttonClick"
        )
    }

}
