# Legado JS Analyze API Coverage

本文档按现有 Rust 实现和原 APP `legacy/android` 对照列举 direct-HTTP analyzer 相关的规则、函数和功能，并标记 Rust 是否已经实现。

状态说明：

- 已实现：Rust direct-HTTP 路径中已有等价或更严格实现。
- 部分实现：Rust 已支持常见直接 HTTP 语义，但缺少原 APP 的部分重载、边界行为或平台侧行为。
- 平台边界：原 APP 通过 Android UI、WebView、浏览器、媒体或设备状态完成；Rust 不直接实现 UI，应通过 UniFFI/Android platform host 连接，未连接时必须 fail-fast。
- 未实现：Rust 当前没有对应能力，或只在内部 helper 中存在但未暴露为原 APP 等价 API。
- 不适用：原 APP 内部实现细节，Rust 用不同结构实现，不需要暴露给规则 JS。

## 参考范围

原 APP 参考文件：

- `legacy/android/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/JsExtensions.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/JsEncodeUtils.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSoup.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByJSonPath.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByXPath.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByRegex.kt`
- `legacy/android/app/src/main/java/io/legado/app/model/analyzeRule/RuleDataInterface.kt`
- `legacy/android/app/src/main/java/io/legado/app/help/source/BaseSourceExtensions.kt`

Rust 实现文件：

- `crates/legado-runtime/src/analyzer.rs`
- `crates/legado-runtime/src/rule_engine.rs`
- `crates/legado-runtime/src/js_runtime.rs`
- `crates/legado-runtime/src/request.rs`
- `crates/legado-runtime/src/session.rs`
- `crates/legado-runtime/src/platform.rs`
- `crates/legado-runtime/src/rss.rs`

## Analyzer 入口

| 能力 | 原 APP 语义 | Rust 状态 | Rust 位置/备注 |
|---|---|---:|---|
| 搜索 `search` | `searchUrl` 经 `AnalyzeUrl` 解析并请求，`ruleSearch.bookList` 选列表，字段规则提取书名、作者、分类、封面、简介、最新章节、字数、详情 URL | 已实现 | `Analyzer::search` |
| 详情 `detail` | 访问 `bookUrl`，执行 `ruleBookInfo.init`，提取书籍字段和 `tocUrl` | 已实现 | `Analyzer::detail` |
| 目录 `toc` | 访问 `tocUrl`，`ruleToc.chapterList` 选列表，支持反转、下一目录页、字段提取、`formatJs`、`preUpdateJs` | 已实现/平台边界 | Rust 已支持 `preUpdateJs`、`reGetBook`、`refreshTocUrl`、分页、反转、去重、`formatJs`；WebView 相关作为平台边界 |
| 正文 `content` | 访问章节 URL，提取标题、正文、下一页、子内容、替换规则；可用 `webJs/sourceRegex` 走 WebView | 已实现/平台边界 | direct-HTTP 已实现；`ruleContent.webJs/sourceRegex` 作为平台边界 fail-fast/host |
| 发现 `explore` | `exploreUrl` 可返回分类列表或分类 URL，再按探索规则/搜索规则提取书籍 | 已实现 | `Analyzer::explore` |
| 任意 JS `eval` | 用 analyzer host 绑定执行 JS，用于调试、登录、工具规则等 | 已实现 | `Analyzer::eval` |
| 任意规则 `evalRule` | 对给定 `result/baseUrl/key/page` 执行规则提取 | 已实现 | `Analyzer::eval_rule` |
| 字典 `dict_search` | 请求 URL，再用 `showRule` 提取/执行 JS | 已实现 | `Analyzer::dict_search` |
| 封面搜索 `cover_search` | 请求封面源 URL，用 `coverRule` 返回封面 URL | 已实现 | `Analyzer::cover_search` |
| URL 解析 `resolve_url` | 对 Legado URL 规则、URL option 和 `serverID` 解析 | 已实现 | `Analyzer::resolve_url` |
| 直链上传 `direct_link_upload` | 按 URL option body 中的 `fileRequest` 上传文件，再用规则提取下载 URL | 已实现 | `Analyzer::direct_link_upload` |
| 文本请求 `fetch_text` | 解析 URL 规则并返回文本响应 JSON | 已实现 | `Analyzer::fetch_text` |
| 二进制请求 `fetch_raw` | 解析 URL 规则并返回 base64 body、headers、状态码；用于 HTTP TTS 等 | 已实现 | `Analyzer::fetch_raw` |
| RSS `sortUrls/articles/content` | RSS 源 URL、列表和正文规则解析 | 已实现/平台边界 | `RssAnalyzer` 覆盖 direct-HTTP、`rssArticle` binding、call-site override、平台边界诊断，并接入 Android bridge |

## 规则调度和提取

| 功能 | 原 APP 语义 | Rust 状态 | 备注 |
|---|---|---:|---|
| `AnalyzeRule.setContent` 内容类型检测 | `content.toString().isJson()` 决定 JSONPath；`Node` 视为非 JSON | 已实现 | `RuleContent::from_body` 区分 JSON 与 HTML；保留 Rust 结构化内容类型 |
| `@CSS:` | 强制 JSoup/CSS 模式 | 已实现 | `RuleMode::Html` / CSS selector |
| `@@` | 去掉转义 `@` 后按默认 HTML 规则 | 已实现 | `classify_rule_mode` |
| `@XPath:` | XPath 模式 | 已实现/诊断 | Rust 支持 direct-HTTP 常见 XPath 到 CSS：多段路径、绝对路径、`[@attr]`、`[@attr='v']`、`contains(@attr/text(),...)`、位置索引、`last()`；非 analyzer 常用/非 CSS 可表达形态 fail-fast |
| `/...` XPath 自动识别 | 以 `/` 开头默认 XPath | 已实现/诊断 | 同上 |
| `@Json:` | 强制 JSONPath | 已实现 | 支持简单 JSONPath、数组、通配、递归等核心用法 |
| `$.` / `$[` 自动 JSONPath | JSON 内容或显式 JSONPath 使用 JSON 提取 | 已实现 | `extract_path/extract_value_path` |
| 默认 HTML/JSoup | CSS selector、属性、文本、HTML 等提取 | 已实现/诊断 | Rust 覆盖 direct-HTTP 常用 selector、属性、文本、HTML、DOM 集合和变更方法；非 analyzer 需要的 JVM/Android DOM 扩展不作为 Rust 目标 |
| 正则 mode | `getElement/getElements` 可按正则链提取 | 已实现 | Rust 支持 `&&` 正则链、单个/列表匹配组，并为结果数组提供 Java List 兼容 `get/size/isEmpty/toArray` |
| `<js>...</js>` | 规则链中执行 JS，`result` 为上一步结果 | 已实现 | `eval_mixed_js_chain` |
| `@js:` | 整段或 URL 段执行 JS | 已实现 | URL 和字段规则都支持 |
| `<webJs>...</webJs>` | 交给 WebView 执行 JS | 平台边界 | Rust 对 WebView 需求 fail-fast 或经 platform host |
| `{{js}}` | 模板 JS，结果拼回规则/URL | 已实现 | `apply_template` |
| `@get:key` | 读取规则变量 | 已实现 | 通过 `java.get` / source store 等兼容 |
| `@put:{...}` | 提取过程中写变量 | 已实现 | `split_put_rules` 写入 Rust store |
| `$1` `$2` 正则组拼接 | 正则结果或模板拼接 | 已实现 | Rust replacement 使用 regex group expansion，覆盖普通替换和 `###` 首匹配替换 |
| `##regex##replacement` | 正则替换全部匹配 | 已实现 | `split_replacements` |
| `##regex##replacement###` | 原 APP 表示取首个匹配后替换 | 已实现 | Rust 按原 APP `split("##")` 后额外 `#` 段触发 `replaceFirst`：仅取首个匹配片段并替换 |
| `isUrl=true` 绝对 URL | 字段 URL 规则按 `redirectUrl/baseUrl` 补全 | 已实现 | `absolutize` |
| HTML 反转列表 | `chapterList` 前缀 `-` 反转 | 已实现 | `Analyzer::toc` |
| 目录多页 | `nextTocUrl` 循环加载 | 已实现 | 带循环去重 |
| 正文多页 | `nextContentUrl` 循环加载 | 已实现 | 避免下一章和重复页 |
| `ruleBookInfo.init` | 可 JS、JSONPath 或直接根内容 | 已实现 | HTML 上使用 JSON init 会报错 |

## URL 规则和请求

| 功能 | 原 APP `AnalyzeUrl` | Rust 状态 | 备注 |
|---|---|---:|---|
| URL 中 `<js>` / `@js:` | 先执行 JS 段，`@result` 引用前段结果 | 已实现 | `RuleEngine::eval_url_js_segments` |
| `{{key}}` `{key}` | 替换搜索关键字 | 已实现 | |
| `{{page}}` `{page}` | 替换页码 | 已实现 | |
| `<a,b,c>` 分页列表 | 按 page 选择列表项，超过取最后 | 已实现 | Rust 现在与 `AnalyzeUrl.pagePattern` 语义一致，`page <= 0` 取第一页，超过列表长度取最后一项 |
| URL option JSON | `url,{...}` 解析请求参数 | 已实现 | `parse_legado_request` |
| `method` | `GET` / `POST` / `HEAD` | 已实现 | |
| `headers` | 合并源 header、URL option header、login header | 已实现 | |
| `body` 字符串或 JSON | POST body；表单串可编码 | 已实现 | 非 JSON/XML 且无 `Content-Type` 时按原 APP 走 form body，默认 UTF-8，可用 `charset`/`escape` 覆盖并保留已编码组件 |
| `charset` | query/form/body 解码编码 | 已实现 | 覆盖 GBK 等 charset 和原 APP `charset="escape"` 的 query/form 编码语义 |
| `origin` | 源 URL 元数据 | 已实现 | Rust 解析并保留元数据；与原 APP 一样不改变请求执行 |
| `retry` | 请求重试次数 | 已实现 | |
| `type` | 文件/二进制类型，原 APP 可返回 body hex | 已实现 | `RequestEngine::get_text` 与 `java.ajax` 对非空 type 返回 body hex |
| `webView` | 用 WebView 请求/渲染 | 平台边界 | Rust fail-fast |
| `webJs` | WebView 中执行 JS | 平台边界 | Rust fail-fast |
| `sourceRegex` | WebView 捕获资源 | 平台边界 | Rust fail-fast |
| `dnsIp` | 指定 DNS/IP | 已实现 | `build_override_client` |
| `js` | URL option 解析后执行 JS，结果替换 URL | 已实现 | `apply_url_option_js` |
| `bodyJs` | 请求返回后执行 JS 处理 body | 已实现 | `consume_url_option_script` |
| `serverID` | 服务器 ID | 已实现 | `resolve_url` 返回 |
| `webViewDelayTime` | WebView 延迟 | 平台边界 | 非 WebView 请求中诊断为不支持 |
| data URI | base64 data URL 直接读取 | 已实现 | text/raw 路径支持 |
| Cookie 合并 | URL/source cookie 和 cookie jar 合并 | 已实现 | Rust 合并持久/session cookie 与显式 Cookie header，URL option 临时 Cookie 覆盖同名 session key |
| 代理 `proxy` header/option | 使用代理 client | 已实现 | `parse_proxy_option` |
| 重定向控制 | JSoup `get/head/post` 不跟随；普通请求默认跟随 | 已实现 | helper API 中区分 |
| HTTP cache replay | 测试可复用缓存 fixture | 已实现 | `http_cache_replay` |

## JS 执行环境

| 绑定/行为 | 原 APP | Rust 状态 | 备注 |
|---|---|---:|---|
| `java` | 当前 `AnalyzeRule`/`AnalyzeUrl` 的 JS host | 已实现 | `JsRuntime::install_host` |
| `source` | 当前书源/订阅源对象 | 已实现 | source host object |
| `cookie` | `CookieStore` | 已实现 | Rust session cookie object |
| `cache` | `CacheManager` | 已实现 | Rust persistent/global cache |
| `book` | 当前书籍/变量对象 | 已实现 | Rust store object + bindings |
| `chapter` | 当前章节/变量对象 | 已实现 | Rust store object + bindings |
| `result` | 当前规则/JS 输入 | 已实现 | 支持 string、response wrapper、byte wrapper、JSON marker |
| `baseUrl` | 当前基础 URL | 已实现 | |
| `key` | 搜索关键字 | 已实现 | |
| `page` | 页码 | 已实现 | |
| `speakText` / `speakSpeed` | HTTP TTS URL JS 绑定 | 已实现 | `tts_bindings_json` |
| `infoMap` | TTS/登录等传入信息 map | 已实现 | explore 持久 infoMap 和通用 eval binding Map-like wrapper 均支持 `get/put/remove/containsKey/putAll` |
| `src` | 原始内容 | 已实现 | 通过 `bindings_json` 注入 JS 全局 |
| `nextChapterUrl` | 下一章 URL | 已实现 | 正文防越界内部使用；通过 `bindings_json` 注入 JS 全局 |
| `rssArticle` | RSS 文章对象 | 已实现 | RSS content 路径按原 APP `AnalyzeRule` 语义注入序列化 `RssArticle`，覆盖 `origin/sort/title/link/pubDate/description/content/image/group/read/variable/type/durPos` 等字段 |
| `fromBookInfo` | 是否详情页流程 | 已实现 | 通过 `bindings_json` 注入 JS 全局 |
| source shared scope | `jsLib` 共享 Rhino scope | 已实现 | Rust 加载 `jsLib`、兼容顶层函数/隐式全局 |
| `importScript` eval 预处理 | 导入脚本可声明函数/隐式全局 | 已实现 | `preprocess_imported_eval_script` |
| Rhino completion value | 语句块返回最后表达式 | 已实现 | `normalize_script`/测试覆盖 |
| `JavaImporter` | Rhino Java importer | 已实现/诊断 | 覆盖常用 `Jsoup`、crypto、JsonPath、Base64 等；未建模 Android/JVM 专有包通过 `Packages` 明确诊断 |
| `getClass(value)` | Rhino/Java class introspection 兼容入口 | 已实现/诊断 | 对 Rust 安全子集返回 class-like wrapper；Android/JVM 专有 `Packages` 明确不支持 |
| `Packages` | Java package 访问 | 已实现/诊断 | 只白盒实现 direct-HTTP 需要的包；Android 专有/未知包不实现 JVM bridge，写 `java.log` 明确记录不支持后 fail-fast |
| `org.jsoup.Jsoup` | JSoup parse/connect | 已实现/诊断 | 覆盖 `parse/select/selectFirst/text/ownText/html/outerHtml/attr/hasAttr/class/Elements size/get/first/last/eachText/eachAttr/remove/before/after` 和 connect GET/HEAD/POST |
| `com.jayway.jsonpath.JsonPath` | JsonPath Java API | 已实现/诊断 | 支持 read/parse/using/suppress、key/index/wildcard/recursive、filter、union、slice 常见 Jayway 用法 |
| `Packages.java.util.Collections.reverse` | Java 列表反转 | 已实现 | prelude |
| `Packages.java.lang.Thread.sleep` | 线程 sleep | 已实现 | Rust host 按毫秒真实 sleep；调用者仍应避免在 Android UI 线程执行 analyzer |
| `Packages.java.net.URLEncoder` | URL 编码 | 已实现 | |
| `Packages.android.util.Base64` | Android Base64 常量和 encode/decode | 已实现 | |
| `Packages.javax.crypto.*` | Mac/Cipher/SecretKeySpec/IvParameterSpec | 已实现/诊断 | 覆盖当前 direct-HTTP/TTS 常见 AES/HMAC 形态；不支持的 Java provider 行为明确报错 |
| `Packages.java.io.ByteArrayInputStream/OutputStream` | 字节流 | 已实现 | JS byte wrapper |

## `java` 网络和请求 API

| API | 原 APP 功能 | Rust 状态 | 备注 |
|---|---|---:|---|
| `java.ajax(url)` | 请求 URL 返回 body string | 已实现 | 出错返回 `__LEGADO_REQUEST_ERROR__...` 并在包装层 fail-fast |
| `java.ajax(url, callTimeout)` | 带超时请求 | 已实现 | |
| `java.ajaxAll(urlList)` | 并发请求，返回 `StrResponse[]` | 已实现 | Rust 返回响应 wrapper 数组，并按源 `concurrentRate` 节流 |
| `java.ajaxAll(urlList, skipRateLimit)` | 跳过限速 | 已实现 | Rust 支持源级 `concurrentRate`，`skipRateLimit=true` 绕过节流 |
| `java.ajaxTestAll(urlList, timeout)` | 批量测速，错误返回负 callTime | 已实现 | Rust 写入正耗时；失败按原 APP 形态返回负码：超时/中断 `-1`、DNS `-3`、连接/发送 `-4`、socket `-5`、TLS `-6`、其他 `-7` |
| `java.ajaxTestAll(urlList, timeout, skipRateLimit)` | 同上 | 已实现 | 同样支持源级 `concurrentRate` 和 `skipRateLimit=true` |
| `java.connect(url)` | 请求 URL 返回 `StrResponse` | 已实现 | `body/code/headers/url/...` wrapper |
| `java.connect(url, header)` | 附加 header 请求 | 已实现 | |
| `java.connect(url, header, callTimeout)` | 附加 header 和超时 | 已实现 | |
| `request(url)` | 全局跨域请求函数，返回 body | 已实现 | |
| `request(url, method, body[, headers, timeout])` | 全局请求重载，支持方法、body、header、超时 | 已实现 | 旧一参行为保留；重载走 Rust `RequestEngine` 并沿用 URL option `js/bodyJs` 处理 |
| `java.get(url, headers)` | JSoup GET，不跟随重定向，返回 response | 已实现 | 通过 `__httpResponse` wrapper |
| `java.get(url, headers, timeout)` | JSoup GET + timeout | 已实现 | |
| `java.head(url, headers)` | JSoup HEAD，不返回 body | 已实现 | |
| `java.head(url, headers, timeout)` | JSoup HEAD + timeout | 已实现 | |
| `java.post(url, body, headers)` | JSoup POST，不跟随重定向 | 已实现 | |
| `java.post(url, body, headers, timeout)` | JSoup POST + timeout | 已实现 | |
| `StrResponse.body()` | 响应 body | 已实现 | |
| `StrResponse.code()` / `statusCode()` | HTTP 状态码 | 已实现 | |
| `StrResponse.message()` / `statusMessage()` | 状态消息 | 已实现 | |
| `StrResponse.url()` | 最终 URL | 已实现 | |
| `StrResponse.headers()` | header map | 已实现 | |
| `StrResponse.headers(name)` | 指定 header 的全部值 | 已实现 | |
| `StrResponse.header(name)` | 指定 header 首值 | 已实现 | |
| `StrResponse.headersList()` | 保留重复 header | 已实现 | |
| `StrResponse.contentType()` | Content-Type | 已实现 | |
| `StrResponse.isSuccessful()` | 2xx 判断 | 已实现 | |
| `StrResponse.callTime()` | 请求耗时或错误码 | 已实现 | `ajaxTestAll` 成功返回耗时毫秒，失败返回原 APP 兼容负错误码 |
| `AnalyzeUrl.getStrResponse/getResponse/getByteArray/getInputStream` | Kotlin 内部请求入口 | 不适用 | Rust 用 `RequestEngine`/`Analyzer` 入口替代 |
| `AnalyzeUrl.upload(fileName,file,contentType)` | multipart 上传 | 已实现 | Rust 通过 `direct_link_upload` 支持 direct-link 上传 |
| `AnalyzeUrl.getGlideUrl/getMediaItem/getMediaRequest` | 图片/媒体平台对象 | 已实现/平台边界 | Android 负责对象创建；URL/header 解析和图片 raw fetch 走 Rust |

## `java` WebView、浏览器、媒体和 Android UI API

| API | 原 APP 功能 | Rust 状态 | 备注 |
|---|---|---:|---|
| `java.webView(html,url,js[,cacheFirst])` | 后台 WebView 渲染/执行 JS | 已实现/平台边界 | Rust 通过 platform host 调 Android WebView；无 host 时 fail-fast |
| `java.webViewGetSource(html,url,js,sourceRegex[,cacheFirst,delay])` | WebView 捕获资源 URL/body | 已实现/平台边界 | Rust 通过 platform host 调 Android WebView |
| `java.webViewGetOverrideUrl(html,url,js,overrideUrlRegex[,cacheFirst,delay])` | WebView 捕获跳转 URL | 已实现/平台边界 | Rust 通过 platform host 调 Android WebView |
| `java.startBrowser(url,title[,html])` | 打开内置浏览器验证 | 已实现/平台边界 | platform host 接 Android 浏览器/验证 UI |
| `java.startBrowserAwait(url,title[,refetchAfterSuccess,html])` | 打开浏览器并等待结果 | 已实现/平台边界 | 返回 Rust `StrResponse` wrapper |
| `java.showBrowser(...)` | 显示浏览器 UI | 已实现/平台边界 | platform host 接 Android UI |
| `java.startBrowserDp(...)` | DP/特定浏览器流程 | 已实现/平台边界 | platform host 接 Android UI |
| `java.showReadingBrowser(...)` | 阅读浏览器 | 已实现/平台边界 | platform host 接 Android UI |
| `java.openVideoPlayer(url,title[,isFloat])` | 打开内置播放器 | 已实现/平台边界 | platform host 接 Android 播放器 |
| `java.getVerificationCode(imageUrl)` | 验证码 UI | 已实现/平台边界 | platform host 接 Android UI |
| `java.openUrl(url[,mimeType])` | 打开外部 URL/导入链接 | 已实现/平台边界 | platform host 接 Android intent/UI |
| `java.copyText(text)` | 复制文本 | 已实现/平台边界 | platform host 接 Android 剪贴板/UI context |
| `java.showPhoto(...)` | 显示图片 | 已实现/平台边界 | platform host 接 Android UI |
| `java.searchBook(...)` | UI 搜索书籍 | 已实现/平台边界 | platform host 接 Android UI |
| `java.addBook(...)` | 添加书籍 | 已实现/平台边界 | platform host 接 Android UI |
| `java.open(...)` | 打开页面/对象 | 已实现/平台边界 | platform host 接 Android UI |
| `java.reLoginView(deltaUp)` | 登录 UI | 已实现/平台边界 | platform host 接 Android 登录 UI |
| `java.upLoginData(...)` | 更新登录信息 UI | 已实现/平台边界 | platform host 接 Android 登录 UI |
| `java.refreshBookInfo()` | UI 刷新书籍信息 | 已实现/平台边界 | platform host 接 Android UI |
| `java.refreshBookToc()` | UI 刷新目录 | 已实现/平台边界 | platform host 接 Android UI |
| `java.refreshContent()` | UI 刷新正文 | 已实现/平台边界 | platform host 接 Android UI |
| `java.clearTtsCache()` | 清 TTS 缓存 | 已实现/平台边界 | platform host 接 Android UI/cache |
| `java.refreshExplore()` / `source.refreshExplore()` | 刷新发现分类/UI | 已实现/平台边界 | platform host 接 Android UI；无 UI context 时调用 source 刷新 |
| `java.getReadBookConfig()` / `getReadBookConfigMap()` | 当前阅读配置 | 已实现/平台边界 | platform host 返回 Android 阅读配置 JSON |
| `java.getThemeMode()` | 当前主题模式 | 已实现/平台边界 | platform host 返回 Android 配置 |
| `java.getThemeConfig()` / `getThemeConfigMap()` | 主题配置 | 已实现/平台边界 | platform host 返回 Android 配置 JSON |
| `java.getWebViewUA()` | Android WebView UA | 已实现/平台边界 | platform host 返回 Android WebView UA；无 host fail-fast |
| `java.androidId()` | 设备 ID | 已实现/平台边界 | platform host 返回 Android ID |
| `java.getAppVersionName()` | APP 版本名 | 已实现/平台边界 | platform host 返回 Android app info |
| `java.getAppVersionCode()` | APP 版本号 | 已实现/平台边界 | platform host 返回 Android app info |
| `java.getAppVariant()` | APP variant | 已实现/平台边界 | platform host 返回 Android app info |

## `java` 文件、缓存、压缩包和字体 API

| API | 原 APP 功能 | Rust 状态 | 备注 |
|---|---|---:|---|
| `java.importScript(path)` | 从网络或本地读 JS 文本 | 已实现 | 网络走 Rust 请求，本地走 Rust-managed virtual file/cache |
| `java.cacheFile(url[,saveTime])` | 下载文本并缓存后返回内容 | 已实现 | |
| `java.downloadFile(url)` | 下载文件到缓存，返回相对路径 | 已实现 | Rust virtual file |
| `java.downloadFile(content,url)` | 旧 API：hex 写文件 | 已实现 | 兼容 `__downloadHexFile` |
| `java.getFile(path)` | 返回 File 对象 | 已实现/诊断 | Rust 返回 analyzer virtual file object；真实 Android/JVM `File` 不是 Rust 目标 |
| `java.readFile(path)` | 读文件为 ByteArray | 已实现 | virtual file byte wrapper |
| `java.readTxtFile(path[,charset])` | 读文本文件 | 已实现 | |
| `java.writeTxtFile(path,text)` | 写文本文件 | 已实现 | Rust 扩展兼容 |
| `java.deleteFile(path)` | 删除文件/目录 | 已实现 | virtual file/cache |
| `java.fileExist(path)` | 文件存在 | 已实现 | Rust 扩展兼容 |
| `java.unzipFile(path)` | 解压 zip 返回目录 | 已实现 | Rust virtual folder |
| `java.un7zFile(path)` | 解压 7z 返回目录 | 已实现 | |
| `java.unrarFile(path)` | 解压 rar 返回目录 | 已实现 | |
| `java.unArchiveFile(path)` | 解压压缩包返回目录 | 已实现 | 统一入口按扩展名支持 zip/7z/rar，并保留 `unzipFile/un7zFile/unrarFile` |
| `java.getTxtInFolder(path)` | 读取目录内文本并删除目录 | 已实现 | virtual folder |
| `java.getZipByteArrayContent(url,path)` | 读取 zip 内文件 bytes | 已实现 | |
| `java.getZipStringContent(url,path[,charset])` | 读取 zip 内文件文本 | 已实现 | |
| `java.getRarByteArrayContent(url,path)` | 读取 rar 内文件 bytes | 已实现 | |
| `java.getRarStringContent(url,path[,charset])` | 读取 rar 内文件文本 | 已实现 | |
| `java.get7zByteArrayContent(url,path)` | 读取 7z 内文件 bytes | 已实现 | |
| `java.get7zStringContent(url,path[,charset])` | 读取 7z 内文件文本 | 已实现 | |
| `java.queryTTF(data[,useCache])` | 解析 TTF 字体 | 已实现 | Rust `ttf-parser` |
| `java.queryBase64TTF(data)` | 旧字体 API | 已实现 | alias |
| `java.replaceFont(text,errorTTF,correctTTF[,filter])` | 字体反混淆 | 已实现 | |
| `QueryTTF.getGlyfIdByUnicode` | Unicode 到 glyph id | 已实现 | JS object method |
| `QueryTTF.getGlyfByUnicode` | Unicode 到轮廓 | 已实现 | |
| `QueryTTF.getUnicodeByGlyf` | 轮廓反查 Unicode | 已实现 | |
| `QueryTTF.isBlankUnicode` | 判断空白 Unicode | 已实现 | |

## `java` 编码、时间、文本和日志 API

| API | 原 APP 功能 | Rust 状态 | 备注 |
|---|---|---:|---|
| `java.strToBytes(str[,charset])` | 字符串转 ByteArray | 已实现 | JS byte wrapper |
| `java.bytesToStr(bytes[,charset])` | ByteArray 转字符串 | 已实现 | |
| `java.base64Decode(str)` | Base64 decode string | 已实现 | |
| `java.base64Decode(str,charset)` | Base64 decode 后按 charset 解码 | 已实现 | |
| `java.base64Decode(str,flags)` | Android Base64 flags | 已实现 | |
| `java.base64DecodeToByteArray(str[,flags])` | Base64 到 ByteArray | 已实现 | |
| `java.base64Encode(str[,flags])` | 字符串 base64 | 已实现 | |
| `java.hexDecodeToByteArray(hex)` | hex 到 ByteArray | 已实现 | |
| `java.hexDecodeToString(hex)` | hex 到 UTF-8 字符串 | 已实现 | |
| `java.hexEncodeToString(str)` | UTF-8 字符串到 hex | 已实现 | |
| `java.timeFormat(time)` | APP 默认时间格式 | 已实现 | |
| `java.timeFormatUTC(time,format,sh)` | 指定 UTC offset/格式 | 已实现 | |
| `java.encodeURI(str[,enc])` | URL encode | 已实现 | |
| `java.htmlFormat(str)` | HTML 正文格式化 | 已实现 | |
| `java.t2s(text)` | 繁转简 | 已实现 | |
| `java.s2t(text)` | 简转繁 | 已实现 | |
| `java.toNumChapter(s)` | 章节数字归一 | 已实现 | |
| `java.toURL(url[,baseUrl])` | URL 解析对象 | 已实现 | 返回 JS URL object JSON 形态 |
| `java.toast(msg)` | 短 Toast | 已实现/平台连接 | 写入 session.toasts，并可分发 platform host |
| `java.longToast(msg)` | 长 Toast | 已实现/平台连接 | 同上 |
| `java.log(msg)` | 调试日志，返回 msg | 已实现/平台连接 | 写入 session.logs |
| `java.logType(any)` | 输出类型 | 已实现/平台连接 | |
| `java.randomUUID()` | UUID | 已实现 | |
| `java.getSource()` | 返回 source 对象 | 已实现 | |
| `java.getTag()` | 返回 source tag/name | 已实现 | |
| `java.getCookie(tag[,key])` | 读取 cookie | 已实现 | 委托 Rust cookie object |

## `java` 加密和摘要 API

| API | 原 APP 功能 | Rust 状态 | 备注 |
|---|---|---:|---|
| `java.md5Encode(str)` | MD5 32 位 | 已实现 | |
| `java.md5Encode16(str)` | MD5 16 位 | 已实现 | |
| `java.digestHex(data,algorithm)` | 摘要 hex | 已实现 | 支持常见 MD5/SHA 系列 |
| `java.digestBase64Str(data,algorithm)` | 摘要 base64 | 已实现 | |
| `java.HMacHex(data,algorithm,key)` | HMAC hex | 已实现 | |
| `java.HMacBase64(data,algorithm,key)` | HMAC base64 | 已实现 | |
| `java.createSymmetricCrypto(transformation,key[,iv])` | 对称加解密对象 | 已实现 | AES/DES/3DES 常用 transformation |
| `crypto.encrypt(data)` | 对称加密 bytes | 已实现 | |
| `crypto.encryptBase64(data)` | 对称加密 base64 | 已实现 | |
| `crypto.encryptHex(data)` | 对称加密 hex | 已实现 | |
| `crypto.decrypt(data)` | 对称解密 bytes | 已实现 | |
| `crypto.decryptStr(data)` | 对称解密字符串 | 已实现 | |
| `cipher.encrypt/encryptHex/decrypt/decryptStr` | 原帮助页链式加解密对象方法名 | 已实现 | `java.createSymmetricCrypto(...)` 和 `java.createAsymmetricCrypto(...)` 返回对象均提供同名方法 |
| `java.createAsymmetricCrypto(transformation)` | RSA 等非对称加密对象 | 已实现/诊断 | 支持 RSA PKCS#1 常见链式 API；不支持的 transformation/provider 明确 fail-fast |
| `asym.setPublicKey(key)` | 设置公钥 | 已实现 | |
| `asym.setPrivateKey(key)` | 设置私钥 | 已实现 | |
| `asym.encryptBase64/Hex/encrypt` | 非对称加密 | 已实现 | |
| `asym.decryptStr/decrypt` | 非对称解密 | 已实现/诊断 | 支持常见 PEM/DER；不支持的 key/provider 明确 fail-fast |
| `java.createSign(algorithm)` | 签名对象 | 已实现/诊断 | 支持常见 RSA 签名路径；不支持的算法明确 fail-fast |
| `sign.setPrivateKey(key)` | 设置签名私钥 | 已实现 | |
| `sign.signHex(data)` | 签名 hex | 已实现 | |
| `sign.signBase64(data)` | 签名 base64 | 已实现 | |
| `sign.sign(data)` | 原帮助页签名 base64 别名 | 已实现 | 等价于当前 Rust signer 的 base64 签名输出 |
| `java.aesDecodeToByteArray` | 旧 AES 解密 bytes | 已实现 | wrapper |
| `java.aesDecodeToString` | 旧 AES 解密 string | 已实现 | wrapper |
| `java.aesDecodeArgsBase64Str` | key/iv base64 的 AES 解密 | 已实现 | wrapper |
| `java.aesBase64DecodeToByteArray` | AES base64 解密 bytes | 已实现 | wrapper |
| `java.aesBase64DecodeToString` | AES base64 解密 string | 已实现 | wrapper |
| `java.aesEncodeToByteArray` | AES 加密 bytes | 已实现 | wrapper |
| `java.aesEncodeToString` | 原 APP 旧方法实际调用 decryptStr | 已实现 | 保留原形态 |
| `java.aesEncodeToBase64ByteArray` | AES 加密 base64 bytes | 已实现 | 返回 base64 字符串的 byte wrapper，匹配原 APP `encryptBase64(...).toByteArray()` 形态 |
| `java.aesEncodeToBase64String` | AES 加密 base64 string | 已实现 | |
| `java.aesEncodeArgsBase64Str` | key/iv base64 的 AES 加密 | 已实现 | |
| `java.desDecodeToString` | DES 解密 string | 已实现 | |
| `java.desBase64DecodeToString` | DES base64 解密 string | 已实现 | |
| `java.desEncodeToString` | DES 加密 lossy string | 已实现 | |
| `java.desEncodeToBase64String` | DES 加密 base64 | 已实现 | |
| `java.tripleDESDecodeStr` | 3DES 解密 | 已实现 | |
| `java.tripleDESDecodeArgsBase64Str` | 3DES key base64 解密 | 已实现 | |
| `java.tripleDESEncodeBase64Str` | 3DES 加密 base64 | 已实现 | |
| `java.tripleDESEncodeArgsBase64Str` | 3DES key base64 加密 | 已实现 | |

## `source` API

| API/属性 | 原 APP 功能 | Rust 状态 | 备注 |
|---|---|---:|---|
| `source.bookSourceUrl` / `source.sourceUrl` | 源 key/url | 已实现 | |
| `source.bookSourceName` / `source.sourceName` | 源名称 | 已实现 | |
| `source.jsLib` | 源 JS 库文本 | 已实现 | |
| `source.loginUrl` | 登录脚本文本 | 已实现 | |
| `source.header` | 源请求头规则文本 | 已实现 | |
| `source.variableComment` | 变量说明 | 已实现 | extra scalar |
| 其他 source 字段 | Android entity 字段 | 已实现 | Rust 将 `source.extra` 中安全的 JSON string/number/bool/object/array/null 暴露到 JS `source`；内置字段和 `loginUi` 特殊函数保持优先 |
| `source.getKey()` | 源 key | 已实现 | |
| `source.getHeaderMap([hasLoginHeader])` | 解析源 header，必要时合并登录 header | 已实现 | 返回 JS map-like object，补 Rust direct-HTTP 默认 UA；`true` 时合并 `source.getLoginHeaderMap()` |
| `source.getVariable()` | 返回 source variable JSON | 已实现 | |
| `source.getVariable(key)` | 取 source variable 字段 | 已实现 | |
| `source.setVariable(json)` | 设置 source variable JSON | 已实现 | |
| `source.setVariable(key,value)` / `putVariable` | 设置 source variable 字段 | 已实现 | |
| `source.put(key,value)` | source-scoped 持久 KV | 已实现 | |
| `source.get(key)` | source-scoped 持久 KV | 已实现 | |
| `source.getLoginInfo()` | 登录信息 JSON/raw | 已实现 | |
| `source.getLoginInfoMap()` | 登录信息 map，带 `.get(key)` | 已实现 | |
| `source.putLoginInfo(json)` | 写登录信息 JSON | 已实现 | |
| `source.putLoginInfo(key,value)` | 写登录信息字段 | 已实现 | |
| `source.removeLoginInfo()` | 清登录信息 | 已实现 | |
| `source.getLoginHeader()` | 登录 header JSON/raw | 已实现 | |
| `source.getLoginHeaderMap()` | 登录 header map | 已实现 | |
| `source.putLoginHeader(header)` | 写登录 header；Cookie 同步 | 已实现 | |
| `source.removeLoginHeader()` | 清登录 header | 已实现 | |
| `source.loginUi` | 登录 UI 配置文本/函数 | 已实现/平台边界 | 文本兼容；实际 UI 由 Android |
| `source.refreshExplore()` | 刷新发现 | 已实现/平台边界 | 通过 platform host 接 Android；无 host 时 fail-fast |
| `source.refreshJSLib()` | 清 jsLib/import 缓存 | 已实现 | |
| `source.putConcurrent(value)` | 运行时更新当前源并发率 | 已实现 | 更新 Rust `RequestEngine` 当前 source key 的 rate limit，影响后续 `java.ajax/ajaxAll/request` |
| `source.login()` | 去除 `loginUrl` 的 `@js:`/`<js>` 包装后 eval，并调用脚本定义的 `login()` | 已实现 | Rust/rquickjs 按原 APP `BaseSource.login()` 语义执行；内部 WebView/UI 行为仍按平台边界处理 |

## `cache`、`cookie`、`book`、`chapter`

| 对象/API | 原 APP 功能 | Rust 状态 | 备注 |
|---|---|---:|---|
| `cache.get(key)` | 全局/持久缓存 | 已实现 | |
| `cache.put(key,value)` | 写缓存 | 已实现 | |
| `cache.delete(key)` | 删除缓存 | 已实现 | |
| `cache.putFile(key,value[,saveTime])` | 缓存较大文本内容 | 已实现 | Rust 使用独立 file-cache 通道；`saveTime` 作为兼容参数接收 |
| `cache.getFile(key)` | 读取 file-cache 文本内容 | 已实现 | 与普通 `cache.get` 分离；`cache.delete` 会一并清理 |
| `cache.putMemory(key,value)` | 内存缓存 | 已实现 | 只写 Rust 会话内存层，不写持久 cache |
| `cache.getFromMemory(key)` | 读内存缓存 | 已实现 | 与原 APP 一样，`cache.put` 写入的内存副本也可读 |
| `cache.deleteMemory(key)` | 删除内存缓存 | 已实现 | 只删除内存层；普通 `cache.delete` 才删除持久 cache |
| `cache.putVariable/setVariable/getVariable` | 变量别名 | 已实现 | |
| `cookie.getCookie(host)` | 取 cookie | 已实现 | 支持域名匹配 |
| `cookie.getKey(host,key)` | 取 cookie 字段 | 已实现 | |
| `cookie.setCookie(host,value)` | 设置 cookie | 已实现 | |
| `cookie.replaceCookie(host,value)` | 替换 cookie | 已实现 | alias |
| `cookie.setWebCookie(host,value)` | 设置 WebView cookie | 已实现/平台边界 | Rust 通过 platform action 接 Android `CookieManager`；无 host 时 fail-fast |
| `cookie.removeCookie(host)` | 删除 cookie | 已实现 | |
| `book.get/put/delete` | 书籍变量 | 已实现 | |
| `book.getVariable/putVariable/setVariable` | 书籍变量别名 | 已实现 | |
| `book.durChapterTitle` | 当前章节标题 | 已实现 | Android bridge bindings 填充；Rust JS host 可读写并回写 session |
| `book.durChapterIndex` | 当前章节索引 | 已实现 | Android bridge bindings 填充；Rust JS host 可读写并回写 session |
| `book.setUseReplaceRule(bool)` | 设置替换净化开关 | 已实现 | 写入 book variable |
| `book.getUseReplaceRule()` | 读取替换净化开关 | 已实现 | |
| `chapter.get/put/delete` | 章节变量 | 已实现 | |
| `chapter.getVariable/putVariable/setVariable` | 章节变量别名 | 已实现 | |
| `chapter.index` | 章节索引 | 已实现 | Android bridge bindings 填充；Rust JS host 可读写并回写 session |
| `chapter.putLyric(value)` | 存储音频章节歌词 | 已实现 | Rust 写入 chapter variable `lyric`；Android bridge 回写 `BookChapter.variable` |
| `chapter.putImgUrl(value)` | 存储章节图标链接 | 已实现 | Rust 写入 chapter `imgUrl`；Android bridge 回写 `BookChapter.imgUrl` |

## AnalyzeRule 暴露方法

| 方法 | 原 APP 功能 | Rust 状态 | 备注 |
|---|---|---:|---|
| `setRuleName(name)` | 设置调试 tag | 不适用 | Rust 用 diagnostics rule_path/source |
| `setContent(content,baseUrl)` | 设置内容并检测 JSON | 已实现 | Rust 内部 `RuleContent::from_body` |
| `setBaseUrl(baseUrl)` | 设置 baseUrl | 已实现 | AnalyzerInput/base URL |
| `setRedirectUrl(url)` | 设置重定向 URL 用于补全 URL | 已实现 | JS host 更新 `java.redirectUrl` 和 `baseUrl`；data URL 保持原 redirectUrl |
| `getStringList(rule[,content,isUrl])` | 规则提取 string list | 已实现 | `RuleEngine::select_list/eval_field_rule` |
| `getString(rule[,content,isUrl,unescape])` | 规则提取 string | 已实现 | HTML unescape/URL 绝对化常见行为覆盖 |
| `getElement(rule)` | 返回元素/对象 | 已实现/平台边界 | JS host 覆盖 HTML selector、`:regex` all-in-one、JSONPath、`@XPath:`/常见 XPath 转 CSS；WebJs 走平台边界诊断 |
| `getElements(rule)` | 返回元素/对象列表 | 已实现/平台边界 | 同上，HTML/regex 结果提供常用 JSoup/Java List 兼容方法 |
| `put(key,value)` | 写当前章节/书/规则/source 变量 | 已实现 | Rust `java.put`/store |
| `get(key)` | 读当前章节/书/规则/source 变量 | 已实现 | Rust `java.get`/store |
| `evalJS(js,result)` | 执行 JS | 已实现 | `JsRuntime::eval_rule_script` |
| `ajax(url)` override | 规则上下文请求 | 已实现 | |
| `reGetBook()` | `preUpdateJs` 里精准搜索并重载详情 | 已实现 | Rust `handle_pre_update_actions` |
| `refreshTocUrl()` | `preUpdateJs` 里重载详情更新 tocUrl | 已实现 | |

## AnalyzeUrl 暴露方法/属性

| 方法/属性 | 原 APP 功能 | Rust 状态 | 备注 |
|---|---|---:|---|
| `ruleUrl` | 原始/规则 URL | 已实现 | JS host 可读写；`initUrl()` 会按当前 ruleUrl 重解析 |
| `url` | 解析后的 URL | 已实现 | |
| `type` | URL option type | 已实现 | 非空 type 触发文本入口返回 hex body |
| `headerMap` | 请求 header map | 已实现 | 内部和 response 输出 |
| `urlNoQuery` | 去 query URL | 已实现 | 内部 |
| `serverID` | URL option serverID | 已实现 | `resolve_url` |
| `initUrl()` | 重新解析 URL | 已实现 | JS host 支持 URL-JS、`{{...}}`、分页列表、baseUrl 补全 |
| `evalJS(js,result)` | URL 上下文 JS | 已实现 | |
| `put/get` | 读写 ruleData/chapter 变量 | 已实现 | |
| `getHeaderMap()` | 登录检查 JS 中读取/修改请求 header map | 已实现 | 兼容原帮助页裸调用；等价于 `java.getHeaderMap()` |
| `getStrResponseAwait/getStrResponse` | 返回文本响应 | 已实现 | Analyzer/request host |
| `getResponseAwait/getResponse` | 返回 OkHttp response | 已实现 | Rust 返回 direct-HTTP response wrapper，覆盖 `body/code/message/header(s)/url/contentType/isSuccessful`；不暴露 JVM OkHttp 对象 |
| `getErrResponse/getErrStrResponse` | 构造错误响应 | 已实现 | Rust 返回 500 `StrResponse` wrapper，body/errorBody 包含错误文本 |
| `getByteArrayAwait/getByteArray` | 二进制 body | 已实现 | `fetch_raw`/file APIs |
| `getInputStreamAwait/getInputStream` | stream | 已实现 | Rust raw bytes 通过 `ByteArrayInputStream` wrapper 暴露，覆盖 `read/available/close` |
| `upload(fileName,file,contentType)` | multipart 上传 | 已实现 | `direct_link_upload` |
| `getGlideUrl()` | Glide 图片 URL | 已实现/平台边界 | Android Glide loader 使用 Rust raw fetch/headers |
| `getUserAgent()` | Header UA 或默认 UA | 已实现 | 合并源 header、登录 header、URL option header；无 UA 时返回 Rust direct-HTTP 默认 UA |
| `isPost()` | 判断 POST | 已实现 | URL resolve 输出 method |
| `getMediaItem/getMediaRequest` | ExoPlayer 请求 | 已实现/平台边界 | Android 通过 Rust `resolveUrl` 后创建 ExoPlayer 对象 |

## 范围边界和诊断策略

当前 Rust 对 direct-HTTP `search/detail/toc/content` 的核心规则和常见 JS host 已经覆盖。下列项目不是 Rust analyzer 继续补 Kotlin/Rhino/JVM bridge 的待实现项，而是明确的边界或受控兼容范围：

| 范围 | 当前行为 |
|---|---|
| WebView/browser/media 行为 | 作为平台边界；Android platform host 已连接的动作执行，缺 host 或无法安全表示时 fail-fast |
| XPath | Rust 支持 direct-HTTP 规则使用的可转换 XPath；不能转换的复杂表达式给出诊断，不静默降级 |
| JSoup selector 和 DOM API | Rust 覆盖 analyzer 常用 `parse/select/text/html/attr/mutation/connect`；非 analyzer 需要的 JVM DOM 扩展诊断处理 |
| Java/Rhino bridge | 不恢复完整 JVM/Rhino bridge；`Packages` 只实现 direct-HTTP 必需包，Android 专有/未知包写日志并明确不支持 |
| Android `File`/`InputStream`/`Response` 原生对象 | Rust 使用安全 JS wrapper/JSON，不暴露 Android/JVM 对象 |
| Android cookie jar 与 WebView cookie 同步 | Rust session cookie store 已实现；WebView cookie 通过 Android host 同步，缺 host fail-fast |
| `source` Android entity 的全部方法/字段 | 暴露 direct-HTTP 规则常用字段和 JSON `extra`；Android entity 专有行为归平台边界 |

## 结论

Rust 已经实现 direct-HTTP analyzer 的主链路：URL 规则、请求、JSON/HTML/JS 规则调度、`jsLib`、常用 Rhino 兼容、`java.ajax/connect/request`、cookie/cache/source/book/chapter 状态、编码/加密/文件/压缩包/字体辅助、登录 header/info 和平台 API 诊断。

Android/JVM/WebView 专有能力不作为 Rust 内部 JVM bridge 实现；Rust 要么通过 UniFFI platform host 连接 Android，要么写入日志并以 source/rule/request/API 上下文 fail-fast。
