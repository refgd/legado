package io.legado.app.ui.main.rss

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.removeSortCache
import io.legado.app.ui.rss.read.resolveStartHtmlByRustEval
import io.legado.app.utils.toastOnUi

class RssViewModel(application: Application) : BaseViewModel(application) {

    fun topSource(vararg sources: RssSource) {
        execute {
            sources.sortBy { it.customOrder }
            val minOrder = appDb.rssSourceDao.minOrder - 1
            val array = Array(sources.size) {
                sources[it].copy(customOrder = minOrder - it)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun bottomSource(vararg sources: RssSource) {
        execute {
            sources.sortBy { it.customOrder }
            val maxOrder = appDb.rssSourceDao.maxOrder + 1
            val array = Array(sources.size) {
                sources[it].copy(customOrder = maxOrder + it)
            }
            appDb.rssSourceDao.update(*array)
        }
    }

    fun del(vararg rssSource: RssSource) {
        execute {
            SourceHelp.deleteRssSources(rssSource.toList())
        }
    }

    fun disable(rssSource: RssSource) {
        execute {
            rssSource.enabled = false
            appDb.rssSourceDao.update(rssSource)
        }
    }

    fun clearArticles(sourceUrl: String, onFinally: () -> Unit) {
        execute {
            appDb.rssArticleDao.delete(sourceUrl)
        }.onFinally {
            onFinally.invoke()
        }
    }

    fun clearSortCache(rssSource: RssSource, onFinally: () -> Unit) {
        execute {
            rssSource.removeSortCache()
        }.onFinally {
            onFinally.invoke()
        }
    }

    fun getSingleUrl(
        rssSource: RssSource,
        onSuccess: (url: String) -> Unit,
        onError: ((Throwable) -> Unit)? = null
    ) {
        execute {
            var sortUrl = rssSource.sortUrl
            if (!sortUrl.isNullOrBlank()) {
                if (sortUrl.startsWith("<js>", false)
                    || sortUrl.startsWith("@js:", false)
                ) {
                    val jsStr = if (sortUrl.startsWith("@")) {
                        sortUrl.substring(4)
                    } else {
                        sortUrl.substring(4, sortUrl.lastIndexOf("<"))
                    }
                    val result = rssSource.evalJS(jsStr)?.toString()
                        ?: throw NoStackTraceException(
                            "${rssSource.getTag()} Rust RSS sortUrl JavaScript returned no result"
                        )
                    if (!result.isNullOrBlank()) {
                        sortUrl = result
                    }
                }
                if (sortUrl.contains("::")) {
                    return@execute sortUrl.split("::")[1]
                } else {
                    return@execute sortUrl
                }
            }
            rssSource.sourceUrl
        }.timeout(10000)
            .onSuccess {
                onSuccess.invoke(it)
            }.onError {
                onError?.invoke(it) ?: context.toastOnUi(it.localizedMessage)
            }
    }

    fun launchRssWithHtml(
        rssSource: RssSource,
        noStartHtml: () -> Unit,
        isStartHtml: (html: String) -> Unit,
        onError: ((Throwable) -> Unit)? = null
    ) {
        execute {
            return@execute rssSource.resolveStartHtmlByRustEval()
        }.timeout(10000)
            .onSuccess {
                if (it.isNullOrBlank()) {
                    noStartHtml.invoke()
                } else {
                    isStartHtml.invoke(it)
                }
            }.onError {
                onError?.invoke(it) ?: context.toastOnUi(it.localizedMessage)
            }
    }


}
