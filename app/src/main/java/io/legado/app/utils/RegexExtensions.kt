package io.legado.app.utils

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceBook
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.CrashHandler
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.webBook.RustAnalyzerBridge
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import splitties.init.appCtx
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val handler by lazy { buildMainHandler() }

/**
 * 带有超时检测的正则替换
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun CharSequence.replace(
    name: String,
    regex: Regex,
    replacement: String,
    timeout: Long,
    chapter: BookChapter? = null,
    book: ReplaceBook? = null
): String {
    val charSequence = this@replace
    val isJs = replacement.startsWith("@js:")
    val replacement1 = if (isJs) replacement.substring(4) else replacement
    return runBlocking {
        suspendCancellableCoroutine { block ->
            Coroutine.async(executeContext = IO) {
                val job = launch {
                    try {
                        val pattern = regex.toPattern()
                        val matcher = pattern.matcher(charSequence)
                        val stringBuffer = StringBuffer()
                        while (matcher.find()) {
                            if (isJs) {
                                val jsResult = RustAnalyzerBridge.evalJsRaw(
                                    script = replacement1,
                                    result = matcher.group(),
                                    rulePath = "RegexExtensions.replace",
                                    bindingsJson = regexBindingsJson(book, chapter)
                                )
                                val quotedResult = jsResult.quoteReplacementJs()
                                matcher.appendReplacement(stringBuffer, quotedResult)
                            } else {
                                matcher.appendReplacement(stringBuffer, replacement1)
                            }
                        }
                        matcher.appendTail(stringBuffer)
                        block.resume(stringBuffer.toString())
                    } catch (e: Exception) {
                        block.resumeWithException(e)
                    }
                }
                select {
                    job.onJoin {}
                    onTimeout(timeout) {
                        val timeoutMsg =
                            "替换超时,3秒后还未结束将重启应用\n规则名称:$name\n替换规则:$regex\n替换内容:$charSequence"
                        val exception = RegexTimeoutException(timeoutMsg)
                        block.cancel(exception)
                        appCtx.longToastOnUi(timeoutMsg)
                        CrashHandler.saveCrashInfo2File(exception)
                        select {
                            job.onJoin {}
                            onTimeout(3000) {
                                appCtx.restart()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun regexBindingsJson(book: ReplaceBook?, chapter: BookChapter?): String {
    val bindings = linkedMapOf<String, Any?>()
    if (book != null) bindings["book"] = book
    if (chapter != null) bindings["chapter"] = chapter
    return if (bindings.isEmpty()) "" else GSON.toJson(bindings)
}
