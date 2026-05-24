package io.legado.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppHttpBoundaryTest {

    @Test
    fun appStartupDoesNotInstallUrlStreamHandler() {
        val source = findAppSource().readText()

        assertFalse(source.contains("URL.setURLStreamHandlerFactory"))
        assertFalse(source.contains("Obsolete" + "UrlFactory"))
        assertFalse(source.contains("ok" + "HttpClient"))
    }

    @Test
    fun legacyDirectHttpAnalyzerHelpersAreRemoved() {
        val httpDir = findProjectFile("app/src/main/java/io/legado/app/help/http")

        assertFalse(File(httpDir, "Obsolete" + "UrlFactory.kt").exists())
        assertFalse(File(httpDir, "Http" + "Helper.kt").exists())
        assertFalse(File(httpDir, "Ok" + "HttpUtils.kt").exists())
        assertFalse(File(httpDir, "Decompress" + "Interceptor.kt").exists())
        assertFalse(File(httpDir, "Request" + "Method.kt").exists())
        assertFalse(File(httpDir, "Cronet.kt").exists())
        assertFalse(File(httpDir, "Ok" + "httpUncaughtExceptionHandler.kt").exists())
    }

    @Test
    fun unusedCronetOkHttpAdapterIsRemoved() {
        val cronetDir = File(findProjectFile("app/src/main/java/io/legado/app/lib"), "cronet")
        val appSource = findAppSource().readText()
        val buildGradle = findProjectFile("app/build.gradle")
        val appDir = buildGradle.parentFile
        val buildFile = buildGradle.readText()
        val proguardFile = findProjectFile("app/proguard-rules.pro").readText()

        assertFalse(appSource.contains("Cronet.pre" + "Download"))
        assertFalse(appSource.contains("org.chromium"))
        assertFalse(buildFile.contains("cronet" + "lib"))
        assertFalse(buildFile.contains("cronet" + "-proguard"))
        assertFalse(buildFile.contains("datasource.ok" + "http"))
        assertFalse(buildFile.contains("glide.ok" + "http"))
        assertFalse(buildFile.contains("libs.ok" + "http"))
        assertFalse(proguardFile.contains("org.chromium.net"))
        assertFalse(File(appDir, "cronetlib").exists())
        assertFalse(File(findProjectFile("app/src/main/assets"), "cronet.json").exists())
        assertFalse(File(findProjectFile(".github/scripts"), "cronet.sh").exists())
        assertFalse(cronetDir.exists())
        assertFalse(File(cronetDir, "Cronet" + "Interceptor.kt").exists())
        assertFalse(File(cronetDir, "Cronet" + "Loader.kt").exists())
        assertFalse(File(cronetDir, "Abs" + "CallBack.kt").exists())
        assertFalse(File(cronetDir, "Body" + "UploadProvider.kt").exists())
        assertFalse(File(cronetDir, "Large" + "BodyUploadProvider.kt").exists())
    }

    @Test
    fun legacyGlideHttpClientNamesAreRemoved() {
        val glideDir = findProjectFile("app/src/main/java/io/legado/app/help/glide")

        assertFalse(File(glideDir, "Ok" + "HttpModelLoader.kt").exists())
        assertFalse(File(glideDir, "Ok" + "HttpStreamFetcher.kt").exists())
        assertFalse(File(glideDir, "Ok" + "HttpModeLoaderFactory.kt").exists())
        assertFalse(File(glideDir, "RustImageStreamFetcher.kt").readText().contains("Ok" + "Http"))
        assertFalse(File(glideDir, "progress/Progress" + "ResponseBody.kt").exists())
    }

    @Test
    fun uriAndImportHelpersDoNotKeepOkHttpBodyTypes() {
        val uriExtensions = findProjectFile("app/src/main/java/io/legado/app/utils/UriExtensions.kt")
            .readText()
        val onlineImport = findProjectFile(
            "app/src/main/java/io/legado/app/ui/association/OnLineImportViewModel.kt"
        ).readText()

        assertFalse(uriExtensions.contains("Request" + "Body"))
        assertFalse(uriExtensions.contains("ok" + "http3"))
        assertFalse(onlineImport.contains("ok" + "http3"))
        assertFalse(onlineImport.contains("to" + "MediaType"))
    }

    @Test
    fun webDavDoesNotUseOkHttpUrlOrCredentials() {
        val webDavDir = findProjectFile("app/src/main/java/io/legado/app/lib/webdav")
        val webDavSource = File(webDavDir, "WebDav.kt").readText()
        val authorizationSource = File(webDavDir, "Authorization.kt").readText()

        assertFalse(webDavSource.contains("ok" + "http3"))
        assertFalse(webDavSource.contains("to" + "HttpUrl"))
        assertFalse(webDavSource.contains("org." + "jsoup"))
        assertFalse(webDavSource.contains("Jsoup."))
        assertTrue(webDavSource.contains("RustAnalyzerBridge.parseWebDavListing"))
        assertTrue(webDavSource.contains("RustAnalyzerBridge.parseWebDavError"))
        assertFalse(authorizationSource.contains("ok" + "http3"))
        assertFalse(authorizationSource.contains("Credentials"))
    }

    @Test
    fun simpleHtmlDocumentHelpersUseRustBridgeInsteadOfAndroidJsoup() {
        val utilsDir = findProjectFile("app/src/main/java/io/legado/app/utils")
        val rssReadSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt"
        ).readText()
        val codeEditSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/code/CodeEditViewModel.kt"
        ).readText()
        val encodingDetectSource = findProjectFile(
            "app/src/main/java/io/legado/app/utils/EncodingDetect.kt"
        ).readText()

        assertFalse(File(utilsDir, "JsoupExtensions.kt").exists())
        assertFalse(rssReadSource.contains("org." + "jsoup"))
        assertFalse(rssReadSource.contains("Jsoup."))
        assertFalse(rssReadSource.contains("textArray"))
        assertTrue(rssReadSource.contains("RustAnalyzerBridge.htmlTextArray"))
        assertFalse(codeEditSource.contains("org." + "jsoup"))
        assertFalse(codeEditSource.contains("Jsoup."))
        assertTrue(codeEditSource.contains("RustAnalyzerBridge.htmlFormat"))
        assertFalse(encodingDetectSource.contains("org." + "jsoup"))
        assertFalse(encodingDetectSource.contains("parseBodyFragment"))
        assertTrue(encodingDetectSource.contains("RustAnalyzerBridge.htmlCharset"))
    }

    @Test
    fun connectionResponseParseDoesNotUseAndroidJsoupParser() {
        val jsExtensionsSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/JsExtensions.kt"
        ).readText()

        assertFalse(jsExtensionsSource.contains("import org.jsoup"))
        assertFalse(jsExtensionsSource.contains("Connection.Response"))
        assertFalse(jsExtensionsSource.contains("Connection.Method"))
        assertFalse(jsExtensionsSource.contains("import org.jsoup.Jsoup"))
        assertFalse(jsExtensionsSource.contains("Jsoup.parse(responseBody"))
        assertTrue(jsExtensionsSource.contains("Rust HTTP response parse cannot expose an Android DOM Document"))
        assertTrue(jsExtensionsSource.contains("Use Rust JS host DOM parse compatibility"))
    }

    @Test
    fun localBookSimpleHtmlParsingUsesRustBridgeWhereMigrated() {
        val mobiSource = findProjectFile(
            "app/src/main/java/io/legado/app/model/localBook/MobiFile.kt"
        ).readText()
        val epubSource = findProjectFile(
            "app/src/main/java/io/legado/app/model/localBook/EpubFile.kt"
        ).readText()
        val textChapterLayoutSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt"
        ).readText()
        val localBookDir = findProjectFile("app/src/main/java/io/legado/app/model/localBook")

        assertFalse(mobiSource.contains("getElementsByTag(\"title\")"))
        assertFalse(mobiSource.contains("import org.jsoup.Jsoup"))
        assertFalse(mobiSource.contains("Jsoup.parse"))
        assertTrue(mobiSource.contains("RustAnalyzerBridge.htmlTitle"))
        assertTrue(mobiSource.contains("RustAnalyzerBridge.mobiContentHtml"))
        assertFalse(File(localBookDir, "EpubMiniLayout.kt").exists())
        assertFalse(epubSource.contains("Jsoup.parse(metadata.descriptions[0]).text()"))
        assertFalse(epubSource.contains("import org.jsoup."))
        assertFalse(epubSource.contains("Jsoup.parse"))
        assertFalse(epubSource.contains("Elements()"))
        assertFalse(epubSource.contains("Element(\"img\")"))
        assertFalse(epubSource.contains("elements.select"))
        assertFalse(epubSource.contains("doc.isEpubBookInfoDocument()"))
        assertFalse(epubSource.contains("doc.select(\"aside[id], section[id], div[id], li[id], p[id], span[id], a[id]\")"))
        assertFalse(epubSource.contains("parseFootnoteDocument"))
        assertFalse(epubSource.contains("footnoteDocumentCache"))
        assertFalse(epubSource.contains("getElementById(targetId)"))
        assertFalse(epubSource.contains("scriptBlockRegex"))
        assertFalse(epubSource.contains("scriptSelfClosingRegex"))
        assertFalse(epubSource.contains("doc.hideEpubFootnotes"))
        assertFalse(epubSource.contains("bodyElement.getElementById(startFragmentId)"))
        assertFalse(epubSource.contains("bodyElement.select(\"image\")"))
        assertFalse(epubSource.contains("bodyElement.materializePageBackgroundColor"))
        assertFalse(epubSource.contains("private fun Element.materializePageBackgroundColor"))
        assertFalse(epubSource.contains("private fun Element.epubImageSrc"))
        assertFalse(epubSource.contains("private fun Element.epubImageOptions"))
        assertFalse(epubSource.contains("private fun normalizeImageWidth"))
        assertFalse(epubSource.contains("private fun normalizeImageLength"))
        assertFalse(epubSource.contains("private fun String?.isInlineEpubImageWidth"))
        assertFalse(epubSource.contains("private fun Element.markSingleImagePage"))
        assertFalse(epubSource.contains("private fun Element.markEpubOverlayImagePage"))
        assertFalse(epubSource.contains("private fun Element.markEpubGalleryPage"))
        assertFalse(epubSource.contains("bodyElement.select(\"img\")"))
        assertFalse(epubSource.contains("withEpubImageOptions"))
        assertFalse(epubSource.contains("private fun String.escapeJson"))
        assertFalse(epubSource.contains("private fun Element.materializeMediaElements"))
        assertFalse(epubSource.contains("private fun Element.applyEpubInlineStyle"))
        assertFalse(epubSource.contains("private fun Element.isEpubDecoratedBlock"))
        assertFalse(epubSource.contains("private fun Element.wrapInnerHtml"))
        assertFalse(epubSource.contains("private fun Element.propagateEpubInheritedStyles"))
        assertFalse(epubSource.contains("fun Element.walkWithInherited"))
        assertFalse(epubSource.contains("bodyElement.select(\"a[href]\")"))
        assertFalse(epubSource.contains("private fun Element.materializeBackgroundImages"))
        assertFalse(epubSource.contains("private fun Element.backgroundImageHref"))
        assertFalse(epubSource.contains("private fun String.extractCssUrl"))
        assertFalse(epubSource.contains("private fun String.extractCssColor"))
        assertFalse(epubSource.contains("doc.head()?.select(\"style\")"))
        assertFalse(epubSource.contains("doc.head()?.select(\"link[href][rel~=stylesheet]\")"))
        assertFalse(epubSource.contains("select(\"style\").forEach { styleElement"))
        assertFalse(epubSource.contains("select(\"link[href][rel~=stylesheet]\").forEach"))
        assertFalse(epubSource.contains("private data class ReadableInlineStyle"))
        assertFalse(epubSource.contains("private fun Element.readableLines"))
        assertFalse(epubSource.contains("cleanReadableEpubElement"))
        assertFalse(epubSource.contains("private fun Element.applyCssRules"))
        assertFalse(epubSource.contains("CascadedCssValue"))
        assertFalse(epubSource.contains("select(rule.selector).forEach"))
        assertFalse(epubSource.contains("this.`is`(rule.selector)"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubAppliedCss"))
        val epubDomBuilderSource = File(localBookDir, "EpubDomBuilder.kt").readText()
        assertFalse(epubDomBuilderSource.contains("import org.jsoup."))
        assertFalse(epubDomBuilderSource.contains("doc.head()?.select(\"style\")"))
        assertFalse(epubDomBuilderSource.contains("doc.head()?.select(\"link[href][rel~=stylesheet]\")"))
        assertFalse(epubDomBuilderSource.contains("body.select(\"style\")"))
        assertFalse(epubDomBuilderSource.contains("body.select(\"link[href][rel~=stylesheet]\")"))
        assertFalse(epubDomBuilderSource.contains("styleElement.data().ifBlank"))
        assertFalse(epubDomBuilderSource.contains("private fun applyGeneratedContent"))
        assertFalse(epubDomBuilderSource.contains("generatedContentText"))
        assertFalse(epubDomBuilderSource.contains("addGeneratedContent"))
        assertFalse(epubDomBuilderSource.contains("private fun matchRules"))
        assertFalse(epubDomBuilderSource.contains("root.select(rule.selector)"))
        assertFalse(epubDomBuilderSource.contains("private fun buildNode"))
        assertFalse(epubDomBuilderSource.contains("private fun buildElement"))
        assertFalse(epubDomBuilderSource.contains("private fun computeStyle"))
        assertTrue(epubDomBuilderSource.contains("RustAnalyzerBridge.epubCssAssets"))
        assertTrue(epubDomBuilderSource.contains("RustAnalyzerBridge.epubGeneratedContent"))
        assertTrue(epubDomBuilderSource.contains("RustAnalyzerBridge.epubNativeDom"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.htmlTextArray"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.htmlTitle"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubReadableTitle"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubBookInfo"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubFootnoteIds"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubFootnoteTarget"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubReadableLines"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubBodyHtml"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubDebugChapterHtml"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubImagePageMarks"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubMaterializedImages"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubMediaPlaceholders"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubInlineStyles"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubInheritedStyles"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubResolvedLinks"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubBodyBackgroundImage"))
        assertTrue(epubSource.contains("RustAnalyzerBridge.epubCssAssets"))
        assertFalse(textChapterLayoutSource.contains("Jsoup.parse(rawNativeEntry)"))
        assertTrue(textChapterLayoutSource.contains("RustAnalyzerBridge.epubNativeEntryHrefs"))
        assertFalse(textChapterLayoutSource.contains("val body = Jsoup.parseBodyFragment(this).body()"))
        assertFalse(textChapterLayoutSource.contains("import org.jsoup"))
        assertFalse(textChapterLayoutSource.contains("Jsoup.parseBodyFragment"))
        assertFalse(textChapterLayoutSource.contains("childNodes().forEach"))
        assertTrue(textChapterLayoutSource.contains("RustAnalyzerBridge.htmlFirstAlignment"))
        assertFalse(textChapterLayoutSource.contains("private fun Element.toReadableTableHtml"))
        assertFalse(textChapterLayoutSource.contains("private fun Element.toReadableInlineHtml"))
        assertFalse(textChapterLayoutSource.contains("private fun Element.htmlAlignOrNull"))
        assertFalse(textChapterLayoutSource.contains("RustAnalyzerBridge.htmlReadableTable"))
        assertFalse(textChapterLayoutSource.contains("private fun Element.hasEpubPageBreakBefore"))
        assertFalse(textChapterLayoutSource.contains("private fun Element.hasEpubBlockBoxDescendant"))
        assertFalse(textChapterLayoutSource.contains("private fun Element.hasHtmlImage"))
        assertFalse(textChapterLayoutSource.contains("private fun Element.isHtmlBlock"))
        assertFalse(textChapterLayoutSource.contains("RustAnalyzerBridge.htmlRenderFlags"))
        assertFalse(textChapterLayoutSource.contains("body.select(\"[data-epub-page-bg]\")"))
        assertFalse(textChapterLayoutSource.contains("body.selectFirst(\"[data-epub-page-bg]\")"))
        assertFalse(textChapterLayoutSource.contains("private fun Element.cssWidth"))
        assertTrue(textChapterLayoutSource.contains("RustAnalyzerBridge.htmlPageBackground"))
        assertFalse(textChapterLayoutSource.contains("RustAnalyzerBridge.htmlImageInfo"))
        assertTrue(textChapterLayoutSource.contains("RustAnalyzerBridge.htmlRenderPlan"))
    }

    @Test
    fun strResponseDoesNotExposeOkHttpTypesAndOldCookieHelpersAreRemoved() {
        val httpDir = findProjectFile("app/src/main/java/io/legado/app/help/http")
        val strResponseSource = File(httpDir, "StrResponse.kt").readText()

        assertFalse(strResponseSource.contains("ok" + "http3"))
        assertFalse(strResponseSource.contains("Response" + "Body"))
        assertFalse(strResponseSource.contains("ok" + "http3.Headers"))
        assertFalse(File(httpDir, "Cookie" + "Manager.kt").exists())
        assertFalse(File(httpDir, "Cookie" + "Store.kt").exists())
        assertFalse(File(httpDir, "api/Cookie" + "ManagerInterface.kt").exists())
    }

    @Test
    fun networkUtilsUsesRustPublicSuffixInsteadOfOkHttp() {
        val networkUtilsSource = findProjectFile("app/src/main/java/io/legado/app/utils/NetworkUtils.kt")
            .readText()
        val versionCatalog = findProjectFile("gradle/libs.versions.toml").readText()

        assertFalse(networkUtilsSource.contains("ok" + "http3"))
        assertFalse(networkUtilsSource.contains("Public" + "SuffixDatabase"))
        assertFalse(networkUtilsSource.contains("getEffective" + "TldPlusOne"))
        assertFalse(networkUtilsSource.contains("}.getOrDefault(baseUrl)"))
        assertFalse(networkUtilsSource.contains("}.getOrDefault(null)"))
        assertTrue(networkUtilsSource.contains("NetworkUtils absolute URL base is invalid for Rust analyzer handoff"))
        assertTrue(networkUtilsSource.contains("NetworkUtils absolute URL join failed for Rust analyzer handoff"))
        assertFalse(versionCatalog.contains("ok" + "http"))
    }

    @Test
    fun jsExtensionsZipEntryExtractionRunsThroughRustHost() {
        val jsExtensionsSource = findProjectFile("app/src/main/java/io/legado/app/help/JsExtensions.kt")
            .readText()
        val regexExtensionsSource = findProjectFile("app/src/main/java/io/legado/app/utils/RegexExtensions.kt")
            .readText()
        val rssJsExtensionsSource = findProjectFile("app/src/main/java/io/legado/app/ui/rss/read/RssJsExtensions.kt")
            .readText()
        val baseSourceSource = findProjectFile("app/src/main/java/io/legado/app/data/entities/BaseSource.kt")
            .readText()
        val bookSourceSource = findProjectFile("app/src/main/java/io/legado/app/data/entities/BookSource.kt")
            .readText()
        val bookSourceControllerSource = findProjectFile(
            "app/src/main/java/io/legado/app/api/controller/BookSourceController.kt"
        ).readText()
        val bookSourceWebControllerSource = findProjectFile(
            "app/src/main/java/io/legado/app/api/controller/BookSourceWebController.kt"
        ).readText()
        val bookControllerSource = findProjectFile(
            "app/src/main/java/io/legado/app/api/controller/BookController.kt"
        ).readText()
        val rssSourceControllerSource = findProjectFile(
            "app/src/main/java/io/legado/app/api/controller/RssSourceController.kt"
        ).readText()
        val replaceRuleControllerSource = findProjectFile(
            "app/src/main/java/io/legado/app/api/controller/ReplaceRuleController.kt"
        ).readText()
        val replaceAnalyzerSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/ReplaceAnalyzer.kt"
        ).readText()
        val importOldDataSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/storage/ImportOldData.kt"
        ).readText()
        val backupConfigSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/storage/BackupConfig.kt"
        ).readText()
        val backupSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/storage/Backup.kt"
        ).readText()
        val restoreSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/storage/Restore.kt"
        ).readText()
        val restoreJournalSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/storage/RestoreJournal.kt"
        ).readText()
        val appConfigSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/config/AppConfig.kt"
        ).readText()
        val infoMapSource = findProjectFile("app/src/main/java/io/legado/app/utils/InfoMap.kt")
            .readText()
        val cacheManagerSource = findProjectFile("app/src/main/java/io/legado/app/help/CacheManager.kt")
            .readText()
        val cacheDaoSource = findProjectFile("app/src/main/java/io/legado/app/data/dao/CacheDao.kt")
            .readText()
        val bookSourceEditSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditViewModel.kt"
        ).readText()
        val rssSourceEditSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/rss/source/edit/RssSourceEditViewModel.kt"
        ).readText()
        val webViewLoginSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/login/WebViewLoginFragment.kt"
        ).readText()
        val sourceLoginDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/login/SourceLoginDialog.kt"
        ).readText()
        val rustAnalyzerBridgeSource = findProjectFile(
            "app/src/main/java/io/legado/app/model/webBook/RustAnalyzerBridge.kt"
        ).readText()
        val webBookSource = findProjectFile(
            "app/src/main/java/io/legado/app/model/webBook/WebBook.kt"
        ).readText()
        val searchModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/model/webBook/SearchModel.kt"
        ).readText()
        val changeBookSourceViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt"
        ).readText()
        val bookInfoViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt"
        ).readText()
        val cacheBookSource = findProjectFile(
            "app/src/main/java/io/legado/app/model/CacheBook.kt"
        ).readText()
        val readBookSource = findProjectFile(
            "app/src/main/java/io/legado/app/model/ReadBook.kt"
        ).readText()
        val readBookViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt"
        ).readText()
        val readMangaSource = findProjectFile(
            "app/src/main/java/io/legado/app/model/ReadManga.kt"
        ).readText()
        val readMangaViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/manga/ReadMangaViewModel.kt"
        ).readText()
        val audioPlayViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/audio/AudioPlayViewModel.kt"
        ).readText()
        val audioPlaySource = findProjectFile(
            "app/src/main/java/io/legado/app/model/AudioPlay.kt"
        ).readText()
        val videoPlaySource = findProjectFile(
            "app/src/main/java/io/legado/app/model/VideoPlay.kt"
        ).readText()
        val cacheBookServiceSource = findProjectFile(
            "app/src/main/java/io/legado/app/service/CacheBookService.kt"
        ).readText()
        val mainViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/main/MainViewModel.kt"
        ).readText()
        val bookshelfViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/main/bookshelf/BookshelfViewModel.kt"
        ).readText()
        val changeBookSourceDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceDialog.kt"
        ).readText()
        val changeChapterSourceDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceDialog.kt"
        ).readText()
        val changeCoverViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/changecover/ChangeCoverViewModel.kt"
        ).readText()
        val bookshelfManageViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/manage/BookshelfManageViewModel.kt"
        ).readText()
        val backstageWebViewSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/http/BackstageWebView.kt"
        ).readText()
        val browserWebViewSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/browser/WebViewActivity.kt"
        ).readText()
        val rssReadSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt"
        ).readText()
        val rssViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/main/rss/RssViewModel.kt"
        ).readText()
        val rustPlatformActionSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/login/RustPlatformAction.kt"
        ).readText()
        val directLinkUploadSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/DirectLinkUpload.kt"
        ).readText()
        val aiBookSourceToolSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/ai/AiBookSourceTool.kt"
        ).readText()
        val aiSettingsToolSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/ai/AiSettingsTool.kt"
        ).readText()
        val aiChatServiceSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/ai/AiChatService.kt"
        ).readText()
        val aiMcpClientSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/ai/AiMcpClient.kt"
        ).readText()
        val aiBookshelfToolSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/ai/AiBookshelfTool.kt"
        ).readText()
        val aiLibraryToolSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/ai/AiLibraryTool.kt"
        ).readText()
        val addToBookshelfDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/association/AddToBookshelfDialog.kt"
        ).readText()
        val httpReadAloudServiceSource = findProjectFile(
            "app/src/main/java/io/legado/app/service/HttpReadAloudService.kt"
        ).readText()
        val httpTtsSource = findProjectFile(
            "app/src/main/java/io/legado/app/data/entities/HttpTTS.kt"
        ).readText()
        val ttsReadAloudServiceSource = findProjectFile(
            "app/src/main/java/io/legado/app/service/TTSReadAloudService.kt"
        ).readText()
        val importHttpTtsDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/association/ImportHttpTtsDialog.kt"
        ).readText()
        val cacheManageViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/cache/CacheManageViewModel.kt"
        ).readText()
        val appWebDavSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/AppWebDav.kt"
        ).readText()
        val exploreShowViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/explore/ExploreShowViewModel.kt"
        ).readText()
        val rssArticlesViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/rss/article/RssArticlesViewModel.kt"
        ).readText()
        val contentEditDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/read/ContentEditDialog.kt"
        ).readText()
        val exploreFragmentSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/main/explore/ExploreFragment.kt"
        ).readText()
        val bookTocLoadingSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/toc/BookTocLoadingActivity.kt"
        ).readText()
        val audioPlayServiceSource = findProjectFile(
            "app/src/main/java/io/legado/app/service/AudioPlayService.kt"
        ).readText()
        val importBookSourceDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/association/ImportBookSourceDialog.kt"
        ).readText()
        val importRssSourceDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/association/ImportRssSourceDialog.kt"
        ).readText()
        val importReplaceRuleDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/association/ImportReplaceRuleDialog.kt"
        ).readText()
        val importDictRuleDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/association/ImportDictRuleDialog.kt"
        ).readText()
        val replaceEditViewModelSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/replace/edit/ReplaceEditViewModel.kt"
        ).readText()
        val txtTocRuleEditDialogSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/toc/rule/TxtTocRuleEditDialog.kt"
        ).readText()
        val bookSearchWebSocketSource = findProjectFile(
            "app/src/main/java/io/legado/app/web/socket/BookSearchWebSocket.kt"
        ).readText()
        val bookSourceDebugWebSocketSource = findProjectFile(
            "app/src/main/java/io/legado/app/web/socket/BookSourceDebugWebSocket.kt"
        ).readText()
        val rssSourceDebugWebSocketSource = findProjectFile(
            "app/src/main/java/io/legado/app/web/socket/RssSourceDebugWebSocket.kt"
        ).readText()
        val customUrlSource = findProjectFile(
            "app/src/main/java/io/legado/app/utils/CustomUrl.kt"
        ).readText()
        val urlUtilSource = findProjectFile(
            "app/src/main/java/io/legado/app/utils/UrlUtil.kt"
        ).readText()
        val exoPlayerHelperSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/exoplayer/ExoPlayerHelper.kt"
        ).readText()
        val defaultDataSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/DefaultData.kt"
        ).readText()
        val bookCoverSource = findProjectFile(
            "app/src/main/java/io/legado/app/model/BookCover.kt"
        ).readText()
        val variableMapSource = findProjectFile(
            "app/src/main/java/io/legado/app/data/entities/VariableMap.kt"
        ).readText()
        val urlOptionsSource = findProjectFile(
            "app/src/main/java/io/legado/app/utils/UrlOptions.kt"
        ).readText()
        val readBookActivitySource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()
        val sourceCallBackSource = findProjectFile(
            "app/src/main/java/io/legado/app/model/SourceCallBack.kt"
        ).readText()
        val bookExtensionsSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/book/BookExtensions.kt"
        ).readText()
        val exploreAdapterSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/main/explore/ExploreAdapter.kt"
        ).readText()
        val textChapterLayoutSource = findProjectFile(
            "app/src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt"
        ).readText()
        val glideImageGetterSource = findProjectFile(
            "app/src/main/java/io/legado/app/help/GlideImageGetter.kt"
        ).readText()
        val viewExtensionsSource = findProjectFile(
            "app/src/main/java/io/legado/app/utils/ViewExtensions.kt"
        ).readText()
        val searchBookSource = findProjectFile(
            "app/src/main/java/io/legado/app/data/entities/SearchBook.kt"
        ).readText()
        val bookEntitySource = findProjectFile(
            "app/src/main/java/io/legado/app/data/entities/Book.kt"
        ).readText()
        val bookChapterSource = findProjectFile(
            "app/src/main/java/io/legado/app/data/entities/BookChapter.kt"
        ).readText()
        val rssArticleSource = findProjectFile(
            "app/src/main/java/io/legado/app/data/entities/RssArticle.kt"
        ).readText()
        val rssStarSource = findProjectFile(
            "app/src/main/java/io/legado/app/data/entities/RssStar.kt"
        ).readText()
        val serverSource = findProjectFile(
            "app/src/main/java/io/legado/app/data/entities/Server.kt"
        ).readText()
        val jsHelpSource = findProjectFile("app/src/main/assets/web/help/md/jsHelp.md").readText()
        val ruleHelpSource = findProjectFile("app/src/main/assets/web/help/md/ruleHelp.md").readText()

        assertFalse(jsExtensionsSource.contains("ZipInput" + "Stream"))
        assertFalse(jsExtensionsSource.contains("Zip" + "Entry"))
        assertFalse(jsExtensionsSource.contains("ByteArray" + "OutputStream"))
        assertFalse(jsExtensionsSource.contains("Encoder" + "Utils"))
        assertFalse(jsExtensionsSource.contains("HexUtil"))
        assertFalse(jsExtensionsSource.contains("EncodingDetect"))
        assertFalse(jsExtensionsSource.contains("cn.hutool.core.codec.Base" + "64"))
        assertFalse(jsExtensionsSource.contains("URL" + "Encoder"))
        assertFalse(jsExtensionsSource.contains("Simple" + "DateFormat"))
        assertFalse(jsExtensionsSource.contains("Lib" + "ArchiveUtils"))
        assertFalse(jsExtensionsSource.contains("Archive" + "Utils.deCompress"))
        assertFalse(jsExtensionsSource.contains("QueryTTF"))
        assertFalse(jsExtensionsSource.contains("CacheManager.get(key)"))
        assertFalse(jsExtensionsSource.contains("CacheManager.put(key"))
        assertFalse(jsExtensionsSource.contains("appCtx.external" + "Cache.absolutePath"))
        assertFalse(jsExtensionsSource.contains("EncodingDetect.getEncode(file)"))
        assertFalse(jsExtensionsSource.contains("FileUtils.delete(file"))
        assertFalse(jsExtensionsSource.contains("FileUtils.getCachePath"))
        assertFalse(jsExtensionsSource.contains("createFileReplace"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"readFile\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"unzipFile\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"un7zFile\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"unrarFile\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"unArchiveFile\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"getTxtInFolder\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"getFile\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"get7zStringContent\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"get7zByteArrayContent\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"getRarStringContent\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"getRarByteArrayContent\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"queryBase64TTF\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"queryTTF\")"))
        assertFalse(jsExtensionsSource.contains("rustUnsupportedHostApi(\"replaceFont\")"))
        assertFalse(jsExtensionsSource.contains("import java.io.File"))
        assertTrue(jsExtensionsSource.contains("class RustFile"))
        assertTrue(jsExtensionsSource.contains("fun getFile(path: String): RustFile"))
        assertTrue(jsExtensionsSource.contains("script = \"java.downloadFile("))
        assertTrue(jsExtensionsSource.contains("script = \"java.__downloadHexFile("))
        assertTrue(jsExtensionsSource.contains("script = \"java.readFile("))
        assertTrue(jsExtensionsSource.contains("script = \"java.readTxtFile(\${GSON.toJson(path)}, \${GSON.toJson(charsetName)})\""))
        assertTrue(jsExtensionsSource.contains("str.isNullOrBlank()"))
        assertTrue(jsExtensionsSource.contains("script = \"java.unzipFile("))
        assertTrue(jsExtensionsSource.contains("script = \"java.un7zFile("))
        assertTrue(jsExtensionsSource.contains("script = \"java.unrarFile("))
        assertTrue(jsExtensionsSource.contains("script = \"java.unArchiveFile("))
        assertTrue(jsExtensionsSource.contains("script = \"java.getTxtInFolder("))
        assertTrue(jsExtensionsSource.contains("script = \"java.get7zStringContent("))
        assertTrue(jsExtensionsSource.contains("script = \"java.get7zByteArrayContent("))
        assertTrue(jsExtensionsSource.contains("script = \"java.getRarStringContent("))
        assertTrue(jsExtensionsSource.contains("script = \"java.getRarByteArrayContent("))
        assertTrue(jsExtensionsSource.contains("script = \"java.queryBase64TTF("))
        assertTrue(jsExtensionsSource.contains("java.queryTTF("))
        assertTrue(jsExtensionsSource.contains("script = \"java.replaceFont("))
        assertFalse(jsExtensionsSource.contains("WebSettings.getDefaultUserAgent"))
        assertFalse(jsExtensionsSource.contains("CookieStore.getKey"))
        assertFalse(jsExtensionsSource.contains("CookieStore.getCookie"))
        assertFalse(jsExtensionsSource.contains("MD5Utils"))
        assertFalse(jsExtensionsSource.contains("import io.legado.app.utils.JsURL"))
        assertFalse(jsExtensionsSource.contains("return JsURL("))
        assertFalse(jsExtensionsSource.contains("AppConst.androidId"))
        assertFalse(jsExtensionsSource.contains("AppConst.appInfo"))
        assertFalse(jsExtensionsSource.contains("ReadBookConfig.durConfig"))
        assertFalse(jsExtensionsSource.contains("ThemeConfig.getDurConfig(appCtx)"))
        assertFalse(jsExtensionsSource.contains("AppConfig.themeMode"))
        assertTrue(jsExtensionsSource.contains("script = \"java.toURL("))
        assertTrue(jsExtensionsSource.contains("script = \"java.androidId()\""))
        assertTrue(jsExtensionsSource.contains("script = \"java.getAppVersionName()\""))
        assertTrue(jsExtensionsSource.contains("script = \"java.getAppVersionCode()\""))
        assertTrue(jsExtensionsSource.contains("script = \"java.getAppVariant()\""))
        assertTrue(jsExtensionsSource.contains("script = \"java.getReadBookConfig()\""))
        assertTrue(jsExtensionsSource.contains("script = \"java.getReadBookConfigMap()\""))
        assertTrue(jsExtensionsSource.contains("script = \"java.getThemeMode()\""))
        assertTrue(jsExtensionsSource.contains("script = \"java.getThemeConfig()\""))
        assertTrue(jsExtensionsSource.contains("script = \"java.getThemeConfigMap()\""))
        assertFalse(jsExtensionsSource.contains("getOrElse { URL(\"http://localhost/\") }"))
        assertTrue(jsExtensionsSource.contains("returned invalid response URL"))
        assertTrue(jsExtensionsSource.contains("Rust response object returned blank URL"))
        assertFalse(regexExtensionsSource.contains("Regex" + "JsExtensions"))
        assertFalse(regexExtensionsSource.contains("Chinese" + "Utils"))
        assertFalse(regexExtensionsSource.contains("RuleData("))
        assertFalse(regexExtensionsSource.contains("Rhino"))
        assertFalse(regexExtensionsSource.contains("com.script"))
        assertFalse(regexExtensionsSource.contains("evalJS("))
        assertFalse(regexExtensionsSource.contains("bindings[\"java\"]"))
        assertTrue(regexExtensionsSource.contains("RustAnalyzerBridge.evalJsRaw"))
        assertTrue(regexExtensionsSource.contains("bindingsJson = regexBindingsJson(book, chapter)"))
        assertFalse(rssJsExtensionsSource.contains("getSource()?.put(key, value)"))
        assertFalse(rssJsExtensionsSource.contains("getSource()?.get(key)"))
        assertTrue(rssJsExtensionsSource.contains("script = \"source.put("))
        assertTrue(rssJsExtensionsSource.contains("script = \"source.get("))
        assertFalse(baseSourceSource.contains("cn.hutool.crypto.symmetric.AES"))
        assertFalse(baseSourceSource.contains("Symmetric" + "CryptoAndroid"))
        assertFalse(baseSourceSource.contains("CacheManager.get(\"userInfo_"))
        assertFalse(baseSourceSource.contains("CacheManager.put(\"userInfo_"))
        assertFalse(baseSourceSource.contains("CacheManager.delete(\"userInfo_"))
        assertFalse(baseSourceSource.contains("CacheManager.put(\"sourceVariable_"))
        assertFalse(baseSourceSource.contains("CacheManager.get(\"sourceVariable_"))
        assertFalse(baseSourceSource.contains("CacheManager.delete(\"sourceVariable_"))
        assertFalse(baseSourceSource.contains("CacheManager.put(\"v_"))
        assertFalse(baseSourceSource.contains("CacheManager.get(\"v_"))
        assertFalse(baseSourceSource.contains("import io.legado.app.help.CacheManager"))
        assertFalse(baseSourceSource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(baseSourceSource.contains("import io.legado.app.help.http.CookieStore"))
        assertFalse(baseSourceSource.contains("执行请求头规则出错"))
        assertFalse(baseSourceSource.contains("catch (e: Exception)"))
        assertFalse(baseSourceSource.contains("return GSON.fromJsonObject<Map<String, String>>(json).getOrNull()"))
        assertFalse(baseSourceSource.contains("GSON.fromJsonArray<RowUi>(loginUiJson).getOrNull()"))
        assertFalse(baseSourceSource.contains("return GSON.fromJsonObject<MutableMap<String, String>>(json).getOrNull() ?: mutableMapOf()"))
        assertFalse(baseSourceSource.contains("GSONStrict.fromJsonObject<Map<String, String>>(json).getOrNull()"))
        assertFalse(baseSourceSource.contains("请求头规则 JSON 格式不规范"))
        assertTrue(baseSourceSource.contains("parseHeaderRuleMap(json, \"BaseSource.getHeaderMap\")"))
        assertTrue(baseSourceSource.contains("parseHeaderRuleMap(json, \"BaseSource.getLoginHeaderMap\")"))
        assertTrue(baseSourceSource.contains("header JSON is invalid for Rust analyzer handoff"))
        assertTrue(baseSourceSource.contains("must return a JSON object"))
        assertTrue(baseSourceSource.contains("parseLoginUiRows(loginUiJson)"))
        assertTrue(baseSourceSource.contains("loginUi must return a JSON array"))
        assertTrue(baseSourceSource.contains("login info must return a JSON object"))
        assertFalse(baseSourceSource.contains("put(\"cache\", CacheManager)"))
        assertFalse(baseSourceSource.contains("put(\"cookie\", CookieStore)"))
        assertTrue(baseSourceSource.contains("script = \"source.getLoginInfo()\""))
        assertTrue(baseSourceSource.contains("script = \"source.putLoginInfo("))
        assertTrue(baseSourceSource.contains("script = \"source.removeLoginInfo()\""))
        assertTrue(baseSourceSource.contains("script = \"source.setVariable("))
        assertTrue(baseSourceSource.contains("script = \"source.putVariable("))
        assertTrue(baseSourceSource.contains("script = \"source.getVariable()\""))
        assertTrue(baseSourceSource.contains("script = \"source.put("))
        assertTrue(baseSourceSource.contains("script = \"source.get("))
        assertFalse(bookSourceSource.contains("GSON.fromJsonObject<ExploreRule>(json).getOrNull()"))
        assertFalse(bookSourceSource.contains("GSON.fromJsonObject<SearchRule>(json).getOrNull()"))
        assertFalse(bookSourceSource.contains("GSON.fromJsonObject<BookInfoRule>(json).getOrNull()"))
        assertFalse(bookSourceSource.contains("GSON.fromJsonObject<TocRule>(json).getOrNull()"))
        assertFalse(bookSourceSource.contains("GSON.fromJsonObject<ContentRule>(json).getOrNull()"))
        assertTrue(bookSourceSource.contains("parseRule<ExploreRule>(json, \"ruleExplore\")"))
        assertTrue(bookSourceSource.contains("BookSource \$ruleName JSON is invalid for Rust analyzer handoff"))
        assertFalse(infoMapSource.contains("CacheManager"))
        assertFalse(infoMapSource.contains("infoMap_"))
        assertTrue(infoMapSource.contains("RustAnalyzerBridge.evalJs"))
        assertTrue(infoMapSource.contains("source.get("))
        assertTrue(infoMapSource.contains("source.put("))
        assertFalse(infoMapSource.contains("GSON.fromJsonObject<MutableMap<String, String>>(cache).getOrNull() ?: mutableMapOf()"))
        assertTrue(infoMapSource.contains("InfoMap Rust source state JSON is invalid"))
        assertFalse(bookSourceWebControllerSource.contains("GSON.fromJsonObject<Map<String, Any?>>(postData).getOrNull()"))
        assertFalse(bookSourceWebControllerSource.contains("kotlin.runCatching {\n            source.login()"))
        assertTrue(bookSourceWebControllerSource.contains("BookSourceWebController.saveLoginData JSON is invalid for Rust analyzer handoff"))
        assertTrue(bookSourceWebControllerSource.contains("BookSourceWebController Rust login failed for \${source.getTag()}"))
        assertFalse(bookControllerSource.contains("GSON.fromJsonObject<Book>(postData).getOrNull()"))
        assertTrue(bookControllerSource.contains("BookController.saveBook JSON is invalid for Rust analyzer handoff"))
        assertTrue(bookControllerSource.contains("BookController.deleteBook JSON is invalid for Rust analyzer handoff"))
        assertFalse(cacheManagerSource.contains("clearSourceVariables"))
        assertFalse(cacheManagerSource.contains("userInfo_"))
        assertFalse(cacheManagerSource.contains("sourceVariable_"))
        assertFalse(cacheManagerSource.contains("loginHeader_"))
        assertFalse(cacheManagerSource.contains("it.startsWith(\"v_"))
        assertFalse(cacheManagerSource.contains("QueryTTF"))
        assertFalse(cacheManagerSource.contains("AppCacheManager"))
        assertFalse(cacheDaoSource.contains("deleteSourceVariables"))
        assertFalse(cacheDaoSource.contains("userInfo_"))
        assertFalse(cacheDaoSource.contains("sourceVariable_"))
        assertFalse(cacheDaoSource.contains("loginHeader_"))
        assertFalse(cacheDaoSource.contains("infoMap_"))
        assertFalse(bookSourceEditSource.contains("import io.legado.app.help.http.CookieStore"))
        assertFalse(bookSourceEditSource.contains("CookieStore.removeCookie"))
        assertFalse(rssSourceEditSource.contains("import io.legado.app.help.http.CookieStore"))
        assertFalse(rssSourceEditSource.contains("CookieStore.removeCookie"))
        assertTrue(bookSourceEditSource.contains("script = \"cookie.removeCookie("))
        assertTrue(rssSourceEditSource.contains("script = \"cookie.removeCookie("))
        assertFalse(webViewLoginSource.contains("import io.legado.app.help.http.CookieStore"))
        assertFalse(webViewLoginSource.contains("CookieStore.setCookie"))
        assertTrue(webViewLoginSource.contains("script = \"cookie.setCookie(source.getKey()"))
        assertFalse(sourceLoginDialogSource.contains("source.getTag() + \" loginUi err:\""))
        assertFalse(sourceLoginDialogSource.contains("AppLog.put(\"loginUi json parse err:\""))
        assertFalse(sourceLoginDialogSource.contains("LoginUI Button \$name JavaScript error"))
        assertFalse(sourceLoginDialogSource.contains("登录出错"))
        assertFalse(sourceLoginDialogSource.contains("}.getOrNull()\n    }\n\n    @SuppressLint(\"SetTextI18n\")"))
        assertTrue(sourceLoginDialogSource.contains("SourceLoginDialog loginUi must return a JSON array"))
        assertTrue(sourceLoginDialogSource.contains("LoginUI Button \$name Rust JavaScript failed"))
        assertTrue(sourceLoginDialogSource.contains("SourceLoginDialog login Rust JavaScript failed"))
        assertFalse(browserWebViewSource.contains("import io.legado.app.help.http.CookieStore"))
        assertFalse(browserWebViewSource.contains("CookieStore.setCookie"))
        assertFalse(browserWebViewSource.contains("AppCookieManager.applyToWebView"))
        assertTrue(browserWebViewSource.contains("RustAnalyzerBridge.applyCookieToWebView(it, url, \"WebViewActivity.applyCookieToWebView\")"))
        assertTrue(browserWebViewSource.contains("rulePath = \"WebViewActivity.syncCookie\""))
        assertTrue(browserWebViewSource.contains("script = \"cookie.setCookie("))
        assertFalse(rssReadSource.contains("import io.legado.app.help.http.CookieManager"))
        assertFalse(rssReadSource.contains("CookieManager.applyToWebView"))
        assertFalse(rssReadSource.contains("url跳转拦截js出错"))
        assertFalse(rssReadSource.contains("url跳转拦截js执行耗时过长"))
        assertTrue(rssReadSource.contains("RustAnalyzerBridge.applyCookieToWebView("))
        assertTrue(rssReadSource.contains("Rust shouldOverrideUrlLoading JS failed"))
        assertTrue(rssReadSource.contains("Rust shouldOverrideUrlLoading JS exceeded 99ms"))
        assertFalse(rssViewModelSource.contains("val result = rssSource.evalJS(jsStr)?.toString()\n                    if"))
        assertTrue(rssViewModelSource.contains("?: throw NoStackTraceException("))
        assertTrue(rssViewModelSource.contains("Rust RSS sortUrl JavaScript returned no result"))
        assertFalse(rssJsExtensionsSource.contains("redirectUrl = runCatching { URL(url) }.getOrNull()"))
        assertTrue(rssJsExtensionsSource.contains("RssJsExtensions.setRedirectUrl received invalid URL from Rust analyzer JS"))
        assertFalse(rustAnalyzerBridgeSource.contains("import io.legado.app.help.http.CookieStore"))
        assertFalse(rustAnalyzerBridgeSource.contains("CookieStore.getCookie"))
        assertTrue(rustAnalyzerBridgeSource.contains("script = \"cookie.getCookie("))
        assertFalse(rustAnalyzerBridgeSource.contains("result.book?.applyToBook"))
        assertFalse(rustAnalyzerBridgeSource.contains("result.content?.content ?: \"\""))
        assertFalse(rustAnalyzerBridgeSource.contains("evalResult.orEmpty()"))
        assertFalse(rustAnalyzerBridgeSource.contains("rssContent.orEmpty()"))
        assertFalse(rustAnalyzerBridgeSource.contains("getOrDefault(emptyList())"))
        assertFalse(rustAnalyzerBridgeSource.contains("Class.forName(\"io.legado.app.rust.Legado_nativeKt\").getMethod"))
        assertFalse(rustAnalyzerBridgeSource.contains("runCatching { Class.forName(\"io.legado.app.rust.PlatformHost\") }.getOrNull()"))
        assertFalse(rustAnalyzerBridgeSource.contains("response.url.ifBlank { url }"))
        assertFalse(rustAnalyzerBridgeSource.contains("return runCatching {\n            when (api)"))
        assertFalse(rustAnalyzerBridgeSource.contains(").orEmpty()\n                    } else {\n                        jsJava.webView"))
        assertFalse(rustAnalyzerBridgeSource.contains("GSON.fromJsonArray<Any?>(json).getOrNull()"))
        assertFalse(rustAnalyzerBridgeSource.contains("GSON.fromJsonObject<Map<String, Any?>>(json).getOrNull()"))
        assertFalse(rustAnalyzerBridgeSource.contains("headers.mapNotNull { pair ->"))
        assertFalse(rustAnalyzerBridgeSource.contains("val key = pair.getOrNull(0)?.takeIf { it.isNotBlank() }"))
        assertFalse(rustAnalyzerBridgeSource.contains("api = args?.getOrNull(0)?.toString().orEmpty()"))
        assertFalse(rustAnalyzerBridgeSource.contains("value.toBooleanStrictOrNull() ?: default"))
        assertFalse(rustAnalyzerBridgeSource.contains("toLongOrNull() ?: 0L"))
        assertFalse(rustAnalyzerBridgeSource.contains("java.copyText(stringArg(0))"))
        assertFalse(rustAnalyzerBridgeSource.contains("java.searchBook(stringArg(0), stringArg(1).ifBlank { null })"))
        assertFalse(rustAnalyzerBridgeSource.contains("java.addBook(stringArg(0))"))
        assertFalse(rustAnalyzerBridgeSource.contains("java.showPhoto(stringArg(0))"))
        assertFalse(rustAnalyzerBridgeSource.contains("java.openUrl(stringArg(0), stringArg(1).ifBlank { null })"))
        assertTrue(rustAnalyzerBridgeSource.contains("import io.legado.app.ui.rss.read.RssJsExtensions"))
        assertTrue(rustAnalyzerBridgeSource.contains("val java = platformJava as? RssJsExtensions"))
        assertTrue(rustAnalyzerBridgeSource.contains("searchBook requires RssJsExtensions UI context"))
        assertTrue(rustAnalyzerBridgeSource.contains("addBook requires RssJsExtensions UI context"))
        assertTrue(rustAnalyzerBridgeSource.contains("showPhoto requires RssJsExtensions UI context"))
        assertTrue(rustAnalyzerBridgeSource.contains("open requires RssJsExtensions UI context"))
        assertFalse(rustAnalyzerBridgeSource.contains("searchBook requires SourceLoginJsExtensions UI context"))
        assertFalse(rustAnalyzerBridgeSource.contains("addBook requires SourceLoginJsExtensions UI context"))
        assertFalse(rustAnalyzerBridgeSource.contains("showPhoto requires SourceLoginJsExtensions UI context"))
        assertFalse(rustAnalyzerBridgeSource.contains("open requires SourceLoginJsExtensions UI context"))
        assertTrue(rustAnalyzerBridgeSource.contains("returned malformed resolved URL header pair"))
        assertTrue(rustAnalyzerBridgeSource.contains("returned blank resolved URL header name"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust platform action callback returned malformed argument count"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust platform action callback returned blank API name"))
        assertTrue(rustAnalyzerBridgeSource.contains("returned missing \$label arg at \$index"))
        assertTrue(rustAnalyzerBridgeSource.contains("returned blank \$label arg at \$index"))
        assertTrue(rustAnalyzerBridgeSource.contains("returned invalid boolean arg at"))
        assertTrue(rustAnalyzerBridgeSource.contains("returned invalid long arg at"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust analyzer detail returned no book"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust analyzer content returned no content"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust \$rulePath returned no eval output"))
        assertTrue(rustAnalyzerBridgeSource.contains("returned invalid args JSON"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust eval StrResponse returned blank URL"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust eval returned unsupported JSON marker value"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust analyzer UniFFI class is missing"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust analyzer UniFFI method is missing"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust \$rulePath returned blank raw response URL"))
        assertTrue(rustAnalyzerBridgeSource.contains("Rust platform action \$api returned no body from Android boundary"))
        assertTrue(rustAnalyzerBridgeSource.contains("refreshBookInfo requires SourceLoginJsExtensions UI context"))
        assertTrue(rustAnalyzerBridgeSource.contains("runPreUpdateJs = runPreUpdateJs"))
        assertFalse(bookshelfManageViewModelSource.contains("搜索书籍出错"))
        assertFalse(bookshelfManageViewModelSource.contains("获取书籍详情出错"))
        assertFalse(bookshelfManageViewModelSource.contains("获取目录出错"))
        assertFalse(bookshelfManageViewModelSource.contains(".getOrNull() ?: return@forEachIndexed"))
        assertFalse(bookshelfManageViewModelSource.contains(".getOrNull()?.let { toc ->"))
        assertTrue(bookshelfManageViewModelSource.contains("BookshelfManage batch change source Rust search failed"))
        assertTrue(bookshelfManageViewModelSource.contains("BookshelfManage batch change source Rust detail failed"))
        assertTrue(bookshelfManageViewModelSource.contains("BookshelfManage batch change source Rust toc failed"))
        assertFalse(searchModelSource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(searchModelSource.contains("书源搜索出错"))
        assertFalse(searchModelSource.contains("mapParallelSafe"))
        assertTrue(searchModelSource.contains("SearchModel Rust search failed for \$searchKey"))
        assertFalse(webBookSource.contains("val book = preciseSearchAwait(source, name, author).getOrNull()"))
        assertTrue(webBookSource.contains("Precise Rust search failed for \${source.bookSourceName}"))
        assertFalse(changeBookSourceViewModelSource.contains("catch (_: Throwable)"))
        assertFalse(changeBookSourceViewModelSource.contains("mapParallelSafe"))
        assertFalse(changeBookSourceViewModelSource.contains("换源搜索出错"))
        assertFalse(changeBookSourceViewModelSource.contains("换源刷新列表出错"))
        assertFalse(changeBookSourceViewModelSource.contains("自动换源失败"))
        assertFalse(changeBookSourceViewModelSource.contains("val result = getToc(book).getOrNull()"))
        assertTrue(changeBookSourceViewModelSource.contains("Change source Rust search failed for \$name"))
        assertTrue(changeBookSourceViewModelSource.contains("Change source refresh Rust detail/toc failed for \$name"))
        assertTrue(changeBookSourceViewModelSource.contains("Change source auto Rust toc failed for \$name"))
        assertTrue(changeBookSourceViewModelSource.contains("val result = getToc(book).getOrThrow()"))
        assertFalse(bookInfoViewModelSource.contains("获取书籍信息失败"))
        assertFalse(bookInfoViewModelSource.contains("获取目录失败"))
        assertTrue(bookInfoViewModelSource.contains("BookInfo Rust detail failed for \${book.name}"))
        assertTrue(bookInfoViewModelSource.contains("BookInfo Rust toc failed for \${book.name}"))
        assertFalse(cacheBookSource.contains("downloadFinish(chapter, \"获取正文失败"))
        assertFalse(cacheBookSource.contains("return \"获取正文失败"))
        assertTrue(cacheBookSource.contains("CacheBook Rust content failed for \${book.name}-\${chapter.title}"))
        assertFalse(readBookSource.contains("加载正文出错"))
        assertTrue(readBookSource.contains("ReadBook Rust content failed at chapter \$index"))
        assertFalse(readBookViewModelSource.contains("mapParallelSafe"))
        assertFalse(readBookViewModelSource.contains("详情页出错:"))
        assertFalse(readBookViewModelSource.contains("自动换源失败"))
        assertTrue(readBookViewModelSource.contains("ReadBook Rust detail failed for \${book.name}"))
        assertTrue(readBookViewModelSource.contains("ReadBook Rust toc failed for \${book.name}"))
        assertTrue(readBookViewModelSource.contains("ReadBook auto change source Rust analyzer failed for \$name"))
        assertFalse(readMangaSource.contains("加载正文出错"))
        assertTrue(readMangaSource.contains("ReadManga Rust content failed at chapter \$index"))
        assertFalse(readMangaViewModelSource.contains("mapParallelSafe"))
        assertFalse(readMangaViewModelSource.contains("详情页出错:"))
        assertFalse(readMangaViewModelSource.contains("自动换源失败"))
        assertTrue(readMangaViewModelSource.contains("ReadManga Rust detail failed for \${book.name}"))
        assertTrue(readMangaViewModelSource.contains("ReadManga Rust toc failed for \${book.name}"))
        assertTrue(readMangaViewModelSource.contains("ReadManga auto change source Rust analyzer failed for \$name"))
        assertFalse(audioPlayViewModelSource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(audioPlayViewModelSource.contains("详情页出错:"))
        assertFalse(audioPlayViewModelSource.contains("context.toastOnUi(R.string.error_load_toc)"))
        assertTrue(audioPlayViewModelSource.contains("AudioPlay Rust detail failed for \${book.name}"))
        assertTrue(audioPlayViewModelSource.contains("AudioPlay Rust toc failed for \${book.name}"))
        assertFalse(audioPlaySource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(audioPlaySource.contains("cache_manage_audio_url_empty"))
        assertFalse(audioPlaySource.contains("获取资源链接出错"))
        assertTrue(audioPlaySource.contains("AudioPlay Rust content returned empty media URL"))
        assertTrue(audioPlaySource.contains("AudioPlay Rust content/media URL failed"))
        assertFalse(videoPlaySource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(videoPlaySource.contains("加载视频链接失败"))
        assertFalse(videoPlaySource.contains("加载订阅源视频链接失败"))
        assertFalse(videoPlaySource.contains("加载订阅源为链接的正文失败"))
        assertFalse(videoPlaySource.contains("获取资源链接出错"))
        assertFalse(videoPlaySource.contains("catch (_: Throwable)"))
        assertTrue(videoPlaySource.contains("VideoPlay Rust single URL media resolution failed"))
        assertTrue(videoPlaySource.contains("VideoPlay Rust RSS link media resolution failed"))
        assertTrue(videoPlaySource.contains("VideoPlay Rust RSS content/media URL failed"))
        assertTrue(videoPlaySource.contains("VideoPlay Rust chapter content/media URL failed"))
        assertTrue(videoPlaySource.contains("VideoPlay Rust preload content/media URL failed"))
        assertFalse(cacheBookServiceSource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(cacheBookServiceSource.contains("目录为空且加载详情页失败"))
        assertFalse(cacheBookServiceSource.contains("目录为空且加载目录失败"))
        assertFalse(cacheBookServiceSource.contains(".getOrNull()?.let { toc ->"))
        assertTrue(cacheBookServiceSource.contains("CacheBookService Rust detail failed for \$name"))
        assertTrue(cacheBookServiceSource.contains("CacheBookService Rust toc failed for \$name"))
        assertFalse(mainViewModelSource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(mainViewModelSource.contains("更新目录出错"))
        assertFalse(mainViewModelSource.contains("更新目录失败"))
        assertTrue(mainViewModelSource.contains("Bookshelf refresh Rust toc flow failed"))
        assertTrue(mainViewModelSource.contains("Bookshelf refresh Rust detail/toc failed for \${book.name}"))
        assertFalse(bookshelfViewModelSource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(bookshelfViewModelSource.contains("添加网址出错"))
        assertFalse(bookshelfViewModelSource.contains("kotlin.runCatching {\n                    WebBook.getBookInfoAwait(bookSource, book)"))
        assertFalse(webBookSource.contains("val source = s.getBookSource() ?: continue"))
        assertTrue(webBookSource.contains("Precise Rust search source is missing from database"))
        assertTrue(bookshelfViewModelSource.contains("Bookshelf add URL Rust detail failed for \$bookUrl"))
        assertTrue(bookshelfViewModelSource.contains("Bookshelf add URL Rust toc failed for \${rustBook.name}"))
        assertTrue(bookshelfViewModelSource.contains("Bookshelf add URL Rust analyzer failed"))
        assertFalse(changeBookSourceDialogSource.contains("换源获取目录出错"))
        assertTrue(changeBookSourceDialogSource.contains("ChangeBookSourceDialog Rust toc failed for \${book.name}"))
        assertFalse(changeChapterSourceDialogSource.contains("单章换源获取目录出错"))
        assertTrue(changeChapterSourceDialogSource.contains("ChangeChapterSourceDialog Rust toc failed for \${book.name}"))
        assertFalse(changeCoverViewModelSource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(changeCoverViewModelSource.contains("mapParallelSafe"))
        assertFalse(changeCoverViewModelSource.contains("封面规则搜索出错"))
        assertFalse(changeCoverViewModelSource.contains("封面换源搜索出错"))
        assertTrue(changeCoverViewModelSource.contains("ChangeCover default Rust cover search failed for \$name"))
        assertTrue(changeCoverViewModelSource.contains("ChangeCover source Rust cover search failed for \$name"))
        assertFalse(rustPlatformActionSource.contains("GSON.fromJsonArray<String>(parts[2]).getOrDefault(emptyList())"))
        assertFalse(rustPlatformActionSource.contains("GSON.fromJsonObject<Map<String, Any?>>(it).getOrNull()"))
        assertFalse(rustPlatformActionSource.contains("if (url.isBlank()) return false"))
        assertFalse(rustPlatformActionSource.contains("toBooleanStrictOrNull() ?: false"))
        assertTrue(rustPlatformActionSource.contains("Rust platform action marker is malformed"))
        assertTrue(rustPlatformActionSource.contains("Rust platform action \$api returned invalid args JSON"))
        assertTrue(rustPlatformActionSource.contains("Rust platform action \$api returned invalid login-data JSON"))
        assertTrue(rustPlatformActionSource.contains("Rust platform action \$api returned blank URL"))
        assertTrue(rustPlatformActionSource.contains("Rust platform action \$api returned invalid boolean"))
        assertFalse(webBookSource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(webBookSource.contains("执行preUpdateJs规则失败"))
        assertFalse(webBookSource.contains("runPreUpdateJs(bookSource: BookSource, book: Book, isFromBookInfo : Boolean = false): Result<Unit>"))
        assertTrue(webBookSource.contains("RustAnalyzerBridge.preUpdateToc(bookSource, book)"))
        assertTrue(webBookSource.contains("RustAnalyzerBridge.toc(bookSource, book, runPreUpdateJs = runPerJs)"))
        assertTrue(rustAnalyzerBridgeSource.contains("CookieManager.getInstance()"))
        assertTrue(rustAnalyzerBridgeSource.contains("syncRustCookiesToWebView(output.session)"))
        assertTrue(rustAnalyzerBridgeSource.contains("private fun syncRustCookiesToWebView(session: RustSession)"))
        assertTrue(rustAnalyzerBridgeSource.contains("cookieManager.setCookie(url, value)"))
        assertTrue(rustAnalyzerBridgeSource.contains("cookieManager.getCookie(source.getKey())"))
        assertTrue(rustAnalyzerBridgeSource.contains("\"cookies\" to cookies"))
        assertTrue(rustAnalyzerBridgeSource.contains("cookies = platformCookies(source, pageUrl)"))
        assertFalse(backstageWebViewSource.contains("CookieStore.setCookie"))
        assertTrue(backstageWebViewSource.contains("RustAnalyzerBridge.evalJs"))
        assertTrue(backstageWebViewSource.contains("rulePath = \"BackstageWebView.syncCookie\""))
        assertFalse(directLinkUploadSource.contains("ZipUtils"))
        assertFalse(directLinkUploadSource.contains("createFileReplace"))
        assertFalse(directLinkUploadSource.contains("externalCache"))
        assertFalse(directLinkUploadSource.contains("return GSON.fromJsonObject<Rule>(json).getOrNull()"))
        assertTrue(directLinkUploadSource.contains("compress = rule.compress"))
        assertTrue(rustAnalyzerBridgeSource.contains("uploadCompress = compress"))
        assertTrue(directLinkUploadSource.contains("DirectLinkUpload rule JSON is invalid for Rust analyzer handoff"))
        assertFalse(aiBookSourceToolSource.contains("GSON.fromJsonObject<BookSource>(merged.toString()).getOrNull()"))
        assertFalse(aiBookSourceToolSource.contains("GSON.fromJsonObject<BookSource>(json).getOrNull()?.let { return it }"))
        assertFalse(aiBookSourceToolSource.contains("runCatching { JSONObject(it) }.getOrNull()"))
        assertFalse(aiBookSourceToolSource.contains("return GSON.fromJsonObject<T>(toString()).getOrNull()"))
        assertTrue(aiBookSourceToolSource.contains("parseBookSourceJson(merged.toString(), \"AiBookSourceTool.updateBookSource\")"))
        assertTrue(aiBookSourceToolSource.contains("AiBookSourceTool patch JSON is invalid for Rust analyzer handoff"))
        assertTrue(aiBookSourceToolSource.contains("AiBookSourceTool rule JSON is invalid for Rust analyzer handoff"))
        assertTrue(aiBookSourceToolSource.contains("BookSource JSON is invalid for Rust analyzer handoff"))
        assertFalse(aiBookSourceToolSource.contains("runCatching { URI(url).host.orEmpty() }.getOrDefault(\"\")"))
        assertFalse(aiBookSourceToolSource.contains("}.getOrDefault(url)"))
        assertFalse(aiBookSourceToolSource.contains("}.getOrElse { throwable ->\n            error(throwable.localizedMessage ?: throwable.javaClass.simpleName)"))
        assertFalse(aiBookSourceToolSource.contains("val sources = sourceUrls.mapNotNull"))
        assertFalse(aiBookSourceToolSource.contains("if (sources.isEmpty()) return error(\"未找到指定书源\")"))
        assertFalse(aiBookSourceToolSource.contains("return error(\"缺少 bookSourceUrl 或 searchKey\")"))
        assertTrue(aiBookSourceToolSource.contains("AiBookSourceTool get_book_source missing source"))
        assertTrue(aiBookSourceToolSource.contains("AiBookSourceTool get_book_source missing bookSourceUrl or searchKey"))
        assertTrue(aiBookSourceToolSource.contains("AiBookSourceTool temporary source URL is invalid for Rust analyzer handoff"))
        assertFalse(aiSettingsToolSource.contains("private fun jsonError"))
        assertFalse(aiSettingsToolSource.contains("return jsonError(\"missing arguments\")"))
        assertFalse(aiSettingsToolSource.contains("return jsonError(\"missing items\")"))
        assertFalse(aiSettingsToolSource.contains("val item = items.optJSONObject(index) ?: continue"))
        assertFalse(aiSettingsToolSource.contains("return runCatching {"))
        assertFalse(aiSettingsToolSource.contains("rawValue.equals(\"true\", true)"))
        assertTrue(aiSettingsToolSource.contains("AiSettingsTool unsupported setting key"))
        assertTrue(aiSettingsToolSource.contains("AiSettingsTool invalid boolean"))
        assertFalse(aiChatServiceSource.contains("runCatching { AiToolRegistry.resolveAvailableTools() }.getOrDefault(emptyList())"))
        assertTrue(aiChatServiceSource.contains("val tools = AiToolRegistry.resolveAvailableTools()"))
        assertFalse(aiChatServiceSource.contains("JSONObject(result).optBoolean(\"ok\", true)\n        }.getOrDefault(true)"))
        assertFalse(aiChatServiceSource.contains("return runCatching {\n            val arguments = toolCall.arguments.trim()"))
        assertFalse(aiChatServiceSource.contains("runCatching { extractContent(rawPayload.toString()) }.getOrDefault(\"\")"))
        assertFalse(aiChatServiceSource.contains(".mapNotNull { line ->"))
        assertFalse(aiChatServiceSource.contains("val first = choices.optJSONObject(0) ?: return \"\""))
        assertTrue(aiChatServiceSource.contains("AiChatService search_book_source result missing results array"))
        assertTrue(aiChatServiceSource.contains("AiChatService custom header line is malformed"))
        assertTrue(aiChatServiceSource.contains("AiChatService response missing choices/response content"))
        assertFalse(aiMcpClientSource.contains("}.getOrElse {\n                sessionMap.remove(server.id)\n                emptyList()"))
        assertFalse(aiMcpClientSource.contains("response.optJSONObject(\"result\") ?: JSONObject()"))
        assertFalse(aiMcpClientSource.contains("result.optJSONArray(\"tools\") ?: JSONArray()"))
        assertFalse(aiMcpClientSource.contains("toolArray.optJSONObject(index) ?: continue"))
        assertFalse(aiMcpClientSource.contains("response.optJSONObject(\"result\") ?: JSONObject()).toString()"))
        assertTrue(aiMcpClientSource.contains("AiMcpClient failed to resolve tools"))
        assertTrue(aiMcpClientSource.contains("AiMcpClient tools/list missing result"))
        assertTrue(aiMcpClientSource.contains("AiMcpClient tools/call missing result"))
        assertTrue(aiMcpClientSource.contains("AiMcpClient initialized notification failed"))
        assertFalse(aiBookshelfToolSource.contains("}.getOrNull()\n            ?: return@withContext errorJson(\"正文未缓存且读取失败\")"))
        assertFalse(aiBookshelfToolSource.contains("put(\"ok\", false)"))
        assertFalse(aiBookshelfToolSource.contains("?: return@withContext errorJson(\"未找到书籍\")"))
        assertFalse(aiBookshelfToolSource.contains("?: return@withContext errorJson(\"未找到章节\")"))
        assertFalse(aiBookshelfToolSource.contains("val matches = scopedBooks.filter { it.bookUrl in urlSet }\n            return matches"))
        assertTrue(aiBookshelfToolSource.contains("AiBookshelfTool Rust content failed for \${book.name}-\${chapter.title}"))
        assertTrue(aiBookshelfToolSource.contains("AiBookshelfTool target book URLs not found"))
        assertTrue(aiBookshelfToolSource.contains("AiBookshelfTool read chapter target chapter not found"))
        assertFalse(aiLibraryToolSource.contains("val errors = JSONArray()"))
        assertFalse(aiLibraryToolSource.contains("put(\"errors\", errors)"))
        assertFalse(aiLibraryToolSource.contains("}.onFailure { throwable ->"))
        assertTrue(aiLibraryToolSource.contains("AiLibraryTool Rust search failed for \${source.bookSourceName}"))
        assertFalse(addToBookshelfDialogSource.contains("WebBook.getBookInfoAwait(source, book)\n            }.getOrNull()"))
        assertFalse(addToBookshelfDialogSource.contains("getBookInfo(bookUrl, source)?.let"))
        assertTrue(addToBookshelfDialogSource.contains("AddToBookshelf Rust detail failed for \$bookUrl from \${source.bookSourceName}"))
        assertFalse(httpReadAloudServiceSource.contains("TTS下载音频出错，使用无声音频代替"))
        assertFalse(httpReadAloudServiceSource.contains("break\n                        }"))
        assertTrue(httpReadAloudServiceSource.contains("HttpReadAloud Rust TTS fetch failed for text"))
        assertFalse(httpTtsSource.contains("name = doc.readString(\"$.name\")!!"))
        assertFalse(httpTtsSource.contains("url = doc.readString(\"$.url\")!!"))
        assertTrue(httpTtsSource.contains("HttpTTS JSON missing name for Rust analyzer handoff"))
        assertTrue(httpTtsSource.contains("HttpTTS JSON missing url for Rust analyzer handoff"))
        assertFalse(ttsReadAloudServiceSource.contains("GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value"))
        assertTrue(ttsReadAloudServiceSource.contains("TTSReadAloudService engine JSON is invalid for Rust analyzer state handoff"))
        assertFalse(importHttpTtsDialogSource.contains("HttpTTS.fromJson(code).getOrNull()?.let"))
        assertTrue(importHttpTtsDialogSource.contains("ImportHttpTtsDialog code JSON is invalid for Rust analyzer handoff"))
        assertFalse(cacheManageViewModelSource.contains("var lastError: Throwable? = null"))
        assertFalse(cacheManageViewModelSource.contains("lastError = e"))
        assertFalse(cacheManageViewModelSource.contains("lastError?.localizedMessage ?: context.getString"))
        assertFalse(cacheManageViewModelSource.contains("GSON.fromJsonArray<String>(content).getOrNull().orEmpty()"))
        assertTrue(cacheManageViewModelSource.contains("RustAnalyzerBridge.resolveMediaRequest("))
        assertTrue(cacheManageViewModelSource.contains("CacheManage media content URL array JSON is invalid for Rust analyzer handoff"))
        assertFalse(exploreShowViewModelSource.contains("it.printOnDebug()"))
        assertTrue(exploreShowViewModelSource.contains("ExploreShow Rust explore failed for \${source.bookSourceName} page \$page"))
        assertFalse(rssArticlesViewModelSource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(rssArticlesViewModelSource.contains("rss获取内容失败"))
        assertTrue(rssArticlesViewModelSource.contains("RssArticles Rust list failed for \${rssSource.sourceName} page \$page"))
        assertTrue(contentEditDialogSource.contains("ContentEdit Rust content failed for current chapter"))
        assertFalse(exploreFragmentSource.contains("AppLog.put(\"新版发现页加载失败\""))
        assertTrue(exploreFragmentSource.contains("ExploreFragment Rust explore failed for \${source.bookSourceName} page \$discoverPage"))
        assertFalse(bookTocLoadingSource.contains("import io.legado.app.constant.AppLog"))
        assertFalse(bookTocLoadingSource.contains("openBookInfo(it.localizedMessage)"))
        assertFalse(bookTocLoadingSource.contains("AppLog.put(\"LoadTocError:"))
        assertTrue(bookTocLoadingSource.contains("BookTocLoading Rust detail/toc failed"))
        assertFalse(audioPlayServiceSource.contains("NoStackTraceException(\"url格式错误\")\n                    return@execute"))
        assertFalse(audioPlayServiceSource.contains("AppLog.put(\"播放出错"))
        assertTrue(audioPlayServiceSource.contains("AudioPlayService Rust media JSON returned invalid media source"))
        assertTrue(audioPlayServiceSource.contains("AudioPlayService Rust media play failed for \$url"))
        assertFalse(importBookSourceDialogSource.contains("GSON.fromJsonObject<BookSource>(code).getOrNull()?.let"))
        assertFalse(importRssSourceDialogSource.contains("GSON.fromJsonObject<RssSource>(code).getOrNull()?.let"))
        assertFalse(importReplaceRuleDialogSource.contains("GSON.fromJsonObject<ReplaceRule>(code).getOrNull()?.let"))
        assertFalse(importDictRuleDialogSource.contains("GSON.fromJsonObject<DictRule>(code).getOrNull()?.let"))
        assertTrue(importBookSourceDialogSource.contains("ImportBookSourceDialog code JSON is invalid for Rust analyzer handoff"))
        assertTrue(importRssSourceDialogSource.contains("ImportRssSourceDialog code JSON is invalid for Rust analyzer handoff"))
        assertTrue(importReplaceRuleDialogSource.contains("ImportReplaceRuleDialog code JSON is invalid for Rust analyzer handoff"))
        assertTrue(importDictRuleDialogSource.contains("ImportDictRuleDialog code JSON is invalid for Rust analyzer handoff"))
        assertFalse(replaceEditViewModelSource.contains("GSON.fromJsonObject<ReplaceRule>(text).getOrNull()"))
        assertFalse(replaceEditViewModelSource.contains("throw NoStackTraceException(\"格式不对\")"))
        assertTrue(replaceEditViewModelSource.contains("ReplaceEdit paste rule JSON is invalid for Rust analyzer handoff"))
        assertTrue(replaceEditViewModelSource.contains("ReplaceEdit paste rule failed"))
        assertFalse(txtTocRuleEditDialogSource.contains("GSON.fromJsonObject<TxtTocRule>(text).getOrNull()"))
        assertFalse(txtTocRuleEditDialogSource.contains("throw NoStackTraceException(\"格式不对\")"))
        assertTrue(txtTocRuleEditDialogSource.contains("TxtTocRuleEdit paste rule JSON is invalid for Rust analyzer handoff"))
        assertTrue(txtTocRuleEditDialogSource.contains("TxtTocRuleEdit paste rule failed"))
        assertFalse(bookSearchWebSocketSource.contains("GSON.fromJsonObject<Map<String, String>>(message.textPayload).getOrNull()"))
        assertTrue(bookSearchWebSocketSource.contains("BookSearchWebSocket message JSON is invalid for Rust analyzer handoff"))
        assertFalse(bookSourceDebugWebSocketSource.contains("GSON.fromJsonObject<Map<String, String>>(message.textPayload).getOrNull()"))
        assertTrue(bookSourceDebugWebSocketSource.contains("BookSourceDebugWebSocket message JSON is invalid for Rust analyzer handoff"))
        assertFalse(rssSourceDebugWebSocketSource.contains("GSON.fromJsonObject<Map<String, String>>(message.textPayload).getOrNull()"))
        assertTrue(rssSourceDebugWebSocketSource.contains("RssSourceDebugWebSocket message JSON is invalid for Rust analyzer handoff"))
        assertFalse(bookSourceControllerSource.contains("GSON.fromJsonObject<BookSource>(postData).getOrNull()"))
        assertFalse(bookSourceControllerSource.contains("GSON.fromJsonArray<BookSource>(postData).getOrNull()"))
        assertFalse(bookSourceControllerSource.contains("kotlin.runCatching {\n            GSON.fromJsonArray<BookSource>(postData).getOrThrow()"))
        assertTrue(bookSourceControllerSource.contains("BookSourceController.saveSource JSON is invalid for Rust analyzer handoff"))
        assertTrue(bookSourceControllerSource.contains("BookSourceController.saveSources JSON is invalid for Rust analyzer handoff"))
        assertTrue(bookSourceControllerSource.contains("BookSourceController.saveSources item \$index is invalid for Rust analyzer handoff"))
        assertTrue(bookSourceControllerSource.contains("BookSourceController.deleteSources JSON is invalid for Rust analyzer handoff"))
        assertFalse(rssSourceControllerSource.contains("GSON.fromJsonArray<RssSource>(postData).getOrNull()"))
        assertFalse(rssSourceControllerSource.contains("return ReturnData().setErrorMsg(\"格式不对\")"))
        assertTrue(rssSourceControllerSource.contains("RssSourceController.saveSource JSON is invalid for Rust analyzer handoff"))
        assertTrue(rssSourceControllerSource.contains("RssSourceController.saveSources JSON is invalid for Rust analyzer handoff"))
        assertTrue(rssSourceControllerSource.contains("RssSourceController.saveSources item \$index is invalid for Rust analyzer handoff"))
        assertTrue(rssSourceControllerSource.contains("RssSourceController.deleteSources JSON is invalid for Rust analyzer handoff"))
        assertFalse(customUrlSource.contains("GSON.fromJsonObject<Map<String, Any>>(attr).getOrNull()"))
        assertTrue(customUrlSource.contains("CustomUrl attribute JSON is invalid for Rust analyzer handoff"))
        assertFalse(urlUtilSource.contains("}.getOrNull()"))
        assertTrue(urlUtilSource.contains("UrlUtil file name resolution failed through Rust fetch"))
        assertFalse(exoPlayerHelperSource.contains("GSON.fromJsonArray<String>(url).getOrNull() ?: return null"))
        assertFalse(exoPlayerHelperSource.contains("GSON.fromJsonArray<String>(url).getOrNull()?.filter"))
        assertTrue(exoPlayerHelperSource.contains("ExoPlayerHelper media source URL array JSON is invalid for Rust analyzer handoff"))
        assertTrue(exoPlayerHelperSource.contains("ExoPlayerHelper media URL list JSON is invalid for Rust analyzer handoff"))
        assertFalse(replaceRuleControllerSource.contains("GSON.fromJsonObject<ReplaceRule>(postData).getOrNull()"))
        assertFalse(replaceRuleControllerSource.contains("GSON.fromJsonObject<Map<String, *>>(postData).getOrNull()"))
        assertFalse(replaceRuleControllerSource.contains("GSON.fromJsonObject<ReplaceRule>(it).getOrNull()"))
        assertTrue(replaceRuleControllerSource.contains("ReplaceRuleController.saveRule JSON is invalid for Rust analyzer handoff"))
        assertTrue(replaceRuleControllerSource.contains("ReplaceRuleController.delete JSON is invalid for Rust analyzer handoff"))
        assertTrue(replaceRuleControllerSource.contains("ReplaceRuleController.testRule rule JSON is invalid for Rust analyzer handoff"))
        assertFalse(replaceAnalyzerSource.contains("GSON.fromJsonObject<ReplaceRule>(json.trim()).getOrNull()"))
        assertTrue(replaceAnalyzerSource.contains("ReplaceAnalyzer rule JSON is invalid for Rust analyzer handoff"))
        assertFalse(importOldDataSource.contains("ReplaceAnalyzer.jsonToReplaceRules(json).getOrNull()"))
        assertTrue(importOldDataSource.contains("ImportOldData replace rule JSON is invalid for Rust analyzer handoff"))
        assertFalse(backupConfigSource.contains("GSON.fromJsonObject<HashMap<String, Boolean>>(json).getOrNull() ?: hashMapOf()"))
        assertTrue(backupConfigSource.contains("Backup restore-ignore JSON is invalid for Rust analyzer state handoff"))
        assertFalse(backupSource.contains("}.getOrDefault(json).let {"))
        assertFalse(backupSource.contains("}.getOrDefault(value.toString())"))
        assertTrue(backupSource.contains("Backup servers JSON encryption failed for Rust analyzer state handoff"))
        assertTrue(backupSource.contains("Backup WebDAV password encryption failed for Rust analyzer state handoff"))
        assertFalse(restoreSource.contains("GSON.fromJsonArray<Server>(json).getOrNull()"))
        assertFalse(restoreSource.contains("恢复服务器配置出错"))
        assertFalse(restoreSource.contains("}.getOrNull()?.let {\n                                edit.putString(key, it)"))
        assertTrue(restoreSource.contains("Restore servers JSON is invalid for Rust analyzer state handoff"))
        assertTrue(restoreSource.contains("Restore WebDAV password is invalid for Rust analyzer state handoff"))
        assertFalse(restoreJournalSource.contains("GSON.fromJsonObject<State>(stateFile.readText()).getOrNull() ?: run"))
        assertTrue(restoreJournalSource.contains("RestoreJournal state JSON is invalid for Rust analyzer state handoff"))
        assertFalse(appConfigSource.contains("GSON.fromJsonObject<Map<String, Any?>>(customHosts).getOrNull() ?: emptyMap()"))
        assertTrue(appConfigSource.contains("AppConfig custom DNS hosts JSON is invalid for Rust analyzer network handoff"))
        assertFalse(defaultDataSource.contains("HttpTTS.fromJsonArray(json).getOrElse"))
        assertFalse(defaultDataSource.contains("GSON.fromJsonArray<TxtTocRule>(json).getOrNull() ?: emptyList()"))
        assertFalse(defaultDataSource.contains("GSON.fromJsonArray<RssSource>(json).getOrDefault(emptyList())"))
        assertTrue(defaultDataSource.contains("GSON.fromJsonArray<TxtTocRule>(json).getOrThrow()"))
        assertTrue(defaultDataSource.contains("GSON.fromJsonArray<RssSource>(json).getOrThrow()"))
        assertFalse(bookCoverSource.contains("GSON.fromJsonObject<CoverRule>(CacheManager.get(coverRuleConfigKey))"))
        assertFalse(bookCoverSource.contains(".getOrNull()\n    }\n\n    suspend fun searchCover"))
        assertTrue(bookCoverSource.contains("BookCover rule JSON is invalid for Rust analyzer handoff"))
        assertTrue(variableMapSource.contains("variable JSON is invalid for Rust analyzer state handoff"))
        assertTrue(urlOptionsSource.contains("fun parseStringMap(value: String, context: String): Map<String, String>"))
        assertTrue(urlOptionsSource.contains("URL option JSON is invalid for Rust analyzer handoff"))
        assertFalse(urlOptionsSource.contains("GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()"))
        assertFalse(urlOptionsSource.contains("GSON.fromJsonArray<Map<String, Any>>(value).getOrNull()"))
        assertTrue(urlOptionsSource.contains("fun parseAnyMap(value: String, context: String): Map<String, Any>"))
        assertTrue(urlOptionsSource.contains("fun parseAnyMapArray(value: String, context: String): List<Map<String, Any>>"))
        assertTrue(urlOptionsSource.contains("UrlOptions.parseAnyMap(value, \"UrlOption.headers\")"))
        assertTrue(urlOptionsSource.contains("UrlOptions.parseAnyMap(value, \"UrlOption.body\")"))
        assertTrue(urlOptionsSource.contains("UrlOptions.parseAnyMapArray(value, \"UrlOption.body\")"))
        assertFalse(readBookActivitySource.contains("GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()"))
        assertFalse(readBookActivitySource.contains("执行购买操作出错"))
        assertFalse(readBookActivitySource.contains("执行图片链接click键值出错"))
        assertFalse(readBookActivitySource.contains("执行图片链接js键值出错"))
        assertTrue(readBookActivitySource.contains("ReadBookActivity payAction Rust JavaScript failed"))
        assertTrue(readBookActivitySource.contains("ReadBookActivity payAction Rust JavaScript returned no result"))
        assertTrue(readBookActivitySource.contains("ReadBookActivity image click Rust JavaScript failed"))
        assertTrue(readBookActivitySource.contains("ReadBookActivity image js Rust JavaScript failed"))
        assertFalse(sourceCallBackSource.contains("书源执行回调事件"))
        assertFalse(sourceCallBackSource.contains("AppLog.put("))
        assertFalse(sourceCallBackSource.contains("kotlin.runCatching {\n                val result = source.evalJS(jsStr)"))
        assertFalse(sourceCallBackSource.contains("kotlin.runCatching {\n                withTimeout(30000L)"))
        assertTrue(sourceCallBackSource.contains("Rust callback JS failed for event"))
        assertFalse(bookExtensionsSource.contains("导出书名规则错误,使用默认规则"))
        assertFalse(bookExtensionsSource.contains("getOrDefault(\""))
        assertFalse(bookExtensionsSource.contains("}.getOrDefault(default).normalizeFileName()"))
        assertFalse(bookExtensionsSource.contains("}.getOrDefault(false)"))
        assertTrue(bookExtensionsSource.contains("Book export filename Rust JavaScript failed"))
        assertTrue(bookExtensionsSource.contains("Book part export filename Rust JavaScript failed"))
        assertFalse(exploreAdapterSource.contains("exploreUi err:"))
        assertFalse(exploreAdapterSource.contains("ExploreUI Button \$name JavaScript error"))
        assertTrue(exploreAdapterSource.contains("ExploreUI Button \$name Rust JavaScript failed"))
        assertTrue(readBookActivitySource.contains("UrlOptions.parseStringMap(urlOptionStr, \"ReadBookActivity.oldClickImg\")"))
        assertFalse(textChapterLayoutSource.contains("GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()"))
        assertTrue(textChapterLayoutSource.contains("UrlOptions.parseStringMap(urlOptionStr, \"TextChapterLayout.titleImage\")"))
        assertTrue(textChapterLayoutSource.contains("UrlOptions.parseStringMap(urlOptionStr, \"TextChapterLayout.inlineImage\")"))
        assertTrue(textChapterLayoutSource.contains("UrlOptions.parseStringMap(urlOptionStr, \"TextChapterLayout.imageSpan\")"))
        assertFalse(glideImageGetterSource.contains("GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()"))
        assertTrue(glideImageGetterSource.contains("UrlOptions.parseStringMap(urlOptionStr, \"GlideImageGetter.dataImage\")"))
        assertTrue(glideImageGetterSource.contains("UrlOptions.parseStringMap(urlOptionStr, \"GlideImageGetter.remoteImage\")"))
        assertFalse(viewExtensionsSource.contains("GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()"))
        assertTrue(viewExtensionsSource.contains("UrlOptions.parseStringMap(urlOptionStr, \"TextView.setHtml.imageClick\")"))
        assertTrue(searchBookSource.contains("parseVariableMap(\"SearchBook("))
        assertTrue(bookEntitySource.contains("parseVariableMap(\"Book("))
        assertFalse(bookEntitySource.contains("fun stringToReadConfig(json: String?) = GSON.fromJsonObject<ReadConfig>(json).getOrNull()"))
        assertTrue(bookEntitySource.contains("Book read config JSON is invalid for Rust analyzer state handoff"))
        assertTrue(bookChapterSource.contains("parseVariableMap(\"BookChapter("))
        assertTrue(rssArticleSource.contains("parseVariableMap(\"RssArticle("))
        assertTrue(rssStarSource.contains("parseVariableMap(\"RssStar("))
        listOf(searchBookSource, bookEntitySource, bookChapterSource, rssArticleSource, rssStarSource).forEach {
            assertFalse(it.contains("GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull()"))
        }
        assertFalse(serverSource.contains("GSON.fromJsonObject<WebDavConfig>(config).getOrNull()"))
        assertTrue(serverSource.contains("Server WebDAV config JSON is invalid for Rust analyzer handoff"))
        assertFalse(appWebDavSource.contains("return GSON.fromJsonObject<BookProgress>(json).getOrNull()"))
        assertFalse(appWebDavSource.contains("获取书籍进度失败"))
        assertTrue(appWebDavSource.contains("AppWebDav book progress JSON is invalid for Rust analyzer state handoff"))
        assertTrue(jsExtensionsSource.contains("source = getSource()"))
        assertFalse(File(findProjectFile("app/src/main/java/io/legado/app/help"), "Regex" + "JsExtensions.kt").exists())
        assertFalse(File(findProjectFile("app/src/main/java/io/legado/app/utils"), "JsURL.kt").exists())
        assertFalse(findProjectFile("app/src/main/java/io/legado/app/model").resolve("analyzeRule").exists())
        assertFalse(jsHelpSource.contains("model/analyzeRule"))
        assertFalse(jsHelpSource.contains("AnalyzeUrl.kt"))
        assertFalse(jsHelpSource.contains("AnalyzeRule.kt"))
        assertFalse(jsHelpSource.contains("CookieStore.kt"))
        assertFalse(jsHelpSource.contains("CacheManager.kt"))
        assertFalse(jsHelpSource.contains("JsURL.kt"))
        assertFalse(jsHelpSource.contains(": JsURL"))
        assertFalse(ruleHelpSource.contains("AnalyzeUrl相关函数"))
        assertTrue(jsHelpSource.contains("Rust Analyzer URL host"))
        assertTrue(jsHelpSource.contains("Rust Analyzer rule host"))
        assertTrue(jsHelpSource.contains("Rust Analyzer cookie host"))
        assertTrue(jsHelpSource.contains("Rust Analyzer cache host"))
        assertTrue(ruleHelpSource.contains("Rust Analyzer URL host"))

        val jsEncodeSource = findProjectFile("app/src/main/java/io/legado/app/help/JsEncodeUtils.kt")
            .readText()
        val helpDir = findProjectFile("app/src/main/java/io/legado/app/help")
        assertFalse(jsEncodeSource.contains("Symmetric" + "CryptoAndroid"))
        assertFalse(jsEncodeSource.contains("cn.hutool.crypto"))
        assertFalse(jsEncodeSource.contains("io.legado.app.help.crypto"))
        assertFalse(jsEncodeSource.contains(": Symmetric" + "Crypto"))
        assertFalse(jsEncodeSource.contains(": Asymmetric" + "Crypto"))
        assertFalse(jsEncodeSource.contains(": Sign"))
        assertFalse(jsEncodeSource.contains("Replace" + "With("))
        assertFalse(jsEncodeSource.contains("return Asymmetric" + "Crypto("))
        assertFalse(jsEncodeSource.contains("return Sign("))
        assertFalse(jsEncodeSource.contains("rustUnsupportedHostApi(\"createSymmetricCrypto\")"))
        assertFalse(jsEncodeSource.contains("rustUnsupportedHostApi(\"createAsymmetricCrypto\")"))
        assertFalse(jsEncodeSource.contains("rustUnsupportedHostApi(\"createSign\")"))
        assertFalse(jsEncodeSource.contains("rustUnsupportedHostApi(\"aesDecodeToByteArray\")"))
        assertFalse(jsEncodeSource.contains("rustUnsupportedHostApi(\"aesDecodeArgsBase64Str\")"))
        assertFalse(jsEncodeSource.contains("rustUnsupportedHostApi(\"aesBase64DecodeToByteArray\")"))
        assertFalse(jsEncodeSource.contains("rustUnsupportedHostApi(\"aesEncodeToByteArray\")"))
        assertFalse(jsEncodeSource.contains("rustUnsupportedHostApi(\"aesEncodeToString\")"))
        assertTrue(jsEncodeSource.contains("RustAnalyzerBridge.evalJsAny"))
        assertTrue(jsEncodeSource.contains("class RustSymmetricCrypto"))
        assertTrue(jsEncodeSource.contains("class RustAsymmetricCrypto"))
        assertTrue(jsEncodeSource.contains("class RustSign"))
        assertTrue(jsEncodeSource.contains("script = \"java.${'$'}name("))
        assertFalse(helpDir.resolve("crypto").exists())
        assertFalse(helpDir.resolve("font").exists())
    }

    private fun findAppSource(): File {
        return findProjectFile("app/src/main/java/io/legado/app/App.kt")
    }

    private fun findProjectFile(relativePath: String): File {
        val startDir = System.getProperty("user.dir") ?: "."
        var dir = File(startDir).absoluteFile
        while (true) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: error("Could not find $relativePath from $startDir")
        }
    }
}
