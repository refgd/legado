package io.legado.app.ui.browser

import android.app.Application
import android.content.Intent
import android.webkit.WebView
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.WebImageBytes
import org.apache.commons.text.StringEscapeUtils
import java.util.Date
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION2

class WebViewModel(application: Application) : BaseViewModel(application) {
    var source: BaseSource? = null
    var intent: Intent? = null
    var baseUrl: String = ""
    var html: String? = null
    var localHtml: Boolean = false
    val headerMap: HashMap<String, String> = hashMapOf()
    var sourceVerificationEnable: Boolean = false
    var refetchAfterSuccess: Boolean = true
    var sourceName: String = ""
    var sourceOrigin: String = ""
    var sourceType = SourceType.book

    fun initData(
        intent: Intent,
        success: () -> Unit
    ) {
        execute {
            this@WebViewModel.intent = intent
            val url = intent.getStringExtra("url")
                ?: throw NoStackTraceException("url不能为空")
            sourceName = intent.getStringExtra("sourceName") ?: ""
            sourceOrigin = intent.getStringExtra("sourceOrigin") ?: ""
            sourceType = intent.getIntExtra("sourceType", SourceType.book)
            sourceVerificationEnable = intent.getBooleanExtra("sourceVerificationEnable", false)
            refetchAfterSuccess = intent.getBooleanExtra("refetchAfterSuccess", true)
            html = intent.getStringExtra("html")?.let{
                localHtml = true
                val headIndex = it.indexOf("<head", ignoreCase = true)
                if (headIndex >= 0) {
                    val closingHeadIndex = it.indexOf('>', startIndex = headIndex)
                    if (closingHeadIndex >= 0) {
                        val insertPos = closingHeadIndex + 1
                        StringBuilder(it).insert(insertPos, "<script>$JS_INJECTION2</script>").toString()
                    } else {
                        "<head><script>$JS_INJECTION2</script></head>$it"
                    }
                } else {
                    "<head><script>$JS_INJECTION2</script></head>$it"
                }
            }
            source = SourceHelp.getSource(sourceOrigin, sourceType)
            val resolved = RustAnalyzerBridge.resolveUrl(
                url = url,
                source = source,
                rulePath = "WebViewModel.initData"
            )
            baseUrl = resolved.url
            headerMap.putAll(resolved.headerMap())
            if (resolved.method.equals("POST", ignoreCase = true)) {
                html = RustAnalyzerBridge.fetchText(
                    url = url,
                    source = source,
                    useWebView = false,
                    rulePath = "WebViewModel.initData.post"
                ).body
            }
        }.onSuccess {
            success.invoke()
        }.onError {
            context.toastOnUi("error\n${it.localizedMessage}")
            it.printOnDebug()
        }
    }

    fun saveImage(webPic: String?, path: String) {
        webPic ?: return
        execute {
            val fileName = "${AppConst.fileNameFormat.format(Date(System.currentTimeMillis()))}.jpg"
            webData2bitmap(webPic)?.let { byteArray ->
                val fileDoc = FileDoc.fromDir(path)
                val picFile = fileDoc.createFileIfNotExist(fileName)
                picFile.openOutputStream().getOrThrow().use {
                    it.write(byteArray)
                }
            } ?: throw Throwable("NULL")
        }.onError {
            ACache.get().remove(imagePathKey)
            context.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            context.toastOnUi("保存成功")
        }
    }

    private suspend fun webData2bitmap(data: String): ByteArray? {
        return WebImageBytes.fetch(data, source, "WebViewModel.saveImage")
    }

    fun saveVerificationResult(webView: WebView, success: () -> Unit) {
        if (!sourceVerificationEnable) {
            return success.invoke()
        }
        if (refetchAfterSuccess) {
            execute {
                val url = intent!!.getStringExtra("url")!!
                val source = appDb.bookSourceDao.getBookSource(sourceOrigin)
                if (html == null) {
                    html = RustAnalyzerBridge.fetchText(
                        url = url,
                        source = source,
                        useWebView = false,
                        rulePath = "WebViewModel.saveVerificationResult"
                    ).body
                }
                SourceVerificationHelp.setResult(sourceOrigin, html ?: "", baseUrl)
            }.onSuccess {
                success.invoke()
            }
        } else {
            webView.evaluateJavascript("document.documentElement.outerHTML") {
                execute {
                    html = StringEscapeUtils.unescapeJson(it).trim('"')
                }.onSuccess {
                    SourceVerificationHelp.setResult(sourceOrigin, html ?: "",  webView.url ?: "")
                    success.invoke()
                }
            }
        }
    }

    fun disableSource(block: () -> Unit) {
        execute {
            SourceHelp.enableSource(sourceOrigin, sourceType, false)
        }.onSuccess {
            block.invoke()
        }
    }

    fun deleteSource(block: () -> Unit) {
        execute {
            SourceHelp.deleteSource(sourceOrigin, sourceType)
        }.onSuccess {
            block.invoke()
        }
    }

}

private fun io.legado.app.model.webBook.RustResolvedUrl.headerMap(): Map<String, String> {
    return headers.mapNotNull { pair ->
        val key = pair.getOrNull(0)?.takeIf { it.isNotBlank() }
        val value = pair.getOrNull(1)
        if (key == null || value == null) null else key to value
    }.toMap()
}
