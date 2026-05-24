package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RustRemoteFetch
import io.legado.app.utils.externalCache
import splitties.init.appCtx

class OnLineImportViewModel(app: Application) : BaseAssociationViewModel(app) {

    fun getText(url: String, success: (text: String) -> Unit) {
        execute {
            RustRemoteFetch.text(url, "OnLineImportViewModel.getText")
        }.onSuccess {
            success.invoke(it)
        }.onError {
            errorLive.postValue(
                it.localizedMessage ?: context.getString(R.string.unknown_error)
            )
        }
    }

    fun getBytes(url: String, success: (bytes: ByteArray) -> Unit) {
        execute {
            RustRemoteFetch.bytes(url, "OnLineImportViewModel.getBytes").body
        }.onSuccess {
            success.invoke(it)
        }.onError {
            errorLive.postValue(
                it.localizedMessage ?: context.getString(R.string.unknown_error)
            )
        }
    }

    fun importReadConfig(bytes: ByteArray, finally: (title: String, msg: String) -> Unit) {
        execute {
            val config = ReadBookConfig.import(bytes)
            val baseName = config.name.ifBlank { "自定义" }
            val existingStyle = ReadBookConfig.allStyleConfigs()
                .firstOrNull { it.value.name.ifBlank { "自定义" } == baseName }
            when {
                existingStyle == null -> {
                    config.name = baseName
                    ReadBookConfig.configList.add(config)
                }
                ReadBookConfig.isBuiltInStyleIndex(existingStyle.index) -> {
                    config.name = uniqueReadStyleName(baseName)
                    ReadBookConfig.configList.add(config)
                }
                else -> {
                    config.name = baseName
                    ReadBookConfig.configList[ReadBookConfig.customIndex(existingStyle.index)] = config
                }
            }
            ReadBookConfig.save()
            config.name
        }.onSuccess {
            finally.invoke(context.getString(R.string.success), "导入排版成功")
        }.onError {
            finally.invoke(
                context.getString(R.string.error),
                it.localizedMessage ?: context.getString(R.string.unknown_error)
            )
        }
    }

    private fun uniqueReadStyleName(baseName: String): String {
        val names = ReadBookConfig.allStyleConfigs()
            .map { it.value.name.ifBlank { "自定义" } }
            .toHashSet()
        if (!names.contains(baseName)) {
            return baseName
        }
        var index = 1
        var name = "$baseName($index)"
        while (names.contains(name)) {
            index++
            name = "$baseName($index)"
        }
        return name
    }

    fun determineType(url: String, finally: (title: String, msg: String) -> Unit) {
        execute {
            val rs = RustRemoteFetch.bytes(url, "OnLineImportViewModel.determineType")
            when (rs.contentType?.substringBefore(';')?.trim()?.lowercase()) {
                "application/zip",
                "application/octet-stream" -> {
                    importReadConfig(rs.body, finally)
                }
                else -> {
                    val file = FileUtils.createFileIfNotExist(
                        appCtx.externalCache,
                        "download",
                        "scheme_import_cache.json"
                    )
                    file.outputStream().use { out ->
                        out.write(rs.body)
                    }
                    importJson(file.toUri())
                }
            }
        }
    }

}
