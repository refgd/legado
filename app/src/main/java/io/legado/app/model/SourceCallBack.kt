package io.legado.app.model

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.isTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.String

object SourceCallBack {
    const val CLICK_AUTHOR = "clickAuthor"
    const val LONG_CLICK_AUTHOR = "longClickAuthor"
    const val CLICK_BOOK_NAME = "clickBookName"
    const val LONG_CLICK_BOOK_NAME = "longClickBookName"
    const val CLICK_CUSTOM_BUTTON = "clickCustomButton"
    const val LONG_CLICK_CUSTOM_BUTTON = "longClickCustomButton"
    const val CLICK_SHARE_BOOK = "clickShareBook"
    const val CLICK_CLEAR_CACHE = "clickClearCache"
    const val CLICK_COPY_BOOK_URL = "clickCopyBookUrl"
    const val CLICK_COPY_TOC_URL = "clickCopyTocUrl"
    const val CLICK_COPY_PLAY_URL = "clickCopyPlayUrl"
    const val CLICK_BOOK_LABEL = "clickBookLabel"
    const val LONG_CLICK_BOOK_LABEL = "longClickBookLabel"

    const val ADD_BOOK_SHELF = "addBookShelf"
    const val DEL_BOOK_SHELF = "delBookShelf"
    const val SAVE_READ = "saveRead"
    const val START_READ = "startRead"
    const val END_READ = "endRead"
    const val START_SHELF_REFRESH = "startShelfRefresh"
    const val END_SHELF_REFRESH = "endShelfRefresh"
    fun callBackBtn(
        activity: AppCompatActivity,
        event: String,
        source: BookSource?,
        book: Book,
        chapter: BookChapter?,
        bookType: Int = 0,
        result: String? = null,
        noCall: (() -> Unit)? = null
    ) {
        if (source == null || !source.eventListener) {
            noCall?.invoke()
            return
        }
        val jsStr = source.getContentRule().callBackJs
        if (jsStr.isNullOrEmpty()) {
            noCall?.invoke()
            return
        }
        activity.lifecycleScope.launch(IO) {
            val java = SourceLoginJsExtensions(activity, source,  bookType)
            try {
                val callbackResult = source.evalJS(jsStr) {
                    put("event", event)
                    put("java", java)
                    put("result", result)
                    put("book", book)
                    put("chapter", chapter)
                }.toString()
                if (!callbackResult.isTrue()) {
                    withContext(Dispatchers.Main) {
                        noCall?.invoke()
                    }
                }
            } catch (error: Throwable) {
                throw callbackError(source, event, error)
            }
        }
    }

    fun callBackBook(
        event: String,
        source: BookSource?,
        book: Book?,
        chapter: BookChapter? = null,
        result: String? = null
    ) {
        if (source == null || book == null || !source.eventListener) return
        val jsStr = source.getContentRule().callBackJs
        if (jsStr.isNullOrEmpty()) return
        Coroutine.async {
            withTimeout(60000L) {
                source.evalJS(jsStr) {
                    put("event", event)
                    put("result", result)
                    put("book", book)
                    put("chapter", chapter)
                }
            }
        }.onError {
            throw callbackError(source, event, it)
        }
    }

    fun callBackSource(scope: CoroutineScope, event: String, source: BookSource) {
        val jsStr = source.getContentRule().callBackJs
        if (jsStr.isNullOrEmpty()) return
        scope.launch(IO) {
            try {
                withTimeout(30000L) {
                    source.evalJS(jsStr) {
                        put("event", event)
                        put("result", null)
                        put("book", null)
                        put("chapter", null)
                    }
                }
            } catch (error: Throwable) {
                throw callbackError(source, event, error)
            }
        }
    }

    private fun callbackError(source: BookSource, event: String, error: Throwable): NoStackTraceException {
        return NoStackTraceException(
            "${source.bookSourceName} Rust callback JS failed for event $event: ${error.localizedMessage ?: error}"
        )
    }

}
