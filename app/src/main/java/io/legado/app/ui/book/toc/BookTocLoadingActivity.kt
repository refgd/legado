package io.legado.app.ui.book.toc

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityBookTocLoadingBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.webBook.isRustNetworkAccessError
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class BookTocLoadingActivity :
    VMBaseActivity<ActivityBookTocLoadingBinding, BookTocLoadingViewModel>() {

    override val binding by viewBinding(ActivityBookTocLoadingBinding::inflate)
    override val viewModel by viewModels<BookTocLoadingViewModel>()

    private val readBookResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.let {
            setResult(result.resultCode, it)
        } ?: setResult(result.resultCode)
        finish()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.loadBookToc(
            bookUrl = intent.getStringExtra("bookUrl"),
            name = intent.getStringExtra("name"),
            author = intent.getStringExtra("author")
        ) { result ->
            result
                .onSuccess { startReadActivity(it) }
                .onFailure { throw it }
        }
    }

    private fun startReadActivity(book: Book) {
        val cls = when {
            book.isVideo -> VideoPlayerActivity::class.java
            book.isAudio -> AudioPlayActivity::class.java
            !book.isLocal && book.isImage && AppConfig.showMangaUi -> ReadMangaActivity::class.java
            else -> ReadBookActivity::class.java
        }

        val sourceIntent = intent
        val readIntent = Intent(this, cls).apply {
            sourceIntent.extras?.let { putExtras(it) }

            putExtra("bookUrl", book.bookUrl)

            if (sourceIntent.hasExtra("inBookshelf")) {
                putExtra("inBookshelf", sourceIntent.getBooleanExtra("inBookshelf", false))
            } else {
                removeExtra("inBookshelf")
            }

            if (book.isAudio || book.isVideo) {
                removeExtra("chapterChanged")
            } else if (sourceIntent.hasExtra("chapterChanged")) {
                putExtra("chapterChanged", sourceIntent.getBooleanExtra("chapterChanged", false))
            } else {
                removeExtra("chapterChanged")
            }
        }

        readBookResult.launch(readIntent)
    }

    private fun openBookInfo(message: String?) {
        message?.let { toastOnUi(it) }

        if (isFromBookInfoActivity()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        startActivity(
            Intent(this, BookInfoActivity::class.java)
                .putExtra("name", intent.getStringExtra("name"))
                .putExtra("author", intent.getStringExtra("author"))
                .putExtra("bookUrl", intent.getStringExtra("bookUrl"))
        )
        finish()
    }

    private fun isFromBookInfoActivity(): Boolean {
        return intent.hasExtra("inBookshelf") || intent.hasExtra("chapterChanged")
    }

}

class BookTocLoadingViewModel(application: Application) : BaseViewModel(application) {

    fun loadBookToc(
        bookUrl: String?,
        name: String?,
        author: String?,
        success: (Result<Book>) -> Unit
    ) {
        execute {
            val book = findBook(bookUrl, name, author)
            if (appDb.bookChapterDao.getChapterCount(book.bookUrl) > 0) {
                return@execute book
            }
            if (book.isLocal) {
                loadLocalToc(book)
            } else {
                loadWebToc(book)
            }
        }.onSuccess {
            success(Result.success(it))
        }.onError {
            if (it.isRustNetworkAccessError()) {
                AppLog.put("BookTocLoading network load failed\n${it.localizedMessage}", it)
                success(Result.failure(it))
                return@onError
            }
            throw NoStackTraceException(
                "BookTocLoading Rust detail/toc failed: " +
                        (it.localizedMessage ?: it::class.java.name)
            )
        }
    }

    private fun findBook(bookUrl: String?, name: String?, author: String?): Book {
        bookUrl?.let {
            appDb.bookDao.getBook(it)?.let { book -> return book }
        }
        if (!name.isNullOrBlank()) {
            appDb.bookDao.getBook(name, author.orEmpty())?.let { return it }
        }
        throw NoStackTraceException("book is null")
    }

    private fun loadLocalToc(book: Book): Book {
        val toc = LocalBook.getChapterList(book)
        appDb.bookDao.update(book)
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*toc.toTypedArray())
        ReadBook.onChapterListUpdated(book)
        return book
    }

    private suspend fun loadWebToc(book: Book): Book {
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
            ?: throw NoStackTraceException(context.getString(R.string.error_no_source))
        val oldBook = book.copy()
        if (book.tocUrl.isEmpty()) {
            WebBook.getBookInfoAwait(bookSource, book)
        }
        if (book.isWebFile) {
            throw NoStackTraceException(context.getString(R.string.chapter_list_empty))
        }
        WebBook.getChapterListAwait(bookSource, book, runPerJs = true).onSuccess { toc ->
            val oldChapterList = appDb.bookChapterDao.getChapterList(oldBook.bookUrl)
            BookHelp.remapContentCache(oldBook, oldChapterList, toc)
            book.removeType(BookType.updateError)
            if (oldBook.bookUrl == book.bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
                BookHelp.updateCacheFolder(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(oldBook.bookUrl)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            ReadBook.onChapterListUpdated(book)
            return book
        }.onFailure {
            currentCoroutineContext().ensureActive()
            throw it
        }
        throw NoStackTraceException(context.getString(R.string.chapter_list_empty))
    }

}
