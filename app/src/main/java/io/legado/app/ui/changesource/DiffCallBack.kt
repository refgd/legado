package io.legado.app.ui.changesource

import android.os.Bundle
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.data.entities.SearchBook

class DiffCallBack(private val oldItems: List<SearchBook>, private val newItems: List<SearchBook>) :
    DiffUtil.Callback() {

    override fun getOldListSize(): Int {
        return oldItems.size
    }

    override fun getNewListSize(): Int {
        return newItems.size
    }

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldItems[oldItemPosition]
        val newItem = newItems[newItemPosition]
        return oldItem.bookUrl == newItem.bookUrl
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldItems[oldItemPosition]
        val newItem = newItems[newItemPosition]
        if (oldItem.originName != newItem.originName) {
            return false
        }
        if (oldItem.latestChapterTitle != newItem.latestChapterTitle) {
            return false
        }
        return true
    }

    override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
        val oldItem = oldItems[oldItemPosition]
        val newItem = newItems[newItemPosition]
        val payload = Bundle()
        if (oldItem.originName != newItem.originName) {
            payload.putString("name", newItem.originName)
        }
        if (oldItem.latestChapterTitle != newItem.latestChapterTitle) {
            payload.putString("latest", newItem.latestChapterTitle)
        }
        if (payload.isEmpty) {
            return null
        }
        return payload
    }

}