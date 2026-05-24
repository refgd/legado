package io.legado.app.help

import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.*

object ReplaceAnalyzer {

    fun jsonToReplaceRules(json: String): Result<MutableList<ReplaceRule>> {
        return kotlin.runCatching {
            val replaceRules = mutableListOf<ReplaceRule>()
            val items: List<Map<String, Any>> = jsonPath.parse(json).read("$")
            for (item in items) {
                val jsonItem = jsonPath.parse(item)
                jsonToReplaceRule(jsonItem.jsonString()).getOrThrow().let {
                    if (it.isValid()) {
                        replaceRules.add(it)
                    }
                }
            }
            replaceRules
        }
    }

    fun jsonToReplaceRule(json: String): Result<ReplaceRule> {
        return runCatching {
            val trimmed = json.trim()
            var parseError: Throwable? = null
            val parsedRule = GSON.fromJsonObject<ReplaceRule>(trimmed).fold(
                onSuccess = { it },
                onFailure = {
                    parseError = it
                    null
                }
            )
            if (parsedRule == null || parsedRule.pattern.isEmpty()) {
                val jsonItem = jsonPath.parse(json.trim())
                val rule = ReplaceRule()
                rule.id = jsonItem.readLong("$.id") ?: System.currentTimeMillis()
                rule.pattern = jsonItem.readString("$.regex") ?: ""
                if (rule.pattern.isEmpty()) {
                    throw NoStackTraceException(
                        "ReplaceAnalyzer rule JSON is invalid for Rust analyzer handoff: missing pattern or legacy regex" +
                                (parseError?.localizedMessage?.let { "; parse error: $it" } ?: "")
                    )
                }
                rule.name = jsonItem.readString("$.replaceSummary") ?: ""
                rule.replacement = jsonItem.readString("$.replacement") ?: ""
                rule.isRegex = jsonItem.readBool("$.isRegex") == true
                rule.scope = jsonItem.readString("$.useTo")
                rule.isEnabled = jsonItem.readBool("$.enable") == true
                rule.order = jsonItem.readInt("$.serialNumber") ?: 0
                return@runCatching rule
            }
            return@runCatching parsedRule
        }
    }

}
