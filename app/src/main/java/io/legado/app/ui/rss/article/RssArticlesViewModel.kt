package io.legado.app.ui.rss.article

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.webBook.isRustNetworkAccessError
import io.legado.app.model.rss.Rss
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO


class RssArticlesViewModel(application: Application) : BaseViewModel(application) {
    val loadFinallyLiveData = MutableLiveData<Boolean>()
    val loadErrorLiveData = MutableLiveData<String>()
    var isLoading = true
    var order = System.currentTimeMillis()
    private var nextPageUrl: String? = null
    var sortName: String = ""
    var sortUrl: String = ""
    var searchKey: String? = null
    var page = 1

    fun init(bundle: Bundle?) {
        bundle?.let {
            sortName = it.getString("sortName") ?: ""
            sortUrl = it.getString("sortUrl") ?: ""
            searchKey = it.getString("searchKey")
        }
    }

    fun loadArticles(rssSource: RssSource) {
        isLoading = true
        page = 1
        order = System.currentTimeMillis()
        Rss.getArticles(viewModelScope, sortName, sortUrl, rssSource, page, searchKey).onSuccess(IO) {
            nextPageUrl = it.second
            val articles = it.first
            articles.forEach { rssArticle ->
                rssArticle.order = order--
            }
            appDb.rssArticleDao.insert(*articles.toTypedArray())
            if (!rssSource.ruleNextPage.isNullOrEmpty()) {
                appDb.rssArticleDao.clearOld(rssSource.sourceUrl, sortName, order)
            }
            val hasMore = articles.isNotEmpty() && !rssSource.ruleNextPage.isNullOrEmpty()
            loadFinallyLiveData.postValue(hasMore)
            isLoading = false
        }.onError {
            loadFinallyLiveData.postValue(false)
            loadErrorLiveData.postValue(it.stackTraceStr)
            if (it.isRustNetworkAccessError()) {
                isLoading = false
                AppLog.put("RssArticles network load failed for ${rssSource.sourceName} page $page\n${it.localizedMessage}", it)
                return@onError
            }
            throw NoStackTraceException(
                "RssArticles Rust list failed for ${rssSource.sourceName} page $page: " +
                        (it.localizedMessage ?: it::class.java.name)
            )
        }
    }

    fun loadMore(rssSource: RssSource) {
        isLoading = true
        page++
        val pageUrl = nextPageUrl
        if (pageUrl.isNullOrEmpty()) {
            loadFinallyLiveData.postValue(false)
            return
        }
        Rss.getArticles(viewModelScope, sortName, pageUrl, rssSource, page, searchKey).onSuccess(IO) {
            nextPageUrl = it.second
            loadMoreSuccess(it.first)
            isLoading = false
        }.onError {
            loadFinallyLiveData.postValue(false)
            loadErrorLiveData.postValue(it.stackTraceStr)
            if (it.isRustNetworkAccessError()) {
                isLoading = false
                AppLog.put("RssArticles network load failed for ${rssSource.sourceName} page $page\n${it.localizedMessage}", it)
                return@onError
            }
            throw NoStackTraceException(
                "RssArticles Rust list failed for ${rssSource.sourceName} page $page: " +
                        (it.localizedMessage ?: it::class.java.name)
            )
        }
    }

    private fun loadMoreSuccess(articles: MutableList<RssArticle>) {
        if (articles.isEmpty()) {
            loadFinallyLiveData.postValue(false)
            return
        }
        val firstArticle = articles.first()
        val dbFirstArticle = appDb.rssArticleDao.get(firstArticle.origin, firstArticle.link, firstArticle.sort)
        val lastArticle = articles.last()
        val dbLastArticle = appDb.rssArticleDao.get(lastArticle.origin, lastArticle.link, firstArticle.sort)
        if (dbFirstArticle != null && dbLastArticle != null) {
            loadFinallyLiveData.postValue(false)
        } else {
            articles.forEach {
                it.order = order--
            }
            appDb.rssArticleDao.append(*articles.toTypedArray())
        }
    }

}
