package io.legado.app.api.controller


import android.text.TextUtils
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject

object BookSourceController {

    val sources: ReturnData
        get() {
            val bookSources = appDb.bookSourceDao.all
            val returnData = ReturnData()
            return if (bookSources.isEmpty()) {
                returnData.setErrorMsg("设备源列表为空")
            } else returnData.setData(bookSources)
        }

    fun saveSource(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val bookSource = GSON.fromJsonObject<BookSource>(postData).getOrElse {
            return returnData.setErrorMsg(
                "BookSourceController.saveSource JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }
        if (TextUtils.isEmpty(bookSource.bookSourceName) || TextUtils.isEmpty(bookSource.bookSourceUrl)) {
            returnData.setErrorMsg("源名称和URL不能为空")
        } else {
            appDb.bookSourceDao.insert(bookSource)
            returnData.setData("")
        }
        return returnData
    }

    fun saveSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据为空")
        val okSources = arrayListOf<BookSource>()
        val bookSources = GSON.fromJsonArray<BookSource>(postData).getOrElse {
            return ReturnData().setErrorMsg(
                "BookSourceController.saveSources JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }
        if (bookSources.isEmpty()) {
            return ReturnData().setErrorMsg("源列表不能为空")
        }
        bookSources.forEachIndexed { index, bookSource ->
            if (bookSource.bookSourceName.isBlank() || bookSource.bookSourceUrl.isBlank()) {
                return ReturnData().setErrorMsg(
                    "BookSourceController.saveSources item $index is invalid for Rust analyzer handoff: source name and URL are required"
                )
            }
            appDb.bookSourceDao.insert(bookSource)
            okSources.add(bookSource)
        }
        return ReturnData().setData(okSources)
    }

    fun getSource(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (url.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定源地址")
        }
        val bookSource = appDb.bookSourceDao.getBookSource(url)
            ?: return returnData.setErrorMsg("未找到源，请检查书源地址")
        return returnData.setData(bookSource)
    }

    fun deleteSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val sources = GSON.fromJsonArray<BookSource>(postData).getOrElse {
            return ReturnData().setErrorMsg(
                "BookSourceController.deleteSources JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }
        SourceHelp.deleteBookSources(sources)
        return ReturnData().setData("已执行"/*okSources*/)
    }
}
