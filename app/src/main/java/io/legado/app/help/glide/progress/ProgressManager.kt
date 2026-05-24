package io.legado.app.help.glide.progress

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 进度监听器管理类
 * 加入图片加载进度监听，加入Https支持
 */
object ProgressManager {
    private val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")
    private val listenersMap = ConcurrentHashMap<String, OnProgressListener>()

    fun addListener(url: String, listener: OnProgressListener) {
        if (url.isNotEmpty()) {
            val url = getUrlNoOption(url)
            listenersMap[url] = listener
            listener.invoke(false, 1, 0, 0)
        }
    }

    fun removeListener(url: String) {
        if (url.isNotEmpty()) {
            val url = getUrlNoOption(url)
            listenersMap.remove(url)
        }
    }

    fun getProgressListener(url: String): OnProgressListener? {
        return if (url.isEmpty() || listenersMap.isEmpty()) {
            null
        } else {
            listenersMap[url]
        }
    }

    private fun getUrlNoOption(url: String): String {
        val urlMatcher = paramPattern.matcher(url)
        return if (urlMatcher.find()) {
            url.take(urlMatcher.start())
        } else {
            url
        }
    }

}
