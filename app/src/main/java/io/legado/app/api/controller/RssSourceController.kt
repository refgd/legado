package io.legado.app.api.controller


import android.text.TextUtils
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject

object RssSourceController {

    val sources: ReturnData
        get() {
            val source = appDb.rssSourceDao.all
            val returnData = ReturnData()
            return if (source.isEmpty()) {
                returnData.setErrorMsg("源列表为空")
            } else returnData.setData(source)
        }

    fun saveSource(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        GSON.fromJsonObject<RssSource>(postData).onFailure {
            returnData.setErrorMsg(
                "RssSourceController.saveSource JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }.onSuccess { source ->
            if (TextUtils.isEmpty(source.sourceName) || TextUtils.isEmpty(source.sourceUrl)) {
                returnData.setErrorMsg("源名称和URL不能为空")
            } else {
                appDb.rssSourceDao.insert(source)
                returnData.setData("")
            }
        }
        return returnData
    }

    fun saveSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val okSources = arrayListOf<RssSource>()
        val source = GSON.fromJsonArray<RssSource>(postData).getOrElse {
            return ReturnData().setErrorMsg(
                "RssSourceController.saveSources JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }
        if (source.isEmpty()) {
            return ReturnData().setErrorMsg("源列表不能为空")
        }
        for ((index, rssSource) in source.withIndex()) {
            if (rssSource.sourceName.isBlank() || rssSource.sourceUrl.isBlank()) {
                return ReturnData().setErrorMsg(
                    "RssSourceController.saveSources item $index is invalid for Rust analyzer handoff: source name and URL are required"
                )
            }
            appDb.rssSourceDao.insert(rssSource)
            okSources.add(rssSource)
        }
        return ReturnData().setData(okSources)
    }

    fun getSource(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (url.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定书源地址")
        }
        val source = appDb.rssSourceDao.getByKey(url)
            ?: return returnData.setErrorMsg("未找到源，请检查源地址")
        return returnData.setData(source)
    }

    fun deleteSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val sources = GSON.fromJsonArray<RssSource>(postData).getOrElse {
            return ReturnData().setErrorMsg(
                "RssSourceController.deleteSources JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }
        SourceHelp.deleteRssSources(sources)
        return ReturnData().setData("已执行"/*okSources*/)
    }
}
