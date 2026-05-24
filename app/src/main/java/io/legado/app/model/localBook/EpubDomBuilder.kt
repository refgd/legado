package io.legado.app.model.localBook

import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.model.webBook.RustEpubCssAsset
import io.legado.app.model.webBook.RustEpubNativeComputedStyle
import io.legado.app.model.webBook.RustEpubNativeDomNode
import io.legado.app.model.webBook.RustEpubNativeStyleValue
import io.legado.app.utils.GSON

internal class EpubDomBuilder(
    private val loadCss: (baseHref: String, href: String) -> String,
    private val resolveHref: (baseHref: String, href: String) -> String
) {

    fun build(
        documentHtml: String,
        bodyHtml: String,
        bodyOuterHtml: String,
        title: String,
        baseHref: String
    ): EpubDomDocument {
        val cssAssets = RustAnalyzerBridge.epubCssAssets(
            documentHtml = documentHtml,
            bodyHtml = bodyHtml,
            rulePath = "EpubDomBuilder.cssAssets"
        ).assets
        val rules = collectRules(cssAssets, baseHref).mapIndexed { index, rule ->
            rule.copy(order = index)
        }
        val fontFaces = collectFontFaces(cssAssets, baseHref)
        val generatedContentRules = collectGeneratedContentRules(cssAssets, baseHref)
        val nativeBodyOuterHtml = if (generatedContentRules.isNotEmpty()) {
            RustAnalyzerBridge.epubGeneratedContent(
                bodyOuterHtml = bodyOuterHtml,
                rulesJson = GSON.toJson(generatedContentRules),
                rulePath = "EpubDomBuilder.generatedContent"
            )
        } else {
            bodyOuterHtml
        }
        val bodyElement = RustAnalyzerBridge.epubNativeDom(
            bodyOuterHtml = nativeBodyOuterHtml,
            rulesJson = GSON.toJson(rules),
            baseHref = baseHref,
            rulePath = "EpubDomBuilder.nativeDom"
        ).body.toEpubDomElement()
        return EpubDomDocument(
            href = baseHref,
            title = title.takeIf { it.isNotBlank() },
            body = bodyElement,
            fontFaces = fontFaces
        )
    }

    private fun collectFontFaces(cssAssets: List<RustEpubCssAsset>, baseHref: String): List<EpubFontFace> {
        val faces = arrayListOf<EpubFontFace>()
        fun resolveCssUrl(cssHref: String, href: String): String {
            return resolveHref(cssHref, href)
        }
        cssAssets.forEach { asset ->
            when (asset.kind) {
                "inline" -> faces.addAll(
                    EpubCss.parseFontFaces(asset.content) { href ->
                        resolveCssUrl(baseHref, href)
                    }
                )
                "stylesheet" -> asset.href.trim().takeIf { it.isNotBlank() }?.let { href ->
                    faces.addAll(EpubCss.parseFontFaces(loadCss(baseHref, href)))
                }
            }
        }
        return faces.distinctBy { face ->
            "${face.family.lowercase()}|${face.weight.orEmpty()}|${face.style.orEmpty()}|${face.src}"
        }
    }

    private fun collectRules(cssAssets: List<RustEpubCssAsset>, baseHref: String): List<EpubCss.Rule> {
        val rules = arrayListOf<EpubCss.Rule>()
        cssAssets.forEach { asset ->
            when (asset.kind) {
                "inline" -> rules.addAll(EpubCss.parseRules(asset.content, supportedOnly = false))
                "stylesheet" -> asset.href.trim().takeIf { it.isNotBlank() }?.let { href ->
                    rules.addAll(EpubCss.parseRules(loadCss(baseHref, href), supportedOnly = false))
                }
            }
        }
        return rules
    }

    private fun collectGeneratedContentRules(
        cssAssets: List<RustEpubCssAsset>,
        baseHref: String
    ): List<EpubCss.GeneratedContentRule> {
        val rules = arrayListOf<EpubCss.GeneratedContentRule>()
        cssAssets.forEach { asset ->
            when (asset.kind) {
                "inline" -> rules.addAll(EpubCss.parseGeneratedContentRules(asset.content))
                "stylesheet" -> asset.href.trim().takeIf { it.isNotBlank() }?.let { href ->
                    rules.addAll(EpubCss.parseGeneratedContentRules(loadCss(baseHref, href)))
                }
            }
        }
        return rules
    }

    private fun RustEpubNativeDomNode.toEpubDomNode(): EpubDomNode? {
        return when (kind) {
            "text" -> EpubDomText(text, sourcePath)
            "element" -> toEpubDomElement()
            else -> null
        }
    }

    private fun RustEpubNativeDomNode.toEpubDomElement(): EpubDomElement {
        return EpubDomElement(
            tagName = tagName.ifBlank { "body" },
            attributes = attributes,
            style = style.toEpubComputedStyle(),
            children = children.mapNotNull { it.toEpubDomNode() },
            sourcePath = sourcePath.ifBlank { "body" }
        )
    }

    private fun RustEpubNativeComputedStyle.toEpubComputedStyle(): EpubComputedStyle {
        return EpubComputedStyle(declarations.mapValues { (_, value) ->
            value.toEpubStyleValue()
        })
    }

    private fun RustEpubNativeStyleValue.toEpubStyleValue(): EpubStyleValue {
        return EpubStyleValue(
            value = value,
            important = important,
            sourceRank = sourceRank,
            specificity = specificity,
            ruleOrder = ruleOrder,
            declarationOrder = declarationOrder
        )
    }
}
