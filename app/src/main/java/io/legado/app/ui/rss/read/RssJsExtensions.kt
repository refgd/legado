package io.legado.app.ui.rss.read

import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.JsExtensions
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.VideoPlay
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.ui.association.AddToBookshelfDialog
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL
import kotlin.coroutines.CoroutineContext


@Suppress("unused")
open class RssJsExtensions(
    activity: AppCompatActivity?,
    source: BaseSource?,
    val bookType: Int = 0
) : JsExtensions {

    val activityRef: WeakReference<AppCompatActivity> = WeakReference(activity)
    val sourceRef: WeakReference<BaseSource?> = WeakReference(source)

    override fun getSource(): BaseSource? {
        return sourceRef.get()
    }

    override fun getTag(): String? {
        return getSource()?.getTag()
    }

    @JavascriptInterface
    fun put(key: String, value: String): String {
        val source = getSource() ?: rustTemporarySource()
        return RustAnalyzerBridge.evalJs(
            source = source,
            script = "source.put(${GSON.toJson(key)}, ${GSON.toJson(value)})",
            baseUrl = source.getKey(),
            rulePath = "RssJsExtensions.put",
            platformJava = this,
            bindingsJson = rustBindingsJson()
        )
    }

    @JavascriptInterface
    fun get(key: String): String {
        val source = getSource() ?: rustTemporarySource()
        return RustAnalyzerBridge.evalJs(
            source = source,
            script = "source.get(${GSON.toJson(key)})",
            baseUrl = source.getKey(),
            rulePath = "RssJsExtensions.get",
            platformJava = this,
            bindingsJson = rustBindingsJson()
        )
    }

    @JavascriptInterface
    @JvmOverloads
    fun searchBook(key: String, searchScope: String? = null) {
        activityRef.get()?.let {
            SearchActivity.start(it, key, searchScope)
        }
    }

    fun searchBook(key: String, source: BookSource) {
        activityRef.get()?.let {
            SearchActivity.start(it, source, key)
        }
    }

    @JavascriptInterface
    fun addBook(bookUrl: String) {
        activityRef.get()?.showDialogFragment(AddToBookshelfDialog(bookUrl))
    }

    @JavascriptInterface
    fun showPhoto(src: String) {
        activityRef.get()?.showDialogFragment(PhotoDialog(src, getSource()?.getKey()))
    }


    @JavascriptInterface
    @JvmOverloads
    fun open(name: String, url: String? = null, title: String? = null, origin: String? = null) {
        if (onOpen(name, url, title, origin)) return
        val activity = activityRef.get() ?: return
        activity.lifecycleScope.launch(IO) {
            val source = getSource() ?: return@launch
            when (name) {
                "login" -> {
                    if (activity is SourceLoginActivity) {
                        activity.toastOnUi("已在登录界面")
                        return@launch
                    }
                    val toSource = origin?.let { o ->
                        appDb.bookSourceDao.getBookSource(o)
                    } ?: source
                    if (toSource.loginUrl.isNullOrBlank()) {
                        activity.toastOnUi("源未配置登录")
                        return@launch
                    }
                    when (toSource) {
                        is BookSource -> {
                            withContext(Main) {
                                activity.startActivity<SourceLoginActivity> {
                                    putExtra("bookType", bookType)
                                    putExtra("type", "bookSource")
                                    putExtra("key", toSource.bookSourceUrl)
                                }
                            }
                        }

                        is RssSource -> {
                            withContext(Main) {
                                activity.startActivity<SourceLoginActivity> {
                                    putExtra("type", "rssSource")
                                    putExtra("key", toSource.sourceUrl)
                                }
                            }
                        }
                    }
                }

                "sort" -> {
                    val toSource = origin?.let { o ->
                        appDb.rssSourceDao.getByKey(o)
                    } ?: (source as? RssSource) ?: return@launch
                    val sortUrl = if (url.isJsonObject()) {
                        url
                    } else {
                        title?.let {
                            JSONObject().put(title, url).toString()
                        } ?: url
                    }
                    val sourceUrl = toSource.sourceUrl
                    RssSortActivity.start(activity, sortUrl, sourceUrl)
                }

                "rss" -> {
                    val toSource = origin?.let { o ->
                        appDb.rssSourceDao.getByKey(o)
                    } ?: (source as? RssSource) ?: return@launch
                    val title = title ?: toSource.sourceName
                    val sourceUrl = toSource.sourceUrl
                    val singleTop = sourceUrl == source.getKey()
                    if (url.isNullOrBlank()) {
                        if (toSource.singleUrl) {
                            if (sourceUrl.startsWith("http", true)) {
                                ReadRssActivity.start(
                                    activity,
                                    singleTop,
                                    sourceUrl,
                                    title
                                )
                            } else {
                                activity.openUrl(sourceUrl)
                            }
                            return@launch
                        }
                        val startHtml = toSource.resolveStartHtmlByRustEval()
                        if (startHtml.isNullOrBlank()) {
                            RssSortActivity.start(activity, null, sourceUrl)
                        } else {
                            ReadRssActivity.start(
                                activity,
                                singleTop,
                                sourceUrl,
                                title,
                                startHtml = startHtml
                            )
                        }
                        return@launch
                    }
                    val rss =appDb.rssStarDao.get(sourceUrl, url)?.toRecord() ?: appDb.rssArticleDao.getByLink(sourceUrl, url)?.toRecord()
                    val rssReadRecord = rss ?: RssReadRecord(
                        record = url,
                        title = title,
                        origin = sourceUrl,
                        readTime = System.currentTimeMillis()
                    )
                    appDb.rssReadRecordDao.insertRecord(rssReadRecord) //留下历史记录
                    withContext(Main) {
                        ReadRssActivity.start(
                            activity,
                            singleTop,
                            sourceUrl,
                            title,
                            url
                        )
                    }
                }

                "search" -> {
                    title?.let {
                        origin?.let { o  ->
                            appDb.bookSourceDao.getBookSource(o)?.let { s ->
                                searchBook(it, s)
                                return@launch
                            }
                        }
                        searchBook(it)
                    }
                }

                "explore" -> {
                    val toSource = origin?.let { o ->
                        appDb.bookSourceDao.getBookSource(o)
                    } ?: (source as? BookSource) ?: return@launch
                    val sourceUrl = toSource.bookSourceUrl
                    withContext(Main) {
                        activity.startActivity<ExploreShowActivity> {
                            putExtra("exploreName", title)
                            putExtra("sourceUrl", sourceUrl)
                            putExtra("exploreUrl", url)
                        }
                    }
                }
            }
        }
    }

    /** Rust analyzer rule helpers exposed to WebView JS **/
    private val bookAndChapter by lazy {
        var book: Book? = null
        var chapter: BookChapter? = null
        when (bookType) {
            BookType.text -> {
                book = ReadBook.book?.also {
                    chapter = appDb.bookChapterDao.getChapter(
                        it.bookUrl,
                        ReadBook.durChapterIndex
                    )
                }
            }

            BookType.audio -> {
                book = AudioPlay.book
                chapter = AudioPlay.durChapter
            }

            BookType.video -> {
                book = VideoPlay.book
                chapter = VideoPlay.chapter
            }
        }
        Pair(book, chapter)
    }
    private val book: Book? get() = bookAndChapter.first
    private val chapter: BookChapter? get() = bookAndChapter.second

    val analyzeRule by lazy {
        RssRustRuleBridge(this, ::getSource, book, chapter)
    }

    private fun rustBindingsJson(): String {
        return GSON.toJson(mapOf("book" to book, "chapter" to chapter))
    }

    private fun rustTemporarySource(): BookSource {
        return BookSource(
            bookSourceUrl = getSource()?.getKey() ?: "legado://rss-js",
            bookSourceName = getSource()?.getTag() ?: "RSS Rust JS"
        )
    }

    @JavascriptInterface
    @JvmOverloads
    fun setContent(content: Any?, baseUrl: String? = null): RssRustRuleBridge {
        return analyzeRule.setContent(content, baseUrl)
    }

    @JavascriptInterface
    fun setBaseUrl(baseUrl: String?): RssRustRuleBridge {
        return analyzeRule.setBaseUrl(baseUrl)
    }

    @JavascriptInterface
    fun setRedirectUrl(url: String): URL? {
        return analyzeRule.setRedirectUrl(url)
    }

    @JvmOverloads
    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        return analyzeRule.getStringList(rule, mContent, isUrl)
    }

    @JvmOverloads
    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        return analyzeRule.getString(ruleStr, mContent, isUrl)
    }

    @JavascriptInterface
    fun getString(ruleStr: String?, unescape: Boolean): String {
        return analyzeRule.getString(ruleStr, unescape)
    }

    fun getElement(ruleStr: String): Any? {
        return analyzeRule.getElement(ruleStr)
    }

    fun getElements(ruleStr: String): List<Any> {
        return analyzeRule.getElements(ruleStr)
    }

    protected open fun onOpen(
        name: String,
        url: String? = null,
        title: String? = null,
        origin: String? = null
    ): Boolean = false
}

class RssRustRuleBridge(
    private val platformJava: RssJsExtensions,
    private val sourceProvider: () -> BaseSource?,
    private val book: Book?,
    private val chapter: BookChapter?
) {
    private var content: Any? = ""
    private var baseUrl: String = sourceProvider()?.getKey().orEmpty()
    private var redirectUrl: URL? = null

    @JvmOverloads
    fun setContent(content: Any?, baseUrl: String? = null): RssRustRuleBridge {
        if (content == null) throw AssertionError("内容不可空（Content cannot be null）")
        this.content = content
        setBaseUrl(baseUrl)
        return this
    }

    fun setBaseUrl(baseUrl: String?): RssRustRuleBridge {
        baseUrl?.let {
            this.baseUrl = it
        }
        return this
    }

    fun setRedirectUrl(url: String): URL? {
        if (url.startsWith("data:", ignoreCase = true) || url.startsWith("data64:", ignoreCase = true)) {
            return redirectUrl
        }
        redirectUrl = runCatching { URL(url) }.getOrElse {
            throw NoStackTraceException(
                "RssJsExtensions.setRedirectUrl received invalid URL from Rust analyzer JS: $url"
            )
        }
        return redirectUrl
    }

    fun setCoroutineContext(@Suppress("UNUSED_PARAMETER") context: CoroutineContext): RssRustRuleBridge {
        return this
    }

    fun evalJS(jsStr: String, result: Any? = null): Any? {
        val source = sourceProvider() ?: temporarySource()
        return RustAnalyzerBridge.evalJsAny(
            source = source,
            script = "java.setContent(src, baseUrl);\n$jsStr",
            result = result,
            baseUrl = baseUrl.ifBlank { source.getKey() },
            rulePath = "RssJsExtensions.evalJS",
            platformJava = platformJava,
            bindingsJson = bindingsJson()
        )
    }

    @JvmOverloads
    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        rule ?: return null
        val value = getString(rule, mContent, isUrl)
        if (value.isBlank()) return emptyList()
        return value.split('\n').filter { it.isNotBlank() }
    }

    @JvmOverloads
    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        val rule = ruleStr.orEmpty()
        if (rule.isBlank()) return ""
        val source = sourceProvider() ?: temporarySource()
        return RustAnalyzerBridge.evalRule(
            source = source,
            rule = rule,
            result = mContent ?: content,
            baseUrl = baseUrl.ifBlank { source.getKey() },
            rulePath = "RssJsExtensions.getString",
            platformJava = platformJava,
            bindingsJson = bindingsJson()
        ).let { value ->
            if (isUrl) {
                RustAnalyzerBridge.resolveUrl(
                    url = value,
                    source = source,
                    baseUrl = baseUrl.ifBlank { source.getKey() },
                    rulePath = "RssJsExtensions.getString.url"
                ).url
            } else {
                value
            }
        }
    }

    fun getString(ruleStr: String?, @Suppress("UNUSED_PARAMETER") unescape: Boolean): String {
        return getString(ruleStr)
    }

    fun getElement(ruleStr: String): Any? {
        return getElements(ruleStr).firstOrNull()
    }

    fun getElements(ruleStr: String): List<Any> {
        val source = sourceProvider() ?: temporarySource()
        val script = """
            java.setContent(result, baseUrl);
            var list = java.getElements(rule);
            Array.prototype.map.call(list, function(item) { return String(item); });
        """.trimIndent()
        return when (val value = RustAnalyzerBridge.evalJsAny(
            source = source,
            script = script,
            result = content,
            baseUrl = baseUrl.ifBlank { source.getKey() },
            rulePath = "RssJsExtensions.getElements",
            platformJava = platformJava,
            bindingsJson = GSON.toJson(
                mapOf(
                    "rule" to ruleStr,
                    "book" to book,
                    "chapter" to chapter
                )
            )
        )) {
            is List<*> -> value.filterNotNull()
            null -> emptyList()
            else -> listOf(value)
        }
    }

    private fun bindingsJson(): String {
        return GSON.toJson(
            mapOf(
                "book" to book,
                "chapter" to chapter,
                "src" to content
            )
        )
    }

    private fun temporarySource(): BookSource {
        return BookSource(
            bookSourceUrl = baseUrl.ifBlank { "legado://rss-rule" },
            bookSourceName = "RSS Rust rule"
        )
    }
}

fun RssSource.resolveStartHtmlByRustEval(): String? {
    val startHtml = startHtml ?: return null
    return when {
        startHtml.startsWith("@js:") -> evalJS(startHtml.substring(4)).toString()
        startHtml.startsWith("<js>") -> evalJS(startHtml.substring(4, startHtml.lastIndexOf("<"))).toString()
        else -> startHtml
    }
}
