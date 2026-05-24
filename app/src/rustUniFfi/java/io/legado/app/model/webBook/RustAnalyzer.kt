package io.legado.app.model.webBook

import io.legado.app.rust.analyzeJson

object RustAnalyzerNative {

    fun search(sourceJson: String, key: String, page: Int): String {
        return analyze(
            sourceJson = sourceJson,
            operation = "search",
            inputJson = """{"key":${key.jsonQuote()},"page":$page}"""
        )
    }

    fun detail(sourceJson: String, bookUrl: String): String {
        return analyze(
            sourceJson = sourceJson,
            operation = "detail",
            inputJson = """{"bookUrl":${bookUrl.jsonQuote()}}"""
        )
    }

    fun toc(sourceJson: String, tocUrl: String, bookUrl: String = ""): String {
        return analyze(
            sourceJson = sourceJson,
            operation = "toc",
            inputJson = """{"tocUrl":${tocUrl.jsonQuote()},"bookUrl":${bookUrl.jsonQuote()}}"""
        )
    }

    fun content(sourceJson: String, chapterUrl: String, bookUrl: String = ""): String {
        return analyze(
            sourceJson = sourceJson,
            operation = "content",
            inputJson = """{"chapterUrl":${chapterUrl.jsonQuote()},"bookUrl":${bookUrl.jsonQuote()}}"""
        )
    }

    fun explore(sourceJson: String, exploreUrl: String, page: Int = 1): String {
        return analyze(
            sourceJson = sourceJson,
            operation = "explore",
            inputJson = """{"exploreUrl":${exploreUrl.jsonQuote()},"page":$page}"""
        )
    }

    fun analyze(sourceJson: String, operation: String, inputJson: String): String {
        return analyzeJson(sourceJson, operation, inputJson)
    }

    private fun String.jsonQuote(): String {
        return buildString(length + 2) {
            append('"')
            for (char in this@jsonQuote) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.code < 0x20) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
            append('"')
        }
    }
}
