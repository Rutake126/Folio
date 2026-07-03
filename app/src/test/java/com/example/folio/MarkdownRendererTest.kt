package com.example.folio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

class MarkdownRendererTest {
    @Test
    fun documentUriSchemeIsRestricted() {
        assertTrue(isSupportedDocumentScheme("content"))
        assertTrue(isSupportedDocumentScheme("file"))
        assertFalse(isSupportedDocumentScheme("https"))
        assertFalse(isSupportedDocumentScheme("javascript"))
        assertFalse(isSupportedDocumentScheme(null))
    }

    @Test
    fun importedDocumentsAreKeyedBySourceUriNotTitle() {
        val existing = listOf(
            LibraryDocument(
                title = "README.md",
                subtitle = "我的手机 • 10:00",
                kind = DocumentKind.Markdown,
                sourceUri = "content://docs/one"
            ),
            LibraryDocument(
                title = "README.md",
                subtitle = "我的手机 • 10:01",
                kind = DocumentKind.Markdown,
                sourceUri = "content://docs/two"
            )
        )

        val updated = updatedImportedDocuments(
            documents = existing,
            document = DocumentState(
                title = "README.md",
                kind = DocumentKind.Markdown,
                sourceUri = "content://docs/two"
            ),
            importedAt = "10:02"
        )

        assertEquals(2, updated.size)
        assertEquals("content://docs/two", updated[0].sourceUri)
        assertEquals("content://docs/one", updated[1].sourceUri)
    }

    @Test
    fun unsafeLinkProtocolsAreNeutralized() {
        val html = MarkdownRenderer.toHtml(
            """
            [script](javascript:alert(1))
            [asset](file:///android_asset/vendor/katex/katex.min.js)
            [relative](../secret)
            """.trimIndent()
        )

        assertFalse(html.contains("javascript:", ignoreCase = true))
        assertFalse(html.contains("file:///android_asset", ignoreCase = true))
        assertFalse(html.contains("../secret", ignoreCase = true))
        assertTrue(html.contains("href=\"#\""))
    }

    @Test
    fun safeLinkProtocolsAreKept() {
        val html = MarkdownRenderer.toHtml(
            """
            [web](https://example.com/docs?q=folio)
            [mail](mailto:feedback@example.com)
            [anchor](#section)
            """.trimIndent()
        )

        assertTrue(html.contains("href=\"https://example.com/docs?q=folio\""))
        assertTrue(html.contains("href=\"mailto:feedback@example.com\""))
        assertTrue(html.contains("href=\"#section\""))
    }

    @Test
    fun unsafeImageProtocolsAreRemoved() {
        val html = MarkdownRenderer.toHtml(
            """
            ![script](javascript:alert(1))
            ![content](content://example/private)
            ![web](https://example.com/image.png)
            """.trimIndent()
        )

        assertFalse(html.contains("javascript:", ignoreCase = true))
        assertFalse(html.contains("content://", ignoreCase = true))
        assertTrue(html.contains("src=\"https://example.com/image.png\""))
    }

    @Test
    fun blockMathIsProtectedBeforeMarkdownParsing() {
        val html = MarkdownRenderer.toHtml(
            """
            $${'$'}
            \begin{bmatrix}
            1 & 2 & 3 \\
            4 & 5 & 6
            \end{bmatrix}
            $${'$'}
            """.trimIndent()
        )

        assertTrue(html.contains("class=\"folio-math folio-math-display\""))
        assertTrue(html.contains("data-display=\"true\""))
        assertTrue(html.contains("""\begin{bmatrix}"""))
        assertTrue(html.contains("""4 &amp; 5 &amp; 6"""))
    }

    @Test
    fun mathMarkersIgnoreCodeFences() {
        val html = MarkdownRenderer.toHtml(
            """
            ```kotlin
            val price = "${'$'}12"
            ```

            Inline math ${'$'}a^2 + b^2 = c^2${'$'}.
            """.trimIndent()
        )

        assertTrue(html.contains("""val price = &quot;${'$'}12&quot;"""))
        assertTrue(html.contains("""class="folio-math""""))
        assertTrue(html.contains("""data-tex="a^2 + b^2 = c^2""""))
    }

    @Test
    fun pageIndexerKeepsFencedBlocksTogether() {
        val markdown = buildString {
            appendLine("# Page")
            repeat(30000) { appendLine("paragraph $it") }
            appendLine("```mermaid")
            repeat(30000) { appendLine("A$it --> B$it") }
            appendLine("```")
            repeat(30000) { appendLine("tail $it") }
        }

        val index = MarkdownPageIndexer.index(ByteArrayInputStream(markdown.toByteArray()))

        assertTrue(index.pages.isNotEmpty())
        index.pages.forEach { page ->
            val slice = markdown.toByteArray().copyOfRange(page.start.toInt(), page.end.toInt()).decodeToString()
            assertFalse(slice.contains("```mermaid") && !slice.contains("\n```"))
        }
    }

    @Test
    fun pageIndexerCreatesAtLeastOnePageForEmptyFile() {
        val index = MarkdownPageIndexer.index(ByteArrayInputStream(ByteArray(0)))

        assertEquals(1, index.pages.size)
        assertEquals(0L, index.pages.first().start)
        assertEquals(0L, index.pages.first().end)
    }

    @Test
    fun pageIndexerRejectsOversizedSingleLine() {
        val markdown = "a".repeat(65)

        try {
            MarkdownPageIndexer.index(
                input = ByteArrayInputStream(markdown.toByteArray()),
                maxLineBytes = 64
            )
            fail("Expected oversized single line to be rejected")
        } catch (error: IllegalArgumentException) {
            assertEquals("单行内容过大，请拆分后再打开", error.message)
        }
    }

    @Test
    fun pageIndexerRejectsUnknownSizeStreamOverLimit() {
        val markdown = buildString {
            repeat(6) { appendLine("line $it") }
        }

        try {
            MarkdownPageIndexer.index(
                input = ByteArrayInputStream(markdown.toByteArray()),
                maxFileBytes = 30
            )
            fail("Expected stream over max file bytes to be rejected")
        } catch (error: IllegalArgumentException) {
            assertEquals("Markdown 文件过大，最大支持 500MB", error.message)
        }
    }

    @Test
    fun readerHtmlBlocksRemoteImagesByDefault() {
        val html = buildReaderHtml(
            body = """<p><img src="https://example.com/a.png" alt=""></p>""",
            fontScale = 1f,
            useSerif = true,
            readerTheme = "light",
            layoutMode = ReaderLayoutMode.Original,
            typographyStyle = ReaderTypographyStyle.Folio,
            allowRemoteImages = false
        )

        assertTrue(html.contains("remote-image-blocked"))
        assertTrue(html.contains("data-remote-src=\"https://example.com/a.png\""))
        assertTrue(Jsoup.parse(html).select("main img[src]").isEmpty())
    }

    @Test
    fun readerHtmlKeepsRemoteImagesWhenAllowed() {
        val html = buildReaderHtml(
            body = """<p><img src="https://example.com/a.png" alt="cover"></p>""",
            fontScale = 1f,
            useSerif = true,
            readerTheme = "light",
            layoutMode = ReaderLayoutMode.Original,
            typographyStyle = ReaderTypographyStyle.Folio,
            allowRemoteImages = true
        )

        assertFalse(html.contains("data-remote-src=\"https://example.com/a.png\""))
        assertTrue(html.contains("src=\"https://example.com/a.png\""))
    }

    @Test
    fun readerHtmlRemovesTaskListMarkers() {
        val body = MarkdownRenderer.toHtml(
            """
            - [x] 支持 CommonMark 基础语法
            - [ ] 支持 500MB 文档的分块分页阅读
            """.trimIndent()
        )
        val html = buildReaderHtml(
            body = body,
            fontScale = 1f,
            useSerif = true,
            readerTheme = "light",
            layoutMode = ReaderLayoutMode.Original,
            typographyStyle = ReaderTypographyStyle.Folio,
            allowRemoteImages = true
        )

        assertTrue(body.contains("type=\"checkbox\""))
        assertTrue(html.contains("task-list-item"))
        assertTrue(html.contains("contains-task-list"))
        assertTrue(html.contains(".task-list-item::marker"))
        assertTrue(html.contains("content: \"\""))
    }

    @Test
    fun readerHtmlKeepsGanttDiagramsScrollable() {
        val html = buildReaderHtml(
            body = """
                <pre><code class="language-mermaid">gantt
                    title Plan
                    dateFormat YYYY-MM-DD
                    section Stability
                    Import test :active, a1, 2026-07-03, 2d
                </code></pre>
            """.trimIndent(),
            fontScale = 1f,
            useSerif = true,
            readerTheme = "light",
            layoutMode = ReaderLayoutMode.Original,
            typographyStyle = ReaderTypographyStyle.Folio,
            allowRemoteImages = true
        )

        val document = Jsoup.parse(html)
        val diagram = document.selectFirst(".mermaid-gantt")

        assertTrue(diagram != null)
        assertEquals("gantt", diagram?.attr("data-diagram-type"))
        assertTrue(html.contains("calculateGanttLayoutWidth"))
        assertTrue(html.contains("useWidth: layoutWidth"))
        assertTrue(html.contains("--folio-gantt-width"))
    }

    @Test
    fun readerHtmlRecognizesFrontmatterGanttDiagrams() {
        val html = buildReaderHtml(
            body = """
                <pre><code class="language-mermaid">---
                title: Plan
                ---
                gantt
                    dateFormat YYYY-MM-DD
                    section Stability
                    Import test :active, a1, 2026-07-03, 2d
                </code></pre>
            """.trimIndent(),
            fontScale = 1f,
            useSerif = true,
            readerTheme = "light",
            layoutMode = ReaderLayoutMode.Original,
            typographyStyle = ReaderTypographyStyle.Folio,
            allowRemoteImages = true
        )

        val document = Jsoup.parse(html)
        val diagram = document.selectFirst(".mermaid-gantt")

        assertTrue(diagram != null)
        assertEquals("gantt", diagram?.attr("data-diagram-type"))
    }
}
