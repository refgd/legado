package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import androidx.core.view.setPadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.DialogLoginBinding
import io.legado.app.databinding.ItemSourceEditBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.applyUiToolbarTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.widget.RowUiForm
import io.legado.app.utils.GSON
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayoutWrapMaxHeight
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import kotlin.text.lastIndexOf
import kotlin.text.startsWith
import kotlin.text.substring
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatSpinner
import io.legado.app.data.entities.rule.RowUi.Type
import io.legado.app.ui.widget.text.TextInputLayout
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.indexOf
import io.legado.app.utils.setSelectionSafely

class SourceLoginDialog : BaseDialogFragment(R.layout.dialog_login, true),
    SourceLoginJsExtensions.Callback {

    private val binding by viewBinding(DialogLoginBinding::bind)
    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var lastClickTime: Long = 0
    private var oKToClose = false
    private var rowUis: List<RowUi>? = null
    private var rowUiName = arrayListOf<String>()
    private val rowUiActionRunnables = hashMapOf<String, Runnable>()
    private var hasChange = false
    private var loginUrl: String? = null
    private val sourceLoginJsExtensions by lazy {
        SourceLoginJsExtensions(
            activity as AppCompatActivity,
            viewModel.source,
            viewModel.bookType,
            this
        )
    }

    private var initHandler = false
    private val handler by lazy {
        initHandler = true
        buildMainHandler()
    }

    override fun upUiData(data: Map<String, Any?>?) {
        try {
            activity?.runOnUiThread { // 鍦ㄤ富绾跨▼涓洿鏂?UI
                handleUpUiData(data)
            }
        } catch (e: Exception) {
            AppLog.put("upLoginData Error: " + e.localizedMessage, e)
        }
    }

    override fun reUiView(deltaUp: Boolean) {
        activity?.runOnUiThread {
            handleReUiView(deltaUp)
        }
    }

    private fun handleReUiView(deltaUp: Boolean) {
        val source = viewModel.source ?: return
        val loginUiStr = source.loginUi ?: return
        val codeStr = loginUiStr.let {
            when {
                it.startsWith("@js:") -> it.substring(4)
                it.startsWith("<js>") -> it.substring(4, it.lastIndexOf("<"))
                else -> null
            }
        }
        if (codeStr != null) {
            hasChange = true
            lifecycleScope.launch(Main) {
                withContext(IO) {
                    val loginUiJson = evalUiJs(codeStr)
                    rowUis = loginUi(loginUiJson)
                }
                rowUiBuilderShared(source, rowUis, deltaUp)
            }
        } else {
            rowUis = loginUi(loginUiStr)
            rowUiBuilderShared(source, rowUis, deltaUp)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleUpUiData(data: Map<String, Any?>?) {
        hasChange = true
        if (data == null) {
            val newLoginInfo: MutableMap<String, String> = mutableMapOf()
            rowUis?.forEachIndexed { index, rowUi ->
                val default = rowUi.default
                when (val rowView = binding.root.findViewById<View>(index + 1000)) {
                    is TextInputLayout -> {
                        val value = default ?: ""
                        newLoginInfo[rowUi.name] = value
                        rowView.editText?.setText(value)
                    }

                    is TextView -> {
                        when (rowUi.type) {
                            Type.button -> {
                                rowView.text = rowUi.viewName ?: rowUi.name
                            }
                            Type.toggle -> {
                                val char = default ?: run{
                                    val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
                                    chars.getOrNull(0) ?: ""
                                }
                                newLoginInfo[rowUi.name] = char
                                val name =  rowUi.viewName ?: rowUi.name
                                val left = rowUi.style?.layout_justifySelf != "right"
                                rowView.text = if (left) char + name else name + char
                            }
                        }
                    }

                    is LinearLayout -> {
                        val chars = rowUi.chars?.filterNotNull() ?: listOf("chars","is null")
                        val index = chars.indexOf(default)
                        newLoginInfo[rowUi.name] = default ?: run{
                            chars.getOrNull(0) ?: ""
                        }
                        rowView.findViewById<AppCompatSpinner>(R.id.sp_type)?.setSelectionSafely(index)
                    }
                }
            }
            viewModel.loginInfo = newLoginInfo
            return
        }
        val loginInfo = viewModel.loginInfo
        data.forEach { (key, value) ->
            val value = value?.toString()
            val index = rowUiName.indexOf(key)
            if (index != -1) {
                val rowUi = rowUis?.getOrNull(index) ?: return@forEach
                val value = value ?: rowUi.default
                when (val rowView = binding.root.findViewById<View>(index + 1000)) {
                    is TextInputLayout -> {
                        val value = value ?: ""
                        loginInfo[rowUi.name] = value
                        rowView.editText?.setText(value)
                    }

                    is TextView -> {
                        when (rowUi.type) {
                            Type.button -> {
                                rowView.text = value ?: rowUi.viewName ?: key
                            }

                            Type.toggle -> {
                                val char = value ?: run{
                                    val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
                                    chars.getOrNull(0) ?: ""
                                }
                                loginInfo[rowUi.name] = char
                                val name =  rowUi.viewName ?: rowUi.name
                                val left = rowUi.style?.layout_justifySelf != "right"
                                rowView.text = if (left) char + name else name + char
                            }
                        }
                    }

                    is LinearLayout -> {
                        val items = rowUi.chars?.filterNotNull() ?: listOf("chars","is null")
                        val index = items.indexOf(value)
                        rowView.findViewById<AppCompatSpinner>(R.id.sp_type)?.setSelectionSafely(index)
                    }
                }
            } else {
                loginInfo[key] = value ?: ""
            }
        }
    }

    override fun onStart() {
        super.onStart()
        adjustLoginDialogSize()
    }

    suspend fun evalUiJs(jsStr: String): String? {
        val source = viewModel.source ?: return null
        val loginJS = loginUrl ?: ""
        val result = rowUis?.let {
            getLoginData(it)
        } ?: viewModel.loginInfo.toMutableMap()
        return evalLoginUiJsByRust(
            source = source,
            loginJs = loginJS,
            jsStr = jsStr,
            result = result,
            book = viewModel.book,
            chapter = viewModel.chapter
        )
    }

    fun loginUi(json: String?): List<RowUi>? {
        if (json.isNullOrBlank()) {
            return null
        }
        return GSON.fromJsonArray<RowUi>(json).getOrElse {
            throw NoStackTraceException(
                "SourceLoginDialog loginUi must return a JSON array: ${json.take(300)}"
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun rowUiBuilderShared(source: BaseSource, rowUis: List<RowUi>?, deltaUp: Boolean) {
        val rows = rowUis ?: emptyList()
        val loginInfo = viewModel.loginInfo
        rowUiName.clear()
        rowUiName.addAll(rows.map { it.name })
        RowUiForm.render(
            container = binding.flexbox,
            rows = rows,
            values = buildLoginUiValues(rows, loginInfo),
            callback = object : RowUiForm.Callback {
                override fun onValueChanged(rowUi: RowUi, value: String) {
                    hasChange = true
                    loginInfo[rowUi.name] = value
                    when {
                        rowUi.type == Type.select && rowUi.action != null -> {
                            handleButtonClick(source, rowUi.action, rowUi.name, false)
                        }
                        (rowUi.type == Type.text || rowUi.type == Type.password)
                            && rowUi.action != null -> {
                            rowUiActionRunnables.remove(rowUi.name)?.let {
                                handler.removeCallbacks(it)
                            }
                            val runnable = Runnable {
                                handleButtonClick(source, rowUi.action, rowUi.name, false)
                            }
                            rowUiActionRunnables[rowUi.name] = runnable
                            handler.postDelayed(runnable, 600)
                        }
                    }
                }

                override fun onAction(rowUi: RowUi, isLongClick: Boolean) {
                    handleButtonClick(source, rowUi.action, rowUi.name, isLongClick)
                }

                override fun resolveViewName(
                    rowUi: RowUi,
                    fallback: String,
                    apply: (String) -> Unit
                ) {
                    resolveLoginViewName(rowUi, fallback, apply)
                }
            }
        )
        adjustLoginDialogSize()
    }

    private fun adjustLoginDialogSize() {
        setLayoutWrapMaxHeight(panelView = binding.vwBg, scrollView = binding.scrollLogin)
    }

    private fun buildLoginUiValues(
        rows: List<RowUi>,
        loginInfo: MutableMap<String, String>
    ): Map<String, String> {
        val values = hashMapOf<String, String>()
        rows.forEach { rowUi ->
            val value = when (rowUi.type) {
                Type.select, Type.toggle -> {
                    val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
                    loginInfo[rowUi.name]?.takeIf { it.isNotEmpty() }
                        ?: rowUi.default
                        ?: chars.firstOrNull()
                        ?: ""
                }
                else -> loginInfo[rowUi.name] ?: rowUi.default.orEmpty()
            }
            if ((rowUi.type == Type.select || rowUi.type == Type.toggle)
                && loginInfo[rowUi.name] != value
            ) {
                hasChange = true
                loginInfo[rowUi.name] = value
            }
            values[rowUi.name] = value
        }
        return values
    }

    private fun resolveLoginViewName(
        rowUi: RowUi,
        fallback: String,
        apply: (String) -> Unit
    ) {
        val viewName = rowUi.viewName
        when {
            viewName == null -> apply(fallback)
            viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'' -> {
                val name = viewName.substring(1, viewName.length - 1)
                rowUi.viewName = name
                apply(name)
            }
            else -> {
                apply(fallback)
                execute {
                    evalUiJs(viewName)
                }.onSuccess { name ->
                    val resolvedName = name?.takeIf { it.isNotEmpty() } ?: "null"
                    rowUi.viewName = resolvedName
                    apply(resolvedName)
                }.onError { _ ->
                    apply("err")
                }
            }
        }
    }

    private fun buttonUi(source: BaseSource, rowUis: List<RowUi>?) {
        rowUiBuilderShared(source, rowUis, false)
        binding.toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_ok -> {
                    oKToClose = true
                    login(source)
                }

                R.id.menu_show_login_header -> alert {
                    setTitle(R.string.login_header)
                    source.getLoginHeader()?.let { loginHeader ->
                        setMessage(loginHeader)
                        positiveButton(R.string.copy_text) {
                            appCtx.sendToClip(loginHeader)
                        }
                    }
                }

                R.id.menu_del_login_header -> source.removeLoginHeader()
                R.id.menu_log -> showDialogFragment<AppLogDialog>()
            }
            return@setOnMenuItemClickListener true
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setOnTouchListener { _, event ->
            val isOutsidePanel = !event.isInsideRoundedPanel(binding.vwBg)
            if (isOutsidePanel && event.action == MotionEvent.ACTION_UP) {
                dismiss()
            }
            isOutsidePanel
        }
        binding.vwBg.setOnClickListener { }
        val source = viewModel.source ?: return
        loginUrl = source.getLoginJs()
        val loginUiStr = source.loginUi ?: return
        val codeStr = loginUiStr.let {
            when {
                it.startsWith("@js:") -> it.substring(4)
                it.startsWith("<js>") -> it.substring(4, it.lastIndexOf("<"))
                else -> null
            }
        }
        if (codeStr != null) {
            lifecycleScope.launch(Main) {
                withContext(IO) {
                    val loginUiJson = evalUiJs(codeStr)
                    rowUis = loginUi(loginUiJson)
                }
                buttonUi(source, rowUis)
            }
        } else {
            rowUis = loginUi(loginUiStr)
            buttonUi(source, rowUis)
        }
        binding.toolBar.setBackgroundColor(requireContext().primaryColor)
        binding.toolBar.setTitleTextColor(primaryTextColor)
        binding.toolBar.title = getString(R.string.login_source, source.getTag())
        binding.toolBar.applyUiToolbarTypeface(requireContext())
        binding.toolBar.inflateMenu(R.menu.source_login)
        binding.toolBar.menu.applyUiMenuStyle(requireContext())
    }

    private fun MotionEvent.isInsideRoundedPanel(panel: View): Boolean {
        val location = IntArray(2)
        panel.getLocationOnScreen(location)
        val localX = rawX - location[0]
        val localY = rawY - location[1]
        val width = panel.width.toFloat()
        val height = panel.height.toFloat()
        if (localX < 0f || localY < 0f || localX > width || localY > height) {
            return false
        }
        val radius = resources.getDimension(R.dimen.ui_panel_radius)
            .coerceAtMost(width / 2f)
            .coerceAtMost(height / 2f)
        val centerX = when {
            localX < radius -> radius
            localX > width - radius -> width - radius
            else -> localX
        }
        val centerY = when {
            localY < radius -> radius
            localY > height - radius -> height - radius
            else -> localY
        }
        val dx = localX - centerX
        val dy = localY - centerY
        return dx * dx + dy * dy <= radius * radius
    }

    private fun handleButtonClick(source: BaseSource, action: String?, name: String, isLongClick: Boolean) {
        lifecycleScope.launch(IO) {
            if (action.isAbsUrl()) {
                context?.openUrl(action!!)
            } else if (action != null) {
                // JavaScript
                val buttonFunctionJS = action
                val loginJS = loginUrl ?: return@launch
                kotlin.runCatching {
                    evalLoginButtonClickByRust(
                        source = source,
                        loginJs = loginJS,
                        buttonFunctionJs = buttonFunctionJS,
                        java = sourceLoginJsExtensions,
                        result = getLoginData(rowUis),
                        book = viewModel.book,
                        chapter = viewModel.chapter,
                        isLongClick = isLongClick
                    )
                }.onSuccess {
                    RustPlatformAction.handle(it, sourceLoginJsExtensions)
                }.onFailure { e ->
                    ensureActive()
                    throw NoStackTraceException("LoginUI Button $name Rust JavaScript failed: ${e.localizedMessage ?: e}")
                }
            }
        }
    }

    private fun getLoginData(rowUis: List<RowUi>?): MutableMap<String, String> {
        val loginData = hashMapOf<String, String>()
        rowUis?.forEachIndexed { index, rowUi ->
            when (rowUi.type) {
                Type.text, Type.password -> {
                    val rowView = binding.root.findViewById<View>(index + 1000)
                    ItemSourceEditBinding.bind(rowView).editText.text.let {
                        loginData[rowUi.name] = it?.toString() ?: rowUi.default ?: "" //没文本的时候存空字符串,而不是删除loginInfo
                    }
                }
            }
        }
        return viewModel.loginInfo.toMutableMap().apply { putAll(loginData) }
    }

    private fun login(source: BaseSource) {
        lifecycleScope.launch(IO) {
            val loginData = getLoginData(rowUis)
            if (loginData.isEmpty()) {
                source.removeLoginInfo()
                withContext(Main) {
                    dismiss()
                }
            } else if (source.putLoginInfo(GSON.toJson(loginData))) {
                try {
                    val buttonFunctionJS = "if (typeof login=='function'){ login.apply(this); } else { throw('Function login not implements!!!') }"
                    val loginJS = loginUrl ?: return@launch
                    evalLoginButtonClickByRust(
                        source = source,
                        loginJs = loginJS,
                        buttonFunctionJs = buttonFunctionJS,
                        java = sourceLoginJsExtensions,
                        result = loginData,
                        book = viewModel.book,
                        chapter = viewModel.chapter,
                        isLongClick = false
                    ).also { RustPlatformAction.handle(it, sourceLoginJsExtensions) }
                    context?.toastOnUi(R.string.success)
                    withContext(Main) {
                        dismiss()
                    }
                } catch (e: Exception) {
                    throw NoStackTraceException("SourceLoginDialog login Rust JavaScript failed: ${e.localizedMessage ?: e}")
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!oKToClose && hasChange) {
            val loginInfo = viewModel.loginInfo
            if (loginInfo.isEmpty()) {
                viewModel.source?.removeLoginInfo()
            } else {
                viewModel.source?.putLoginInfo(GSON.toJson(loginInfo))
            }
        }
        if (initHandler) {
            handler.removeCallbacksAndMessages(null)
            rowUiActionRunnables.clear()
        }
        super.onDismiss(dialog)
        activity?.finish()
    }

}

fun evalLoginUiJsByRust(
    source: BaseSource,
    loginJs: String,
    jsStr: String,
    result: Map<String, String>,
    book: Book?,
    chapter: BookChapter?
): String {
    return source.evalJS("$loginJs\n$jsStr") {
        put("result", result)
        put("book", book)
        put("chapter", chapter)
    }.toString()
}

fun evalLoginButtonClickByRust(
    source: BaseSource,
    loginJs: String,
    buttonFunctionJs: String,
    java: SourceLoginJsExtensions,
    result: Map<String, String>,
    book: Book?,
    chapter: BookChapter?,
    isLongClick: Boolean
): Any? {
    return source.evalJS("$loginJs\n$buttonFunctionJs") {
        put("java", java)
        put("result", result)
        put("book", book)
        put("chapter", chapter)
        put("isLongClick", isLongClick)
    }
}
