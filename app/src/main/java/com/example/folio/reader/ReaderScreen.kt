package com.example.folio

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader

@Composable
internal fun DocumentCanvas(
    document: DocumentState,
    readerTheme: String,
    readerState: ReaderUiState,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onAllowRemoteImages: () -> Unit
) {
    val context = LocalContext.current
    var pendingExternalUri by remember { mutableStateOf<Uri?>(null) }
    var showRemoteImageWarning by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(readerBackground(readerTheme))
    ) {
        when (readerState) {
            ReaderUiState.Idle -> ReaderStatusMessage(stringResource(R.string.reader_ready, document.title))
            is ReaderUiState.Loading -> ReaderStatusMessage(readerState.message)
            is ReaderUiState.Error -> ReaderStatusMessage(readerState.message)
            is ReaderUiState.Loaded -> {
                HtmlPreview(
                    html = readerState.html,
                    readerTheme = readerTheme,
                    allowRemoteImages = readerState.allowRemoteImages,
                    onExternalUrl = { pendingExternalUri = it }
                )
                ReaderPagingBar(
                    pageIndex = readerState.pageIndex,
                    pageCount = readerState.pageCount,
                    largePage = readerState.largePage,
                    allowRemoteImages = readerState.allowRemoteImages,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    onAllowRemoteImages = { showRemoteImageWarning = true },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
    if (showRemoteImageWarning) {
        AlertDialog(
            onDismissRequest = { showRemoteImageWarning = false },
            title = { Text("加载远程图片") },
            text = { Text("远程图片可能向外部站点暴露你的网络信息和阅读行为。确认后仅对当前阅读会话生效。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoteImageWarning = false
                        onAllowRemoteImages()
                    }
                ) {
                    Text(stringResource(R.string.open))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoteImageWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    pendingExternalUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingExternalUri = null },
            title = { Text(stringResource(R.string.external_link_title)) },
            text = { Text(externalUrlPrompt(uri)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingExternalUri = null
                        openExternalReaderUrl(context, uri)
                    }
                ) {
                    Text(stringResource(R.string.open))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingExternalUri = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun HtmlPreview(
    html: String,
    readerTheme: String,
    allowRemoteImages: Boolean,
    onExternalUrl: (Uri) -> Unit
) {
    val webBackground = readerBackground(readerTheme).toArgb()
    val webContext = LocalContext.current
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(webContext))
            .build()
    }

    DisposableEffect(Unit) {
        onDispose {
            activeWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            activeWebView = null
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            ResettableZoomWebView(context).apply {
                activeWebView = this
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.javaScriptEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.textZoom = 100
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = true
                settings.loadsImagesAutomatically = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.blockNetworkImage = !allowRemoteImages
                settings.domStorageEnabled = false
                settings.setSupportZoom(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        return request?.url?.let(assetLoader::shouldInterceptRequest)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return handleReaderUrl(request?.url, onExternalUrl)
                    }

                    @Deprecated("Deprecated in Android API 24")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return handleReaderUrl(url?.toUri(), onExternalUrl)
                    }
                }
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isNestedScrollingEnabled = false
                setBackgroundColor(webBackground)
            }
        },
        update = { webView ->
            val nextState = WebViewRenderState(html, webBackground, ReaderLayoutMode.Original, ReaderTypographyStyle.Folio, allowRemoteImages)
            if (webView.tag != nextState) {
                webView.settings.textZoom = 100
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = false
                webView.settings.loadWithOverviewMode = false
                webView.settings.useWideViewPort = true
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = false
                webView.settings.allowFileAccessFromFileURLs = false
                webView.settings.allowUniversalAccessFromFileURLs = false
                webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webView.settings.blockNetworkImage = !allowRemoteImages
                webView.settings.setSupportZoom(false)
                webView.settings.builtInZoomControls = false
                webView.settings.displayZoomControls = false
                webView.setBackgroundColor(webBackground)
                webView.loadDataWithBaseURL("https://appassets.androidplatform.net/assets/", html, "text/html", "UTF-8", null)
                webView.tag = nextState
            }
        }
    )
}

@Composable
private fun ReaderStatusMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = IosColors.OnSurfaceVariant,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ReaderPagingBar(
    pageIndex: Int,
    pageCount: Int,
    largePage: Boolean,
    allowRemoteImages: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onAllowRemoteImages: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(IosColors.Bar.copy(alpha = 0.96f))
            .border(BorderStroke(0.6.dp, IosColors.Separator.copy(alpha = 0.55f)))
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (largePage) {
            Text(
                text = stringResource(R.string.large_page_warning),
                color = IosColors.Outline,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReaderPageButton(stringResource(R.string.previous_page), enabled = pageIndex > 0, onClick = onPreviousPage)
            Text(
                text = "${pageIndex + 1} / $pageCount",
                color = IosColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            ReaderPageButton(stringResource(R.string.next_page), enabled = pageIndex < pageCount - 1, onClick = onNextPage)
        }
        if (!allowRemoteImages) {
            val loadRemoteImagesText = stringResource(R.string.load_remote_images)
            Spacer(Modifier.height(6.dp))
            Text(
                text = loadRemoteImagesText,
                color = IosColors.Blue,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = loadRemoteImagesText }
                    .clickable(role = Role.Button, onClick = onAllowRemoteImages)
            )
        }
    }
}

@Composable
private fun ReaderPageButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (enabled) IosColors.Blue else IosColors.Placeholder,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .width(72.dp)
            .semantics { contentDescription = text }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        textAlign = TextAlign.Center
    )
}
