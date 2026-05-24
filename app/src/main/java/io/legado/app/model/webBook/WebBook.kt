package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.addType
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.removeAllBookType
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getBookType
import io.legado.app.model.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

@Suppress("MemberVisibilityCanBePrivate")
object WebBook {

    private data class ChapterListResult(
        val book: Book,
        val chapters: List<BookChapter>
    )

    private val chapterListJobs = ConcurrentHashMap<String, Deferred<Result<ChapterListResult>>>()

    /**
     * 搜索
     */
    fun searchBook(
        scope: CoroutineScope,
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
    ): Coroutine<ArrayList<SearchBook>> {
        return Coroutine.async(scope, context, start = start, executeContext = executeContext) {
            searchBookAwait(bookSource, key, page)
        }
    }

    suspend fun searchBookAwait(
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        filter: ((name: String, author: String, kind: String?) -> Boolean)? = null,
        shouldBreak: ((size: Int) -> Boolean)? = null
    ): ArrayList<SearchBook> {
        val searchUrl = bookSource.searchUrl
        if (searchUrl.isNullOrBlank()) {
            throw NoStackTraceException("搜索url不能为空")
        }
        requireRustAnalyzer(bookSource)
        val books = RustAnalyzerBridge.search(bookSource, key, page)
        val filtered = if (filter == null) {
            books
        } else {
            books.filterTo(arrayListOf()) { filter(it.name, it.author, it.kind) }
        }
        shouldBreak?.invoke(filtered.size)
        return filtered
    }

    /**
     * 发现
     */
    fun exploreBook(
        scope: CoroutineScope,
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
    ): Coroutine<List<SearchBook>> {
        return Coroutine.async(scope, context) {
            exploreBookAwait(bookSource, url, page)
        }
    }

    suspend fun exploreBookAwait(
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
    ): ArrayList<SearchBook> {
        requireRustAnalyzer(bookSource)
        return RustAnalyzerBridge.explore(bookSource, url, page)
    }

    /**
     * 书籍信息
     */
    fun getBookInfo(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        context: CoroutineContext = Dispatchers.IO,
        canReName: Boolean = true,
    ): Coroutine<Book> {
        return Coroutine.async(scope, context) {
            getBookInfoAwait(bookSource, book, canReName)
        }
    }

    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean = true,
    ): Book {
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        requireRustAnalyzer(bookSource)
        RustAnalyzerBridge.detail(bookSource, book)
        return book
    }

    /**
     * 目录
     */
    fun getChapterList(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        context: CoroutineContext = Dispatchers.IO,
        isFromBookInfo : Boolean = false
    ): Coroutine<List<BookChapter>> {
        return Coroutine.async(scope, context) {
            getChapterListAwait(bookSource, book, runPerJs,isFromBookInfo).getOrThrow()
        }
    }

    suspend fun runPreUpdateJs(bookSource: BookSource, book: Book, isFromBookInfo : Boolean = false) {
        val preUpdateJs = bookSource.ruleToc?.preUpdateJs
        if (!preUpdateJs.isNullOrBlank()) {
            requireRustAnalyzer(bookSource)
            RustAnalyzerBridge.preUpdateToc(bookSource, book)
        }
    }

    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        isFromBookInfo : Boolean = false
    ): Result<List<BookChapter>> {
        val key = chapterListLoadKey(bookSource, book, runPerJs)
        val job = CoroutineScope(currentCoroutineContext()).async(start = CoroutineStart.LAZY) {
            loadChapterListAwait(bookSource, book, runPerJs, isFromBookInfo)
        }
        val runningJob = chapterListJobs.putIfAbsent(key, job)
        return if (runningJob == null) {
            job.await()
                .onFailure {
                    currentCoroutineContext().ensureActive()
                }
                .map { it.chapters }
                .also {
                    chapterListJobs.remove(key, job)
                }
        } else {
            job.cancel()
            runningJob.await()
                .onSuccess {
                    applyChapterListBookState(book, it.book)
                }
                .onFailure {
                    currentCoroutineContext().ensureActive()
                }
                .map { it.chapters }
        }
    }

    private suspend fun loadChapterListAwait(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        isFromBookInfo : Boolean = false
    ): Result<ChapterListResult> {
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        return kotlin.runCatching {
            requireRustAnalyzer(bookSource)
            val chapters = RustAnalyzerBridge.toc(bookSource, book, runPreUpdateJs = runPerJs)
            book.totalChapterNum = chapters.size
            ChapterListResult(book.copy(), chapters)
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }
    }

    private fun chapterListLoadKey(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean
    ): String {
        return listOf(
            bookSource.bookSourceUrl,
            book.bookUrl,
            book.tocUrl,
            runPerJs
        ).joinToString("\n")
    }

    private fun applyChapterListBookState(target: Book, source: Book) {
        target.bookUrl = source.bookUrl
        target.tocUrl = source.tocUrl
        target.origin = source.origin
        target.originName = source.originName
        target.name = source.name
        target.author = source.author
        target.kind = source.kind
        target.coverUrl = source.coverUrl
        target.intro = source.intro
        target.charset = source.charset
        target.type = source.type
        target.latestChapterTitle = source.latestChapterTitle
        target.latestChapterTime = source.latestChapterTime
        target.lastCheckTime = source.lastCheckTime
        target.lastCheckCount = source.lastCheckCount
        target.totalChapterNum = source.totalChapterNum
        target.wordCount = source.wordCount
        target.originOrder = source.originOrder
        target.variable = source.variable
        target.syncTime = source.syncTime
        target.infoHtml = source.infoHtml
        target.tocHtml = source.tocHtml
        target.downloadUrls = source.downloadUrls
    }

    /**
     * 章节内容
     */
    fun getContent(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
        semaphore: Semaphore? = null,
    ): Coroutine<String> {
        return Coroutine.async(
            scope,
            context,
            start = start,
            executeContext = executeContext,
            semaphore = semaphore
        ) {
            getContentAwait(bookSource, book, bookChapter, nextChapterUrl, needSave)
        }
    }

    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true
    ): String {
        val contentRule = bookSource.getContentRule()
        if (contentRule.content.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "⇒正文规则为空,使用章节链接:${bookChapter.url}")
            return bookChapter.url
        }
        if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            Debug.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析规则")
            return bookChapter.tag ?: ""
        }
        requireRustAnalyzer(bookSource)
        val content = RustAnalyzerBridge.content(bookSource, book, bookChapter, nextChapterUrl)
        if (!bookChapter.isVolume && content.isBlank()) {
            throw ContentEmptyException("内容为空")
        }
        if (needSave) {
            BookHelp.saveContent(bookSource, book, bookChapter, content)
        }
        return content
    }

    /**
     * 精准搜索
     */
    fun preciseSearch(
        scope: CoroutineScope,
        bookSourceParts: List<BookSourcePart>,
        name: String,
        author: String,
        context: CoroutineContext = Dispatchers.IO,
        semaphore: Semaphore? = null,
    ): Coroutine<Pair<Book, BookSource>> {
        return Coroutine.async(scope, context, semaphore = semaphore) {
            for (s in bookSourceParts) {
                val source = s.getBookSource()
                    ?: throw NoStackTraceException(
                        "Precise Rust search source is missing from database: ${s.bookSourceName} (${s.bookSourceUrl})"
                    )
                val result = preciseSearchAwait(source, name, author)
                result.onSuccess { book ->
                    return@async Pair(book, source)
                }
                val error = result.exceptionOrNull()
                if (error != null && !error.isPreciseSearchNoMatch(name, author)) {
                    throw NoStackTraceException(
                        "Precise Rust search failed for ${source.bookSourceName}: " +
                            (error.localizedMessage ?: error.toString())
                    )
                }
            }
            throw NoStackTraceException("没有搜索到<$name>$author")
        }
    }

    private fun Throwable.isPreciseSearchNoMatch(name: String, author: String): Boolean {
        return this is NoStackTraceException && message == "未搜索到 $name($author) 书籍"
    }

    suspend fun preciseSearchAwait(
        bookSource: BookSource,
        name: String,
        author: String,
    ): Result<Book> {
        return kotlin.runCatching {
            currentCoroutineContext().ensureActive()
            searchBookAwait(
                bookSource, name,
                filter = { fName, fAuthor, _ -> fName == name && fAuthor == author },
                shouldBreak = { it > 0 }
            ).firstOrNull()?.let { searchBook ->
                currentCoroutineContext().ensureActive()
                return@runCatching searchBook.toBook()
            }
            throw NoStackTraceException("未搜索到 $name($author) 书籍")
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }
    }

    private fun requireRustAnalyzer(bookSource: BookSource) {
        if (!RustAnalyzerBridge.isAvailable) {
            throw NoStackTraceException(
                "Rust analyzer is required for direct HTTP book source ${bookSource.bookSourceName}(${bookSource.bookSourceUrl})"
            )
        }
    }
}
