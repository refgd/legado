package io.legado.app.help.glide

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.util.ContentLengthInputStream
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.ReadManga
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.model.webBook.RustRawFetchResult
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isWifiConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.InputStream


class RustImageStreamFetcher(
    private val url: GlideUrl,
    private val options: Options,
) :
    DataFetcher<InputStream> {
    private var stream: InputStream? = null
    private var callback: DataFetcher.DataCallback<in InputStream>? = null
    private var source: BaseSource? = null
    private val manga = options.get(RustImageModelLoader.mangaOption) == true
    private val coroutineContext = SupervisorJob()
    private val coroutineScope = CoroutineScope(coroutineContext)

    @Volatile
    private var cancelled = false

    companion object {
        private val failUrl = hashSetOf<String>()
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        if (failUrl.contains(url.toStringUrl())) {
            callback.onLoadFailed(NoStackTraceException("跳过加载失败的图片"))
            return
        }
        val loadOnlyWifi = options.get(RustImageModelLoader.loadOnlyWifiOption) ?: false
        if (loadOnlyWifi && !appCtx.isWifiConnect) {
            callback.onLoadFailed(NoStackTraceException("只在wifi加载图片"))
            return
        }

        options.get(RustImageModelLoader.sourceOriginOption)?.let { sourceUrl ->
            source = SourceHelp.getSource(sourceUrl)
        }
        if (source == null && manga) {
            source = ReadManga.bookSource
        }

        this.callback = callback
        Coroutine.async(coroutineScope, executeContext = IO) {
            val response = source?.let {
                RustAnalyzerBridge.fetchRawResponse(
                    it,
                    url.toString(),
                    "RustImageStreamFetcher.image"
                )
            } ?: RustAnalyzerBridge.fetchRawUrl(url.toString(), "RustImageStreamFetcher.image")
            if (cancelled) return@async
            onRustResponse(response)
        }.onError {
            callback.onLoadFailed(it as? Exception ?: RuntimeException(it))
        }
    }

    override fun cleanup() {
        kotlin.runCatching {
            stream?.close()
        }
        coroutineContext.cancel()
        callback = null
    }

    override fun cancel() {
        cancelled = true
        coroutineContext.cancel()
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.REMOTE
    }

    private fun onRustResponse(response: RustRawFetchResult) {
        if (response.code !in 200..299) {
            if (!manga) {
                failUrl.add(url.toStringUrl())
            }
            callback?.onLoadFailed(HttpException(response.message, response.code))
            return
        }
        if (ImageUtils.skipDecode(source, !manga)) {
            onStreamReady(ByteArrayInputStream(response.body), response.body.size.toLong())
            return
        }
        val decodeResult = if (manga) {
            ImageUtils.decode(
                url.toString(),
                response.body,
                isCover = false,
                source,
                ReadManga.book
            )?.inputStream()
        } else {
            ImageUtils.decode(
                response.url,
                ByteArrayInputStream(response.body),
                isCover = true,
                source
            )
        }
        onStreamReady(decodeResult, response.body.size.toLong())
    }

    private fun onStreamReady(inputStream: InputStream?, contentLength: Long) {
        if (cancelled) {
            kotlin.runCatching {
                inputStream?.close()
            }
            return
        }
        if (inputStream == null) {
            if (!manga) {
                failUrl.add(url.toStringUrl())
            }
            callback?.onLoadFailed(NoStackTraceException("封面二次解密失败"))
        } else {
            stream = ContentLengthInputStream.obtain(inputStream, contentLength)
            callback?.onDataReady(stream)
        }
    }

}
