package io.legado.app.ui.book.read.page

import io.legado.app.service.help.ReadBook
import io.legado.app.ui.book.read.page.entities.TextPage

class TextPageFactory(dataSource: DataSource) : PageFactory<TextPage>(dataSource) {

    override fun hasPrev(): Boolean = with(dataSource) {
        return hasPrevChapter() || pageIndex > 0
    }

    override fun hasNext(): Boolean = with(dataSource) {
        return hasNextChapter() || currentChapter?.isLastIndex(pageIndex) != true
    }

    override fun moveToFirst() {
        ReadBook.setPageIndex(0)
    }

    override fun moveToLast() = with(dataSource) {
        currentChapter?.let {
            if (it.pageSize() == 0) {
                ReadBook.setPageIndex(0)
            } else {
                ReadBook.setPageIndex(it.pageSize().minus(1))
            }
        } ?: ReadBook.setPageIndex(0)
    }

    override fun moveToNext(): Boolean = with(dataSource) {
        return if (hasNext()) {
            if (currentChapter?.isLastIndex(pageIndex) == true) {
                ReadBook.moveToNextChapter(false)
            } else {
                ReadBook.setPageIndex(pageIndex.plus(1))
            }
            true
        } else
            false
    }

    override fun moveToPrev(): Boolean = with(dataSource) {
        return if (hasPrev()) {
            if (pageIndex <= 0) {
                ReadBook.moveToPrevChapter(false)
            } else {
                ReadBook.setPageIndex(pageIndex.minus(1))
            }
            true
        } else
            false
    }

    override val currentPage: TextPage
        get() = with(dataSource) {
            currentChapter?.let {
                return@with it.page(pageIndex)
                    ?: TextPage(title = it.title).format()
            }
            return TextPage().format()
        }

    override val nextPage: TextPage
        get() = with(dataSource) {
            currentChapter?.let {
                if (pageIndex < it.pageSize() - 1) {
                    return@with it.page(pageIndex + 1)?.removePageAloudSpan()
                        ?: TextPage(title = it.title).format()
                }
            }
            nextChapter?.let {
                return@with it.page(0)?.removePageAloudSpan()
                    ?: TextPage(title = it.title).format()
            }
            return TextPage().format()
        }

    override val prevPage: TextPage
        get() = with(dataSource) {
            if (pageIndex > 0) {
                currentChapter?.let {
                    return@with it.page(pageIndex - 1)?.removePageAloudSpan()
                        ?: TextPage(title = it.title).format()
                }
            }
            prevChapter?.let {
                return@with it.lastPage()?.removePageAloudSpan()
                    ?: TextPage(title = it.title).format()
            }
            return TextPage().format()
        }

    override val nextPagePlus: TextPage
        get() = with(dataSource) {
            currentChapter?.let {
                if (pageIndex < it.pageSize() - 2) {
                    return@with it.page(pageIndex + 2)?.removePageAloudSpan()
                        ?: TextPage(title = it.title).format()
                }
                nextChapter?.let { nc ->
                    if (pageIndex < it.pageSize() - 1) {
                        return@with nc.page(0)?.removePageAloudSpan()
                            ?: TextPage(title = nc.title).format()
                    }
                    return@with nc.page(1)?.removePageAloudSpan()
                        ?: TextPage(title = nc.title).format()
                }

            }
            return TextPage().format()
        }
}
