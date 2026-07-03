package com.example.folio

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MaxMarkdownFileBytes = 500L * 1024 * 1024
internal const val TargetMarkdownPageBytes = 256 * 1024
internal const val MaxMarkdownPageBytes = 512 * 1024

internal data class DocumentState(
    val title: String,
    val kind: DocumentKind,
    val sourceUri: String,
    val sizeBytes: Long? = null
)

internal data class ReaderRenderOptions(
    val fontScale: Float,
    val useSerif: Boolean,
    val readerTheme: String,
    val markdownEnhanced: Boolean,
    val layoutMode: ReaderLayoutMode,
    val typographyStyle: ReaderTypographyStyle,
    val allowRemoteImages: Boolean
)

internal sealed interface ReaderUiState {
    data object Idle : ReaderUiState
    data class Loading(val title: String, val message: String) : ReaderUiState
    data class Loaded(
        val document: DocumentState,
        val pageIndex: Int,
        val pageCount: Int,
        val html: String,
        val largePage: Boolean,
        val allowRemoteImages: Boolean
    ) : ReaderUiState
    data class Error(val title: String, val message: String) : ReaderUiState
}

internal data class MarkdownPage(
    val start: Long,
    val end: Long,
    val largePage: Boolean
)

internal data class MarkdownPageIndex(
    val pages: List<MarkdownPage>,
    val totalBytes: Long
)

internal class DocumentRepository(private val context: Context) {
    fun loadMetadata(uri: Uri): Result<DocumentState> = runCatching {
        require(isSupportedDocumentUri(uri)) { "不支持的文件来源" }
        val title = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "文档"
        require(isSupportedDocumentName(title)) { "仅支持 Markdown 文件" }
        val size = documentSize(uri)
        size?.let { require(it <= MaxMarkdownFileBytes) { "Markdown 文件过大，最大支持 500MB" } }
        DocumentState(title = title, kind = DocumentKind.Markdown, sourceUri = uri.toString(), sizeBytes = size)
    }

    suspend fun index(document: DocumentState): MarkdownPageIndex = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(document.sourceUri.toUri()).use { input ->
            requireNotNull(input) { "无法打开文件" }
            MarkdownPageIndexer.index(input)
        }
    }

    suspend fun openSession(document: DocumentState): PagedDocumentSession = withContext(Dispatchers.IO) {
        val cacheFile = createCacheFile()
        runCatching {
            context.contentResolver.openInputStream(document.sourceUri.toUri()).use { input ->
                requireNotNull(input) { "无法打开文件" }
                cacheFile.outputStream().buffered().use { output ->
                    val index = MarkdownPageIndexer.index(input, cacheOutput = output)
                    PagedDocumentSession(document, index, cacheFile)
                }
            }
        }.getOrElse { error ->
            cacheFile.delete()
            throw error
        }
    }

    suspend fun readPage(document: DocumentState, page: MarkdownPage): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(document.sourceUri.toUri()).use { input ->
            requireNotNull(input) { "无法打开文件" }
            input.skipFully(page.start)
            val length = (page.end - page.start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read == -1) break
                offset += read
            }
            bytes.decodeToString(endIndex = offset)
        }
    }

    suspend fun readPage(session: PagedDocumentSession, page: MarkdownPage): String = withContext(Dispatchers.IO) {
        RandomAccessFile(session.cacheFile, "r").use { file ->
            file.seek(page.start)
            val length = (page.end - page.start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val bytes = ByteArray(length)
            file.readFully(bytes)
            bytes.decodeToString()
        }
    }

    private fun createCacheFile(): File {
        val directory = File(context.cacheDir, "folio-reader-pages").apply { mkdirs() }
        return File.createTempFile("folio-page-", ".md", directory)
    }

    private fun displayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }

    private fun documentSize(uri: Uri): Long? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            } else {
                null
            }
        }
    }
}

internal fun isSupportedDocumentUri(uri: Uri): Boolean = isSupportedDocumentScheme(uri.scheme)

internal fun isSupportedDocumentScheme(scheme: String?): Boolean = scheme in setOf("content", "file")

internal object MarkdownPageIndexer {
    fun index(
        input: InputStream,
        cacheOutput: OutputStream? = null,
        maxFileBytes: Long = MaxMarkdownFileBytes,
        maxLineBytes: Int = MaxMarkdownPageBytes
    ): MarkdownPageIndex {
        val buffered = BufferedInputStream(input)
        val pages = mutableListOf<MarkdownPage>()
        var total = 0L
        var pageStart = 0L
        var lastSafeBreak = 0L
        var inFence = false
        var fenceMarker = ""
        var inMathBlock = false

        while (true) {
            val line = buffered.readLineBytes(cacheOutput, maxLineBytes) ?: break
            total += line.size
            require(total <= maxFileBytes) { "Markdown 文件过大，最大支持 500MB" }

            val text = line.decodeToString().trimEnd('\r', '\n')
            val trimmed = text.trimStart()
            val fence = Regex("""^(`{3,}|~{3,})""").find(trimmed)?.value
            if (fence != null) {
                if (!inFence) {
                    inFence = true
                    fenceMarker = fence
                } else if (trimmed.startsWith(fenceMarker)) {
                    inFence = false
                    fenceMarker = ""
                    lastSafeBreak = total
                }
            }
            if (!inFence && trimmed == "$$") {
                inMathBlock = !inMathBlock
                if (!inMathBlock) lastSafeBreak = total
            }

            val safeLineBreak = !inFence && !inMathBlock && (trimmed.isBlank() || trimmed.startsWith("#"))
            if (safeLineBreak) lastSafeBreak = total

            val currentSize = total - pageStart
            if (currentSize >= TargetMarkdownPageBytes && lastSafeBreak > pageStart) {
                pages += MarkdownPage(pageStart, lastSafeBreak, largePage = lastSafeBreak - pageStart > MaxMarkdownPageBytes)
                pageStart = lastSafeBreak
            } else if (currentSize >= MaxMarkdownPageBytes && !inFence && !inMathBlock) {
                pages += MarkdownPage(pageStart, total, largePage = true)
                pageStart = total
                lastSafeBreak = total
            }
        }
        cacheOutput?.flush()

        if (total > pageStart || pages.isEmpty()) {
            pages += MarkdownPage(pageStart, total, largePage = total - pageStart > MaxMarkdownPageBytes)
        }
        return MarkdownPageIndex(pages = pages, totalBytes = total)
    }
}

internal class PagedDocumentSession(
    val document: DocumentState,
    val index: MarkdownPageIndex,
    val cacheFile: File
) {
    private val htmlCache = linkedMapOf<Int, ReaderPage>()

    fun cachedPage(pageIndex: Int): ReaderPage? = htmlCache[pageIndex]

    fun putPage(page: ReaderPage) {
        htmlCache[page.pageIndex] = page
        val keep = setOf(page.pageIndex - 1, page.pageIndex, page.pageIndex + 1)
        htmlCache.keys.filter { it !in keep }.forEach { htmlCache.remove(it) }
    }

    fun clear() {
        htmlCache.clear()
        cacheFile.delete()
    }
}

internal data class ReaderPage(
    val pageIndex: Int,
    val html: String,
    val largePage: Boolean
)

internal class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DocumentRepository(application)
    private var session: PagedDocumentSession? = null
    private var renderOptions: ReaderRenderOptions? = null
    private var renderJob: Job? = null
    private val prefetchJobs = mutableSetOf<Job>()

    var state: ReaderUiState by mutableStateOf(ReaderUiState.Idle)
        private set

    fun openDocument(document: DocumentState, options: ReaderRenderOptions) {
        renderJob?.cancel()
        cancelPrefetch()
        session?.clear()
        session = null
        renderOptions = options
        state = ReaderUiState.Loading(document.title, "正在建立分页索引...")
        renderJob = viewModelScope.launch {
            runCatching {
                session = repository.openSession(document)
                renderJob = null
                renderPage(0)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = ReaderUiState.Error(document.title, error.message ?: "无法打开文档")
            }
        }
    }

    fun updateRenderOptions(options: ReaderRenderOptions) {
        renderOptions = options
        val loaded = state as? ReaderUiState.Loaded ?: return
        renderPage(loaded.pageIndex, force = true)
    }

    fun nextPage() {
        val loaded = state as? ReaderUiState.Loaded ?: return
        renderPage((loaded.pageIndex + 1).coerceAtMost(loaded.pageCount - 1))
    }

    fun previousPage() {
        val loaded = state as? ReaderUiState.Loaded ?: return
        renderPage((loaded.pageIndex - 1).coerceAtLeast(0))
    }

    fun setRemoteImagesAllowed(allowed: Boolean) {
        val options = renderOptions ?: return
        updateRenderOptions(options.copy(allowRemoteImages = allowed))
    }

    private fun renderPage(pageIndex: Int, force: Boolean = false) {
        val currentSession = session ?: return
        val options = renderOptions ?: return
        val cached = currentSession.cachedPage(pageIndex)
        if (!force && cached != null) {
            state = cached.toLoaded(currentSession, options)
            return
        }

        renderJob?.cancel()
        if (force) cancelPrefetch()
        state = ReaderUiState.Loading(currentSession.document.title, "正在渲染第 ${pageIndex + 1} 页...")
        renderJob = viewModelScope.launch {
            runCatching {
                val markdownPage = currentSession.index.pages[pageIndex]
                val markdown = repository.readPage(currentSession, markdownPage)
                val html = withContext(Dispatchers.Default) {
                    buildReaderHtml(
                        body = MarkdownRenderer.toHtml(markdown, enhanced = options.markdownEnhanced),
                        fontScale = options.fontScale,
                        useSerif = options.useSerif,
                        readerTheme = options.readerTheme,
                        layoutMode = options.layoutMode,
                        typographyStyle = options.typographyStyle,
                        allowRemoteImages = options.allowRemoteImages
                    )
                }
                ReaderPage(pageIndex, html, markdownPage.largePage)
            }.onSuccess { page ->
                currentSession.putPage(page)
                state = page.toLoaded(currentSession, options)
                prefetchAround(page.pageIndex, options)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = ReaderUiState.Error(currentSession.document.title, error.message ?: "页面渲染失败")
            }
        }
    }

    private fun prefetchAround(pageIndex: Int, options: ReaderRenderOptions) {
        val currentSession = session ?: return
        listOf(pageIndex - 1, pageIndex + 1)
            .filter { it in currentSession.index.pages.indices && currentSession.cachedPage(it) == null }
            .forEach { index ->
                val job = viewModelScope.launch {
                    runCatching {
                        val markdownPage = currentSession.index.pages[index]
                        val markdown = repository.readPage(currentSession, markdownPage)
                        val html = withContext(Dispatchers.Default) {
                            buildReaderHtml(
                                body = MarkdownRenderer.toHtml(markdown, enhanced = options.markdownEnhanced),
                                fontScale = options.fontScale,
                                useSerif = options.useSerif,
                                readerTheme = options.readerTheme,
                                layoutMode = options.layoutMode,
                                typographyStyle = options.typographyStyle,
                                allowRemoteImages = options.allowRemoteImages
                            )
                        }
                        currentSession.putPage(ReaderPage(index, html, markdownPage.largePage))
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                    }
                }
                prefetchJobs += job
                job.invokeOnCompletion { prefetchJobs -= job }
            }
    }

    private fun cancelPrefetch() {
        prefetchJobs.forEach { it.cancel() }
        prefetchJobs.clear()
    }

    override fun onCleared() {
        renderJob?.cancel()
        cancelPrefetch()
        session?.clear()
        session = null
        super.onCleared()
    }

    private fun ReaderPage.toLoaded(session: PagedDocumentSession, options: ReaderRenderOptions): ReaderUiState.Loaded {
        return ReaderUiState.Loaded(
            document = session.document,
            pageIndex = pageIndex,
            pageCount = session.index.pages.size,
            html = html,
            largePage = largePage,
            allowRemoteImages = options.allowRemoteImages
        )
    }
}

private fun BufferedInputStream.readLineBytes(): ByteArray? {
    return readLineBytes(cacheOutput = null, maxLineBytes = MaxMarkdownPageBytes)
}

private fun BufferedInputStream.readLineBytes(cacheOutput: OutputStream?, maxLineBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    while (true) {
        val next = read()
        if (next == -1) break
        output.write(next)
        cacheOutput?.write(next)
        require(output.size() <= maxLineBytes) { "单行内容过大，请拆分后再打开" }
        if (next == '\n'.code) break
    }
    return if (output.size() == 0) null else output.toByteArray()
}

private fun InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0) {
            if (read() == -1) return
            remaining -= 1
        } else {
            remaining -= skipped
        }
    }
}
