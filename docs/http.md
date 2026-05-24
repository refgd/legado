# Legado Jsoup / OkHttp / Cronet Rust Replacement Coverage

本文档依据原 APP `legacy/android`、现有 Rust `crates/legado-runtime` / `crates/legado-uniffi`、以及新 APP `app/android` 的当前代码，列举原 APP 中与 Jsoup、OkHttp、Cronet 相关的函数和功能，并标明 Rust 是否已经实现、新 APP 是否已经替换。

状态说明：

- 已实现并已替换：Rust 已有等价实现，新 APP direct-HTTP / JS host / analyzer 路径已经使用 Rust。
- 已实现但 Android 仍保留包装：Rust 已执行核心逻辑，Android 仍保留平台侧 DTO 或 `StrResponse` 兼容包装。
- 部分实现：Rust 覆盖 analyzer/direct-HTTP 常用语义，但原 APP 的全部 JVM/Android/DOM 能力尚未完整等价。
- 平台边界：原 APP 依赖 Android UI、WebView、Glide、ExoPlayer、Cronet 或系统 API；Rust 不直接实现 UI，应通过 UniFFI/platform host 或 Android 平台层处理。
- 待替换：新 APP 生产代码仍直接使用 Android Jsoup/OkHttp 相关能力，或 Rust 尚缺对应通用 API。
- 不适用：原 APP 内部实现细节，Rust 用不同结构替代，不需要暴露同名 API。

## 参考范围

原 APP Jsoup 参考文件：

- `legacy/android/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSoup.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/JsExtensions.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/http/CookieManager.kt`
- `legacy/android/app/src/main/java/io/legado/app/lib/webdav/WebDav.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/localBook/EpubFile.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/localBook/EpubDomBuilder.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/localBook/EpubMiniLayout.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/localBook/MobiFile.kt`
- `legacy/android/app/src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt`
- `legacy/android/app/src/main/java/io/legado/app/ui/code/CodeEditViewModel.kt`
- `legacy/android/app/src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt`
- `legacy/android/app/src/main/java/io/legado/app/utils/EncodingDetect.kt`
- `legacy/android/app/src/main/java/io/legado/app/utils/JsoupExtensions.kt`

原 APP OkHttp 参考文件：

- `legacy/android/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/http/OkHttpUtils.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/http/HttpHelper.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/http/CookieManager.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/http/CookieStore.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/http/StrResponse.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/http/DecompressInterceptor.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/http/OkHttpExceptionInterceptor.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/http/ObsoleteUrlFactory.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/http/Cronet.kt`
- `legacy/android/app/src/main/java/io/legado/app/lib/cronet/*`
- `legacy/android/app/src/main/java/io/legado/app/help/glide/*`
- 原 APP 中所有调用 `okHttpClient.newCallResponse*`、`Request.Builder`、`ResponseBody`、Cronet loader/interceptor/callback/upload provider 的导入、下载、配置、RSS、TTS、图片和 AI 调用点。

Rust / 新 APP 参考文件：

- `crates/legado-runtime/src/request.rs`
- `crates/legado-runtime/src/rule_engine.rs`
- `crates/legado-runtime/src/js_runtime.rs`
- `crates/legado-runtime/src/session.rs`
- `crates/legado-runtime/src/rss.rs`
- `crates/legado-uniffi/src/lib.rs`
- `app/android/app/src/main/java/io/legado/app/model/webBook/RustAnalyzerBridge.kt`
- `app/android/app/src/main/java/io/legado/app/help/JsExtensions.kt`
- `app/android/app/src/test/java/io/legado/app/AppHttpBoundaryTest.kt`

## 总体结论

direct-HTTP analyzer 主链路已经从 Android Jsoup + OkHttp 切到 Rust：书源 `search`、`detail`、`toc`、`content`、`explore`，RSS direct-HTTP，字典、封面、HTTP TTS、raw/text fetch、URL option、JS host 网络 API、cookie/session、并发限速和 replay cache 均由 Rust 负责。

新 APP 生产代码中 OkHttp/Cronet direct request 能力已基本删除，剩余 OkHttp/Cronet 相关文本主要是测试里的边界断言、实体字段名 `enabledCookieJar`、以及非 OkHttp 的 ExoPlayer `DownloadRequest.Builder`。这些不是 Android direct-HTTP fallback。

新 APP 生产代码中的 Android Jsoup 直接解析与 `org.jsoup.Connection` 兼容接口包装已迁移完毕；Android Gradle 也不再声明 `org.jsoup:jsoup` 或 `JsoupXpath` 依赖。核心请求和脚本侧 DOM parse 均由 Rust 负责。WebDAV PROPFIND XML/HTML listing、WebDAV 错误体解析、RSS 阅读页纯文本提取、代码编辑页 HTML parse/serialize、charset meta 探测、MOBI 内容清理、MOBI/EPUB 简单标题/简介提取、EPUB native entry href 提取、EPUB book-info/footnote id 索引与 footnote target 提取、EPUB readable lines、EPUB body 预处理与 body DTO 提取（script 清理、fragment 裁剪、脚注隐藏、XHTML image 转 img、page background color 标记、body/title/style/background 提取）、EPUB debug-only classic HTML dump cleanup、EPUB CSS application、EPUB image src/options 提取、EPUB 单图/叠字封面/画廊页标记、EPUB image option materialization、EPUB media placeholder materialization、EPUB inline style materialization、EPUB inherited style propagation、EPUB generated content injection、EPUB native DOM selector matching / node traversal / style computation、EPUB `a[href]` 相对链接重写、EPUB body background image 候选提取、EPUB CSS asset 提取与 body style/link 清理、Reader table readable HTML、Reader render flags、Reader page background、Reader image info 与 Reader HTML fragment render plan 已经迁移到 Rust UniFFI；Android `RustConnectionResponse.parse()` 不返回 Android DOM Document，而是 fail-fast 指向 Rust JS host DOM parse 或 Rust document UniFFI。

## 原 APP Jsoup 功能矩阵

| 原 APP 函数/功能 | 原语义 | Rust 是否实现 | 新 APP 是否替换 | 备注 |
|---|---|---:|---:|---|
| `AnalyzeByJSoup.parse(doc)` | `Element` 原样返回；`JXNode` 转 Element 或字符串；XML 头使用 `Parser.xmlParser()`；否则 `Jsoup.parse` | 部分实现 | analyzer 已替换 | Rust `RuleContent::from_body` 和 `scraper::Html` 覆盖 direct-HTTP HTML；XML mode 仍不是完整 Jsoup XML DOM |
| `Jsoup.parse(html)` | 解析 HTML 文档/片段 | 部分实现 | analyzer/JS host/WebDAV/RSS read/CodeEdit/MOBI/EPUB/Reader 已替换 | Rust host 暴露 `org.jsoup.Jsoup.parse`；WebDAV HTML listing、RSS read text extraction、CodeEdit HTML serialize、MOBI content/title helper、EPUB title/intro/book-info/footnote id/footnote target/readable lines/body/debug dump/image/media/inline-style/link/background-image/native DOM helper 和 Reader render plan/table/render flags/page background/image info helper 走 Rust parser |
| `Jsoup.parse(html, baseUrl)` | 解析并保留 base URL，用于相对链接解析 | 部分实现 | analyzer/JS host 已替换；Android wrapper 不暴露 Document | Rust 规则 URL 绝对化已实现；Android `Connection.Response.parse()` 不再调用 Jsoup，改为 fail-fast 诊断 |
| `Jsoup.parse(html, Parser.xmlParser())` | XML 模式解析 WebDAV/RSS/EPUB 元数据等 | 部分实现 | WebDAV/EPUB helper 已替换 | Rust RSS direct-HTTP 已有 XML/RSS 解析；WebDAV PROPFIND XML/错误体解析走 Rust `webdav` API；EPUB 本地阅读 helper 不再直接用 Android XML/HTML Jsoup parse |
| `Jsoup.parseBodyFragment(html)` | 解析 HTML body fragment | 部分实现 | charset 探测、Reader 对齐探测、Reader render traversal、EPUB local body/debug 已替换 | Rust 规则解析用 HTML fragment；`EncodingDetect.getHtmlEncode` 走 Rust charset meta parser；`TextChapterLayout.epubResourceAlignment` 和 `TextChapterLayout.setTypeHtml` render plan 走 Rust；EPUB body DTO、readable lines、debug dump cleanup 和 native DOM 构建入参都走 Rust |
| `element.select(css)` | CSS selector 选节点 | 已实现并已替换 | analyzer 已替换 | Rust `rule_engine` 支持 CSS selector 和 selector cache |
| `element.selectFirst(css)` | 取第一个匹配节点 | 已实现并已替换 | JS host 已替换 | Rust host `selectFirst` 返回第一个 node |
| `element.text()` | 取文本 | 已实现并已替换 | analyzer/JS host 已替换 | Rust analyzer 和 host 均支持 |
| `element.ownText()` | 取当前节点文本 | 已实现并已替换 | analyzer/JS host 已替换 | Rust 当前实现等同常见 direct-HTTP 文本提取语义，复杂 DOM whitespace 仍需 fixture 验证 |
| `element.textNodes()` | 取直接文本节点列表并 join | 部分实现 | analyzer 已替换 | Rust `textNodes` 映射到文本提取；不暴露完整 TextNode JVM API |
| `element.html()` | inner HTML | 已实现并已替换 | analyzer/JS host 已替换 | Rust host 支持 getter/setter 形态 |
| `element.outerHtml()` | outer HTML | 已实现并已替换 | analyzer/JS host 已替换 | Rust host 支持 |
| `elements.outerHtml()` | 集合 outer HTML 拼接 | 已实现并已替换 | analyzer/JS host 已替换 | Rust host `Elements.outerHtml()` join |
| `element.attr(name)` | 取属性 | 已实现并已替换 | analyzer/JS host 已替换 | Rust analyzer 任意 attr last rule 支持 |
| `element.attr(name, value)` | 设置属性 | 已实现并已替换 | JS host 已替换 | Rust host 支持字符串层 DOM mutation |
| `element.hasAttr(name)` | 判断属性 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `element.hasClass/addClass/removeClass` | class 判断和修改 | 已实现并已替换 | JS host 已替换 | Rust host 支持常用形态 |
| `element.tagName()` | 标签名 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `element.appendChild(child)` | 追加子节点 | 已实现并已替换 | JS host 已替换 | Rust host 用 HTML 字符串 mutation 覆盖常见 dict/source 脚本 |
| `element.appendText(text)` | 追加文本 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `element.remove()` / `elements.remove()` | 删除节点 | 已实现并已替换 | JS host 已替换 | Rust host 支持父 HTML 替换式 mutation |
| `element.replaceWith(value)` | 替换节点 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `element.before(value)` / `elements.before(value)` | 节点前插入 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `element.after(value)` / `elements.after(value)` | 节点后插入 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `elements.size()` / `isEmpty()` / `get(i)` | 集合大小、判空、取项 | 已实现并已替换 | JS host 已替换 | Rust host `__legadoElements` 支持 |
| `elements.first()` / `last()` | 集合首尾 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `elements.text()` | 集合文本 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `elements.eachText()` | 每个元素文本数组 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `elements.attr(name)` | 首元素属性 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `elements.eachAttr(name)` | 每个元素属性数组 | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `elements.html()` | 集合 inner HTML | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `new Element(tag)` / `Packages.org.jsoup.nodes.Element` | JS 中构造 Jsoup Element | 已实现并已替换 | JS host 已替换 | Rust host 暴露 `Element(tag)` 和 `Packages.org.jsoup.nodes.Element` |
| `Packages.org.jsoup.Jsoup` | Rhino/JavaImporter 导入 Jsoup | 已实现并已替换 | JS host 已替换 | Rust host 支持 `JavaImporter(Packages.org.jsoup.Jsoup, ...)` |
| `Packages.org.jsoup.Connection.Method` | JS 中引用 GET/POST/HEAD 等枚举 | 已实现并已替换 | JS host 已替换 | Rust host 暴露 `GET/POST/HEAD/PUT/DELETE/PATCH/OPTIONS` 字符串枚举 |
| `Packages.org.jsoup.select.Elements` | JS 中引用 Elements 类型 | 已实现并已替换 | JS host 已替换 | Rust host 映射为 Array-like collection |
| `Jsoup.connect(url)` | 构建 Jsoup HTTP connection | 已实现并已替换 | JS host 已替换 | Rust host `__legadoJsoupConnect` 转到 Rust HTTP wrapper |
| `Connection.timeout(ms)` | 请求超时 | 已实现并已替换 | JS host 已替换 | Rust request `call_timeout_ms` |
| `Connection.ignoreContentType(true)` | 忽略 content type | 已实现并已替换 | JS host 已替换 | Rust 请求不按 content type 拒绝 body |
| `Connection.followRedirects(false)` | 不跟随重定向 | 已实现并已替换 | JS host 已替换 | Rust `no_redirect_client` 覆盖 `java.get/head/post` |
| `Connection.headers(map)` / `header(k,v)` | 设置 header | 已实现并已替换 | JS host 已替换 | Rust 合并 headers |
| `Connection.requestBody(body)` | POST body | 已实现并已替换 | JS host 已替换 | Rust `body` |
| `Connection.data(k,v)` | form body 增加字段，GET 转 POST | 已实现并已替换 | JS host 已替换 | Rust host 拼接 urlencoded body |
| `Connection.method(Method)` | 设置 HTTP method | 已实现并已替换 | JS host 已替换 | Rust request 支持任意 reqwest Method，原常用 GET/POST/HEAD 已测 |
| `Connection.execute()` | 执行并返回 response | 已实现并已替换 | JS host 已替换 | Rust `java.__httpResponse` 返回 response wrapper |
| `Connection.get()` / `post()` | 执行并返回 body | 已实现并已替换 | JS host 已替换 | Rust host 支持 |
| `Connection.Response.statusCode/statusMessage` | 状态码/消息 | 已实现并已替换 | 已替换核心 | 新 APP `RustConnectionResponse` 是项目自有 DTO，不再实现 Android Jsoup 接口 |
| `Connection.Response.charset/contentType` | charset/content-type | 已实现并已替换 | 已替换核心 | `charset()` 从 content-type/header 解析；content-type 来自 Rust headers |
| `Connection.Response.parse()` | 将 body parse 成 Jsoup Document | Android Jsoup 调用已移除；脚本侧由 Rust host 支持 | 已替换为 fail-fast 边界 | Android `RustConnectionResponse.parse()` 不返回 DOM `Document`，提示使用 Rust JS host DOM parse 或 Rust document UniFFI |
| `Connection.Response.body/bodyAsBytes/bodyStream` | body 字符串/字节/流 | 已实现并已替换 | 已替换核心 | Android 项目自有 DTO 从 Rust body 构造 |
| `Connection.Response.url/method` | URL 和 method | 已实现并已替换 | 已替换核心 | Android 项目自有 DTO 保留同名方法和 `RustConnectionMethod` |
| `Connection.Response.header/headers/multiHeaders` | header 单值、多值、全部 | 已实现并已替换 | 已替换核心 | Rust 保留重复 header；Android 项目自有 DTO 暴露同名访问方法 |
| `Connection.Response.cookie/cookies/hasCookie/removeCookie` | 响应 cookie map | 已实现并已替换 | 已替换核心 | Android 项目自有 DTO 从 `Set-Cookie` 提取 cookie |
| `AnalyzeByJSoup` last rule `text` | 文本 | 已实现并已替换 | analyzer 已替换 | |
| `AnalyzeByJSoup` last rule `textNodes` | 直接文本节点 | 部分实现 | analyzer 已替换 | 不暴露完整 TextNode |
| `AnalyzeByJSoup` last rule `ownText` | 当前节点文本 | 已实现并已替换 | analyzer 已替换 | |
| `AnalyzeByJSoup` last rule `html` | 去除 script/style 后 outer HTML | 部分实现 | analyzer 已替换 | Rust `html` 返回 inner HTML；`all`/formatter 覆盖正文常用场景，去 script/style 行为需继续 fixture 覆盖 |
| `AnalyzeByJSoup` last rule `all` | elements outerHtml | 已实现并已替换 | analyzer 已替换 | |
| `AnalyzeByJSoup` last rule 任意属性 | `element.attr(lastRule)`，去重并跳过空 | 已实现并已替换 | analyzer 已替换 | |
| `children` selector | 当前元素 children | 部分实现 | analyzer 已替换 | Rust CSS selector 可覆盖多数用法；裸 `children` 专用规则不是完整 DOM API |
| `class.xxx` selector | `getElementsByClass` | 已实现并已替换 | analyzer 已替换 | Rust legacy selector 转 CSS |
| `tag.xxx` selector | `getElementsByTag` | 已实现并已替换 | analyzer 已替换 | Rust legacy selector 转 CSS |
| `id.xxx` selector | `Evaluator.Id` | 已实现并已替换 | analyzer 已替换 | Rust legacy selector 转 CSS |
| `text.xxx` selector | `getElementsContainingOwnText` | 已实现并已替换 | analyzer 已替换 | Rust `contains_text` 过滤 |
| `@` 链式子规则 | 多段节点下钻 | 已实现并已替换 | analyzer 已替换 | Rust `split_rule_at` |
| `&&` | 多规则合并 | 已实现并已替换 | analyzer 已替换 | |
| `||` | 首个非空规则 | 已实现并已替换 | analyzer 已替换 | |
| `%%` | 多规则按索引交错合并 | 已实现并已替换 | analyzer 已替换 | |
| `.0` / `.-1` 索引 | 选择指定索引，支持负数 | 已实现并已替换 | analyzer 已替换 | Rust 支持基础正负索引 |
| `!0` 排除索引 | 排除指定索引 | 部分实现 | analyzer 已替换常用选择 | Rust 对复杂排除/区间需继续测试确认 |
| `[i,j]` / `[start:end:step]` | JSONPath 风格索引/区间/反转 | 部分实现 | analyzer 已替换常用选择 | Rust 支持基础索引；原 APP 复杂区间全部语义需补齐或 fail-fast |
| `Element.textArray()` | 保留 block/br 换行的文本数组 | RSS read 场景已实现 | 已替换并删除 Android helper | 新 APP `ReadRssActivity` 走 Rust `html_text_array_json`；未发现其他调用后已删除 `JsoupExtensions.textArray` |
| `Element.findNS(tag, namespace)` | 命名空间标签查找 | WebDAV 场景已替换 | 已替换并删除 Android helper | WebDAV 不再依赖 Android Jsoup namespace helper；未发现其他调用后已删除 `JsoupExtensions.findNS` |
| `Element.findNSPrefix(namespaceURI)` | 查命名空间前缀 | WebDAV 场景已替换 | 已替换并删除 Android helper | WebDAV 不再依赖 Android Jsoup namespace helper；未发现其他调用后已删除 `JsoupExtensions.findNSPrefix` |
| `List<Element>.toElements()` | List 转 Jsoup Elements | 不适用 | 已删除 Android helper | 仅由已删除 namespace helper 使用，新 APP 无调用 |
| `EncodingDetect.getHtmlEncode` 中 `Jsoup.parseBodyFragment` | 从 meta charset 探测编码 | 已实现并已替换 | 已替换 | Rust `html_charset_json` 解析 `meta charset` 与 `http-equiv=Content-Type` charset；ICU fallback 仍在 Android |
| WebDAV `Jsoup.parse(xml/html)` | 解析 PROPFIND XML、HTML directory listing 和错误体 | 已实现并已替换解析核心 | 已替换 | Rust `crates/legado-runtime/src/webdav.rs` 解析 namespaced PROPFIND、Caddy 风格 HTML listing 和 SabreDAV 错误体；Android `WebDav.kt` 不再导入 Jsoup |
| EPUB/MOBI `Jsoup.parse` / DOM traversal | 本地书籍 HTML、metadata、章节、图片、media、layout | 部分实现本地 helper | MOBI/EPUB 已替换核心 | `mobi_content_html_json`、`html_title_json` 已覆盖 MOBI content cleanup 和首章 title；`html_text_array_json`、`epub_readable_title_json`、`epub_book_info_json`、`epub_footnote_ids_json`、`epub_footnote_target_json`、`epub_readable_lines_json`、`epub_body_html_json`、`epub_debug_chapter_html_json`、`epub_applied_css_json`、`epub_image_options_json`、`epub_image_page_marks_json`、`epub_materialized_images_json`、`epub_media_placeholders_json`、`epub_inline_styles_json`、`epub_inherited_styles_json`、`epub_generated_content_json`、`epub_native_dom_json`、`epub_resolved_links_json`、`epub_body_background_image_json`、`epub_css_assets_json` 已覆盖 EPUB description/title/book-info/footnote id 索引、footnote target、readable 模式文本行输出、正文 parse 前的 script 清理、fragment 裁剪、脚注隐藏、XHTML image 转换、page background color 标记、body/title/style/background DTO 提取、debug-only classic HTML dump cleanup、CSS selector application、图片 src/options 推导、单图/叠字封面/画廊页标记、图片最终属性/option materialization、video/audio/source/iframe/embed/object placeholder materialization、inline style 到阅读器兼容标签/属性的转换、继承样式传播、generated content 注入、native DOM selector matching / node traversal / style computation、`a[href]` 相对链接重写、body background image 候选提取、head/body style 与 stylesheet asset 提取，以及 body 内 style/link 清理；Android 仍保留 CSS declaration parsing、CSS resource loading、图片尺寸/字体/布局等本地平台边界 |
| Reader `Jsoup.parseBodyFragment` | 阅读排版 HTML fragment | 已实现 reader helper | 已替换核心 | `epub_native_entry_json`、`html_first_alignment_json`、`html_readable_table_json`、`html_render_flags_json`、`html_page_background_json`、`html_image_info_json`、`html_render_plan_json` 已替换 native entry href、资源对齐探测、table readable HTML、render flags、page background、image info 与正文 fragment traversal；`TextChapterLayout.kt` 不再导入 Android Jsoup |
| Code editor `Jsoup.parse(html)` | HTML 代码/网页解析辅助 | 已实现并已替换核心 | 已替换核心 | `CodeEditViewModel.formatCodeHtml` 走 Rust `html_format_json`，不再导入 Android Jsoup |
| RSS read `Jsoup.parse(html).textArray()` | RSS 正文纯文本显示 | 已实现并已替换 | 已替换 | `ReadRssActivity.readAloud` 走 Rust `html_text_array_json` |

## 原 APP OkHttp 功能矩阵

| 原 APP 函数/功能 | 原语义 | Rust 是否实现 | 新 APP 是否替换 | 备注 |
|---|---|---:|---:|---|
| `okHttpClient` | 全局 OkHttpClient；默认 UA、Keep-Alive、no-cache、unsafe SSL、redirect、retry、Cronet、decompress、cookie network interceptor | 部分实现 | direct-HTTP 已替换 | Rust `reqwest` 实现默认 UA、redirect、timeout、cookie、proxy、dnsIp、retry、unsafe TLS；Cronet/OkHttp interceptor 不再作为 analyzer 目标 |
| `okHttpClientManga` | 漫画图片限速和进度 body | 平台边界 | 已删除/平台化 | 新 APP 不应把图片加载作为 analyzer HTTP fallback；图片 UI 属 Android/Glide 或 Rust raw fetch 边界 |
| `getProxyClient(proxy)` | http/socks4/socks5 代理，支持 `@user@pass` 旧格式 | 已实现并已替换 | direct-HTTP 已替换 | Rust `parse_proxy_option` / reqwest proxy |
| address cache DNS | AppConfig addressCache 覆盖 hostname lookup | 部分实现 | direct-HTTP 已替换 | Rust 支持 URL option `dnsIp`；全局 addressCache 不是 analyzer 必需面 |
| `cookieJar.saveFromResponse` | 临时保存 Set-Cookie 到 memory | 已实现并已替换 | direct-HTTP 已替换 | Rust `store_response_cookies` 写入 session/persistent cookie |
| `CookieManager.saveResponse(Response)` | 从 OkHttp response 保存 cookie | 已实现并已替换 | direct-HTTP 已替换 | Rust response headers 解析 `Set-Cookie` |
| `CookieManager.saveResponse(Connection.Response)` | 从 Jsoup response 保存 cookie | 已实现并已替换 | JS host 已替换核心 | Rust session/cookie host 保存 cookie；Android 不再保留 Jsoup response 接口 |
| `CookieManager.loadRequest(Request)` | 合并 CookieStore 到 OkHttp Request | 已实现并已替换 | direct-HTTP 已替换 | Rust `merge_session_cookie_header` |
| `CookieManager.mergeCookies` | 多 cookie 字符串按 key 合并 | 已实现并已替换 | direct-HTTP 已替换 | Rust cookie pairs merge；同名后者覆盖 |
| `CookieManager.removeCookie(url,key)` | 删除单个 cookie | 已实现并已替换 | JS host 已替换 | Rust `cookie.removeCookie` / session store |
| `CookieManager.getCookieNoSession` | 取持久 cookie，不含 session | 部分实现 | direct-HTTP 已替换 | Rust persistent cookie store 有 host/domain；无 Android DB schema 兼容暴露 |
| `CookieManager.applyToWebView` | 同步 cookie 到 Android WebView | 平台边界 | 已替换-平台边界 | 新 APP `RustAnalyzerBridge.applyCookieToWebView` |
| `CookieStore.setCookie` | 保存 cookie 到 DB/cache | 已实现并已替换 | JS host 已替换 | Rust `AnalyzerSession::set_cookie` + persistent store |
| `CookieStore.replaceCookie` | 合并保存 cookie | 已实现并已替换 | JS host 已替换 | |
| `CookieStore.getCookie` | 取 cookie，含 session，超过 4096 随机删 | 已实现并已替换 | JS host 已替换 | Rust 取 host/domain cookie；超过 4096 时做确定性裁剪并回写，避免随机不可测 |
| `CookieStore.getKey` | 取单个 cookie key | 已实现并已替换 | JS host 已替换 | |
| `CookieStore.removeCookie` | 删除 domain cookie | 已实现并已替换 | JS host 已替换 | |
| `CookieStore.cookieToMap` | cookie 字符串转 map | 已实现并已替换 | JS host 已替换 | |
| `CookieStore.mapToCookie` | map 转 cookie 字符串 | 已实现并已替换 | JS host 已替换 | |
| `OkHttpClient.newCallResponse(retry,builder)` | 构建 Request，按 retry 重试到成功，返回 Response | 已实现并已替换 | direct-HTTP 已替换 | Rust `retry_attempts` |
| `newCallResponseBody` | 返回 ResponseBody | 已实现并已替换 | direct-HTTP 已替换 | Rust raw/text output 替代 |
| `newCallStrResponse` | ResponseBody 解码成 String 并包 `StrResponse` | 已实现并已替换 | direct-HTTP 已替换 | Rust `RequestOutput` + JS response wrapper |
| `Call.await()` | coroutine await / cancellation | 不适用 | 已替换 | Rust blocking request 在 analyzer worker 内执行 |
| `ResponseBody.text(encode)` | BOM 去除；显式 charset；Content-Type charset；HTML meta 探测 | 部分实现 | direct-HTTP 已替换 | Rust 支持 raw bytes、charset option、text decode；HTML meta 自动探测需继续补齐 |
| `ResponseBody.decompressed()` | `application/zip` 取首个 zip entry body | 已实现并已替换 | 在线导入/订阅更新已替换 | 新 APP `RustRemoteFetch.bytes` 对 `application/zip` 恢复首个 zip entry body 语义；raw 图片/文件桥接仍保留原 bytes |
| `Request.Builder.addHeaders` | 逐个 add header | 已实现并已替换 | direct-HTTP 已替换 | Rust header pair merge |
| `Request.Builder.get(url, queryMap, encoded)` | query map 编码后 GET | 已实现并已替换 | direct-HTTP 已替换 | Rust URL option charset/query encoding |
| `Request.Builder.get(url, encodedQuery)` | 直接设置 encodedQuery | 已实现并已替换 | direct-HTTP 已替换 | |
| `Request.Builder.postForm(encodedForm)` | `application/x-www-form-urlencoded` POST | 已实现并已替换 | direct-HTTP 已替换 | Rust `apply_charset_encoding` + body |
| `Request.Builder.postForm(form, encoded)` | Map form POST | 部分实现 | direct-HTTP 已替换 | URL option body Map 会序列化/发送；完整 FormBody API 不暴露 |
| `Request.Builder.postMultipart(type, form)` | multipart；字段可为 File/ByteArray/String/JSON；`fileRequest` 上传 | 已实现并已替换 | direct-link 已替换 | Rust `upload_multipart_*` 和 `direct_link_upload` |
| `Request.Builder.postJson(json)` | JSON body，默认 `application/json; charset=UTF-8` | 已实现并已替换 | direct-HTTP 已替换 | |
| `StrResponse(raw,errorBody)` | OkHttp response + body wrapper | 已实现并已替换 | 新 APP保留轻量类 | 新 APP `StrResponse` 不暴露 OkHttp raw types |
| `StrResponse.url/body/code/message/headers/isSuccessful/callTime` | JS/API 响应方法 | 已实现并已替换 | JS host 已替换 | Rust `java.__strResponse` / Android bridge wrapper |
| `StrResponse.errorBody/raw` | OkHttp raw response 访问 | 不适用 | 已删除 direct-HTTP 依赖 | 新 APP 测试断言不暴露 OkHttp types |
| `AnalyzeUrl.initUrl/analyzeJs/replaceKeyPageJs/analyzeUrl` | URL JS、key/page、URL option 解析 | 已实现并已替换 | analyzer 已替换 | Rust URL rule + `parse_legado_request` + JS runtime |
| URL option `method` | GET/POST/HEAD | 已实现并已替换 | analyzer 已替换 | Rust支持任意 Method，常用已测 |
| URL option `headers` | 合并源/header/login header | 已实现并已替换 | analyzer 已替换 | |
| URL option `body` | POST body；非 JSON/XML 无 Content-Type 时 form 编码 | 已实现并已替换 | analyzer 已替换 | |
| URL option `charset` | query/form 编码，含 `escape` | 已实现并已替换 | analyzer 已替换 | |
| URL option `origin` | 源 URL 元数据 | 已实现并已替换 | analyzer 已替换 | |
| URL option `retry` | 请求重试 | 已实现并已替换 | analyzer 已替换 | |
| URL option `type` | 二进制/文件类型，返回 hex body | 已实现并已替换 | analyzer/TTS 已替换 | |
| URL option `webView` | WebView 渲染请求 | 平台边界 | 已替换-平台边界 | Rust 诊断/platform host |
| URL option `webJs` | WebView JS | 平台边界 | 已替换-平台边界 | |
| URL option `sourceRegex` | WebView 捕获资源 | 平台边界 | 已替换-平台边界 | |
| URL option `dnsIp` | 指定 DNS/IP | 已实现并已替换 | analyzer 已替换 | Rust `resolve_to_addrs` |
| URL option `js` | 请求前 JS 改写 URL | 已实现并已替换 | analyzer 已替换 | |
| URL option `bodyJs` | 响应后 JS 改写 body | 已实现并已替换 | analyzer 已替换 | |
| URL option `serverID` | 服务器 ID 元数据 | 已实现并已替换 | bridge 已替换 | |
| URL option `webViewDelayTime` | WebView 等待 | 平台边界 | 已替换-平台边界 | |
| `AnalyzeUrl.getStrResponseAwait/getStrResponse` | 文本请求，支持 WebView、bodyJs、测试错误码、限速 | 已实现并已替换 | analyzer 已替换 | Rust request + platform boundary + `ajaxTestAll` 负错误码 |
| `AnalyzeUrl.getResponseAwait/getResponse` | 返回 OkHttp Response | 不适用 | 已替换 | Rust raw/text DTO 替代，不暴露 OkHttp |
| `AnalyzeUrl.getErrResponse/getErrStrResponse` | 构造 OkHttp error response | 不适用 | 已替换 | Rust fail-fast diagnostics + response wrapper |
| `AnalyzeUrl.getByteArray/getByteArrayAwait` | data URI 或 HTTP bytes | 已实现并已替换 | raw/TTS 已替换 | Rust `get_raw` / data URL |
| `AnalyzeUrl.getInputStream/getInputStreamAwait` | data URI 或 HTTP stream | 部分实现 | direct-HTTP 已替换 | Rust 返回 bytes/base64；Android InputStream 包装仅平台需要 |
| `AnalyzeUrl.upload` | multipart 上传 direct link | 已实现并已替换 | 已替换 | Rust `direct_link_upload` |
| `AnalyzeUrl.setCookie` | 合并 CookieStore 与 URL Cookie；启用 CookieJar header | 已实现并已替换 | analyzer 已替换 | Rust session/persistent cookie 和显式 Cookie header 合并 |
| `AnalyzeUrl.getGlideUrl` | URL/header 转 GlideUrl | 平台边界 | 已替换-平台边界 | Rust 解析 URL/header，Android 图像层消费 |
| `AnalyzeUrl.getMediaItem/getMediaRequest` | URL/header 转 ExoPlayer 对象 | 平台边界 | 已替换-平台边界 | 新 APP `resolveMediaItem/Request` |
| `AnalyzeUrl.getUserAgent` | 从 header 或默认 UA 获取 | 已实现并已替换 | JS host 已替换 | |
| `AnalyzeUrl.isPost` | method 是否 POST | 已实现并已替换 | JS host/analyzer 已替换 | |
| `java.ajax` | 通过 AnalyzeUrl 请求并返回 body | 已实现并已替换 | 已替换 | Android `JsExtensions.ajax` 委托 Rust |
| `java.ajaxAll` | 并发请求，多 `StrResponse` | 已实现并已替换 | 已替换 | Rust 支持 source concurrent rate |
| `java.ajaxTestAll` | 并发测速，失败负错误码 | 已实现并已替换 | 已替换 | Rust 覆盖错误码形态 |
| `java.connect` | 请求返回 `StrResponse` | 已实现并已替换 | 已替换 | |
| `java.get/head/post` | Jsoup no-redirect GET/HEAD/POST response | 已实现并已替换 | 已替换核心 | Rust JS host 提供脚本侧 response；Android `RustConnectionResponse` 为项目自有 DTO，不依赖 Jsoup interface |
| `java.importScript/cacheFile/downloadFile` | 下载/缓存 JS 或文件 | 已实现并已替换 | 已替换 | Rust virtual file/cache |
| `java.getCookie` | JS 读取 cookie | 已实现并已替换 | 已替换 | |
| `DecompressInterceptor` | gzip/deflate/br 之外处理和 zip body first-entry | 已实现并已替换 | direct-HTTP/在线导入已替换 | reqwest 自带 gzip/deflate/br；在线导入/订阅更新 zip 首 entry 由 `RustRemoteFetch.bytes` 处理 |
| `OkHttpExceptionInterceptor` | 对连接异常做统一包装 | 已实现并已替换 | direct-HTTP 已替换 | Rust diagnostics 替代 |
| `SSLHelper.unsafe*` | 信任所有证书/hostname | 已实现并已替换 | direct-HTTP 已替换 | Rust request clients 启用 invalid cert/hostname 兼容旧全局 OkHttp 行为 |
| `ObsoleteUrlFactory` | 用 OkHttp 替换 `HttpURLConnection` URLStreamHandler | 不适用 | 已删除/不应恢复 | Rust analyzer 不通过 JVM URLStreamHandler |
| `Cronet.preDownload` / `CronetLoader` | 预下载/加载 Chromium Cronet so | 不适用/平台边界 | 已完全移除 | 新 APP 不下载或加载 Cronet so；`cronetlib` jars、`cronet.json`、`cronet.sh`、`org.chromium.net` proguard 规则均已删除，测试断言不回归 |
| `CronetInterceptor` / `CronetCoroutineInterceptor` | Cronet engine 作为 OkHttp interceptor，失败时回退 OkHttp | 不适用/平台边界 | 已删除 direct-HTTP 依赖 | Rust request 不依赖 Android Cronet，也不允许 Cronet->OkHttp 回退链路重新进入 direct-HTTP |
| Cronet callback/upload provider | Cronet `UrlRequest` 回调、body source、upload provider | 不适用/平台边界 | 已删除 direct-HTTP 依赖 | Rust raw/text/multipart request 覆盖 app-owned HTTP；Android 若未来需要平台 Cronet 只能作为明确平台特性，不能接入 analyzer/source JS/RSS HTTP |
| Glide OkHttp loader/fetcher/progress | 图片加载、进度、manga 限速 | 平台边界 | 已删除 direct-HTTP fallback | 图片 UI 不属于 Rust analyzer，但 Rust raw fetch 可供需要的桥接 |
| `RuleUpdate` 在线规则更新 | OkHttp 下载规则文件 | 已实现并已替换 | 已替换 | 新 APP `RuleUpdate.cacheSource` 走 `RustRemoteFetch.bytes`/Rust raw fetch，含 zip 首 entry 兼容 |
| `SharedJsScope` 导入 jsLib | OkHttp 下载共享 JS | 已实现并已替换 | 已替换 | Rust jsLib/importScript |
| 在线导入书源/RSS/TTS/替换规则/主题/字典 | OkHttp 下载导入 JSON/text | 已实现并已替换 | 已替换 | 新 APP `Import*ViewModel`/`OnLineImportViewModel` 走 `RustRemoteFetch.text/bytes` |
| RSS 阅读远程正文下载 | OkHttp `newCallResponseBody` / Jsoup 文本提取 | 已实现并已替换核心 | 已替换核心 | RSS analyzer 与 RSS read UI 朗读文本提取均走 Rust；WebView 渲染仍属 Android UI |
| HTTP TTS 音频请求 | OkHttp/ExoPlayer downloader | 已实现并已替换 | 已替换核心 | Rust `fetchTtsAudio/fetchRaw`，播放仍 Android |
| AI / Tavily / MCP HTTP | 原 APP OkHttp JSON API 请求 | 已实现并已替换 | 已替换 | 新 APP `AiChatService`、`AiTavilyTool`、`AiMcpClient` 走 `RustAnalyzerBridge.fetchRawResponse` |
| WebDAV HTTP | 原 APP WebDAV 可能通过 OkHttp/URL/Authorization 处理 | 已实现并已替换核心 | 已替换核心 | 新 APP `WebDav.kt` 的 PROPFIND/GET/PUT/DELETE/MKCOL 请求走 Rust raw fetch；XML/HTML listing 和错误体解析走 Rust UniFFI；Android 仍负责文件/Uri I/O 与进度回调 |

## 新 APP 当前残留

当前新 APP production Jsoup 状态：

| 新 APP 文件 | 残留功能 | 状态 |
|---|---|---:|
| `app/android/app/src/main/java/io/legado/app/help/JsExtensions.kt` | `org.jsoup.Connection` 接口兼容、`RustConnectionResponse.parse()` Document 暴露 | 已清理；项目自有 `RustConnectionResponse`/`RustConnectionMethod`，不依赖 Android Jsoup |
| `app/android/app/src/main/java/io/legado/app/lib/webdav/WebDav.kt` | WebDAV HTTP 请求、XML/HTML listing parse、错误体 parse | 已替换核心 |
| `app/android/app/src/main/java/io/legado/app/model/localBook/EpubFile.kt` | EPUB HTML/metadata/章节/图片解析 | 已替换核心；Android 平台边界保留 | body parse、debug-only classic HTML dump、readable lines、body parse 前预处理、CSS application、image src/options、image page marks、image option materialization、media placeholders、inline style materialization、inherited style propagation、`a[href]` link resolution、body background image candidate extraction、CSS asset extraction/body style-link cleanup 已迁移；Android 仍负责 CSS declaration parsing、EPUB resource href/data、图片尺寸与 native layout 调度 |
| `app/android/app/src/main/java/io/legado/app/model/localBook/EpubDomBuilder.kt` | EPUB DOM 构建 | 已替换核心；Android 平台边界保留 | CSS asset 提取、generated-content 注入、selector matching、node traversal、style computation、body/title DTO handoff 已迁移到 Rust；Android 仅保留 CSS 规则解析、CSS resource loading 与 DTO 映射 |
| `app/android/app/src/main/java/io/legado/app/model/localBook/EpubMiniLayout.kt` | EPUB mini layout parse | 已无调用并删除 |
| `app/android/app/src/main/java/io/legado/app/model/localBook/MobiFile.kt` | MOBI HTML parse | 已替换核心 |
| `app/android/app/src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt` | 阅读 HTML fragment parse/layout | 已替换核心；分页/图片缓存仍为 Android 边界 |
| `app/android/app/src/main/java/io/legado/app/ui/code/CodeEditViewModel.kt` | HTML parse/serialize 辅助 | 已替换核心 |
| `app/android/app/src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt` | RSS HTML to textArray | 已替换 |
| `app/android/app/src/main/java/io/legado/app/utils/EncodingDetect.kt` | HTML charset meta parse | 已替换 |
| `app/android/app/src/main/java/io/legado/app/utils/JsoupExtensions.kt` | Jsoup textArray/namespace helpers | 已无调用并删除 |

当前新 APP OkHttp 状态：

- `app/android/app/src/main/java` 中未发现生产 OkHttp direct request helper、`okhttp3`、`okHttpClient`、`newCallResponse*`、`ResponseBody` 主链路命中。
- `app/android/app/src/test/java/io/legado/app/AppHttpBoundaryTest.kt` 中的 OkHttp/Cronet/ResponseBody 文本是负向边界测试，应保留。
- `DownloadRequest.Builder` 是 ExoPlayer 类型，不是 OkHttp `Request.Builder`。
- `enabledCookieJar` 是源配置字段，不代表 Android OkHttp CookieJar 仍存在。

当前新 APP Cronet 状态：

- `app/android/app/src/main/java` 中未发现生产 Cronet loader/interceptor/callback/upload provider 命中。
- `app/android/app/src/test/java/io/legado/app/AppHttpBoundaryTest.kt` 保留 Cronet 负向边界测试，断言 `app/src/main/java/io/legado/app/lib/cronet`、`Cronet.kt`、`CronetInterceptor.kt`、`CronetLoader.kt`、upload provider、`app/cronetlib`、`assets/cronet.json`、`.github/scripts/cronet.sh`、Gradle `cronetlib` / `cronet-proguard`、`org.chromium.net` proguard 规则等不会回归。
- Cronet 在 Stage 003 中与 OkHttp 一并视为 Android direct-HTTP fallback 风险；除非明确是独立平台特性，否则不得重新接入 analyzer、source JS、RSS、RuleUpdate、在线导入、AI/MCP/Tavily、WebDAV 或 TTS 请求链路。

## Rust 已实现位置

| 能力 | Rust 位置 |
|---|---|
| URL option 解析、method/header/body/charset/dnsIp/proxy/retry/webView 诊断 | `crates/legado-runtime/src/request.rs` |
| HTTP text/raw 请求、data URL、replay cache、cookie 合并、Set-Cookie 保存、unsafe TLS 旧行为兼容 | `crates/legado-runtime/src/request.rs` / `session.rs` |
| multipart direct-link 上传 | `crates/legado-runtime/src/request.rs` / `analyzer.rs` |
| source concurrent rate / ajaxAll 限速 | `crates/legado-runtime/src/request.rs` / `js_runtime.rs` |
| HTML/CSS 规则提取、属性、文本、列表、XPath-to-CSS 子集 | `crates/legado-runtime/src/rule_engine.rs` |
| WebDAV XML/HTML listing、错误体解析 | `crates/legado-runtime/src/webdav.rs` |
| 通用 HTML textArray、charset meta、HTML parse/serialize、title、alignment、readable table、render flags、page background、image info、render plan、MOBI content cleanup、EPUB native entry/readable title/book-info/footnote ids/footnote target/readable lines/body pre-processing/applied CSS/image options/image page marks/media placeholders/inline styles/generated content/native DOM/resolved links/body background image/css assets | `crates/legado-runtime/src/document.rs` |
| `org.jsoup.Jsoup` / `Packages.org.jsoup.*` JS host 兼容 | `crates/legado-runtime/src/js_runtime.rs` |
| `java.ajax/connect/get/head/post/request/ajaxAll/ajaxTestAll` | `crates/legado-runtime/src/js_runtime.rs` |
| response wrapper：`body/code/message/url/header/headers/headersList/contentType/isSuccessful/callTime` | `crates/legado-runtime/src/js_runtime.rs` |
| cookie host API：`cookie.*` / `java.getCookie` / source login cookie | `crates/legado-runtime/src/js_runtime.rs` / `session.rs` |
| UniFFI bridge：`fetchText/fetchRaw/analyzeRaw` 等 | `crates/legado-uniffi/src/lib.rs` |

## 2026-05-24 验证结果

- `docs/http.md` 状态审计：除状态图例外，已无未闭合状态词命中。
- Android/Gradle Jsoup 扫描：`app/android/app/src/main`、`app/android/app/build.gradle`、`app/android/gradle/libs.versions.toml` 对 `org.jsoup|Jsoup.|Connection.Response|Connection.Method|libs.jsoup|libs.jsoupxpath|jsoup|jsoupxpath` 无命中。
- Android production OkHttp/Cronet 扫描：仅剩 ExoPlayer `DownloadRequest.Builder`，不是 OkHttp `Request.Builder`；Cronet 仅在边界测试中出现。
- Cronet artifact 清理：`app/cronetlib`、`app/src/main/assets/cronet.json`、`.github/scripts/cronet.sh`、`org.chromium.net` proguard 规则已删除；`find app/android -iname '*cronet*' -not -path '*/build/*'` 无结果。
- 旧 analyzer/Rhino 扫描：仅剩 `AppHttpBoundaryTest` 负向断言。
- Rust：`cargo fmt --check`、`cargo test -p legado-runtime`、`cargo test -p legado-runtime --test http_cache_replay`、`cargo test -p legado-uniffi` 均通过；`legado-runtime` 当前为 295 个单元测试、6 个 direct replay、9 个 cache replay。
- Android：`SYNC_ONLY=1 ./build.sh` 同步 native artifacts 后，`cd app/android && ./gradlew :app:testAppDebugUnitTest :app:assembleAppDebug :app:assembleAppDebugAndroidTest` 通过。
- Cronet 清理后补测：`cd app/android && ./gradlew :app:testAppDebugUnitTest --tests io.legado.app.AppHttpBoundaryTest :app:assembleAppDebug` 通过。
- 真机：设备 `89RX0E523` 安装 `legado_app_3.26.052419_10009.apk` 和 `app-app-debug-androidTest.apk` 后，`RustAnalyzerInstrumentedSmokeTest#htmlDocumentHelpersUseRustUniFfi` 通过，`OK (1 test)`，耗时 5.336s。
- 工作区检查：`git diff --check` 通过。

## 必跑扫描

从仓库根目录运行：

```bash
rg -n "org\.jsoup|Jsoup\.|Connection\.Response|Connection\.Method|okhttp3|OkHttp|okHttpClient|newCall|newCallResponse|newCallResponseBody|newCallStrResponse|ResponseBody|Request\.Builder|FormBody|MultipartBody|CookieJar|Interceptor|Cronet|HttpURLConnection" \
  legacy/android/app/src/main \
  -g '*.kt' -g '*.java'
```

```bash
rg -n "org\.jsoup|Jsoup\.|Connection\.Response|Connection\.Method|okhttp3|OkHttp|okHttpClient|newCall|newCallResponse|newCallResponseBody|newCallStrResponse|ResponseBody|Request\.Builder|FormBody|MultipartBody|CookieJar|Interceptor|Cronet|HttpURLConnection" \
  app/android/app/src/main \
  app/android/app/src/test \
  app/android/app/src/androidTest \
  -g '*.kt' -g '*.java'
```

```bash
rg -n "Jsoup|org\.jsoup|__legadoJsoup|reqwest|RequestEngine|parse_legado_request|upload_multipart|cookie|Set-Cookie|Selector|Html::parse|select_html|extract_html" \
  crates \
  -g '*.rs' -g 'Cargo.toml'
```

## 后续优先级

1. 继续补真实服务器/坏 HTML/XML fixture，覆盖 WebDAV、charset、reader 本地书边界。
2. 若后续扫描发现新的 Android Jsoup/OkHttp/Cronet 生产引用，应按本文件矩阵继续迁移到 Rust 或明确标注平台边界。
