package com.example.folio

import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.alerts.AlertsExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension
import org.commonmark.ext.image.attributes.ImageAttributesExtension
import org.commonmark.ext.ins.InsExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.html.UrlSanitizer
import java.util.Locale

internal object MarkdownRenderer {
    private data class ProtectedMarkdown(
        val markdown: String,
        val replacements: List<MathReplacement>
    )

    private data class MathReplacement(
        val marker: String,
        val tex: String,
        val display: Boolean
    )

    private val basicParser = Parser.builder().build()
    private val basicRenderer = HtmlRenderer.builder()
        .escapeHtml(true)
        .sanitizeUrls(true)
        .urlSanitizer(StrictMarkdownUrlSanitizer)
        .percentEncodeUrls(true)
        .build()
    private val enhancedExtensions: List<Extension> = listOf(
        TablesExtension.create(),
        AutolinkExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        FootnotesExtension.create(),
        HeadingAnchorExtension.create(),
        ImageAttributesExtension.create(),
        InsExtension.create(),
        AlertsExtension.create()
    )
    private val enhancedParser = Parser.builder()
        .extensions(enhancedExtensions)
        .build()
    private val enhancedRenderer = HtmlRenderer.builder()
        .extensions(enhancedExtensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .urlSanitizer(StrictMarkdownUrlSanitizer)
        .percentEncodeUrls(true)
        .build()

    fun toHtml(markdown: String, enhanced: Boolean = true): String {
        val parser = if (enhanced) enhancedParser else basicParser
        val renderer = if (enhanced) enhancedRenderer else basicRenderer
        val protectedMarkdown = protectMath(markdown)
        val rendered = renderer.render(parser.parse(protectedMarkdown.markdown))
        return restoreMath(rendered, protectedMarkdown.replacements)
    }

    private fun protectMath(markdown: String): ProtectedMarkdown {
        val replacements = mutableListOf<MathReplacement>()
        val protectedLines = mutableListOf<String>()
        val lines = markdown.lines()
        var index = 0
        var inFence = false
        var fenceMarker: String? = null

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            val fenceMatch = Regex("""^(`{3,}|~{3,})""").find(trimmed)
            if (fenceMatch != null) {
                val marker = fenceMatch.value.first().toString().repeat(fenceMatch.value.length)
                if (!inFence) {
                    inFence = true
                    fenceMarker = marker
                } else if (trimmed.startsWith(fenceMarker.orEmpty())) {
                    inFence = false
                    fenceMarker = null
                }
                protectedLines += line
                index += 1
                continue
            }

            if (!inFence && line.trim() == "${'$'}${'$'}") {
                val mathLines = mutableListOf<String>()
                index += 1
                while (index < lines.size && lines[index].trim() != "${'$'}${'$'}") {
                    mathLines += lines[index]
                    index += 1
                }
                if (index < lines.size) {
                    val marker = "FOLIO_BLOCK_MATH_${replacements.size}"
                    replacements += MathReplacement(marker, mathLines.joinToString("\n"), display = true)
                    protectedLines += marker
                    index += 1
                    continue
                }
                protectedLines += "${'$'}${'$'}"
                protectedLines += mathLines
                continue
            }

            protectedLines += if (inFence) line else protectInlineMath(line, replacements)
            index += 1
        }

        return ProtectedMarkdown(protectedLines.joinToString("\n"), replacements)
    }

    private fun protectInlineMath(line: String, replacements: MutableList<MathReplacement>): String {
        val result = StringBuilder()
        var cursor = 0
        while (cursor < line.length) {
            val start = findUnescapedDollar(line, cursor)
            if (start < 0) {
                result.append(line.substring(cursor))
                break
            }
            val end = findUnescapedDollar(line, start + 1)
            if (end < 0) {
                result.append(line.substring(cursor))
                break
            }
            val tex = line.substring(start + 1, end)
            if (tex.isBlank()) {
                result.append(line.substring(cursor, end + 1))
            } else {
                val marker = "FOLIO_INLINE_MATH_${replacements.size}"
                replacements += MathReplacement(marker, tex, display = false)
                result.append(line.substring(cursor, start))
                result.append(marker)
            }
            cursor = end + 1
        }
        return result.toString()
    }

    private fun findUnescapedDollar(text: String, startIndex: Int): Int {
        var index = startIndex
        while (index < text.length) {
            if (text[index] == '$') {
                var slashCount = 0
                var previous = index - 1
                while (previous >= 0 && text[previous] == '\\') {
                    slashCount += 1
                    previous -= 1
                }
                val isDoubleDollar = index + 1 < text.length && text[index + 1] == '$'
                if (slashCount % 2 == 0 && !isDoubleDollar) return index
            }
            index += 1
        }
        return -1
    }

    private fun restoreMath(html: String, replacements: List<MathReplacement>): String {
        var restored = html
        replacements.forEach { replacement ->
            val mathHtml = mathElementHtml(replacement)
            if (replacement.display) {
                restored = Regex("""<p>\s*${Regex.escape(replacement.marker)}\s*</p>""")
                    .replace(restored) { mathHtml }
            }
            restored = restored.replace(replacement.marker, mathHtml)
        }
        return restored
    }

    private fun mathElementHtml(replacement: MathReplacement): String {
        val escapedTex = escapeHtmlAttribute(replacement.tex)
        return if (replacement.display) {
            """<div class="folio-math folio-math-display" data-display="true" data-tex="$escapedTex"></div>"""
        } else {
            """<span class="folio-math" data-display="false" data-tex="$escapedTex"></span>"""
        }
    }

    private fun escapeHtmlAttribute(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(char)
            }
        }
    }
}

internal object StrictMarkdownUrlSanitizer : UrlSanitizer {
    override fun sanitizeLinkUrl(url: String): String {
        val clean = url.trim()
        if (clean.startsWith("#")) return clean
        return if (clean.schemeIsOneOf("http", "https", "mailto")) clean else "#"
    }

    override fun sanitizeImageUrl(url: String): String {
        val clean = url.trim()
        return if (clean.schemeIsOneOf("http", "https")) clean else ""
    }

    private fun String.schemeIsOneOf(vararg allowed: String): Boolean {
        val separator = indexOf(':')
        if (separator <= 0) return false
        val scheme = substring(0, separator).lowercase(Locale.ROOT)
        return allowed.any { it == scheme }
    }
}
