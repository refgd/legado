package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.get
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogReadBookStyleBinding
import io.legado.app.databinding.ItemBgImageBinding
import io.legado.app.databinding.ItemReadStyleBinding
import io.legado.app.databinding.ItemRestoreReadStyleBinding
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.ColorPreference.ColorPickerDialogCompat
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.BG_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.READ_MENU_BG_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_ACCENT_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_COLOR
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.widget.DetailSeekBar
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.delete
import io.legado.app.utils.dpToPx
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.hexString
import io.legado.app.utils.inputStream
import io.legado.app.utils.longToast
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readBytes
import io.legado.app.utils.readUri
import io.legado.app.utils.RustRemoteFetch
import io.legado.app.utils.setSelectionSafely
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ReadStyleDialog : BaseDialogFragment(R.layout.dialog_read_book_style),
    FontSelectDialog.CallBack {

    private val binding by viewBinding(DialogReadBookStyleBinding::bind)
    private val callBack get() = activity as? ReadBookActivity
    private val configFileName = "readConfig.zip"
    private var bgSelectDialog: androidx.appcompat.app.AlertDialog? = null
    private var primaryTextColor = 0
    private var secondaryTextColor = 0
    private var currentStyleTab = StyleTab.TEXT
    private var firstStyleTabHeight = 0
    private val importFormNet = "网络导入"
    private val draftStyleIndex = -1
    private var savedConfigSnapshot = ""
    private var pendingExportConfig: ReadBookConfig.Config? = null
    private var reloadStyleManager: (() -> Unit)? = null
    private val selectBgImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> setBgFromUri(uri) }
    }
    private val selectExportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            exportConfig(uri, pendingExportConfig ?: ReadBookConfig.durConfig)
        }
        pendingExportConfig = null
    }
    private val selectImportDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFormNet") {
                importNetConfigAlert()
            } else {
                importConfig(uri)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            (activity as? ReadBookActivity)?.postReadAloudFloatingAvoidanceForView(
                EventBus.FLOATING_AVOID_SOURCE_READ_STYLE_DIALOG,
                binding.rootView
            )
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        initView()
        initData()
        initViewEvent()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
        (activity as? ReadBookActivity)?.clearReadAloudFloatingAvoidance(
            EventBus.FLOATING_AVOID_SOURCE_READ_STYLE_DIALOG
        )
    }

    override fun onResume() {
        super.onResume()
        upView()
    }

    private fun initView() = binding.run {
        rootView.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        llTextGroup.background = null
        panelPageAnim.background = null
        panelColorBackground.background = null
        updateDialogStyle()
        dsbTextSize.valueFormat = {
            (it + 5).toString()
        }
        dsbTextLetterSpacing.valueFormat = {
            ((it - 50) / 100f).toString()
        }
        dsbLineSize.valueFormat = { ((it - 10) / 10f).toString() }
        dsbParagraphSpacing.valueFormat = { (it / 10f).toString() }
        dsbTextShadow.valueFormat = {
            percentValue(it)
        }
        rowBgImage.setOnClickListener { showBgImageSelector() }
        lockHeightToFirstStyleTab()
    }

    private fun initData() {
        binding.cbShareLayout.isChecked = ReadBookConfig.shareLayout
        upView()
        savedConfigSnapshot = savedStyleSnapshot()
    }

    private fun initViewEvent() = binding.run {
        observeEvent<Boolean>(EventBus.UPDATE_READ_ACTION_BAR) {
            upView()
        }
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) {
            if (it.any { value -> value == 1 || value == 2 }) {
                upView()
            }
        }
        btnTabText.setOnClickListener { showStyleTab(StyleTab.TEXT) }
        btnTabPage.setOnClickListener { showStyleTab(StyleTab.PAGE) }
        btnTabStyle.setOnClickListener { showStyleTab(StyleTab.STYLE) }

        ivEdit.setOnClickListener {
            editDraftStyleName()
        }
        tvRestore.setOnClickListener { showStyleManager() }

        rowTextColor.setOnClickListener {
            ColorPickerDialog.newBuilder()
                .setColor(ReadBookConfig.durConfig.curTextColor())
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(TEXT_COLOR)
                .show(requireActivity())
        }
        rowTextAccentColor.setOnClickListener {
            ColorPickerDialog.newBuilder()
                .setColor(ReadBookConfig.durConfig.curTextAccentColor())
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(TEXT_ACCENT_COLOR)
                .show(requireActivity())
        }
        rowBgColor.setOnClickListener {
            val bgColor = currentBgColorForPicker()
            ColorPickerDialog.newBuilder()
                .setColor(bgColor)
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(BG_COLOR)
                .show(requireActivity())
        }
        rowMenuBgColor.setOnClickListener {
            ColorPickerDialogCompat.newBuilder()
                .setColor(ReadBookConfig.durConfig.curReadMenuBgColor() ?: defaultReadMenuBgColor())
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(READ_MENU_BG_COLOR)
                .setShowDefaultColorButton(true)
                .show(requireActivity())
        }
        rowReadMenuAlpha.setOnClickListener {
            NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.read_menu_alpha))
                .setMaxValue(100)
                .setMinValue(35)
                .setValue(ReadBookConfig.durConfig.readMenuAlpha.coerceIn(35, 100))
                .setCustomButton(R.string.btn_default_s) {
                    ReadBookConfig.durConfig.readMenuAlpha = 100
                    upView()
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
                .show {
                    ReadBookConfig.durConfig.readMenuAlpha = it.coerceIn(35, 100)
                    upView()
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
        }
        rowBgAlpha.setOnClickListener {
            NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.bg_alpha))
                .setMaxValue(100)
                .setMinValue(0)
                .setValue(ReadBookConfig.bgAlpha.coerceIn(0, 100))
                .show {
                    ReadBookConfig.bgAlpha = it.coerceIn(0, 100)
                    updateBgAlphaRow()
                    postEvent(EventBus.UP_CONFIG, arrayListOf(3))
                }
        }
        btnThemeLight.setOnClickListener { switchReadThemeMode(StyleThemeMode.LIGHT) }
        btnThemeDark.setOnClickListener { switchReadThemeMode(StyleThemeMode.DARK) }
        btnThemeEink.setOnClickListener { switchReadThemeMode(StyleThemeMode.EINK) }
        chineseConverter.onChanged {
            ChineseUtils.unLoad(*TransType.entries.toTypedArray())
            updateTextRows()
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
        }
        textFontWeightConverter.onChanged {
            updateTextRows()
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 6))
        }
        rowTextFontWeight.setOnClickListener {
            textFontWeightConverter.performClick()
        }
        rowChineseConverter.setOnClickListener {
            chineseConverter.performClick()
        }
        rowTextFont.setOnClickListener {
            showDialogFragment<FontSelectDialog>()
        }
        tvTextFont.setOnClickListener {
            rowTextFont.performClick()
        }
        rowTextIndent.setOnClickListener {
            context?.selector(
                title = getString(R.string.text_indent),
                items = resources.getStringArray(R.array.indent).toList()
            ) { _, index ->
                ReadBookConfig.paragraphIndent = "　".repeat(index)
                updateTextRows()
                postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
            }
        }
        tvTextIndent.setOnClickListener {
            rowTextIndent.performClick()
        }
        rowTextUnderline.setOnClickListener {
            if (ReadBook.book?.isImage == true) {
                return@setOnClickListener
            }
            context?.selector(
                title = getString(R.string.text_underline),
                items = underlineModeNames()
            ) { _, index ->
                ReadBookConfig.durConfig.underlineMode = index
                updateFontExtraRows()
                postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
            }
        }
        tvTextUnderlineValue.setOnClickListener {
            rowTextUnderline.performClick()
        }
        val updateTextShadow: (Int) -> Unit = {
            ReadBookConfig.paperInkStrength = it.coerceIn(0, 100)
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 9, 6))
        }
        dsbTextShadow.onChanging = updateTextShadow
        dsbTextShadow.onChanged = updateTextShadow
        rowDarkStatusIcon.setOnClickListener {
            val isDark = !ReadBookConfig.durConfig.curStatusIconDark()
            ReadBookConfig.durConfig.setCurStatusIconDark(isDark)
            updateMoreRows()
            callBack?.upSystemUiVisibility()
        }
        tvDarkStatusIconValue.setOnClickListener {
            rowDarkStatusIcon.performClick()
        }
        rowScrollFollowBg.setOnClickListener {
            val isFollow = !ReadBookConfig.durConfig.curReadScrollFollowBackground()
            ReadBookConfig.durConfig.setCurReadScrollFollowBackground(isFollow)
            updateMoreRows()
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 5))
        }
        tvScrollFollowBgValue.setOnClickListener {
            rowScrollFollowBg.performClick()
        }
        rowPadding.setOnClickListener {
            dismissAllowingStateLoss()
            callBack?.showPaddingConfig()
        }
        tvPadding.setOnClickListener {
            rowPadding.performClick()
        }
        rowTip.setOnClickListener {
            TipConfigDialog().show(childFragmentManager, "tipConfigDialog")
        }
        tvTip.setOnClickListener {
            rowTip.performClick()
        }
        pageAnimButtons().forEach { button ->
            button.setOnClickListener {
                val checkedId = button.id
                checkPageAnim(checkedId)
                ReadBook.book?.setPageAnim(-1)
                ReadBookConfig.pageAnim = pageAnimById(checkedId)
                callBack?.upPageAnim()
                ReadBook.loadContent(false)
            }
        }
        cbShareLayout.onCheckedChangeListener = { _, isChecked ->
            ReadBookConfig.shareLayout = isChecked
            upView()
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        }
        dsbTextSize.onChanged = {
            ReadBookConfig.textSize = it + 5
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbTextLetterSpacing.onChanged = {
            ReadBookConfig.letterSpacing = (it - 50) / 100f
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbLineSize.onChanged = {
            ReadBookConfig.lineSpacingExtra = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbParagraphSpacing.onChanged = {
            ReadBookConfig.paragraphSpacing = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
    }

    private fun showStyleTab(tab: StyleTab, requestLayout: Boolean = true) = binding.run {
        currentStyleTab = tab
        llTextGroup.visibility = if (tab == StyleTab.TEXT) View.VISIBLE else View.GONE
        panelPageAnim.visibility = if (tab == StyleTab.PAGE) View.VISIBLE else View.GONE
        panelColorBackground.visibility = if (tab == StyleTab.STYLE) View.VISIBLE else View.GONE
        val bg = ReadBookConfig.durConfig.curReadMenuBgColor() ?: defaultReadMenuBgColor()
        val palette = ReaderSheetStyle.resolve(requireContext(), bg)
        val isLight = ColorUtils.isColorLight(bg)
        val menuOpacity = (ReadBookConfig.durConfig.readMenuAlpha / 100f).coerceIn(0.35f, 1f)
        val selectedBackground = ColorUtils.blendColors(
            palette.surface,
            palette.primaryColor,
            if (isLight) 0.26f else 0.2f
        )
        listOf(
            btnTabText to StyleTab.TEXT,
            btnTabPage to StyleTab.PAGE,
            btnTabStyle to StyleTab.STYLE
        ).forEach { (tabView, itemTab) ->
            tabView.isSelected = itemTab == tab
            tabView.background = if (itemTab == tab) {
                UiCorner.opaqueRounded(
                    ColorUtils.withAlpha(selectedBackground, menuOpacity),
                    UiCorner.actionRadius(requireContext())
                )
            } else {
                ColorDrawable(Color.TRANSPARENT)
            }
        }
        if (requestLayout) {
            rootView.requestLayout()
        }
    }

    private fun lockHeightToFirstStyleTab() = binding.run {
        rootView.post {
            if (firstStyleTabHeight > 0 || currentStyleTab != StyleTab.TEXT || rootView.height <= 0) {
                return@post
            }
            firstStyleTabHeight = rootView.height
            rootView.layoutParams = rootView.layoutParams.apply {
                height = firstStyleTabHeight
            }
        }
    }

    private fun changeBgTextConfig(index: Int) {
        if (!ReadBookConfig.isValidStyleIndex(index)) {
            return
        }
        if (index == ReadBookConfig.styleSelect && !isUnsavedCurrentStyle()) {
            return
        }
        ReadBookConfig.setActiveConfig(ReadBookConfig.getConfig(index).copy(), index)
        savedConfigSnapshot = savedStyleSnapshot()
        upView()
        postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        if (AppConfig.readBarStyleFollowPage) {
            postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
        }
    }

    private fun restoreStyleToCurrent(index: Int) {
        if (!ReadBookConfig.isValidStyleIndex(index)) {
            return
        }
        val restoredConfig = ReadBookConfig.getConfig(index).copy()
        ReadBookConfig.setActiveConfig(restoredConfig, index)
        savedConfigSnapshot = savedStyleSnapshot()
        upView()
        postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
    }

    private fun upView() = binding.run {
        updateDialogStyle()
        textFontWeightConverter.upUi(ReadBookConfig.textBold)
        ReadBook.pageAnim().let {
            checkPageAnim(pageAnimIdByValue(it))
        }
        ReadBookConfig.let {
            binding.tvName.text = ReadBookConfig.durConfig.name.ifBlank { "自定义" }
            dsbTextSize.progress = it.textSize - 5
            dsbTextLetterSpacing.progress = (it.letterSpacing * 100).toInt() + 50
            dsbLineSize.progress = it.lineSpacingExtra
            dsbParagraphSpacing.progress = it.paragraphSpacing
        }
        updateTextRows()
        updateColorRows()
        updateFontExtraRows()
        updateMoreRows()
        updateThemeModeTabs()
    }

    private fun updateDialogStyle() = binding.run {
        val bg = ReadBookConfig.durConfig.curReadMenuBgColor() ?: defaultReadMenuBgColor()
        val palette = ReaderSheetStyle.resolve(requireContext(), bg)
        val menuOpacity = (ReadBookConfig.durConfig.readMenuAlpha / 100f).coerceIn(0.35f, 1f)
        primaryTextColor = palette.textColor
        secondaryTextColor = palette.secondaryTextColor
        rootView.background = ReaderSheetStyle.topSheetDrawable(
            palette.copy(surface = ColorUtils.withAlpha(bg, menuOpacity))
        )

        val isLight = ColorUtils.isColorLight(bg)
        val tabBg = ColorUtils.blendColors(
            bg,
            palette.primaryColor,
            if (isLight) 0.16f else 0.16f
        )
        tabEditBar.background = UiCorner.opaqueRounded(
            ColorUtils.withAlpha(tabBg, menuOpacity),
            UiCorner.panelRadius(requireContext())
        )
        showStyleTab(currentStyleTab, requestLayout = false)

        ivEdit.setColorFilter(secondaryTextColor, PorterDuff.Mode.SRC_IN)
        tvRestore.background = UiCorner.opaqueRoundedStroke(
            Color.TRANSPARENT,
            UiCorner.actionRadius(requireContext()),
            1.dpToPx(),
            ColorUtils.withAlpha(primaryTextColor, 0.45f)
        )
        applyDialogTextColors()
    }

    private fun applyDialogTextColors() = binding.run {
        applyTextColorDeep(rootView)
        listOf(
            tvName,
            tvTextFont,
            tvTextIndent,
            tvTextUnderlineValue,
            tvTextColorValue,
            tvBgColorValue,
            tvTextAccentColorValue,
            tvMenuBgColorValue,
            tvReadMenuAlphaValue,
            tvBgAlphaValue,
            tvDarkStatusIconValue,
            tvScrollFollowBgValue,
            tvPadding,
            tvTip
        ).forEach {
            it.setTextColor(secondaryTextColor)
        }
        listOf(
            dsbTextSize,
            dsbTextLetterSpacing,
            dsbLineSize,
            dsbParagraphSpacing,
            dsbTextShadow
        ).forEach {
            applyDetailSeekBarColors(it)
        }
    }

    private fun applyTextColorDeep(view: View) {
        if (view is TextView) {
            view.setTextColor(primaryTextColor)
        }
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                applyTextColorDeep(view.getChildAt(index))
            }
        }
    }

    private fun applyDetailSeekBarColors(seekBar: DetailSeekBar) {
        fun apply(view: View) {
            when (view) {
                is TextView -> view.setTextColor(primaryTextColor)
                is ImageView -> view.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
            }
            if (view is ViewGroup) {
                repeat(view.childCount) { index ->
                    apply(view.getChildAt(index))
                }
            }
        }
        apply(seekBar)
    }

    private fun updateTextRows() = binding.run {
        textFontWeightConverter.upUi(ReadBookConfig.textBold)
        tvTextFont.text = ReadBookConfig.textFont
            .takeIf { it.isNotBlank() }
            ?.let { FileDoc.fromFile(it).name }
            ?: getString(R.string.btn_default_s)
        val indentItems = resources.getStringArray(R.array.indent)
        tvTextIndent.text = indentItems.getOrNull(ReadBookConfig.paragraphIndent.length)
            ?: ReadBookConfig.paragraphIndent.length.toString()
        val config = ReadBookConfig.config
        tvPadding.text = getString(R.string.setting)
        tvTip.text = getString(R.string.setting)
    }

    private fun updateFontExtraRows() = binding.run {
        rowTextUnderline.visibility = if (ReadBook.book?.isImage == true) {
            View.GONE
        } else {
            View.VISIBLE
        }
        tvTextUnderlineValue.text = underlineModeNames()
            .getOrNull(ReadBookConfig.durConfig.underlineMode)
            ?: getString(R.string.jf_convert_o)
        dsbTextShadow.progress = ReadBookConfig.paperInkStrength.coerceIn(0, 100)
    }

    private fun updateMoreRows() = binding.run {
        tvDarkStatusIconValue.text = switchValue(ReadBookConfig.durConfig.curStatusIconDark())
        tvScrollFollowBgValue.text = switchValue(
            ReadBookConfig.durConfig.curReadScrollFollowBackground()
        )
    }

    private fun updateThemeModeTabs() = binding.run {
        val bg = ReadBookConfig.durConfig.curReadMenuBgColor() ?: defaultReadMenuBgColor()
        val palette = ReaderSheetStyle.resolve(requireContext(), bg)
        val isLight = ColorUtils.isColorLight(bg)
        val selectedBackground = ColorUtils.blendColors(
            palette.surface,
            palette.primaryColor,
            if (isLight) 0.26f else 0.2f
        )
        val menuOpacity = (ReadBookConfig.durConfig.readMenuAlpha / 100f).coerceIn(0.35f, 1f)
        themeModeEditBar.background = UiCorner.opaqueRounded(
            ColorUtils.withAlpha(
                ColorUtils.blendColors(bg, palette.primaryColor, if (isLight) 0.16f else 0.16f),
                menuOpacity
            ),
            UiCorner.panelRadius(requireContext())
        )
        listOf(
            btnThemeLight to StyleThemeMode.LIGHT,
            btnThemeDark to StyleThemeMode.DARK,
            btnThemeEink to StyleThemeMode.EINK
        ).forEach { (tabView, mode) ->
            tabView.isSelected = mode == currentReadThemeMode()
            tabView.background = if (tabView.isSelected) {
                UiCorner.opaqueRounded(
                    ColorUtils.withAlpha(selectedBackground, menuOpacity),
                    UiCorner.actionRadius(requireContext())
                )
            } else {
                ColorDrawable(Color.TRANSPARENT)
            }
        }
    }

    override val curFontPath: String
        get() = ReadBookConfig.textFont

    override fun selectFont(path: String) {
        if (path != ReadBookConfig.textFont || path.isEmpty()) {
            ReadBookConfig.textFont = path
            updateTextRows()
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 5))
        }
    }

    private fun pageAnimById(checkedId: Int): Int {
        return when (checkedId) {
            R.id.rb_anim0 -> PageAnim.coverPageAnim
            R.id.rb_anim_linked_cover -> PageAnim.linkedCoverPageAnim
            R.id.rb_anim1 -> PageAnim.slidePageAnim
            R.id.rb_simulation_anim -> PageAnim.simulationPageAnim
            R.id.rb_scroll_anim -> PageAnim.scrollPageAnim
            R.id.rb_no_anim -> PageAnim.noAnim
            else -> PageAnim.coverPageAnim
        }
    }

    private fun pageAnimIdByValue(pageAnim: Int): Int {
        return when (pageAnim) {
            PageAnim.coverPageAnim -> R.id.rb_anim0
            PageAnim.linkedCoverPageAnim -> R.id.rb_anim_linked_cover
            PageAnim.slidePageAnim -> R.id.rb_anim1
            PageAnim.simulationPageAnim -> R.id.rb_simulation_anim
            PageAnim.scrollPageAnim -> R.id.rb_scroll_anim
            PageAnim.noAnim -> R.id.rb_no_anim
            else -> R.id.rb_anim0
        }
    }

    private fun pageAnimButtons(): List<CompoundButton> = binding.run {
        listOf(rbAnim0, rbAnimLinkedCover, rbAnim1, rbSimulationAnim, rbScrollAnim, rbNoAnim)
    }

    private fun checkPageAnim(checkedId: Int) {
        pageAnimButtons().forEach {
            it.isChecked = it.id == checkedId
        }
    }

    private fun updateColorRows() = binding.run {
        val config = ReadBookConfig.durConfig
        val textColor = config.curTextColor()
        val bgIsImage = config.curBgType() != 0
        val bgColor = currentBgColorForPicker()
        val textAccentColor = config.curTextAccentColor()
        val menuColor = config.curReadMenuBgColor() ?: defaultReadMenuBgColor()
        tvTextColorValue.text = textColor.toHexText()
        tvBgColorValue.text = if (bgIsImage) "图片" else bgColor.toHexText()
        tvTextAccentColorValue.text = textAccentColor.toHexText()
        tvMenuBgColorValue.text = config.curReadMenuBgColor()?.toHexText() ?: getString(R.string.btn_default_s)
        updateReadMenuAlphaRow()
        updateBgAlphaRow()
        vwTextColorSwatch.background = colorSwatch(textColor)
        vwBgColorSwatch.background = if (bgIsImage) {
            config.curBgDrawable(22.dpToPx(), 22.dpToPx())
        } else {
            colorSwatch(bgColor)
        }
        vwTextAccentColorSwatch.background = colorSwatch(textAccentColor)
        vwMenuBgColorSwatch.background = colorSwatch(menuColor)
        ivBgPreview.setImageDrawable(config.curBgDrawable(88.dpToPx(), 88.dpToPx()))
    }

    private fun updateReadMenuAlphaRow() = binding.run {
        tvReadMenuAlphaValue.text = getString(
            R.string.ui_layout_alpha_value,
            ReadBookConfig.durConfig.readMenuAlpha.coerceIn(35, 100)
        )
    }

    private fun updateBgAlphaRow() = binding.run {
        tvBgAlphaValue.text = percentValue(ReadBookConfig.bgAlpha.coerceIn(0, 100))
    }

    private fun defaultReadMenuBgColor(): Int {
        val baseColor = if (
            AppConfig.readBarStyleFollowPage
            && ReadBookConfig.durConfig.curBgType() == 0
        ) ReadBookConfig.durConfig.curBgColor()
        else requireContext().bottomBackground
        val palette = ReaderSheetStyle.resolve(requireContext(), baseColor)
        val isBgLight = ColorUtils.isColorLight(baseColor)
        return ColorUtils.blendColors(
            palette.surface,
            palette.primaryColor,
            if (isBgLight) 0.18f else 0.28f
        )
    }

    private fun currentBgColorForPicker(): Int {
        val config = ReadBookConfig.durConfig
        return if (config.curBgType() == 0) {
            config.curBgColor()
        } else {
            "#015A86".toColorInt()
        }
    }

    private fun colorSwatch(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiCorner.scaledDp(11f)
            setColor(color)
            setStroke(1.dpToPx(), secondaryTextColor)
        }
    }

    private fun currentReadThemeMode(): StyleThemeMode {
        return when {
            AppConfig.isEInkMode -> StyleThemeMode.EINK
            AppConfig.isNightTheme -> StyleThemeMode.DARK
            else -> StyleThemeMode.LIGHT
        }
    }

    private fun switchReadThemeMode(mode: StyleThemeMode) {
        if (currentReadThemeMode() == mode) {
            return
        }
        requireContext().putPrefString(PreferKey.themeMode, mode.preferenceValue)
        AppConfig.themeMode = mode.preferenceValue
        AppConfig.isEInkMode = mode == StyleThemeMode.EINK
        ThemeConfig.applyDayNightNoRecreate(requireContext())
        upView()
        callBack?.upSystemUiVisibility()
        postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
    }

    private fun underlineModeNames(): List<CharSequence> {
        return listOf(getString(R.string.jf_convert_o), "实线", "虚线")
    }

    private fun switchValue(value: Boolean): String {
        return if (value) "开启" else getString(R.string.jf_convert_o)
    }

    private fun percentValue(value: Int): String {
        return if (value == 0) getString(R.string.jf_convert_o) else "$value%"
    }

    private fun showBgImageSelector() {
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = GridLayoutManager(requireContext(), 5)
            clipToPadding = false
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        val adapter = BgAdapter(requireContext(), secondaryTextColor) {
            bgSelectDialog?.dismiss()
            upView()
        }
        recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemBgImageBinding.inflate(layoutInflater, it, false).apply {
                root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
                tvName.setTextColor(secondaryTextColor)
                tvName.text = getString(R.string.select_image)
                ivBg.setImageResource(R.drawable.ic_image)
                ivBg.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
                root.setOnClickListener {
                    bgSelectDialog?.dismiss()
                    selectBgImage.launch {
                        mode = HandleFileContract.IMAGE
                    }
                }
            }
        }
        requireContext().assets.list("bg")?.let {
            adapter.setItems(it.toList())
        }
        bgSelectDialog = alert(getString(R.string.bg_image)) {
            customView { recyclerView }
            onDismiss {
                bgSelectDialog = null
            }
        }
        bgSelectDialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun Int.toHexText(): String {
        return "#${hexString}".uppercase(Locale.ROOT)
    }

    private fun savedStyleSnapshot(): String {
        return GSON.toJson(ReadBookConfig.getConfig(ReadBookConfig.styleSelect))
    }

    private fun isUnsavedCurrentStyle(): Boolean {
        return GSON.toJson(ReadBookConfig.durConfig) != savedConfigSnapshot
    }

    private fun isBuiltInStyle(index: Int): Boolean {
        return ReadBookConfig.isBuiltInStyleIndex(index)
    }

    private fun styleDisplayName(config: ReadBookConfig.Config, isBuiltIn: Boolean): String {
        return config.name.ifBlank {
            if (isBuiltIn) "文字" else "自定义"
        }
    }

    private fun styleDisplayName(index: Int): String {
        return styleDisplayName(ReadBookConfig.getConfig(index), isBuiltInStyle(index))
    }

    private fun uniqueStyleName(baseName: String): String {
        var index = 1
        var newName = "$baseName($index)"
        val names = ReadBookConfig.allStyleConfigs().map { styleDisplayName(it.index) }.toHashSet()
        while (names.contains(newName)) {
            index++
            newName = "$baseName($index)"
        }
        return newName
    }

    private fun launchImportStyle() {
        selectImportDoc.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.import_str)
            allowExtensions = arrayOf("zip")
            otherActions = arrayListOf(SelectItem(importFormNet, -1))
        }
    }

    private fun launchExportStyle(config: ReadBookConfig.Config) {
        pendingExportConfig = config.copy()
        selectExportDir.launch {
            title = getString(R.string.export_str)
        }
    }

    private fun editDraftStyleName() {
        alert(R.string.style_name) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "name"
                editView.setText(ReadBookConfig.durConfig.name.ifBlank { "自定义" })
                root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
            }
            customView { alertBinding.root }
            okButton {
                val styleName = alertBinding.editView.text?.toString()?.trim().orEmpty()
                    .ifBlank { "自定义" }
                ReadBookConfig.durConfig.name = styleName
                upView()
                reloadStyleManager?.invoke()
                postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            }
            cancelButton()
        }
    }

    private fun saveCurrentAsCustomStyle(onSaved: (() -> Unit)? = null) {
        saveCurrentStyleByName(
            styleDisplayName(ReadBookConfig.durConfig, isBuiltInStyle(ReadBookConfig.styleSelect)),
            onSaved
        )
    }

    private fun saveCurrentStyleByName(
        styleName: String,
        onSaved: (() -> Unit)? = null
    ) {
        val existingIndex = ReadBookConfig.allStyleConfigs()
            .firstOrNull { styleDisplayName(it.index) == styleName }?.index ?: -1
        val newConfig = ReadBookConfig.durConfig.copy(name = styleName)
        when {
            existingIndex < 0 -> saveStyleAsNew(newConfig, onSaved)
            isBuiltInStyle(existingIndex) -> {
                saveStyleAsNew(newConfig.copy(name = uniqueStyleName(styleName)), onSaved)
            }
            else -> {
                alert("覆盖样式") {
                    setMessage("已存在同名样式，是否覆盖？")
                    positiveButton("覆盖") {
                        saveStyleAt(existingIndex, newConfig, onSaved)
                    }
                    negativeButton("不覆盖") {
                        saveStyleAsNew(newConfig.copy(name = uniqueStyleName(styleName)), onSaved)
                    }
                    cancelButton()
                }
            }
        }
    }

    private fun saveStyleAsNew(
        config: ReadBookConfig.Config,
        onSaved: (() -> Unit)? = null
    ) {
        ReadBookConfig.configList.add(config)
        saveStyleAt(ReadBookConfig.customGlobalIndex(ReadBookConfig.configList.lastIndex), config, onSaved)
    }

    private fun saveStyleAt(
        index: Int,
        config: ReadBookConfig.Config,
        onSaved: (() -> Unit)? = null
    ) {
        if (!ReadBookConfig.isCustomStyleIndex(index)) {
            return
        }
        ReadBookConfig.configList[ReadBookConfig.customIndex(index)] = config
        ReadBookConfig.setActiveConfig(config.copy(), index)
        savedConfigSnapshot = savedStyleSnapshot()
        ReadBookConfig.save()
        upView()
        reloadStyleManager?.invoke()
        postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        if (AppConfig.readBarStyleFollowPage) {
            postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
        }
        onSaved?.invoke()
    }

    private fun showStyleManager() {
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            clipToPadding = false
            setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
        }
        val titleView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dpToPx(), 14.dpToPx(), 24.dpToPx(), 4.dpToPx())
            addView(TextView(requireContext()).apply {
                text = "管理样式"
                textSize = 20f
                typeface = requireContext().uiTypeface()
                setTextColor(primaryTextColor)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ))
            addView(TextView(requireContext()).apply {
                text = getString(R.string.import_str)
                textSize = 14f
                typeface = requireContext().uiTypeface()
                setTextColor(primaryTextColor)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_import, 0, 0, 0)
                compoundDrawablePadding = 8.dpToPx()
                compoundDrawablesRelative.forEach {
                    it?.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
                }
                minHeight = 40.dpToPx()
                gravity = Gravity.CENTER_VERTICAL
                background = UiCorner.opaqueRoundedStroke(
                    Color.TRANSPARENT,
                    UiCorner.actionRadius(requireContext()),
                    1.dpToPx(),
                    ColorUtils.withAlpha(primaryTextColor, 0.45f)
                )
                setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
                setOnClickListener { launchImportStyle() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val adapter = RestoreStyleAdapter()
        fun reloadItems() {
            val items = arrayListOf<IndexedValue<ReadBookConfig.Config>>()
            if (isUnsavedCurrentStyle()) {
                items.add(IndexedValue(draftStyleIndex, ReadBookConfig.durConfig.copy()))
            }
            items.addAll(ReadBookConfig.allStyleConfigs())
            adapter.setItems(items)
        }
        recyclerView.adapter = adapter
        reloadItems()
        reloadStyleManager = { reloadItems() }
        val managerDialog = alert {
            customTitle { titleView }
            customView { recyclerView }
            onDismiss {
                reloadStyleManager = null
            }
        }
        adapter.onRestore = { index ->
            confirmSwitchStyle(index, managerDialog)
        }
        adapter.onSave = {
            saveCurrentAsCustomStyle {
                reloadItems()
            }
        }
        adapter.onExport = { index ->
            if (index == draftStyleIndex) {
                launchExportStyle(ReadBookConfig.durConfig)
            } else {
                launchExportStyle(ReadBookConfig.getConfig(index))
            }
        }
        adapter.onDelete = { index ->
            if (index != draftStyleIndex && ReadBookConfig.deleteAt(index)) {
                ReadBookConfig.clearBgAndCache()
                savedConfigSnapshot = savedStyleSnapshot()
                ReadBookConfig.save()
                reloadItems()
                upView()
                postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
                toastOnUi(getString(R.string.delete_success))
            } else if (index != draftStyleIndex) {
                toastOnUi("至少保留一个样式")
            }
        }
    }

    private fun confirmSwitchStyle(
        index: Int,
        managerDialog: androidx.appcompat.app.AlertDialog
    ) {
        if (index == draftStyleIndex || !ReadBookConfig.isValidStyleIndex(index)) {
            return
        }
        if (index == ReadBookConfig.styleSelect && !isUnsavedCurrentStyle()) {
            return
        }
        val targetName = styleDisplayName(
            ReadBookConfig.getConfig(index),
            isBuiltInStyle(index)
        )
        alert("切换样式") {
            setMessage("是否切换到“$targetName”？")
            positiveButton(getString(R.string.sure)) {
                switchStyleAfterUnsavedCheck(index, managerDialog)
            }
            cancelButton()
        }
    }

    private fun switchStyleAfterUnsavedCheck(
        index: Int,
        managerDialog: androidx.appcompat.app.AlertDialog
    ) {
        if (!isUnsavedCurrentStyle()) {
            managerDialog.dismiss()
            changeBgTextConfig(index)
            return
        }
        alert("保存当前样式?") {
            setMessage("当前样式有未保存修改，是否保存？")
            yesButton {
                saveCurrentStyleByName(
                    styleDisplayName(ReadBookConfig.durConfig, isBuiltInStyle(ReadBookConfig.styleSelect))
                ) {
                    managerDialog.dismiss()
                    changeBgTextConfig(index)
                }
            }
            noButton {
                managerDialog.dismiss()
                changeBgTextConfig(index)
            }
            cancelButton()
        }
    }

    private fun exportConfig(uri: Uri, targetConfig: ReadBookConfig.Config) {
        val exportFileName = if (targetConfig.name.isBlank()) {
            configFileName
        } else {
            "${targetConfig.name}.zip"
        }
        execute {
            val exportFiles = arrayListOf<File>()
            val configDir = requireContext().externalCache.getFile("readConfig")
            configDir.createFolderReplace()
            val configFile = configDir.getFile("readConfig.json")
            configFile.createFileReplace()
            val config = targetConfig.copy()
            val fontPath = config.textFont
            if (fontPath.isNotEmpty()) {
                val fontDoc = FileDoc.fromFile(fontPath)
                val fontName = fontDoc.name
                fontDoc.openInputStream().getOrNull()?.use {
                    val fontExportFile = FileUtils.createFileIfNotExist(configDir, fontName)
                    fontExportFile.outputStream().use { out -> it.copyTo(out) }
                    config.textFont = fontName
                    exportFiles.add(fontExportFile)
                }
            }
            configFile.writeText(GSON.toJson(config))
            exportFiles.add(configFile)
            repeat(3) {
                val path = targetConfig.getBgPath(it) ?: return@repeat
                val bgExportFile = copyBgImage(path, configDir) ?: return@repeat
                exportFiles.add(bgExportFile)
            }
            val configZipPath = FileUtils.getPath(requireContext().externalCache, configFileName)
            if (ZipUtils.zipFiles(exportFiles, File(configZipPath))) {
                val exportDir = FileDoc.fromDir(uri)
                exportDir.find(exportFileName)?.delete()
                val exportFileDoc = exportDir.createFileIfNotExist(exportFileName)
                exportFileDoc.openOutputStream().getOrThrow().use { out ->
                    File(configZipPath).inputStream().use { it.copyTo(out) }
                }
            }
        }.onSuccess {
            toastOnUi("导出成功, 文件名为 $exportFileName")
        }.onError {
            it.printOnDebug()
            longToast("导出失败:${it.localizedMessage}")
        }
    }

    private fun copyBgImage(path: String, configDir: File): File? {
        val bgName = FileUtils.getName(path)
        val bgFile = File(path)
        if (bgFile.exists()) {
            val bgExportFile = File(FileUtils.getPath(configDir, bgName))
            if (!bgExportFile.exists()) {
                bgFile.copyTo(bgExportFile)
            }
            return bgExportFile
        }
        return null
    }

    @SuppressLint("InflateParams")
    private fun importNetConfigAlert() {
        alert("请输入地址") {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater)
            alertBinding.root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { url ->
                    importNetConfig(url)
                }
            }
            cancelButton()
        }
    }

    private fun importNetConfig(url: String) {
        execute {
            importConfig(RustRemoteFetch.bytes(url, "ReadStyleDialog.importNetConfig").body)
        }.onError {
            longToast(it.stackTraceStr)
        }
    }

    private fun importConfig(uri: Uri) {
        execute {
            ReadBookConfig.import(uri.readBytes(requireContext()))
        }.onSuccess {
            saveImportedStyle(it)
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun importConfig(byteArray: ByteArray) {
        execute {
            ReadBookConfig.import(byteArray)
        }.onSuccess {
            saveImportedStyle(it)
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun saveImportedStyle(importedConfig: ReadBookConfig.Config) {
        val baseName = importedConfig.name.trim().ifBlank { "自定义" }
        val config = importedConfig.copy(name = baseName)
        val existingIndex = ReadBookConfig.allStyleConfigs()
            .firstOrNull { styleDisplayName(it.index) == baseName }?.index ?: -1
        when {
            existingIndex < 0 -> addImportedStyle(config)
            isBuiltInStyle(existingIndex) -> {
                addImportedStyle(config.copy(name = uniqueStyleName(baseName)))
            }
            else -> {
                alert("覆盖样式") {
                    setMessage("已存在同名样式，是否覆盖？")
                    positiveButton("覆盖") {
                        val wasCurrentUnsaved = isUnsavedCurrentStyle()
                        ReadBookConfig.configList[ReadBookConfig.customIndex(existingIndex)] = config
                        if (existingIndex == ReadBookConfig.styleSelect) {
                            savedConfigSnapshot = GSON.toJson(config)
                            if (!wasCurrentUnsaved) {
                                ReadBookConfig.setActiveConfig(config.copy(), existingIndex)
                            }
                            upView()
                            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
                            postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                        }
                        ReadBookConfig.save()
                        reloadStyleManager?.invoke()
                        toastOnUi("导入成功")
                    }
                    negativeButton("不覆盖") {
                        addImportedStyle(config.copy(name = uniqueStyleName(baseName)))
                    }
                    cancelButton()
                }
            }
        }
    }

    private fun addImportedStyle(config: ReadBookConfig.Config) {
        ReadBookConfig.configList.add(config)
        ReadBookConfig.save()
        reloadStyleManager?.invoke()
        toastOnUi("导入成功")
    }

    private fun setBgFromUri(uri: Uri) {
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "bg", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                ReadBookConfig.durConfig.setCurBg(2, fileName)
                upView()
                postEvent(EventBus.UP_CONFIG, arrayListOf(1))
            }.onFailure {
                toastOnUi(it.localizedMessage.orEmpty())
            }
        }
    }

    inner class RestoreStyleAdapter :
        RecyclerAdapter<IndexedValue<ReadBookConfig.Config>, ItemRestoreReadStyleBinding>(requireContext()) {

        var onRestore: ((index: Int) -> Unit)? = null
        var onSave: (() -> Unit)? = null
        var onExport: ((index: Int) -> Unit)? = null
        var onDelete: ((index: Int) -> Unit)? = null

        override fun getViewBinding(parent: ViewGroup): ItemRestoreReadStyleBinding {
            return ItemRestoreReadStyleBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemRestoreReadStyleBinding,
            item: IndexedValue<ReadBookConfig.Config>,
            payloads: MutableList<Any>
        ) {
            val config = item.value
            binding.apply {
                val isDraft = item.index == draftStyleIndex
                val isBuiltIn = !isDraft && isBuiltInStyle(item.index)
                val isUnsaved = isDraft
                val isCurrent = isDraft || (item.index == ReadBookConfig.styleSelect && !isUnsavedCurrentStyle())
                val styleName = styleDisplayName(config, isBuiltIn)
                root.background = if (isCurrent) {
                    UiCorner.opaqueRounded(
                        ColorUtils.withAlpha(accentColor, 0.16f),
                        UiCorner.actionRadius(requireContext())
                    )
                } else {
                    ColorDrawable(Color.TRANSPARENT)
                }
                tvStyleName.text = if (isUnsaved) "$styleName(未保存)" else styleName
                tvStyleName.setTextColor(if (isCurrent) accentColor else primaryTextColor)
                tvStyleName.typeface = requireContext().uiTypeface()
                ivStylePreview.setText("")
                ivStylePreview.setImageDrawable(config.curBgDrawable(72, 72))
                ivStylePreview.borderColor = if (isCurrent) accentColor else config.curTextColor()
                ivSave.visibility = if (isDraft) View.VISIBLE else View.GONE
                ivSave.isEnabled = isDraft
                ivSave.setColorFilter(secondaryTextColor, PorterDuff.Mode.SRC_IN)
                ivExport.setColorFilter(secondaryTextColor, PorterDuff.Mode.SRC_IN)
                ivDelete.visibility = if (isBuiltIn || isDraft) View.INVISIBLE else View.VISIBLE
                ivDelete.isEnabled = !isBuiltIn && !isDraft
                ivDelete.setColorFilter(secondaryTextColor, PorterDuff.Mode.SRC_IN)
            }
        }

        override fun registerListener(
            holder: ItemViewHolder,
            binding: ItemRestoreReadStyleBinding
        ) {
            binding.root.setOnClickListener {
                getItem(holder.layoutPosition - getHeaderCount())?.let {
                    onRestore?.invoke(it.index)
                }
            }
            binding.ivSave.setOnClickListener {
                onSave?.invoke()
            }
            binding.ivExport.setOnClickListener {
                getItem(holder.layoutPosition - getHeaderCount())?.let { stl ->
                    onExport?.invoke(stl.index)
                }
            }
            binding.ivDelete.setOnClickListener {
                getItem(holder.layoutPosition - getHeaderCount())?.let { stl ->
                    if (stl.index == draftStyleIndex || isBuiltInStyle(stl.index)) {
                        return@let
                    }
                    alert(getString(R.string.delete)) {
                        setMessage(getString(R.string.sure_del_any, stl.value.name.ifBlank { "自定义" }))
                        positiveButton(getString(R.string.sure)) {
                            onDelete?.invoke(stl.index)
                        }
                        cancelButton()
                    }
                }
            }
        }
    }

    inner class StyleAdapter :
        RecyclerAdapter<ReadBookConfig.Config, ItemReadStyleBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemReadStyleBinding {
            return ItemReadStyleBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReadStyleBinding,
            item: ReadBookConfig.Config,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                ivStyle.setText(item.name.ifBlank { "文字" })
                tvStyleName.text = item.name.ifBlank { "文字" }
                tvStyleName.setTextColor(item.curTextColor())
                tvStyleName.typeface = requireContext().uiTypeface()
                ivStyle.setTypeface(requireContext().uiTypeface())
                ivStyle.setTextColor(item.curTextColor())
                ivStyle.setImageDrawable(item.curBgDrawable(100, 150))
                val itemPosition = holder.layoutPosition - getHeaderCount()
                if (ReadBookConfig.styleSelect == itemPosition) {
                    ivStyle.borderColor = accentColor
                    ivStyle.setTextBold(true)
                } else {
                    ivStyle.borderColor = item.curTextColor()
                    ivStyle.setTextBold(false)
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReadStyleBinding) {
            binding.apply {
                root.setOnClickListener {
                    if (ivStyle.isInView) {
                        changeBgTextConfig(holder.layoutPosition - getHeaderCount())
                    }
                }
            }
        }

    }

    private enum class StyleTab {
        TEXT, PAGE, STYLE
    }

    private enum class StyleThemeMode(val preferenceValue: String) {
        LIGHT("1"),
        DARK("2"),
        EINK("3")
    }
}
