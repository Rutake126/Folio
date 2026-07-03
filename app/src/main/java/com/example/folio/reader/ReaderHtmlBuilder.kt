package com.example.folio

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale

private fun annotateTablesForMobile(body: String): String {
    val fragment = Jsoup.parseBodyFragment(body)
    fragment.select("table").forEach { table ->
        val headers = table.select("thead tr").firstOrNull()?.select("th,td")?.map { it.text().trim() }
            ?: table.select("tr").firstOrNull()?.select("th,td")?.map { it.text().trim() }
            ?: emptyList()

        table.select("tbody tr, tr").forEachIndexed { rowIndex, row ->
            val cells = row.select("td")
            if (cells.isEmpty() || rowIndex == 0 && row.parent()?.tagName() != "tbody") return@forEachIndexed
            cells.forEachIndexed { index, cell ->
                val label = headers.getOrNull(index).orEmpty()
                if (label.isNotBlank()) {
                    cell.attr("data-label", label)
                }
            }
        }
    }
    return fragment.body().html().trim()
}

private fun wrapTablesForScroll(body: String): String {
    val fragment = Jsoup.parseBodyFragment(body)
    fragment.select("table").forEach { table ->
        if (table.parent()?.hasClass("table-scroll") != true) {
            table.wrap("""<div class="table-scroll"></div>""")
        }
    }
    return fragment.body().html().trim()
}

private fun prepareMarkdownEnhancements(body: String, allowRemoteImages: Boolean): String {
    val fragment = Jsoup.parseBodyFragment(body)
    fragment.select("pre > code").forEach { code ->
        val languageClasses = code.classNames()
        val isMermaid = languageClasses.any { it == "mermaid" || it == "language-mermaid" }
        if (isMermaid) {
            val diagram = normalizeMermaidDiagram(code.wholeText().trim())
            val diagramType = mermaidDiagramType(diagram)
            val diagramElement = Element("div")
                .addClass("mermaid")
                .addClass("mermaid-$diagramType")
                .attr("data-diagram", diagram)
                .attr("data-diagram-type", diagramType)
            code.parent()?.replaceWith(diagramElement)
        }
    }
    fragment.select("li").forEach { item ->
        if (item.selectFirst("input[type=checkbox]") != null) {
            item.addClass("task-list-item")
            item.parent()?.addClass("contains-task-list")
        }
    }
    if (!allowRemoteImages) {
        fragment.select("img[src]").forEach { image ->
            val src = image.attr("src")
            if (src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)) {
                image.attr("data-remote-src", src)
                image.removeAttr("src")
                image.addClass("remote-image-blocked")
                if (image.attr("alt").isBlank()) {
                    image.attr("alt", "远程图片已阻止")
                }
            }
        }
    }
    return fragment.body().html().trim()
}

private fun mermaidDiagramType(diagram: String): String {
    val firstLine = mermaidBodyLines(diagram).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return firstLine.substringBefore(' ')
        .substringBefore(':')
        .lowercase(Locale.ROOT)
        .ifBlank { "diagram" }
}

private fun normalizeMermaidDiagram(diagram: String): String {
    val firstLine = mermaidBodyLines(diagram).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (!firstLine.startsWith("flowchart") && !firstLine.startsWith("graph")) return diagram
    return diagram
        .replace(Regex("""([A-Za-z][\w-]*)\[([^\]"\n]*[\u0080-\uFFFF ][^\]"\n]*)]""")) { match ->
            val id = match.groupValues[1]
            val label = match.groupValues[2].replace("\"", "\\\"")
            """$id["$label"]"""
        }
}

private fun mermaidBodyLines(diagram: String): Sequence<String> {
    val lines = diagram.lineSequence().toList()
    val firstContentIndex = lines.indexOfFirst { it.isNotBlank() }
    if (firstContentIndex < 0 || lines[firstContentIndex].trim() != "---") return lines.asSequence()
    val closingIndex = lines
        .drop(firstContentIndex + 1)
        .indexOfFirst { it.trim() == "---" }
        .takeIf { it >= 0 }
        ?.let { firstContentIndex + 1 + it }
        ?: return lines.asSequence()
    return lines.asSequence().drop(closingIndex + 1)
}

private fun mobileTableCss(): String = """
            table {
              display: block;
              width: 100%;
              border-collapse: separate;
              border-spacing: 0;
              border: 0;
            }
            table thead {
              display: none;
            }
            table tbody, table tr, table td {
              display: block;
              width: 100%;
            }
            table tr {
              margin: 0 0 .9em;
              border: 1px solid var(--line);
              border-radius: 10px;
              background: transparent;
              overflow: hidden;
            }
            table td {
              border: 0;
              border-bottom: 1px solid var(--line);
              padding: .68em .8em;
              color: var(--muted);
              text-align: left;
              word-break: normal;
              overflow-wrap: break-word;
            }
            table td:last-child {
              border-bottom: 0;
            }
            table td[data-label] {
              display: grid;
              grid-template-columns: minmax(86px, 34%) 1fr;
              gap: .75em;
            }
            table td[data-label]::before {
              content: attr(data-label);
              color: var(--text);
              font-weight: 700;
              overflow-wrap: break-word;
            }
""".trimIndent()

private fun originalTableCss(): String = """
            .table-scroll {
              width: 100%;
              overflow-x: auto;
              margin: 1rem 0;
              -webkit-overflow-scrolling: touch;
            }
            .table-scroll > table {
              margin: 0;
            }
            table {
              width: max-content;
              min-width: 100%;
              border-collapse: collapse;
              table-layout: auto;
              font-size: 0.9375rem;
              line-height: 1.55;
            }
            th, td {
              border: 1px solid var(--reader-border);
              padding: 0.5em 1em;
              text-align: left;
              vertical-align: top;
              white-space: normal;
              word-break: normal;
              overflow-wrap: break-word;
              hyphens: none;
            }
            th {
              background: var(--reader-soft);
              font-weight: 600;
              white-space: nowrap;
            }
            tr:nth-child(2n) {
              background: var(--reader-soft);
            }
""".trimIndent()

private val ReaderTypographyCss = """
            :root {
              color-scheme: light dark;
              --reader-bg: #ffffff;
              --reader-text: #24292f;
              --reader-muted: #57606a;
              --reader-subtle: #6e7781;
              --reader-border: #d8dee4;
              --reader-soft: #f6f8fa;
              --reader-link: #0969da;
              --reader-code-text: #24292f;
              --reader-code-bg: #f6f8fa;
              --reader-code-border: #d0d7de;
            }
            body.theme-paper {
              --reader-bg: #fbf7ec;
              --reader-text: #2f2a21;
              --reader-muted: #635c50;
              --reader-subtle: #7d7468;
              --reader-border: #ded3bd;
              --reader-soft: #f3ead8;
              --reader-code-bg: #f1e6d1;
              --reader-code-border: #dfd0b7;
            }
            body.theme-dark {
              --reader-bg: #0d1117;
              --reader-text: #e6edf3;
              --reader-muted: #b1bac4;
              --reader-subtle: #8b949e;
              --reader-border: #30363d;
              --reader-soft: #161b22;
              --reader-link: #58a6ff;
              --reader-code-text: #e6edf3;
              --reader-code-bg: #161b22;
              --reader-code-border: #30363d;
            }
            @media (prefers-color-scheme: dark) {
              body.theme-system {
                --reader-bg: #0d1117;
                --reader-text: #e6edf3;
                --reader-muted: #b1bac4;
                --reader-subtle: #8b949e;
                --reader-border: #30363d;
                --reader-soft: #161b22;
                --reader-link: #58a6ff;
                --reader-code-text: #e6edf3;
                --reader-code-bg: #161b22;
                --reader-code-border: #30363d;
              }
            }
            html {
              font-size: 16px;
              -webkit-text-size-adjust: 100%;
            }
            * {
              box-sizing: border-box;
            }
            body {
              margin: 0;
              background: var(--reader-bg);
              color: var(--reader-text);
              font-family: -apple-system, "PingFang SC", "Source Han Sans SC", "Noto Sans CJK SC",
                "Segoe UI", "Microsoft YaHei", sans-serif;
              font-size: 1rem;
              line-height: 1.7;
              overflow-wrap: break-word;
              overflow-x: hidden;
            }
            body.reader-serif {
              font-family: "New York", "Songti SC", "Noto Serif CJK SC", Georgia, serif;
            }
            main {
              width: min(100%, 48rem);
              margin: 0 auto;
              padding: 2rem 1rem max(7rem, env(safe-area-inset-bottom));
              zoom: var(--reader-zoom, 1);
            }
            h1, h2, h3, h4, h5, h6 {
              color: var(--reader-text);
              font-weight: 700;
              line-height: 1.25;
              letter-spacing: 0;
            }
            h1 {
              margin: 0 0 0.8em;
              padding-bottom: 0.3em;
              border-bottom: 1px solid var(--reader-border);
              font-size: 2rem;
            }
            h2 {
              margin: 1.5em 0 0.6em;
              padding-bottom: 0.3em;
              border-bottom: 1px solid var(--reader-border);
              font-size: 1.5rem;
            }
            h3 {
              margin: 1.5em 0 0.55em;
              font-size: 1.25rem;
            }
            h4 {
              margin: 1.35em 0 0.5em;
              font-size: 1rem;
            }
            h5 {
              margin: 1.25em 0 0.45em;
              font-size: 0.875rem;
            }
            h6 {
              margin: 1.25em 0 0.45em;
              color: var(--reader-subtle);
              font-size: 0.85rem;
            }
            p {
              margin: 1em 0;
            }
            a {
              color: var(--reader-link);
              text-decoration: none;
            }
            a:hover {
              text-decoration: underline;
            }
            strong {
              font-weight: 600;
            }
            em {
              font-style: italic;
            }
            blockquote {
              margin: 1em 0;
              padding: 0 1em;
              color: var(--reader-muted);
              border-left: 0.25em solid #d0d7de;
            }
            blockquote > :first-child {
              margin-top: 0;
            }
            blockquote > :last-child {
              margin-bottom: 0;
            }
            ul, ol {
              margin: 1em 0;
              padding-left: 2em;
            }
            li {
              margin: 0.25em 0;
            }
            li > p {
              margin: 0.5em 0;
            }
            code, pre {
              font-family: "SF Mono", "JetBrains Mono", Consolas, "Source Han Mono SC", monospace;
            }
            code {
              padding: 0.2em 0.4em;
              border-radius: 0.375rem;
              background: var(--reader-code-bg);
              color: var(--reader-code-text);
              font-size: 0.875em;
            }
            pre {
              margin: 1em 0;
              padding: 1em;
              overflow-x: auto;
              border: 1px solid var(--reader-code-border);
              border-radius: 0.375rem;
              background: #f6f8fa;
              color: var(--reader-code-text);
              font-size: 0.875rem;
              line-height: 1.45;
              -webkit-overflow-scrolling: touch;
            }
            body.theme-dark pre,
            body.theme-system pre {
              background: var(--reader-code-bg);
            }
            pre code {
              padding: 0;
              border-radius: 0;
              background: transparent;
              color: inherit;
              font-size: inherit;
              line-height: inherit;
            }
            hr {
              height: 0.25em;
              margin: 1.5em 0;
              border: 0;
              background: var(--reader-border);
            }
            img {
              max-width: 100%;
              height: auto;
              margin: 1em 0;
              border-radius: 0.375rem;
            }
            ins {
              text-decoration-thickness: 0.08em;
              text-underline-offset: 0.18em;
            }
            .heading-anchor {
              color: var(--reader-subtle);
              margin-right: 0.35em;
              text-decoration: none;
            }
            .footnotes {
              margin-top: 2rem;
              padding-top: 1rem;
              border-top: 1px solid var(--reader-border);
              color: var(--reader-muted);
              font-size: 0.875rem;
            }
            .footnote-ref, .footnote-backref {
              font-size: 0.78em;
            }
            .markdown-alert {
              margin: 1em 0;
              padding: 0.75em 1em;
              border-left: 0.25em solid var(--reader-link);
              border-radius: 0.375rem;
              background: var(--reader-soft);
            }
            .markdown-alert-title {
              margin: 0 0 0.35em;
              font-weight: 700;
            }
            .markdown-alert > :last-child {
              margin-bottom: 0;
            }
            .contains-task-list {
              padding-left: 0;
              list-style: none;
            }
            .task-list-item {
              list-style: none;
              margin-left: 0;
            }
            .task-list-item::marker {
              content: "";
            }
            input[type="checkbox"] {
              margin: 0 0.45em 0 0;
              transform: translateY(0.08em);
              accent-color: var(--reader-link);
            }
            @media (min-width: 45rem) {
              main {
                padding-left: 2rem;
                padding-right: 2rem;
              }
            }
""".trimIndent()

internal fun buildReaderHtml(
    body: String,
    fontScale: Float,
    useSerif: Boolean,
    readerTheme: String,
    layoutMode: ReaderLayoutMode,
    typographyStyle: ReaderTypographyStyle,
    allowRemoteImages: Boolean
): String {
    val initialZoom = fontScale.coerceIn(0.3f, 2.0f)
    val initialZoomCss = String.format(Locale.US, "%.3f", initialZoom)
    val enhancedBody = prepareMarkdownEnhancements(body, allowRemoteImages)
    val preparedBody = if (layoutMode == ReaderLayoutMode.Mobile) {
        annotateTablesForMobile(enhancedBody)
    } else {
        wrapTablesForScroll(enhancedBody)
    }
    val tableCss = if (layoutMode == ReaderLayoutMode.Mobile) {
        mobileTableCss()
    } else {
        originalTableCss()
    }
    val themeClass = when (readerTheme) {
        "paper" -> "theme-paper"
        "dark" -> "theme-dark"
        else -> "theme-system"
    }
    val typeClass = if (useSerif) "reader-serif" else "reader-sans"
    val typographyClass = when (typographyStyle) {
        ReaderTypographyStyle.Folio -> "typography-folio"
        ReaderTypographyStyle.GitHub -> "typography-github"
    }
    val mainClass = when (typographyStyle) {
        ReaderTypographyStyle.Folio -> ""
        ReaderTypographyStyle.GitHub -> " class=\"markdown-body\""
    }
    val githubTypographyCss = if (typographyStyle == ReaderTypographyStyle.GitHub) {
        """<link rel="stylesheet" href="vendor/github-markdown-css/github-markdown.css">"""
    } else {
        ""
    }
    val mathDollar = "$"
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <link rel="stylesheet" href="vendor/katex/katex.min.css">
          $githubTypographyCss
          <style>
            $ReaderTypographyCss
            $tableCss
            body.typography-github .markdown-body {
              background: transparent;
              color: var(--reader-text);
              font-family: inherit;
              font-size: 1rem;
              line-height: 1.65;
              min-height: auto;
            }
            body.typography-github.theme-paper .markdown-body {
              --color-canvas-default: transparent;
              --color-fg-default: var(--reader-text);
              --color-fg-muted: var(--reader-muted);
              --color-fg-subtle: var(--reader-subtle);
              --color-border-default: var(--reader-border);
              --color-border-muted: var(--reader-border);
              --color-accent-fg: var(--reader-link);
              --color-canvas-subtle: var(--reader-soft);
              --color-neutral-muted: rgba(99, 92, 80, 0.18);
            }
            body.typography-github.theme-dark .markdown-body,
            body.typography-github.theme-system .markdown-body {
              --color-canvas-default: transparent;
              --color-fg-default: var(--reader-text);
              --color-fg-muted: var(--reader-muted);
              --color-fg-subtle: var(--reader-subtle);
              --color-border-default: var(--reader-border);
              --color-border-muted: var(--reader-border);
              --color-accent-fg: var(--reader-link);
              --color-canvas-subtle: var(--reader-soft);
              --color-neutral-muted: rgba(110, 118, 129, 0.32);
            }
            .katex-display {
              overflow-x: auto;
              overflow-y: hidden;
              padding: .35rem 0;
            }
            .folio-math-display {
              display: block;
              overflow-x: auto;
              overflow-y: hidden;
              padding: .35rem 0;
              -webkit-overflow-scrolling: touch;
            }
            .mermaid {
              width: 100%;
              max-width: 100%;
              margin: 1.35em 0;
              overflow-x: auto;
              overflow-y: hidden;
              text-align: center;
              -webkit-overflow-scrolling: touch;
              overscroll-behavior-x: contain;
            }
            .mermaid svg {
              height: auto;
            }
            .mermaid:not(.mermaid-gantt) svg {
              max-width: 100%;
            }
            .mermaid-gantt {
              contain: inline-size;
              display: block;
              padding: .25rem 0 .75rem;
              text-align: left;
              touch-action: pan-x;
            }
            .mermaid-gantt svg {
              display: block;
              width: var(--folio-gantt-width, 100%) !important;
              min-width: var(--folio-gantt-width, 100%);
              max-width: none !important;
            }
            .remote-image-blocked {
              display: block;
              width: 100%;
              min-height: 4rem;
              padding: 1rem;
              border: 1px dashed var(--reader-border);
              border-radius: 0.375rem;
              background: var(--reader-soft);
            }
            .remote-image-blocked::after {
              content: "远程图片已阻止";
              color: var(--reader-muted);
              font-size: .875rem;
            }
            main::after {
              content: "";
              display: block;
              height: 1.5rem;
            }
          </style>
        </head>
        <body class="$themeClass $typeClass $typographyClass" style="--reader-zoom: $initialZoomCss;">
          <main$mainClass>$preparedBody</main>
          <script src="vendor/katex/katex.min.js"></script>
          <script src="vendor/katex/auto-render.min.js"></script>
          <script src="vendor/mermaid/mermaid.min.js"></script>
          <script>
            (() => {
              const minZoom = 0.3;
              const maxZoom = 2.0;
              let zoom = $initialZoomCss;
              let pinchStartDistance = 0;
              let pinchStartZoom = zoom;

              const clamp = value => Math.min(maxZoom, Math.max(minZoom, value));
              const distance = touches => {
                const dx = touches[0].clientX - touches[1].clientX;
                const dy = touches[0].clientY - touches[1].clientY;
                return Math.hypot(dx, dy);
              };
              const setZoom = next => {
                zoom = clamp(next);
                document.body.style.setProperty("--reader-zoom", zoom.toFixed(3));
              };

              window.FolioReaderZoom = {
                reset: () => setZoom(1),
                set: setZoom,
                get: () => zoom
              };

              document.addEventListener("touchstart", event => {
                if (event.touches.length === 2) {
                  pinchStartDistance = distance(event.touches);
                  pinchStartZoom = zoom;
                  event.preventDefault();
                }
              }, { passive: false });

              document.addEventListener("touchmove", event => {
                if (event.touches.length === 2 && pinchStartDistance > 0) {
                  setZoom(pinchStartZoom * distance(event.touches) / pinchStartDistance);
                  event.preventDefault();
                }
              }, { passive: false });

              document.addEventListener("touchend", event => {
                if (event.touches.length < 2) {
                  pinchStartDistance = 0;
                }
              }, { passive: false });

              const renderEnhancements = async () => {
                const main = document.querySelector("main");
                if (window.katex && main) {
                  main.querySelectorAll(".folio-math").forEach(element => {
                    const tex = element.getAttribute("data-tex") || "";
                    const displayMode = element.getAttribute("data-display") === "true";
                    try {
                      katex.render(tex, element, {
                        displayMode,
                        throwOnError: false,
                        strict: "ignore"
                      });
                    } catch (error) {
                      element.textContent = tex;
                    }
                  });
                }
                if (typeof renderMathInElement === "function" && main) {
                  renderMathInElement(main, {
                    delimiters: [
                      { left: "$mathDollar$mathDollar", right: "$mathDollar$mathDollar", display: true },
                      { left: "\\[", right: "\\]", display: true },
                      { left: "\\(", right: "\\)", display: false },
                      { left: "$mathDollar", right: "$mathDollar", display: false }
                    ],
                    throwOnError: false,
                    ignoredTags: ["script", "noscript", "style", "textarea", "pre", "code"]
                  });
                }
                if (window.mermaid) {
                  const mermaidBaseConfig = {
                    startOnLoad: false,
                    securityLevel: "strict",
                    theme: document.body.classList.contains("theme-dark") ? "dark" : "default",
                    flowchart: { useMaxWidth: true, htmlLabels: true },
                    gantt: { useMaxWidth: false }
                  };
                  mermaid.initialize(mermaidBaseConfig);
                  const diagrams = Array.from(document.querySelectorAll(".mermaid"));
                  const parseDate = value => {
                    const parts = value.split("-").map(Number);
                    if (parts.length !== 3 || parts.some(Number.isNaN)) return NaN;
                    return Date.UTC(parts[0], parts[1] - 1, parts[2]);
                  };
                  const collectGanttDates = source => {
                    const dateMatches = Array.from(source.matchAll(/\b\d{4}-\d{2}-\d{2}\b/g));
                    const dates = dateMatches
                      .map(match => parseDate(match[0]))
                      .filter(timestamp => Number.isFinite(timestamp));
                    for (const match of source.matchAll(/(\d{4}-\d{2}-\d{2})\s*,\s*(\d+)\s*([dhw])/gi)) {
                      const start = parseDate(match[1]);
                      const amount = Number.parseInt(match[2], 10);
                      const unit = match[3].toLowerCase();
                      const dayMultiplier = unit === "w" ? 7 : 1;
                      if (Number.isFinite(start) && Number.isFinite(amount)) {
                        dates.push(start + amount * dayMultiplier * 86400000);
                      }
                    }
                    return dates;
                  };
                  const calculateGanttLayoutWidth = (source, viewportWidth) => {
                    const dates = collectGanttDates(source);
                    const taskLabels = source.split("\n")
                      .map(line => line.trim())
                      .filter(line => line && !/^(---|gantt|title|dateFormat|axisFormat|tickInterval|section|excludes|includes|todayMarker|weekday)\b/i.test(line))
                      .map(line => line.split(":")[0].trim())
                      .filter(Boolean);
                    const longestLabel = taskLabels.reduce((longest, label) => Math.max(longest, label.length), 0);
                    const labelWidth = Math.min(360, Math.max(160, longestLabel * 13 + 48));
                    const minWidth = Math.max(viewportWidth, 720);
                    if (dates.length < 2) return minWidth;
                    const minDate = Math.min(...dates);
                    const maxDate = Math.max(...dates);
                    const daySpan = Math.max(1, Math.ceil((maxDate - minDate) / 86400000));
                    const pixelsPerDay = daySpan <= 10 ? 140 : daySpan <= 45 ? 72 : 38;
                    return Math.ceil(Math.max(minWidth, labelWidth + daySpan * pixelsPerDay + 128));
                  };
                  const prepareMermaidSvg = (element, layoutWidth) => {
                    const svg = element.querySelector("svg");
                    if (!svg) return;
                    const type = element.getAttribute("data-diagram-type") || "";
                    if (type !== "gantt") {
                      svg.style.maxWidth = "100%";
                      svg.style.height = "auto";
                      return;
                    }
                    const viewBoxWidth = svg.viewBox && svg.viewBox.baseVal ? svg.viewBox.baseVal.width : 0;
                    const viewBoxHeight = svg.viewBox && svg.viewBox.baseVal ? svg.viewBox.baseVal.height : 0;
                    const attrWidth = Number.parseFloat((svg.getAttribute("width") || "").replace("px", ""));
                    const readableWidth = Math.max(viewBoxWidth || 0, attrWidth || 0, layoutWidth || 0);
                    const widthPx = Math.ceil(readableWidth);
                    element.style.setProperty("--folio-gantt-width", `${'$'}{widthPx}px`);
                    svg.setAttribute("width", `${'$'}{widthPx}`);
                    svg.removeAttribute("style");
                    svg.style.maxWidth = "none";
                    svg.style.minWidth = `${'$'}{widthPx}px`;
                    svg.style.width = `${'$'}{widthPx}px`;
                    svg.style.height = "auto";
                    if (viewBoxWidth > 0 && viewBoxHeight > 0) {
                      svg.style.height = `${'$'}{Math.ceil(widthPx * viewBoxHeight / viewBoxWidth)}px`;
                    }
                    svg.setAttribute("preserveAspectRatio", "xMinYMin meet");
                    element.scrollLeft = 0;
                  };
                  for (const [index, element] of diagrams.entries()) {
                    const source = element.getAttribute("data-diagram") || element.textContent || "";
                    try {
                      const type = element.getAttribute("data-diagram-type") || "";
                      const viewportWidth = element.clientWidth || window.innerWidth || 360;
                      const layoutWidth = type === "gantt" ? calculateGanttLayoutWidth(source, viewportWidth) : 0;
                      mermaid.initialize(type === "gantt"
                        ? { ...mermaidBaseConfig, gantt: { ...mermaidBaseConfig.gantt, useWidth: layoutWidth } }
                        : mermaidBaseConfig
                      );
                      const result = await mermaid.render(`folio-mermaid-${'$'}{index}`, source);
                      element.innerHTML = result.svg;
                      prepareMermaidSvg(element, layoutWidth);
                      if (typeof result.bindFunctions === "function") {
                        result.bindFunctions(element);
                      }
                    } catch (error) {
                      element.textContent = source;
                      element.classList.add("mermaid-error");
                    }
                  }
                }
              };

              if (document.readyState === "loading") {
                document.addEventListener("DOMContentLoaded", () => { renderEnhancements(); }, { once: true });
              } else {
                renderEnhancements();
              }
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}
