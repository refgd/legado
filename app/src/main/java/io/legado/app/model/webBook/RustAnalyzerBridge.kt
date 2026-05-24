package io.legado.app.model.webBook

import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.FlexChildStyle
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import io.legado.app.help.JsExtensions
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.source.getBookType
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isTrue
import io.legado.app.utils.longToastOnUi
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import splitties.init.appCtx

object RustAnalyzerBridge {
    private val operationCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val persistentStoreConfigured = AtomicBoolean(false)

    private val rustUniFfiClass by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustClass("io.legado.app.rust.Legado_nativeKt")
        }
    }

    private val analyzeJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod(
                "analyzeJson",
                String::class.java,
                String::class.java,
                String::class.java
            )
        }
    }

    private val configurePersistentStoreMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("configurePersistentStore", String::class.java)
        }
    }

    private val fetchRawJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("fetchRawJson", String::class.java, String::class.java)
        }
    }

    private val effectiveDomainMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("effectiveDomain", String::class.java)
        }
    }

    private val htmlTextArrayJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("htmlTextArrayJson", String::class.java)
        }
    }

    private val htmlCharsetJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("htmlCharsetJson", String::class.java)
        }
    }

    private val htmlFormatJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("htmlFormatJson", String::class.java)
        }
    }

    private val htmlTitleJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("htmlTitleJson", String::class.java)
        }
    }

    private val htmlFirstAlignmentJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("htmlFirstAlignmentJson", String::class.java)
        }
    }

    private val epubNativeEntryJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubNativeEntryJson", String::class.java)
        }
    }

    private val epubReadableTitleJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubReadableTitleJson", String::class.java)
        }
    }

    private val epubBookInfoJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubBookInfoJson", String::class.java)
        }
    }

    private val epubFootnoteIdsJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubFootnoteIdsJson", String::class.java)
        }
    }

    private val epubFootnoteTargetJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubFootnoteTargetJson", String::class.java, String::class.java)
        }
    }

    private val epubReadableLinesJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubReadableLinesJson", String::class.java, Boolean::class.javaPrimitiveType!!)
        }
    }

    private val epubBodyHtmlJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubBodyHtmlJson", String::class.java, String::class.java, String::class.java)
        }
    }

    private val epubDebugChapterHtmlJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubDebugChapterHtmlJson", String::class.java, Boolean::class.javaPrimitiveType!!)
        }
    }

    private val epubImageOptionsJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubImageOptionsJson", String::class.java)
        }
    }

    private val epubImagePageMarksJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubImagePageMarksJson", String::class.java)
        }
    }

    private val epubMaterializedImagesJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod(
                "epubMaterializedImagesJson",
                String::class.java,
                String::class.java,
                String::class.java
            )
        }
    }

    private val epubMediaPlaceholdersJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubMediaPlaceholdersJson", String::class.java, String::class.java)
        }
    }

    private val epubInlineStylesJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubInlineStylesJson", String::class.java, String::class.java)
        }
    }

    private val epubInheritedStylesJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubInheritedStylesJson", String::class.java, String::class.java)
        }
    }

    private val epubGeneratedContentJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubGeneratedContentJson", String::class.java, String::class.java)
        }
    }

    private val epubNativeDomJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubNativeDomJson", String::class.java, String::class.java, String::class.java)
        }
    }

    private val epubAppliedCssJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubAppliedCssJson", String::class.java, String::class.java)
        }
    }

    private val epubResolvedLinksJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubResolvedLinksJson", String::class.java, String::class.java)
        }
    }

    private val epubBodyBackgroundImageJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubBodyBackgroundImageJson", String::class.java, String::class.java)
        }
    }

    private val epubCssAssetsJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("epubCssAssetsJson", String::class.java, String::class.java)
        }
    }

    private val htmlReadableTableJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("htmlReadableTableJson", String::class.java)
        }
    }

    private val htmlRenderFlagsJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("htmlRenderFlagsJson", String::class.java)
        }
    }

    private val htmlPageBackgroundJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("htmlPageBackgroundJson", String::class.java)
        }
    }

    private val htmlImageInfoJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("htmlImageInfoJson", String::class.java)
        }
    }

    private val htmlRenderPlanJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("htmlRenderPlanJson", String::class.java, Boolean::class.javaPrimitiveType!!)
        }
    }

    private val mobiContentHtmlJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("mobiContentHtmlJson", String::class.java, Boolean::class.javaPrimitiveType!!)
        }
    }

    private val parseWebDavListingJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod(
                "parseWebdavListingJson",
                String::class.java,
                String::class.java,
                String::class.java
            )
        }
    }

    private val parseWebDavErrorJsonMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod("parseWebdavErrorJson", String::class.java)
        }
    }

    private val platformHostClass by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustClass("io.legado.app.rust.PlatformHost")
        }
    }

    private val analyzeJsonWithPlatformMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val hostClass = platformHostClass ?: return@lazy null
        if (!BuildConfig.BUILD_RUST_UNIFFI) {
            null
        } else {
            rustMethod(
                "analyzeJsonWithPlatform",
                String::class.java,
                String::class.java,
                String::class.java,
                hostClass
            )
        }
    }

    val isAvailable: Boolean
        get() = analyzeJsonMethod != null

    fun configurePersistentStore(context: android.content.Context = appCtx) {
        val method = configurePersistentStoreMethod ?: return
        val dir = java.io.File(context.filesDir, "rust-analyzer").absolutePath
        val json = method.invoke(null, dir) as String
        val result = GSON.fromJsonObject<Map<String, Any?>>(json).getOrElse {
            throw NoStackTraceException("Rust analyzer persistent store returned invalid JSON: ${it.localizedMessage}")
        }
        if (result["ok"] != true) {
            val message = result["error"]?.toString().orEmpty().ifBlank {
                "unknown persistent store error"
            }
            AppLog.put("Rust analyzer persistent store init failed: $message")
            throw NoStackTraceException("Rust analyzer persistent store init failed: $message")
        }
        persistentStoreConfigured.set(true)
        AppLog.putDebug("Rust analyzer persistent store: $dir")
    }

    private fun rustClass(name: String): Class<*> {
        return try {
            Class.forName(name)
        } catch (_: ClassNotFoundException) {
            throw NoStackTraceException("Rust analyzer UniFFI class is missing: $name")
        }
    }

    private fun rustMethod(name: String, vararg parameterTypes: Class<*>): Method {
        val owner = rustUniFfiClass
            ?: throw NoStackTraceException("Rust analyzer UniFFI binding is not enabled")
        return try {
            owner.getMethod(name, *parameterTypes)
        } catch (_: NoSuchMethodException) {
            throw NoStackTraceException("Rust analyzer UniFFI method is missing: $name")
        }
    }

    fun search(bookSource: BookSource, key: String, page: Int?): ArrayList<SearchBook> {
        val result = analyze(bookSource, "search", RustInput(key = key, page = page ?: 1))
        return result.books.mapTo(arrayListOf()) { item ->
            item.toSearchBook(bookSource)
        }
    }

    fun explore(bookSource: BookSource, url: String, page: Int?): ArrayList<SearchBook> {
        val result = analyze(bookSource, "explore", RustInput(exploreUrl = url, page = page ?: 1))
        return result.books.mapTo(arrayListOf()) { item ->
            item.toSearchBook(bookSource)
        }
    }

    fun exploreKinds(bookSource: BookSource): List<ExploreKind> {
        val result = analyze(bookSource, "explore", RustInput(page = 1))
        return result.explore.map { item ->
            ExploreKind(
                title = item.title,
                url = item.url.ifBlank { null },
                type = item.type.ifBlank { ExploreKind.Type.url },
                action = item.action.ifBlank { null },
                chars = item.chars.takeIf { it.isNotEmpty() }?.toTypedArray(),
                default = item.default.ifBlank { null },
                style = item.style
            )
        }
    }

    fun detail(bookSource: BookSource, book: Book) {
        val result = analyze(bookSource, "detail", RustInput(bookUrl = book.bookUrl))
        val item = result.book
            ?: throw NoStackTraceException("Rust analyzer detail returned no book for ${book.bookUrl}")
        item.applyToBook(bookSource, book)
    }

    fun toc(bookSource: BookSource, book: Book, runPreUpdateJs: Boolean = false): List<BookChapter> {
        val result = analyze(
            bookSource,
            "toc",
            RustInput(
                bookUrl = book.bookUrl,
                tocUrl = book.tocUrl.ifBlank { book.bookUrl },
                bindingsJson = bindingsJson(book = book),
                runPreUpdateJs = runPreUpdateJs
            )
        )
        result.session.applyToBook(book)
        return result.chapters.mapIndexed { index, item ->
            BookChapter(
                url = item.url,
                title = item.title,
                isVolume = item.isVolume.isTrue(),
                baseUrl = book.tocUrl.ifBlank { book.bookUrl },
                bookUrl = book.bookUrl,
                index = index,
                isVip = item.isVip.isTrue(),
                isPay = item.isPay.isTrue(),
                tag = item.updateTime.ifBlank { null }
            )
        }
    }

    fun preUpdateToc(bookSource: BookSource, book: Book) {
        val result = analyze(
            bookSource,
            "preUpdateToc",
            RustInput(
                bookUrl = book.bookUrl,
                tocUrl = book.tocUrl.ifBlank { book.bookUrl },
                bindingsJson = bindingsJson(book = book)
            )
        )
        result.session.applyToBook(book)
    }

    fun content(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String? = null
    ): String {
        val result = analyze(
            bookSource,
            "content",
            RustInput(
                bookUrl = book.bookUrl,
                tocUrl = book.tocUrl,
                chapterUrl = chapter.getAbsoluteURL(),
                nextChapterUrl = nextChapterUrl.orEmpty(),
                bindingsJson = bindingsJson(book = book, chapter = chapter)
            )
        )
        result.session.applyToBook(book)
        result.session.applyToChapter(chapter)
        result.content?.subContent?.takeIf { it.isNotBlank() }?.let { subContent ->
            when {
                book.isAudio -> chapter.putLyric(subContent)
                book.isVideo -> chapter.putDanmaku(subContent)
            }
        }
        return result.content?.content
            ?: throw NoStackTraceException("Rust analyzer content returned no content for ${chapter.url}")
    }

    fun rssSortUrls(source: RssSource): List<Pair<String, String>> {
        val result = analyze(
            compatBookSource(source),
            "rssSorts",
            RustInput(),
            sourceJsonOverride = GSON.toJson(source)
        )
        return result.rssSorts.map { it.name to it.url }
    }

    fun rssArticles(
        source: RssSource,
        sortName: String,
        sortUrl: String,
        key: String?,
        page: Int
    ): Pair<MutableList<RssArticle>, String?> {
        val result = analyze(
            compatBookSource(source),
            "rssArticles",
            RustInput(
                key = key.orEmpty(),
                page = page,
                exploreUrl = sortUrl,
                sortName = sortName
            ),
            sourceJsonOverride = GSON.toJson(source)
        )
        return result.articles.toMutableList() to result.nextUrl
    }

    fun rssContent(source: RssSource, article: RssArticle, ruleContent: String = source.ruleContent.orEmpty()): String {
        val result = analyze(
            compatBookSource(source),
            "rssContent",
            RustInput(
                result = GSON.toJson(article),
                script = ruleContent,
                rulePath = "rss.ruleContent"
            ),
            sourceJsonOverride = GSON.toJson(source)
        )
        return normalizeEvalString(
            result.rssContent
                ?: throw NoStackTraceException("Rust rssContent returned no content output")
        )
    }

    fun dictSearch(name: String, urlRule: String, showRule: String, word: String): String {
        val source = BookSource(
            bookSourceUrl = "legado://dict/${name.ifBlank { "anonymous" }}",
            bookSourceName = name.ifBlank { "DictRule" },
            bookSourceType = BookSourceType.default
        )
        val result = analyze(
            source,
            "dictSearch",
            RustInput(
                key = word,
                page = 1,
                bookUrl = urlRule,
                script = showRule,
                rulePath = "DictRule.$name"
            )
        )
        return normalizeEvalString(requireEvalResult(result, "dictSearch"))
    }

    fun coverSearch(source: BaseSource, searchUrl: String, coverRule: String, book: Book): String {
        val result = analyze(
            compatBookSource(source, searchUrl),
            "coverSearch",
            RustInput(
                key = book.name,
                page = 1,
                bookUrl = searchUrl,
                script = coverRule,
                rulePath = "BookCover.coverRule",
                bindingsJson = GSON.toJson(mapOf("book" to book))
            )
        )
        return normalizeEvalString(requireEvalResult(result, "coverSearch"))
    }

    fun resolveUrl(
        url: String,
        source: BaseSource? = null,
        baseUrl: String = "",
        rulePath: String = "resolveUrl"
    ): RustResolvedUrl {
        val bookSource = source?.let { compatBookSource(it, url) } ?: BookSource(
            bookSourceUrl = baseUrl.ifBlank { url },
            bookSourceName = "Rust URL resolver",
            bookSourceType = BookSourceType.default
        )
        val result = analyze(
            bookSource,
            "resolveUrl",
            RustInput(
                bookUrl = url,
                page = 1,
                baseUrl = baseUrl,
                rulePath = rulePath
            )
        )
        return GSON.fromJsonObject<RustResolvedUrl>(requireEvalResult(result, rulePath)).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid URL JSON: ${it.localizedMessage}")
        }
    }

    fun directLinkUpload(
        uploadUrl: String,
        downloadUrlRule: String,
        fileName: String,
        contentType: String,
        body: ByteArray,
        compress: Boolean = false
    ): String {
        val source = BookSource(
            bookSourceUrl = uploadUrl,
            bookSourceName = "DirectLinkUpload",
            bookSourceType = BookSourceType.default
        )
        val result = analyze(
            source,
            "directLinkUpload",
            RustInput(
                bookUrl = uploadUrl,
                script = downloadUrlRule,
                rulePath = "DirectLinkUpload",
                uploadFileName = fileName,
                uploadContentType = contentType,
                uploadBodyBase64 = Base64.encodeToString(body, Base64.NO_WRAP),
                uploadCompress = compress
            )
        )
        return normalizeEvalString(requireEvalResult(result, "directLinkUpload"))
    }

    fun resolveMediaRequest(
        url: String,
        source: BaseSource,
        baseUrl: String = "",
        rulePath: String = "resolveMediaRequest"
    ): ExoPlayerHelper.MediaRequest {
        val resolved = resolveUrl(url, source, baseUrl, rulePath)
        return ExoPlayerHelper.createMediaRequest(resolved.url, resolved.headerMap(rulePath))
    }

    fun resolveMediaItem(
        url: String,
        source: BaseSource,
        baseUrl: String = "",
        rulePath: String = "resolveMediaItem"
    ) = resolveUrl(url, source, baseUrl, rulePath).let { resolved ->
        ExoPlayerHelper.createMediaItem(resolved.url, resolved.headerMap(rulePath))
    }

    fun fetchText(
        url: String,
        source: BaseSource? = null,
        baseUrl: String = "",
        useWebView: Boolean = false,
        rulePath: String = "fetchText"
    ): RustFetchTextResult {
        val bookSource = source?.let { compatBookSource(it, url) } ?: BookSource(
            bookSourceUrl = baseUrl.ifBlank { url },
            bookSourceName = "Rust text fetch",
            bookSourceType = BookSourceType.default
        )
        val result = analyze(
            bookSource,
            "fetchText",
            RustInput(
                bookUrl = url,
                baseUrl = baseUrl,
                rulePath = rulePath,
                useWebView = useWebView
            )
        )
        return GSON.fromJsonObject<RustFetchTextResult>(requireEvalResult(result, rulePath)).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid fetch JSON: ${it.localizedMessage}")
        }
    }

    fun analyzeRaw(bookSource: BookSource, operation: String, inputJson: String): String {
        ensurePersistentStoreConfigured()
        val method = analyzeJsonMethod
            ?: throw NoStackTraceException("Rust analyzer UniFFI binding is not available")
        operationCounts.getOrPut(operation) { AtomicInteger() }.incrementAndGet()
        return invokeAnalyzer(method, bookSource, operation, inputJson, null)
    }

    fun fetchRaw(source: BaseSource, url: String, rulePath: String = "fetchRaw"): ByteArray {
        return fetchRawResponse(source, url, rulePath).body
    }

    fun fetchRawUrl(url: String, rulePath: String = "fetchRaw"): RustRawFetchResult {
        val source = BookSource(
            bookSourceUrl = url,
            bookSourceName = "Rust raw fetch",
            bookSourceType = BookSourceType.default
        )
        return fetchRawResponse(source, url, rulePath)
    }

    fun fetchRawResponse(source: BaseSource, url: String, rulePath: String = "fetchRaw"): RustRawFetchResult {
        ensurePersistentStoreConfigured()
        val method = fetchRawJsonMethod
            ?: throw NoStackTraceException("Rust raw fetch UniFFI binding is not available")
        operationCounts.getOrPut("fetchRaw") { AtomicInteger() }.incrementAndGet()
        val sourceJson = GSON.toJson(compatBookSource(source))
        val json = method.invoke(null, sourceJson, url) as String
        val response = GSON.fromJsonObject<RustRawFetchResponse>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid raw response JSON: ${it.localizedMessage}")
        }
        response.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return RustRawFetchResult(
            url = response.url.ifBlank {
                throw NoStackTraceException("Rust $rulePath returned blank raw response URL")
            },
            code = response.code,
            message = response.message,
            headers = response.headers,
            headersList = response.headersList,
            contentType = response.contentType,
            body = Base64.decode(response.bodyBase64, Base64.DEFAULT)
        )
    }

    fun effectiveDomain(url: String): String {
        val method = effectiveDomainMethod
            ?: throw NoStackTraceException("Rust effectiveDomain UniFFI binding is not available")
        operationCounts.getOrPut("effectiveDomain") { AtomicInteger() }.incrementAndGet()
        return method.invoke(null, url) as String
    }

    fun htmlTextArray(html: String, rulePath: String = "html.textArray"): List<String> {
        val method = htmlTextArrayJsonMethod
            ?: throw NoStackTraceException("Rust HTML textArray UniFFI binding is not available")
        operationCounts.getOrPut("htmlTextArray") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustHtmlTextArray>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid HTML textArray JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.lines
    }

    fun htmlCharset(html: String, rulePath: String = "html.charset"): String {
        val method = htmlCharsetJsonMethod
            ?: throw NoStackTraceException("Rust HTML charset UniFFI binding is not available")
        operationCounts.getOrPut("htmlCharset") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustHtmlCharset>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid HTML charset JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.charset
    }

    fun htmlFormat(html: String, rulePath: String = "html.format"): String {
        val method = htmlFormatJsonMethod
            ?: throw NoStackTraceException("Rust HTML format UniFFI binding is not available")
        operationCounts.getOrPut("htmlFormat") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustHtmlFormat>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid HTML format JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.html
    }

    fun htmlTitle(html: String, rulePath: String = "html.title"): String {
        val method = htmlTitleJsonMethod
            ?: throw NoStackTraceException("Rust HTML title UniFFI binding is not available")
        operationCounts.getOrPut("htmlTitle") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustHtmlTitle>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid HTML title JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.title
    }

    fun htmlFirstAlignment(html: String, rulePath: String = "html.firstAlignment"): String {
        val method = htmlFirstAlignmentJsonMethod
            ?: throw NoStackTraceException("Rust HTML first alignment UniFFI binding is not available")
        operationCounts.getOrPut("htmlFirstAlignment") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustHtmlAlignment>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid HTML alignment JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.alignment
    }

    fun epubNativeEntryHrefs(html: String, rulePath: String = "epub.nativeEntry"): List<String> {
        val method = epubNativeEntryJsonMethod
            ?: throw NoStackTraceException("Rust EPUB native entry UniFFI binding is not available")
        operationCounts.getOrPut("epubNativeEntry") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustEpubNativeEntry>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB native entry JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.hrefs
    }

    fun epubReadableTitle(html: String, rulePath: String = "epub.readableTitle"): String {
        val method = epubReadableTitleJsonMethod
            ?: throw NoStackTraceException("Rust EPUB readable title UniFFI binding is not available")
        operationCounts.getOrPut("epubReadableTitle") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustHtmlTitle>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB readable title JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.title
    }

    fun epubBookInfo(html: String, rulePath: String = "epub.bookInfo"): RustEpubBookInfo {
        val method = epubBookInfoJsonMethod
            ?: throw NoStackTraceException("Rust EPUB book info UniFFI binding is not available")
        operationCounts.getOrPut("epubBookInfo") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustEpubBookInfo>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB book info JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun epubFootnoteIds(html: String, rulePath: String = "epub.footnoteIds"): List<String> {
        val method = epubFootnoteIdsJsonMethod
            ?: throw NoStackTraceException("Rust EPUB footnote ids UniFFI binding is not available")
        operationCounts.getOrPut("epubFootnoteIds") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustEpubFootnoteIds>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB footnote ids JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.ids
    }

    fun epubFootnoteTarget(
        html: String,
        targetId: String,
        rulePath: String = "epub.footnoteTarget"
    ): RustEpubFootnoteTarget {
        val method = epubFootnoteTargetJsonMethod
            ?: throw NoStackTraceException("Rust EPUB footnote target UniFFI binding is not available")
        operationCounts.getOrPut("epubFootnoteTarget") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html, targetId) as String
        val value = GSON.fromJsonObject<RustEpubFootnoteTarget>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB footnote target JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun epubReadableLines(
        html: String,
        deleteRuby: Boolean,
        rulePath: String = "epub.readableLines"
    ): List<String> {
        val method = epubReadableLinesJsonMethod
            ?: throw NoStackTraceException("Rust EPUB readable lines UniFFI binding is not available")
        operationCounts.getOrPut("epubReadableLines") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html, deleteRuby) as String
        val value = GSON.fromJsonObject<RustEpubReadableLines>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB readable lines JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.lines
    }

    fun epubBodyHtml(
        html: String,
        startFragmentId: String?,
        endFragmentId: String?,
        rulePath: String = "epub.bodyHtml"
    ): RustEpubBodyHtml {
        val method = epubBodyHtmlJsonMethod
            ?: throw NoStackTraceException("Rust EPUB body HTML UniFFI binding is not available")
        operationCounts.getOrPut("epubBodyHtml") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html, startFragmentId.orEmpty(), endFragmentId.orEmpty()) as String
        val value = GSON.fromJsonObject<RustEpubBodyHtml>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB body HTML JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun epubDebugChapterHtml(
        bodies: List<String>,
        deleteRuby: Boolean,
        rulePath: String = "epub.debugChapterHtml"
    ): String {
        val method = epubDebugChapterHtmlJsonMethod
            ?: throw NoStackTraceException("Rust EPUB debug chapter HTML UniFFI binding is not available")
        operationCounts.getOrPut("epubDebugChapterHtml") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, GSON.toJson(bodies), deleteRuby) as String
        val value = GSON.fromJsonObject<RustEpubDebugChapterHtml>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB debug chapter HTML JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.html
    }

    fun epubImageOptions(html: String, rulePath: String = "epub.imageOptions"): RustEpubImageOptions {
        val method = epubImageOptionsJsonMethod
            ?: throw NoStackTraceException("Rust EPUB image options UniFFI binding is not available")
        operationCounts.getOrPut("epubImageOptions") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustEpubImageOptions>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB image options JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun epubImagePageMarks(html: String, rulePath: String = "epub.imagePageMarks"): RustEpubImagePageMarks {
        val method = epubImagePageMarksJsonMethod
            ?: throw NoStackTraceException("Rust EPUB image page marks UniFFI binding is not available")
        operationCounts.getOrPut("epubImagePageMarks") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustEpubImagePageMarks>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB image page marks JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun epubMaterializedImages(
        html: String,
        baseHref: String,
        resourceHrefs: List<String>,
        rulePath: String = "epub.materializedImages"
    ): String {
        val method = epubMaterializedImagesJsonMethod
            ?: throw NoStackTraceException("Rust EPUB materialized images UniFFI binding is not available")
        operationCounts.getOrPut("epubMaterializedImages") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html, baseHref, GSON.toJson(resourceHrefs)) as String
        val value = GSON.fromJsonObject<RustEpubMaterializedImages>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB materialized images JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.html
    }

    fun epubMediaPlaceholders(
        html: String,
        baseHref: String,
        rulePath: String = "epub.mediaPlaceholders"
    ): String {
        val method = epubMediaPlaceholdersJsonMethod
            ?: throw NoStackTraceException("Rust EPUB media placeholders UniFFI binding is not available")
        operationCounts.getOrPut("epubMediaPlaceholders") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html, baseHref) as String
        val value = GSON.fromJsonObject<RustEpubMediaPlaceholders>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB media placeholders JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.html
    }

    fun epubInlineStyles(
        html: String,
        bodyStyle: String,
        rulePath: String = "epub.inlineStyles"
    ): String {
        val method = epubInlineStylesJsonMethod
            ?: throw NoStackTraceException("Rust EPUB inline styles UniFFI binding is not available")
        operationCounts.getOrPut("epubInlineStyles") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html, bodyStyle) as String
        val value = GSON.fromJsonObject<RustEpubInlineStyles>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB inline styles JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.html
    }

    fun epubInheritedStyles(
        html: String,
        bodyStyle: String,
        rulePath: String = "epub.inheritedStyles"
    ): String {
        val method = epubInheritedStylesJsonMethod
            ?: throw NoStackTraceException("Rust EPUB inherited styles UniFFI binding is not available")
        operationCounts.getOrPut("epubInheritedStyles") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html, bodyStyle) as String
        val value = GSON.fromJsonObject<RustEpubInheritedStyles>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB inherited styles JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.html
    }

    fun epubGeneratedContent(
        bodyOuterHtml: String,
        rulesJson: String,
        rulePath: String = "epub.generatedContent"
    ): String {
        val method = epubGeneratedContentJsonMethod
            ?: throw NoStackTraceException("Rust EPUB generated content UniFFI binding is not available")
        operationCounts.getOrPut("epubGeneratedContent") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, bodyOuterHtml, rulesJson) as String
        val value = GSON.fromJsonObject<RustEpubGeneratedContent>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB generated content JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.html
    }

    fun epubNativeDom(
        bodyOuterHtml: String,
        rulesJson: String,
        baseHref: String,
        rulePath: String = "epub.nativeDom"
    ): RustEpubNativeDom {
        val method = epubNativeDomJsonMethod
            ?: throw NoStackTraceException("Rust EPUB native DOM UniFFI binding is not available")
        operationCounts.getOrPut("epubNativeDom") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, bodyOuterHtml, rulesJson, baseHref) as String
        val value = GSON.fromJsonObject<RustEpubNativeDom>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB native DOM JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun epubAppliedCss(
        bodyOuterHtml: String,
        rulesJson: String,
        rulePath: String = "epub.appliedCss"
    ): RustEpubAppliedCss {
        val method = epubAppliedCssJsonMethod
            ?: throw NoStackTraceException("Rust EPUB applied CSS UniFFI binding is not available")
        operationCounts.getOrPut("epubAppliedCss") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, bodyOuterHtml, rulesJson) as String
        val value = GSON.fromJsonObject<RustEpubAppliedCss>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB applied CSS JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun epubResolvedLinks(
        html: String,
        baseHref: String,
        rulePath: String = "epub.resolvedLinks"
    ): String {
        val method = epubResolvedLinksJsonMethod
            ?: throw NoStackTraceException("Rust EPUB resolved links UniFFI binding is not available")
        operationCounts.getOrPut("epubResolvedLinks") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html, baseHref) as String
        val value = GSON.fromJsonObject<RustEpubResolvedLinks>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB resolved links JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.html
    }

    fun epubBodyBackgroundImage(
        bodyStyle: String,
        bodyBackground: String,
        rulePath: String = "epub.bodyBackgroundImage"
    ): String {
        val method = epubBodyBackgroundImageJsonMethod
            ?: throw NoStackTraceException("Rust EPUB body background image UniFFI binding is not available")
        operationCounts.getOrPut("epubBodyBackgroundImage") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, bodyStyle, bodyBackground) as String
        val value = GSON.fromJsonObject<RustEpubBodyBackgroundImage>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB body background image JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.href
    }

    fun epubCssAssets(
        documentHtml: String,
        bodyHtml: String,
        rulePath: String = "epub.cssAssets"
    ): RustEpubCssAssets {
        val method = epubCssAssetsJsonMethod
            ?: throw NoStackTraceException("Rust EPUB CSS assets UniFFI binding is not available")
        operationCounts.getOrPut("epubCssAssets") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, documentHtml, bodyHtml) as String
        val value = GSON.fromJsonObject<RustEpubCssAssets>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid EPUB CSS assets JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun htmlReadableTable(html: String, rulePath: String = "html.readableTable"): String {
        val method = htmlReadableTableJsonMethod
            ?: throw NoStackTraceException("Rust HTML readable table UniFFI binding is not available")
        operationCounts.getOrPut("htmlReadableTable") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustHtmlReadableTable>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid HTML readable table JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.html
    }

    fun htmlRenderFlags(html: String, rulePath: String = "html.renderFlags"): RustHtmlRenderFlags {
        val method = htmlRenderFlagsJsonMethod
            ?: throw NoStackTraceException("Rust HTML render flags UniFFI binding is not available")
        operationCounts.getOrPut("htmlRenderFlags") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustHtmlRenderFlags>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid HTML render flags JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun htmlPageBackground(html: String, rulePath: String = "html.pageBackground"): RustHtmlPageBackground {
        val method = htmlPageBackgroundJsonMethod
            ?: throw NoStackTraceException("Rust HTML page background UniFFI binding is not available")
        operationCounts.getOrPut("htmlPageBackground") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustHtmlPageBackground>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid HTML page background JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun htmlImageInfo(html: String, rulePath: String = "html.imageInfo"): RustHtmlImageInfo {
        val method = htmlImageInfoJsonMethod
            ?: throw NoStackTraceException("Rust HTML image info UniFFI binding is not available")
        operationCounts.getOrPut("htmlImageInfo") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html) as String
        val value = GSON.fromJsonObject<RustHtmlImageInfo>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid HTML image info JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun htmlRenderPlan(
        html: String,
        classicEpub: Boolean,
        rulePath: String = "html.renderPlan"
    ): List<RustHtmlRenderAction> {
        val method = htmlRenderPlanJsonMethod
            ?: throw NoStackTraceException("Rust HTML render plan UniFFI binding is not available")
        operationCounts.getOrPut("htmlRenderPlan") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html, classicEpub) as String
        val value = GSON.fromJsonObject<RustHtmlRenderPlan>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid HTML render plan JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.actions
    }

    fun mobiContentHtml(
        html: String,
        rewriteRecindexImages: Boolean,
        rulePath: String = "mobi.contentHtml"
    ): String {
        val method = mobiContentHtmlJsonMethod
            ?: throw NoStackTraceException("Rust MOBI content HTML UniFFI binding is not available")
        operationCounts.getOrPut("mobiContentHtml") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, html, rewriteRecindexImages) as String
        val value = GSON.fromJsonObject<RustMobiContentHtml>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid MOBI content HTML JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value.html
    }

    fun parseWebDavListing(
        body: String,
        requestUrl: String,
        originalPath: String,
        rulePath: String = "WebDav.parseListing"
    ): List<RustWebDavFile> {
        val method = parseWebDavListingJsonMethod
            ?: throw NoStackTraceException("Rust WebDAV listing UniFFI binding is not available")
        operationCounts.getOrPut("parseWebDavListing") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, body, requestUrl, originalPath) as String
        val response = GSON.fromJsonObject<RustWebDavListingResponse>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid WebDAV listing JSON: ${it.localizedMessage}")
        }
        response.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return response.files
    }

    fun parseWebDavError(body: String, rulePath: String = "WebDav.parseError"): RustWebDavError {
        val method = parseWebDavErrorJsonMethod
            ?: throw NoStackTraceException("Rust WebDAV error UniFFI binding is not available")
        operationCounts.getOrPut("parseWebDavError") { AtomicInteger() }.incrementAndGet()
        val json = method.invoke(null, body) as String
        val value = GSON.fromJsonObject<RustWebDavError>(json).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid WebDAV error JSON: ${it.localizedMessage}")
        }
        value.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return value
    }

    fun applyCookieToWebView(source: BaseSource, url: String, rulePath: String) {
        val cookie = evalJs(
            source = source,
            script = "cookie.getCookie(${GSON.toJson(url)})",
            rulePath = rulePath
        )
        val targetUrl = webViewCookieUrl(url)
            ?: throw NoStackTraceException("Rust $rulePath cannot apply cookie for blank URL")
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeSessionCookies(null)
        cookie.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { cookieManager.setCookie(targetUrl, it) }
        cookieManager.flush()
    }

    fun fetchTtsAudio(
        httpTts: HttpTTS,
        speakText: String,
        speakSpeed: Int,
        rulePath: String = "HttpTTS.fetch"
    ): RustRawFetchResult {
        val result = analyze(
            compatBookSource(httpTts, httpTts.url),
            "fetchRaw",
            RustInput(
                key = speakText,
                page = 1,
                bookUrl = httpTts.url,
                speakText = speakText,
                speakSpeed = speakSpeed,
                rulePath = rulePath
            ),
            sourceJsonOverride = GSON.toJson(httpTts)
        )
        val response = GSON.fromJsonObject<RustRawFetchResponse>(requireEvalResult(result, rulePath)).getOrElse {
            throw NoStackTraceException("Rust $rulePath returned invalid raw response JSON: ${it.localizedMessage}")
        }
        response.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust $rulePath failed: $it")
        }
        return RustRawFetchResult(
            url = response.url.ifBlank { httpTts.url },
            code = response.code,
            message = response.message,
            headers = response.headers,
            headersList = response.headersList,
            contentType = response.contentType,
            body = Base64.decode(response.bodyBase64, Base64.DEFAULT)
        )
    }

    fun evalJs(
        source: BaseSource,
        script: String,
        result: Any? = null,
        baseUrl: String? = null,
        key: String? = null,
        page: Int? = null,
        rulePath: String = "eval",
        platformJava: Any? = null,
        bindingsJson: String = ""
    ): String {
        return normalizeEvalString(
            evalJsRaw(
                source = source,
                script = script,
                result = result,
                baseUrl = baseUrl,
                key = key,
                page = page,
                rulePath = rulePath,
                platformJava = platformJava,
                bindingsJson = bindingsJson
            )
        )
    }

    private fun evalJsRaw(
        source: BaseSource,
        script: String,
        result: Any? = null,
        baseUrl: String? = null,
        key: String? = null,
        page: Int? = null,
        rulePath: String = "eval",
        platformJava: Any? = null,
        bindingsJson: String = ""
    ): String {
        val bookSource = source as? BookSource ?: compatBookSource(source, key)
        val output = analyze(
            bookSource,
            "eval",
            RustInput(
                key = key.orEmpty(),
                page = page ?: 1,
                script = script,
                result = rustEvalResultJson(result),
                baseUrl = baseUrl.orEmpty(),
                rulePath = rulePath,
                bindingsJson = bindingsJson,
                bootstrapLoginUrl = source is RssSource && !source.loginUrl.isNullOrBlank()
            ),
            platformJava,
            sourceJsonOverride = if (source is RssSource) GSON.toJson(source) else null
        )
        val platformAction = output.session.javaStore["__platform_actions"].orEmpty()
        return platformAction.takeIf { it.startsWith("__LEGADO_UNSUPPORTED_PLATFORM_API__:") }
            ?: requireEvalResult(output, rulePath)
    }

    fun evalJsAny(
        source: BaseSource,
        script: String,
        result: Any? = null,
        baseUrl: String? = null,
        key: String? = null,
        page: Int? = null,
        rulePath: String = "eval",
        platformJava: Any? = null,
        bindingsJson: String = ""
    ): Any? {
        return decodeEvalResult(
            evalJsRaw(
                source = source,
                script = script,
                result = result,
                baseUrl = baseUrl,
                key = key,
                page = page,
                rulePath = rulePath,
                platformJava = platformJava,
                bindingsJson = bindingsJson
            )
        )
    }

    fun evalRule(
        source: BaseSource,
        rule: String,
        result: Any? = null,
        baseUrl: String? = null,
        key: String? = null,
        page: Int? = null,
        rulePath: String = "evalRule",
        platformJava: Any? = null,
        bindingsJson: String = ""
    ): String {
        val bookSource = source as? BookSource ?: compatBookSource(source, key)
        val output = analyze(
            bookSource,
            "evalRule",
            RustInput(
                key = key.orEmpty(),
                page = page ?: 1,
                script = rule,
                result = rustEvalResultJson(result),
                baseUrl = baseUrl.orEmpty(),
                rulePath = rulePath,
                bindingsJson = bindingsJson,
                bootstrapLoginUrl = source is RssSource && !source.loginUrl.isNullOrBlank()
            ),
            platformJava,
            sourceJsonOverride = if (source is RssSource) GSON.toJson(source) else null
        )
        return normalizeEvalString(requireEvalResult(output, rulePath))
    }

    fun decodeEvalResult(result: String): Any? {
        val responseMarker = "__LEGADO_STR_RESPONSE_JSON__"
        if (result.startsWith(responseMarker)) {
            val value = GSON.fromJsonObject<RustJsHttpResponse>(result.removePrefix(responseMarker))
                .getOrElse {
                    throw NoStackTraceException(
                        "Rust eval returned invalid StrResponse JSON: ${it.localizedMessage}"
                    )
                }
            return value.toStrResponse()
        }
        val jsonMarker = "__LEGADO_JSON_VALUE__"
        if (!result.startsWith(jsonMarker)) {
            return result
        }
        val json = result.removePrefix(jsonMarker)
        val element = runCatching {
            JsonParser.parseString(json)
        }.getOrElse {
            throw NoStackTraceException(
                "Rust eval returned invalid JSON value: ${it.localizedMessage}; ${json.take(300)}"
            )
        }
        if (element.isJsonArray) {
            return GSON.fromJsonArray<Any?>(json).getOrElse {
                throw NoStackTraceException(
                    "Rust eval returned invalid JSON array value: ${it.localizedMessage}; ${json.take(300)}"
                )
            }
        }
        if (element.isJsonObject) {
            val value = GSON.fromJsonObject<Map<String, Any?>>(json).getOrElse {
                throw NoStackTraceException(
                    "Rust eval returned invalid JSON object value: ${it.localizedMessage}; ${json.take(300)}"
                )
            }
            decodeRustByteObject(value)?.let { return it }
            return value
        }
        throw NoStackTraceException(
            "Rust eval returned unsupported JSON marker value: ${json.take(300)}"
        )
    }

    private fun normalizeEvalString(result: String): String {
        val jsonMarker = "__LEGADO_JSON_VALUE__"
        return if (result.startsWith(jsonMarker)) {
            result.removePrefix(jsonMarker)
        } else {
            result
        }
    }

    private fun requireEvalResult(result: RustOutput, rulePath: String): String {
        return result.evalResult
            ?: throw NoStackTraceException("Rust $rulePath returned no eval output")
    }

    private fun RustResolvedUrl.headerMap(rulePath: String): Map<String, String> {
        return headers.mapIndexed { index, pair ->
            if (pair.size != 2) {
                throw NoStackTraceException(
                    "Rust $rulePath returned malformed resolved URL header pair at $index: $pair"
                )
            }
            val key = pair[0].takeIf { it.isNotBlank() }
                ?: throw NoStackTraceException(
                    "Rust $rulePath returned blank resolved URL header name at $index"
                )
            key to pair[1]
        }.toMap()
    }

    private fun rustEvalResultJson(result: Any?): String {
        return when (result) {
            null -> ""
            is ByteArray -> GSON.toJson(mapOf("__javaBytesHex" to result.toHexString()))
            is InputStream -> GSON.toJson(mapOf("__javaBytesHex" to result.readBytes().toHexString()))
            is String -> result
            is StrResponse -> GSON.toJson(
                mapOf(
                    "__strResponse" to true,
                    "url" to result.url(),
                    "body" to result.body().orEmpty(),
                    "code" to result.code(),
                    "message" to result.message(),
                    "headers" to result.headers().mapValues { entry ->
                        entry.value.firstOrNull().orEmpty()
                    },
                    "raw" to result.toString()
                )
            )
            else -> GSON.toJson(result)
        }
    }

    private fun decodeRustByteObject(value: Map<String, Any?>): ByteArray? {
        val hex = (value["__javaBytesHex"] ?: value["__hex"]) as? String ?: return null
        if (hex.length % 2 != 0) {
            throw NoStackTraceException("Rust eval returned odd-length byte hex: ${hex.take(80)}")
        }
        return runCatching {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrElse {
            throw NoStackTraceException("Rust eval returned invalid byte hex: ${it.localizedMessage}")
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun evalJsRaw(
        script: String,
        result: Any? = null,
        baseUrl: String = "legado://local",
        rulePath: String = "eval",
        bindingsJson: String = ""
    ): String {
        val source = BookSource(
            bookSourceUrl = baseUrl,
            bookSourceName = "Rust local JS",
            bookSourceType = BookSourceType.default
        )
        return evalJs(
            source = source,
            script = script,
            result = result,
            baseUrl = baseUrl,
            rulePath = rulePath,
            bindingsJson = bindingsJson
        )
    }

    fun evalJsAny(
        script: String,
        result: Any? = null,
        baseUrl: String = "legado://local",
        rulePath: String = "eval",
        bindingsJson: String = ""
    ): Any? {
        val source = BookSource(
            bookSourceUrl = baseUrl,
            bookSourceName = "Rust local JS",
            bookSourceType = BookSourceType.default
        )
        return evalJsAny(
            source = source,
            script = script,
            result = result,
            baseUrl = baseUrl,
            rulePath = rulePath,
            bindingsJson = bindingsJson
        )
    }

    fun resetCallCounts() {
        operationCounts.clear()
    }

    fun callCount(operation: String): Int {
        return operationCounts[operation]?.get() ?: 0
    }

    private fun analyze(
        bookSource: BookSource,
        operation: String,
        input: RustInput,
        platformJava: Any? = null,
        sourceJsonOverride: String? = null
    ): RustOutput {
        ensurePersistentStoreConfigured()
        val method = analyzeJsonMethod
            ?: throw NoStackTraceException("Rust analyzer UniFFI binding is not available")
        operationCounts.getOrPut(operation) { AtomicInteger() }.incrementAndGet()
        val json = invokeAnalyzer(
            method,
            bookSource,
            operation,
            GSON.toJson(input),
            platformJava,
            sourceJsonOverride
        )
        val output = GSON.fromJsonObject<RustOutput>(json).getOrElse {
            throw NoStackTraceException("Rust analyzer returned invalid JSON: ${it.localizedMessage}")
        }
        syncRustCookiesToWebView(output.session)
        flushRuntimeRecords(bookSource, RustSession(), output.session, platformJava)
        if (output.diagnostics.isNotEmpty()) {
            AppLog.put("Rust analyzer $operation diagnostics\n${output.diagnostics.joinToString("\n")}")
        }
        output.error?.takeIf { it.isNotBlank() }?.let {
            throw NoStackTraceException("Rust analyzer $operation failed: $it")
        }
        return output
    }

    private fun ensurePersistentStoreConfigured() {
        if (!persistentStoreConfigured.get() && BuildConfig.BUILD_RUST_UNIFFI) {
            configurePersistentStore(appCtx)
        }
    }

    private fun compatBookSource(source: BaseSource, key: String? = null): BookSource {
        return BookSource(
            bookSourceUrl = key ?: source.getKey(),
            bookSourceName = source.getTag().ifBlank { source.getKey() },
            bookSourceType = BookSourceType.default,
            jsLib = source.jsLib,
            enabledCookieJar = source.enabledCookieJar,
            header = source.header,
            loginUrl = source.loginUrl,
            loginUi = source.loginUi,
            concurrentRate = source.concurrentRate
        )
    }

    private fun invokeAnalyzer(
        fallbackMethod: Method,
        bookSource: BookSource,
        operation: String,
        inputJson: String,
        platformJava: Any?,
        sourceJsonOverride: String? = null
    ): String {
        val sourceJson = sourceJsonOverride ?: GSON.toJson(bookSource)
        val platformMethod = analyzeJsonWithPlatformMethod
        val hostClass = platformHostClass
        if (platformMethod != null && hostClass != null) {
            return platformMethod.invoke(
                null,
                sourceJson,
                operation,
                inputJson,
                platformHost(bookSource, platformJava, hostClass)
            ) as String
        }
        return fallbackMethod.invoke(null, sourceJson, operation, inputJson) as String
    }

    private fun platformHost(source: BaseSource, platformJava: Any?, hostClass: Class<*>): Any {
        return Proxy.newProxyInstance(hostClass.classLoader, arrayOf(hostClass)) { _, method, args ->
            if (method.name != "handlePlatformAction") {
                return@newProxyInstance null
            }
            if (args == null || args.size != 3) {
                throw NoStackTraceException(
                    "Rust platform action callback returned malformed argument count: ${args?.size ?: 0}"
                )
            }
            val api = args[0]?.toString().orEmpty()
            if (api.isBlank()) {
                throw NoStackTraceException("Rust platform action callback returned blank API name")
            }
            handlePlatformAction(
                source = source,
                platformJava = platformJava,
                api = api,
                sourceName = args[1]?.toString().orEmpty(),
                argsJson = args[2]?.toString().orEmpty()
            )
        }
    }

    private fun handlePlatformAction(
        source: BaseSource,
        platformJava: Any?,
        api: String,
        sourceName: String,
        argsJson: String
    ): String {
        val actionArgs = GSON.fromJsonArray<Any?>(argsJson).getOrElse {
            throw NoStackTraceException(
                "Rust platform action $api returned invalid args JSON for $sourceName: ${it.localizedMessage}"
            )
        }
        fun stringArg(index: Int): String = actionArgs.getOrNull(index)?.toString().orEmpty()
        fun requiredStringArg(index: Int, label: String): String {
            val value = actionArgs.getOrNull(index)?.toString()
                ?: throw NoStackTraceException(
                    "Rust platform action $api returned missing $label arg at $index"
                )
            if (value.isBlank()) {
                throw NoStackTraceException(
                    "Rust platform action $api returned blank $label arg at $index"
                )
            }
            return value
        }
        fun boolArg(index: Int, default: Boolean): Boolean {
            return when (val value = actionArgs.getOrNull(index) ?: return default) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value.toBooleanStrictOrNull()
                    ?: throw NoStackTraceException(
                        "Rust platform action $api returned invalid boolean arg at $index: $value"
                    )
                else -> throw NoStackTraceException(
                    "Rust platform action $api returned invalid boolean arg type at $index: ${value::class.java.name}"
                )
            }
        }
        fun longArg(index: Int, default: Long): Long {
            return when (val value = actionArgs.getOrNull(index) ?: return default) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                    ?: throw NoStackTraceException(
                        "Rust platform action $api returned invalid long arg at $index: $value"
                    )
                else -> throw NoStackTraceException(
                    "Rust platform action $api returned invalid long arg type at $index: ${value::class.java.name}"
                )
            }
        }
        val url = stringArg(0)
        val title = stringArg(1).ifBlank { sourceName.ifBlank { api } }
        fun requiredUrl(): String = requiredStringArg(0, "URL")
        val jsJava = (platformJava as? JsExtensions) ?: object : JsExtensions {
            override fun getSource(): BaseSource = source
            override fun getTag(): String = source.getTag()
        }
        return when (api) {
                "startBrowser" -> {
                    val actionUrl = requiredUrl()
                    SourceVerificationHelp.startBrowser(
                        source = source,
                        url = actionUrl,
                        title = title,
                        html = stringArg(2).ifBlank { null }
                    )
                    platformResponse(url = actionUrl, body = "", api = api)
                }
                "startBrowserAwait" -> {
                    val actionUrl = requiredUrl()
                    val result = SourceVerificationHelp.getVerificationResult(
                        source = source,
                        url = actionUrl,
                        title = title,
                        useBrowser = true,
                        refetchAfterSuccess = boolArg(2, true),
                        html = stringArg(3).ifBlank { null }
                    )
                    platformResponse(url = result.first.ifBlank { actionUrl }, body = result.second, api = api)
                }
                "showBrowser" -> {
                    val actionUrl = requiredUrl()
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.showBrowser(
                            url = actionUrl,
                            html = stringArg(1).ifBlank { null },
                            preloadJs = stringArg(2).ifBlank { null },
                            config = stringArg(3).ifBlank { null }
                        )
                    } else {
                        SourceVerificationHelp.startBrowser(
                            source = source,
                            url = actionUrl,
                            title = title,
                            html = stringArg(1).ifBlank { null }
                        )
                    }
                    platformResponse(url = actionUrl, body = "", api = api)
                }
                "openVideoPlayer" -> {
                    val actionUrl = requiredUrl()
                    SourceHelp.openVideoPlayer(
                        source = source,
                        url = actionUrl,
                        title = title,
                        isFloat = boolArg(2, false)
                    )
                    platformResponse(url = actionUrl, body = "", api = api)
                }
                "reLoginView" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.reLoginView(boolArg(0, false))
                        platformResponse(url = url, body = "", api = api)
                    } else {
                        platformResponse(
                            url = url,
                            body = "",
                            api = api,
                            code = 501,
                            message = "reLoginView requires SourceLoginJsExtensions UI context",
                            unsupported = true
                        )
                    }
                }
                "refreshExplore" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.refreshExplore()
                        platformResponse(url = url, body = "", api = api)
                    } else {
                        source.refreshExplore()
                        platformResponse(url = url, body = "", api = api)
                    }
                }
                "startBrowserDp", "showReadingBrowser" -> {
                    val actionUrl = requiredUrl()
                    SourceVerificationHelp.startBrowser(
                        source = source,
                        url = actionUrl,
                        title = title,
                        html = stringArg(2).ifBlank { null }
                    )
                    platformResponse(url = actionUrl, body = "", api = api)
                }
                "copyText" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.copyText(requiredStringArg(0, "text"))
                        platformResponse(url = url, body = "", api = api)
                    } else unsupportedPlatformResponse(api, url, "copyText requires SourceLoginJsExtensions UI context")
                }
                "toast" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.toast(stringArg(0))
                        platformResponse(url = url, body = stringArg(0), api = api)
                    } else {
                        AppLog.put("${source.getTag()}: ${stringArg(0)}")
                        platformResponse(url = url, body = stringArg(0), api = api)
                    }
                }
                "longToast" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.longToast(stringArg(0))
                        platformResponse(url = url, body = stringArg(0), api = api)
                    } else {
                        AppLog.put("${source.getTag()}: ${stringArg(0)}")
                        platformResponse(url = url, body = stringArg(0), api = api)
                    }
                }
                "log" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.log(stringArg(0))
                    } else {
                        AppLog.putDebug("${source.getTag()}调试输出: ${stringArg(0)}")
                    }
                    platformResponse(url = url, body = stringArg(0), api = api)
                }
                "logType" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.logType(stringArg(0))
                    } else {
                        AppLog.putDebug("${source.getTag()}调试类型: ${stringArg(0)}")
                    }
                    platformResponse(url = url, body = stringArg(0), api = api)
                }
                "upLoginData" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        val data = actionArgs.getOrNull(0)?.let { value ->
                            @Suppress("UNCHECKED_CAST")
                            (value as? Map<String, Any?>)
                                ?: GSON.fromJsonObject<Map<String, Any?>>(GSON.toJson(value)).getOrElse {
                                    throw NoStackTraceException(
                                        "Rust platform action upLoginData returned invalid data JSON for $sourceName: ${it.localizedMessage}"
                                    )
                                }
                        }
                        java.upLoginData(data)
                        platformResponse(url = url, body = "", api = api)
                    } else unsupportedPlatformResponse(api, url, "upLoginData requires SourceLoginJsExtensions UI context")
                }
                "refreshBookInfo" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.refreshBookInfo()
                        platformResponse(url = url, body = "", api = api)
                    } else unsupportedPlatformResponse(api, url, "refreshBookInfo requires SourceLoginJsExtensions UI context")
                }
                "refreshBookToc" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.refreshBookToc()
                        platformResponse(url = url, body = "", api = api)
                    } else unsupportedPlatformResponse(api, url, "refreshBookToc requires SourceLoginJsExtensions UI context")
                }
                "refreshContent" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.refreshContent()
                        platformResponse(url = url, body = "", api = api)
                    } else unsupportedPlatformResponse(api, url, "refreshContent requires SourceLoginJsExtensions UI context")
                }
                "clearTtsCache" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.clearTtsCache()
                        platformResponse(url = url, body = "", api = api)
                    } else unsupportedPlatformResponse(api, url, "clearTtsCache requires SourceLoginJsExtensions UI context")
                }
                "searchBook" -> {
                    val java = platformJava as? RssJsExtensions
                    if (java != null) {
                        java.searchBook(requiredStringArg(0, "book name"), stringArg(1).ifBlank { null })
                        platformResponse(url = url, body = "", api = api)
                    } else unsupportedPlatformResponse(api, url, "searchBook requires RssJsExtensions UI context")
                }
                "addBook" -> {
                    val java = platformJava as? RssJsExtensions
                    if (java != null) {
                        java.addBook(requiredStringArg(0, "book URL"))
                        platformResponse(url = url, body = "", api = api)
                    } else unsupportedPlatformResponse(api, url, "addBook requires RssJsExtensions UI context")
                }
                "showPhoto" -> {
                    val java = platformJava as? RssJsExtensions
                    if (java != null) {
                        java.showPhoto(requiredStringArg(0, "photo URL"))
                        platformResponse(url = url, body = "", api = api)
                    } else unsupportedPlatformResponse(api, url, "showPhoto requires RssJsExtensions UI context")
                }
                "open" -> {
                    val java = platformJava as? RssJsExtensions
                    if (java != null) {
                        java.open(
                            name = requiredStringArg(0, "name"),
                            url = stringArg(1).ifBlank { null },
                            title = stringArg(2).ifBlank { null },
                            origin = stringArg(3).ifBlank { null }
                        )
                        platformResponse(url = url, body = "", api = api)
                    } else unsupportedPlatformResponse(api, url, "open requires RssJsExtensions UI context")
                }
                "openUrl" -> {
                    val actionUrl = requiredUrl()
                    val java = platformJava as? SourceLoginJsExtensions
                    if (java != null) {
                        java.openUrl(actionUrl, stringArg(1).ifBlank { null })
                        platformResponse(url = actionUrl, body = "", api = api)
                    } else {
                        jsJava.openUrl(actionUrl, stringArg(1).ifBlank { null })
                        platformResponse(url = actionUrl, body = "", api = api)
                    }
                }
                "setWebCookie" -> {
                    val actionUrl = requiredUrl()
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.removeSessionCookies(null)
                    stringArg(1).split(';')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { cookieManager.setCookie(actionUrl, it) }
                    cookieManager.flush()
                    platformResponse(url = actionUrl, body = "", api = api)
                }
                "webView" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    val html = stringArg(0)
                    val js = stringArg(2)
                    val pageUrl = stringArg(1)
                    val body = requirePlatformBody(api, if (java != null) {
                        java.webView(
                            html = html.ifBlank { null },
                            url = pageUrl.ifBlank { null },
                            js = js.ifBlank { null },
                            cacheFirst = boolArg(3, false)
                        )
                    } else {
                        jsJava.webView(
                            html = html.ifBlank { null },
                            url = pageUrl.ifBlank { null },
                            js = js.ifBlank { null },
                            cacheFirst = boolArg(3, false)
                        )
                    })
                    platformResponse(
                        url = pageUrl,
                        body = body,
                        api = api,
                        cookies = platformCookies(source, pageUrl)
                    )
                }
                "webViewGetSource" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    val body = requirePlatformBody(api, if (java != null) {
                        java.webViewGetSource(
                            html = stringArg(0).ifBlank { null },
                            url = stringArg(1).ifBlank { null },
                            js = stringArg(2).ifBlank { null },
                            sourceRegex = stringArg(3),
                            cacheFirst = boolArg(4, false),
                            delayTime = longArg(5, 0L)
                        )
                    } else {
                        jsJava.webViewGetSource(
                            html = stringArg(0).ifBlank { null },
                            url = stringArg(1).ifBlank { null },
                            js = stringArg(2).ifBlank { null },
                            sourceRegex = stringArg(3),
                            cacheFirst = boolArg(4, false),
                            delayTime = longArg(5, 0L)
                        )
                    })
                    platformResponse(url = url, body = body, api = api)
                }
                "webViewGetOverrideUrl" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    val body = requirePlatformBody(api, if (java != null) {
                        java.webViewGetOverrideUrl(
                            html = stringArg(0).ifBlank { null },
                            url = stringArg(1).ifBlank { null },
                            js = stringArg(2).ifBlank { null },
                            overrideUrlRegex = stringArg(3),
                            cacheFirst = boolArg(4, false),
                            delayTime = longArg(5, 0L)
                        )
                    } else {
                        jsJava.webViewGetOverrideUrl(
                            html = stringArg(0).ifBlank { null },
                            url = stringArg(1).ifBlank { null },
                            js = stringArg(2).ifBlank { null },
                            overrideUrlRegex = stringArg(3),
                            cacheFirst = boolArg(4, false),
                            delayTime = longArg(5, 0L)
                        )
                    })
                    platformResponse(url = url, body = body, api = api)
                }
                "getVerificationCode" -> {
                    val java = platformJava as? SourceLoginJsExtensions
                    val body = if (java != null) {
                        java.getVerificationCode(stringArg(0))
                    } else {
                        jsJava.getVerificationCode(stringArg(0))
                    }
                    platformResponse(url = url, body = body, api = api)
                }
                "getReadBookConfig" -> platformResponse(
                    url = url,
                    body = GSON.toJson(ReadBookConfig.durConfig),
                    api = api
                )
                "getThemeMode" -> platformResponse(
                    url = url,
                    body = AppConfig.themeMode ?: "0",
                    api = api
                )
                "getThemeConfig" -> platformResponse(
                    url = url,
                    body = GSON.toJson(ThemeConfig.getDurConfig(appCtx)),
                    api = api
                )
                "getWebViewUA" -> platformResponse(
                    url = url,
                    body = WebSettings.getDefaultUserAgent(appCtx),
                    api = api
                )
                "androidId" -> platformResponse(
                    url = url,
                    body = AppConst.androidId,
                    api = api
                )
                "getAppVersionName" -> platformResponse(
                    url = url,
                    body = AppConst.appInfo.versionName,
                    api = api
                )
                "getAppVersionCode" -> platformResponse(
                    url = url,
                    body = AppConst.appInfo.versionCode.toString(),
                    api = api
                )
                "getAppVariant" -> platformResponse(
                    url = url,
                    body = AppConst.appInfo.appVariant,
                    api = api
                )
                else -> platformResponse(
                    url = url,
                    body = "",
                    api = api,
                    code = 501,
                    message = "Unsupported platform action: $api",
                    unsupported = true
                )
        }
    }

    private fun requirePlatformBody(api: String, body: String?): String {
        return body
            ?: throw NoStackTraceException("Rust platform action $api returned no body from Android boundary")
    }

    private fun unsupportedPlatformResponse(api: String, url: String, message: String): String {
        return platformResponse(
            url = url,
            body = "",
            api = api,
            code = 501,
            message = message,
            unsupported = true
        )
    }

    private fun platformResponse(
        url: String,
        body: String,
        api: String,
        code: Int = 200,
        message: String = "OK",
        unsupported: Boolean = false,
        cookies: Map<String, String> = emptyMap()
    ): String {
        return GSON.toJson(
            mapOf(
                "handled" to !unsupported,
                "unsupported" to unsupported,
                "api" to api,
                "url" to url,
                "body" to body,
                "code" to code,
                "message" to message,
                "marker" to "__LEGADO_PLATFORM_API__:$api",
                "cookies" to cookies
            )
        )
    }

    private fun platformCookies(source: BaseSource, url: String): Map<String, String> {
        val cookies = linkedMapOf<String, String>()
        val cookieManager = CookieManager.getInstance()
        cookieManager.getCookie(source.getKey()).orEmpty().takeIf { it.isNotBlank() }?.let {
            cookies[source.getKey()] = it
        }
        url.takeIf { it.isNotBlank() }?.let { pageUrl ->
            cookieManager.getCookie(pageUrl).orEmpty().takeIf { it.isNotBlank() }?.let {
                cookies[pageUrl] = it
            }
        }
        return cookies
    }

    private fun syncRustCookiesToWebView(session: RustSession) {
        if (session.cookies.isEmpty()) return
        val cookieManager = CookieManager.getInstance()
        session.cookies.forEach { (host, cookie) ->
            val url = webViewCookieUrl(host) ?: return@forEach
            cookie.split(';')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { value ->
                    cookieManager.setCookie(url, value)
                }
        }
        cookieManager.flush()
    }

    private fun webViewCookieUrl(host: String): String? {
        val value = host.trim()
        if (value.isBlank()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return "https://$value"
    }

    private fun flushRuntimeRecords(
        source: BaseSource,
        before: RustSession,
        after: RustSession,
        platformJava: Any?
    ) {
        val newLogs = after.logs.drop(before.logs.size)
        val newToasts = after.toasts.drop(before.toasts.size)
        if (platformJava == null) {
            newToasts.forEach { message ->
                appCtx.longToastOnUi("${source.getTag()}: $message")
            }
            newLogs.forEach { message ->
                AppLog.putDebug("${source.getTag()}调试输出: $message")
            }
        } else if (newLogs.isNotEmpty() || newToasts.isNotEmpty()) {
            AppLog.putDebug(
                buildString {
                    append(source.getTag())
                    append(" Rust UI records were handled by Android PlatformHost")
                    if (newToasts.isNotEmpty()) {
                        append("\ntoasts:\n")
                        append(newToasts.joinToString("\n"))
                    }
                    if (newLogs.isNotEmpty()) {
                        append("\nlogs:\n")
                        append(newLogs.joinToString("\n"))
                    }
                }
            )
        }
    }

    private fun bindingsJson(book: Book? = null, chapter: BookChapter? = null): String {
        val bindings = linkedMapOf<String, Any?>()
        if (book != null) {
            bindings["book"] = linkedMapOf<String, Any?>(
                "bookUrl" to book.bookUrl,
                "tocUrl" to book.tocUrl,
                "origin" to book.origin,
                "originName" to book.originName,
                "name" to book.name,
                "author" to book.author,
                "kind" to book.kind,
                "coverUrl" to book.coverUrl,
                "intro" to book.intro,
                "type" to book.type,
                "latestChapterTitle" to book.latestChapterTitle,
                "durChapterTitle" to book.durChapterTitle,
                "durChapterIndex" to book.durChapterIndex,
                "order" to book.order,
                "originOrder" to book.originOrder,
                "variable" to book.variable,
                "wordCount" to book.wordCount
            )
        }
        if (chapter != null) {
            bindings["chapter"] = linkedMapOf<String, Any?>(
                "url" to chapter.url,
                "title" to chapter.title,
                "baseUrl" to chapter.baseUrl,
                "bookUrl" to chapter.bookUrl,
                "index" to chapter.index,
                "tag" to chapter.tag,
                "variable" to chapter.variable,
                "resourceUrl" to chapter.resourceUrl,
                "imgUrl" to chapter.imgUrl
            )
        }
        return GSON.toJson(bindings)
    }

    private fun RustSession.applyToBook(book: Book) {
        bookVariables["bookUrl"]?.takeIf { it.isNotBlank() }?.let { book.bookUrl = it }
        bookVariables["tocUrl"]?.takeIf { it.isNotBlank() }?.let { book.tocUrl = it }
        bookVariables["origin"]?.takeIf { it.isNotBlank() }?.let { book.origin = it }
        bookVariables["originName"]?.takeIf { it.isNotBlank() }?.let { book.originName = it }
        bookVariables["name"]?.takeIf { it.isNotBlank() }?.let { book.name = it }
        bookVariables["author"]?.takeIf { it.isNotBlank() }?.let { book.author = it }
        bookVariables["kind"]?.let { book.kind = it.ifBlank { null } }
        bookVariables["coverUrl"]?.let { book.coverUrl = it.ifBlank { null } }
        bookVariables["intro"]?.let { book.intro = it.ifBlank { null } }
        bookVariables["wordCount"]?.let { book.wordCount = it.ifBlank { null } }
        bookVariables["latestChapterTitle"]?.let { book.latestChapterTitle = it.ifBlank { null } }
        bookVariables["type"]?.toIntOrNull()?.let { book.type = it }
        bookVariables["variable"]?.let { book.variable = it.ifBlank { null } }
        bookVariables["durChapterTitle"]?.let { book.durChapterTitle = it.ifBlank { null } }
        bookVariables["durChapterIndex"]?.toIntOrNull()?.let { book.durChapterIndex = it }
        bookVariables["order"]?.toIntOrNull()?.let { book.order = it }
        bookVariables["originOrder"]?.toIntOrNull()?.let { book.originOrder = it }
    }

    private fun RustSession.applyToChapter(chapter: BookChapter) {
        chapterVariables["title"]?.takeIf { it.isNotBlank() }?.let { chapter.title = it }
        chapterVariables["url"]?.takeIf { it.isNotBlank() }?.let { chapter.url = it }
        chapterVariables["variable"]?.let { chapter.variable = it.ifBlank { null } }
        chapterVariables["resourceUrl"]?.let { chapter.resourceUrl = it.ifBlank { null } }
        chapterVariables["imgUrl"]?.let { chapter.imgUrl = it.ifBlank { null } }
        chapterVariables["lyric"]?.let { chapter.putRustVariable("lyric", it) }
        chapterVariables["danmaku"]?.let { chapter.putRustVariable("danmaku", it) }
    }

    private fun BookChapter.putRustVariable(key: String, value: String) {
        val variables = variable?.takeIf { it.isNotBlank() }
            ?.let {
                GSON.fromJsonObject<MutableMap<String, String>>(it).getOrElse { error ->
                    throw NoStackTraceException(
                        "chapter variable JSON is invalid for Rust analyzer state handoff: ${error.message}"
                    )
                }
            }
            ?.toMutableMap()
            ?: linkedMapOf()
        variables[key] = value
        variable = if (variables.isEmpty()) null else GSON.toJson(variables)
    }

    private fun RustBook.toSearchBook(bookSource: BookSource): SearchBook {
        return SearchBook(
            bookUrl = bookUrl,
            origin = bookSource.bookSourceUrl,
            originName = bookSource.bookSourceName,
            type = bookSource.getBookType(),
            name = name,
            author = author,
            kind = kind.ifBlank { null },
            coverUrl = coverUrl.ifBlank { null },
            intro = intro.ifBlank { null },
            wordCount = wordCount.ifBlank { null },
            latestChapterTitle = lastChapter.ifBlank { null },
            tocUrl = tocUrl,
            originOrder = bookSource.customOrder
        )
    }

    private fun RustBook.applyToBook(bookSource: BookSource, book: Book) {
        book.origin = bookSource.bookSourceUrl
        book.originName = bookSource.bookSourceName
        book.type = bookSource.getBookType()
        if (name.isNotBlank()) book.name = name
        if (author.isNotBlank()) book.author = author
        book.kind = kind.ifBlank { book.kind }
        book.coverUrl = coverUrl.ifBlank { book.coverUrl }
        book.intro = intro.ifBlank { book.intro }
        book.wordCount = wordCount.ifBlank { book.wordCount }
        book.latestChapterTitle = lastChapter.ifBlank { book.latestChapterTitle }
        if (bookUrl.isNotBlank()) book.bookUrl = bookUrl
        if (tocUrl.isNotBlank()) book.tocUrl = tocUrl
        book.originOrder = bookSource.customOrder
    }
}

private data class RustInput(
    val key: String = "",
    val page: Int = 1,
    val bookUrl: String = "",
    val tocUrl: String = "",
    val chapterUrl: String = "",
    val nextChapterUrl: String = "",
    val exploreUrl: String = "",
    val script: String = "",
    val result: String = "",
    val baseUrl: String = "",
    val rulePath: String = "",
    val bindingsJson: String = "",
    val uploadFileName: String = "",
    val uploadContentType: String = "",
    val uploadBodyBase64: String = "",
    val uploadCompress: Boolean = false,
    val speakText: String = "",
    val speakSpeed: Int = 0,
    val useWebView: Boolean = false,
    val bootstrapLoginUrl: Boolean = false,
    val runPreUpdateJs: Boolean = false,
    val sortName: String = ""
)

private data class RustJsHttpResponse(
    val url: String = "",
    val body: String? = null,
    val code: Int = 200,
    val message: String = "OK"
) {
    fun toStrResponse(): StrResponse {
        if (url.isBlank()) {
            throw NoStackTraceException("Rust eval StrResponse returned blank URL")
        }
        return StrResponse(url, body, code, message)
    }
}

private data class RustRawFetchResponse(
    val url: String = "",
    val code: Int = 200,
    val message: String = "OK",
    val headers: Map<String, String> = emptyMap(),
    val headersList: List<List<String>> = emptyList(),
    val contentType: String? = null,
    val bodyBase64: String = "",
    val error: String? = null
)

data class RustRawFetchResult(
    val url: String,
    val code: Int,
    val message: String,
    val headers: Map<String, String>,
    val headersList: List<List<String>>,
    val contentType: String?,
    val body: ByteArray
)

data class RustFetchTextResult(
    val url: String = "",
    val statusCode: Int = 200,
    val message: String = "OK",
    val body: String = "",
    val contentType: String? = null
)

data class RustResolvedUrl(
    val url: String = "",
    val method: String = "GET",
    val headers: List<List<String>> = emptyList(),
    val body: String? = null,
    val options: Map<String, Any?> = emptyMap(),
    val serverId: Long? = null
)

private data class RustWebDavListingResponse(
    val files: List<RustWebDavFile> = emptyList(),
    val error: String? = null
)

data class RustWebDavFile(
    val url: String = "",
    val displayName: String = "",
    val urlName: String = "",
    val size: Long = 0,
    val contentType: String = "",
    val resourceType: String = "",
    val lastModify: Long = 0
)

data class RustWebDavError(
    val exception: String = "",
    val message: String = "",
    val error: String? = null
)

private data class RustHtmlTextArray(
    val lines: List<String> = emptyList(),
    val error: String? = null
)

private data class RustHtmlCharset(
    val charset: String = "",
    val error: String? = null
)

private data class RustHtmlFormat(
    val html: String = "",
    val error: String? = null
)

private data class RustHtmlTitle(
    val title: String = "",
    val error: String? = null
)

private data class RustHtmlAlignment(
    val alignment: String = "",
    val error: String? = null
)

private data class RustEpubNativeEntry(
    val hrefs: List<String> = emptyList(),
    val error: String? = null
)

data class RustEpubBookInfo(
    val isBookInfo: Boolean = false,
    val author: String = "",
    val intro: String = "",
    val error: String? = null
)

private data class RustEpubFootnoteIds(
    val ids: List<String> = emptyList(),
    val error: String? = null
)

data class RustEpubFootnoteTarget(
    val found: Boolean = false,
    val title: String = "",
    val html: String = "",
    val text: String = "",
    val imageSources: List<String> = emptyList(),
    val error: String? = null
)

private data class RustEpubReadableLines(
    val lines: List<String> = emptyList(),
    val error: String? = null
)

data class RustEpubBodyHtml(
    val html: String = "",
    val documentHtml: String = "",
    val bodyHtml: String = "",
    val bodyOuterHtml: String = "",
    val bodyStyle: String = "",
    val bodyBackground: String = "",
    val title: String = "",
    val sliced: Boolean = false,
    val error: String? = null
)

private data class RustEpubDebugChapterHtml(
    val html: String = "",
    val error: String? = null
)

data class RustEpubImageOptions(
    val src: String = "",
    val alt: String = "",
    val isBackground: Boolean = false,
    val width: String = "",
    val height: String = "",
    val style: String = "",
    val error: String? = null
)

data class RustEpubImagePageMarks(
    val html: String = "",
    val bodyStyleAppend: String = "",
    val error: String? = null
)

private data class RustEpubMaterializedImages(
    val html: String = "",
    val error: String? = null
)

private data class RustEpubMediaPlaceholders(
    val html: String = "",
    val error: String? = null
)

private data class RustEpubInlineStyles(
    val html: String = "",
    val error: String? = null
)

private data class RustEpubInheritedStyles(
    val html: String = "",
    val error: String? = null
)

private data class RustEpubGeneratedContent(
    val html: String = "",
    val error: String? = null
)

data class RustEpubNativeDom(
    val body: RustEpubNativeDomNode = RustEpubNativeDomNode(),
    val error: String? = null
)

data class RustEpubNativeDomNode(
    val kind: String = "",
    val tagName: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val style: RustEpubNativeComputedStyle = RustEpubNativeComputedStyle(),
    val children: List<RustEpubNativeDomNode> = emptyList(),
    val text: String = "",
    val sourcePath: String = ""
)

data class RustEpubNativeComputedStyle(
    val declarations: Map<String, RustEpubNativeStyleValue> = emptyMap()
)

data class RustEpubNativeStyleValue(
    val value: String = "",
    val important: Boolean = false,
    val sourceRank: Int = 0,
    val specificity: Int = 0,
    val ruleOrder: Int = 0,
    val declarationOrder: Int = 0
)

data class RustEpubAppliedCss(
    val html: String = "",
    val bodyStyle: String = "",
    val bodyBackground: String = "",
    val error: String? = null
)

private data class RustEpubResolvedLinks(
    val html: String = "",
    val error: String? = null
)

private data class RustEpubBodyBackgroundImage(
    val href: String = "",
    val error: String? = null
)

data class RustEpubCssAssets(
    val assets: List<RustEpubCssAsset> = emptyList(),
    val html: String = "",
    val error: String? = null
)

data class RustEpubCssAsset(
    val kind: String = "",
    val content: String = "",
    val href: String = ""
)

private data class RustHtmlReadableTable(
    val html: String = "",
    val error: String? = null
)

data class RustHtmlRenderFlags(
    val tagName: String = "",
    val isBlock: Boolean = false,
    val hasImage: Boolean = false,
    val hasBlockBoxStyle: Boolean = false,
    val hasBlockBoxDescendant: Boolean = false,
    val pageBreakBefore: Boolean = false,
    val pageBreakAfter: Boolean = false,
    val blockSpacingBefore: Boolean = false,
    val blockSpacingAfter: Boolean = false,
    val error: String? = null
)

data class RustHtmlPageBackground(
    val pageColor: String = "",
    val backgroundSrc: String = "",
    val html: String = "",
    val error: String? = null
)

data class RustHtmlImageInfo(
    val src: String = "",
    val isBackground: Boolean = false,
    val style: String = "",
    val width: String = "",
    val click: String = "",
    val error: String? = null
)

private data class RustHtmlRenderPlan(
    val actions: List<RustHtmlRenderAction> = emptyList(),
    val error: String? = null
)

data class RustHtmlRenderAction(
    val kind: String = "",
    val html: String = "",
    val pageColor: String = "",
    val marginTop: String = "",
    val paddingTop: String = "",
    val marginBottom: String = "",
    val paddingBottom: String = "",
    val image: RustHtmlImageInfo = RustHtmlImageInfo(),
    val error: String? = null
)

private data class RustMobiContentHtml(
    val html: String = "",
    val error: String? = null
)

private data class RustOutput(
    val books: List<RustBook> = emptyList(),
    val book: RustBook? = null,
    val chapters: List<RustChapter> = emptyList(),
    val content: RustContent? = null,
    val explore: List<RustExplore> = emptyList(),
    val rssSorts: List<RustRssSort> = emptyList(),
    val articles: List<RssArticle> = emptyList(),
    val nextUrl: String? = null,
    val rssContent: String? = null,
    @SerializedName("eval_result")
    val evalResult: String? = null,
    val diagnostics: List<String> = emptyList(),
    val session: RustSession = RustSession(),
    val error: String? = null
)

private data class RustRssSort(
    val name: String = "",
    val url: String = ""
)

private data class RustExplore(
    val title: String = "",
    val url: String = "",
    val type: String = ExploreKind.Type.url,
    val action: String = "",
    val chars: List<String?> = emptyList(),
    val default: String = "",
    val style: FlexChildStyle? = null
)

private data class RustSession(
    var sourceVariable: String = "",
    val variables: MutableMap<String, String> = linkedMapOf(),
    val sourceStore: MutableMap<String, String> = linkedMapOf(),
    val cache: MutableMap<String, String> = linkedMapOf(),
    val cookies: MutableMap<String, String> = linkedMapOf(),
    val loginInfoRaw: String = "",
    val loginInfo: MutableMap<String, String> = linkedMapOf(),
    val bookVariables: MutableMap<String, String> = linkedMapOf(),
    val chapterVariables: MutableMap<String, String> = linkedMapOf(),
    val javaStore: MutableMap<String, String> = linkedMapOf(),
    val logs: List<String> = emptyList(),
    val toasts: List<String> = emptyList()
) {
    fun deepCopy(): RustSession {
        return copy(
            variables = LinkedHashMap(variables),
            sourceStore = LinkedHashMap(sourceStore),
            cache = LinkedHashMap(cache),
            cookies = LinkedHashMap(cookies),
            loginInfoRaw = loginInfoRaw,
            loginInfo = LinkedHashMap(loginInfo),
            bookVariables = LinkedHashMap(bookVariables),
            chapterVariables = LinkedHashMap(chapterVariables),
            javaStore = LinkedHashMap(javaStore),
            logs = logs.toList(),
            toasts = toasts.toList()
        )
    }
}

private data class RustBook(
    val name: String = "",
    val author: String = "",
    val kind: String = "",
    @SerializedName("cover_url")
    val coverUrl: String = "",
    val intro: String = "",
    @SerializedName("last_chapter")
    val lastChapter: String = "",
    @SerializedName("word_count")
    val wordCount: String = "",
    @SerializedName("book_url")
    val bookUrl: String = "",
    @SerializedName("toc_url")
    val tocUrl: String = ""
)

private data class RustChapter(
    val title: String = "",
    val url: String = "",
    @SerializedName("update_time")
    val updateTime: String = "",
    @SerializedName("is_vip")
    val isVip: String = "",
    @SerializedName("is_pay")
    val isPay: String = "",
    @SerializedName("is_volume")
    val isVolume: String = ""
)

private data class RustContent(
    val title: String = "",
    val content: String = "",
    @SerializedName("next_content_url")
    val nextContentUrl: String = "",
    @SerializedName("sub_content")
    val subContent: String = ""
)
