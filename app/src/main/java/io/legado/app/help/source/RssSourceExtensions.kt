package io.legado.app.help.source

import io.legado.app.data.entities.RssSource
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.utils.ACache
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val aCache by lazy { ACache.get("rssSortUrl") }

private fun RssSource.getSortUrlsKey(): String {
    return MD5Utils.md5Encode(sourceUrl + sortUrl)
}

suspend fun RssSource.sortUrls(): List<Pair<String, String>> {
    return withContext(Dispatchers.IO) {
        val sortUrlsKey = getSortUrlsKey()
        val cached = aCache.getAsString(sortUrlsKey)
        if (!cached.isNullOrBlank()) {
            return@withContext parseSortUrls(cached).ifEmpty { listOf("" to sourceUrl) }
        }
        val sorts = RustAnalyzerBridge.rssSortUrls(this@sortUrls)
        if (sortUrl?.startsWith("<js>", false) == true
            || sortUrl?.startsWith("@js:", false) == true
        ) {
            aCache.put(sortUrlsKey, sorts.joinToString("\n") { "${it.first}::${it.second}" })
        }
        sorts.ifEmpty { listOf("" to sourceUrl) }
    }
}

suspend fun RssSource.removeSortCache() {
    withContext(Dispatchers.IO) {
        aCache.remove(getSortUrlsKey())
    }
}

private fun parseSortUrls(raw: String): List<Pair<String, String>> {
    return raw.split("(&&|\n)+".toRegex())
        .mapNotNull { sort ->
            val name = sort.substringBefore("::")
            val url = sort.substringAfter("::", "")
            if (url.isNotEmpty()) name to url else null
        }
}
