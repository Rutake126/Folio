package com.example.folio

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.folio.ui.theme.FolioTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class DocumentKind {
    Markdown
}

internal enum class ReaderLayoutMode {
    Mobile,
    Original
}

internal enum class ReaderTypographyStyle {
    Folio,
    GitHub
}

private val SupportedDocumentMimeTypes = arrayOf(
    "text/markdown",
    "text/x-markdown"
)

private const val ReaderPrefs = "folio_reader_preferences"
private const val ReaderFontScaleKey = "font_scale"
private const val ReaderUseSerifKey = "use_serif"
private const val ReaderThemeKey = "theme"
private const val ReaderMarkdownEnhancedKey = "markdown_enhanced"
private const val ReaderLayoutModeKey = "layout_mode"
private const val ReaderTypographyStyleKey = "typography_style"
private const val FolioVisualSystemName = "Folio iOS-like"

internal val android.content.Context.readerPreferencesDataStore by preferencesDataStore(name = ReaderPrefs)

private enum class AppTab {
    Library,
    Settings
}

private enum class LibraryView {
    Recent,
    All
}

private data class ScreenState(
    val tab: AppTab,
    val document: DocumentState?
) {
    val reading: Boolean
        get() = document != null
}

internal data class WebViewRenderState(
    val html: String,
    val backgroundColor: Int,
    val layoutMode: ReaderLayoutMode,
    val typographyStyle: ReaderTypographyStyle,
    val allowRemoteImages: Boolean
)

internal data class ReaderPreferences(
    val fontScale: Float,
    val useSerif: Boolean,
    val theme: String,
    val markdownEnhanced: Boolean,
    val layoutMode: ReaderLayoutMode,
    val typographyStyle: ReaderTypographyStyle
)

private val DefaultReaderPreferences = ReaderPreferences(
    fontScale = 1.0f,
    useSerif = true,
    theme = "light",
    markdownEnhanced = true,
    layoutMode = ReaderLayoutMode.Original,
    typographyStyle = ReaderTypographyStyle.Folio
)

internal class FolioAppViewModel(application: Application) : AndroidViewModel(application) {
    val importedDocuments = mutableStateListOf<LibraryDocument>()

    var preferences by mutableStateOf(DefaultReaderPreferences)
        private set

    var preferencesLoaded by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var savePreferencesJob: Job? = null

    init {
        viewModelScope.launch {
            val app = getApplication<Application>()
            importedDocuments.clear()
            importedDocuments.addAll(loadUserDocuments(app))
            preferences = loadReaderPreferences(app)
            preferencesLoaded = true
        }
    }

    fun clearError() {
        errorMessage = null
    }

    fun updatePreferences(next: ReaderPreferences) {
        preferences = next
        if (!preferencesLoaded) return
        savePreferencesJob?.cancel()
        savePreferencesJob = viewModelScope.launch {
            delay(250)
            saveReaderPreferences(getApplication(), next)
        }
    }

    fun resetReaderPreferences() {
        updatePreferences(DefaultReaderPreferences)
    }

    fun openLibraryDocument(
        item: LibraryDocument,
        loadDocument: suspend (Uri) -> Result<DocumentState>,
        onOpened: (DocumentState) -> Unit
    ) {
        val sourceUri = item.sourceUri ?: return
        viewModelScope.launch {
            loadDocument(sourceUri.toUri())
                .onSuccess { loaded ->
                    onOpened(loaded)
                    rememberImportedDocument(getApplication(), importedDocuments, loaded)
                }
                .onFailure(::handleDocumentError)
        }
    }

    fun importDocument(
        uri: Uri,
        loadDocument: suspend (Uri) -> Result<DocumentState>,
        onImported: () -> Unit
    ) {
        viewModelScope.launch {
            val permissionResult = runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (permissionResult.isFailure) {
                errorMessage = "无法保存文档访问权限，请重新选择文件"
                return@launch
            }
            loadDocument(uri)
                .onSuccess { loaded ->
                    rememberImportedDocument(getApplication(), importedDocuments, loaded)
                    onImported()
                }
                .onFailure(::handleDocumentError)
        }
    }

    fun openIncomingDocument(
        uri: Uri,
        loadDocument: suspend (Uri) -> Result<DocumentState>,
        onOpened: (DocumentState) -> Unit
    ) {
        viewModelScope.launch {
            loadDocument(uri)
                .onSuccess(onOpened)
                .onFailure(::handleDocumentError)
        }
    }

    fun deleteDocuments(sourceUris: Collection<String?>) {
        importedDocuments.removeAll { it.sourceUri in sourceUris }
        viewModelScope.launch {
            saveUserDocuments(getApplication(), importedDocuments)
        }
    }

    private fun handleDocumentError(error: Throwable) {
        if (error is CancellationException) throw error
        errorMessage = error.message ?: "无法打开文档"
    }
}

internal class ResettableZoomWebView(context: android.content.Context) : WebView(context) {
    private var doubleTapConsumed = false
    private val resetGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                doubleTapConsumed = true
                evaluateJavascript("window.FolioReaderZoom && window.FolioReaderZoom.reset();", null)
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        doubleTapConsumed = false
        resetGestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP && !doubleTapConsumed) {
            performClick()
        }
        return if (doubleTapConsumed) {
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

class MainActivity : ComponentActivity() {
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUri = intent?.data
        enableEdgeToEdge()
        setContent {
            FolioTheme(dynamicColor = false) {
                DocumentViewerApp(
                    incomingUri = incomingUri,
                    loadDocument = ::loadDocumentFromUri
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingUri = intent.data
    }

    private suspend fun loadDocumentFromUri(uri: Uri): Result<DocumentState> = withContext(Dispatchers.IO) {
        DocumentRepository(this@MainActivity).loadMetadata(uri)
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun DocumentViewerApp(
    incomingUri: Uri? = null,
    loadDocument: suspend (Uri) -> Result<DocumentState> = { Result.failure(IllegalStateException("无法打开文档")) }
) {
    val appViewModel: FolioAppViewModel = viewModel()
    val readerViewModel: ReaderViewModel = viewModel()
    var document by rememberSaveable(stateSaver = DocumentSaver) { mutableStateOf(null) }
    var lastReadingDocument by rememberSaveable(stateSaver = DocumentSaver) { mutableStateOf(null) }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Library) }
    var showReaderOptions by rememberSaveable { mutableStateOf(false) }
    val preferences = appViewModel.preferences
    val importedDocuments = appViewModel.importedDocuments
    val systemDark = isSystemInDarkTheme()
    val resolvedReaderTheme = resolveReaderTheme(preferences.theme, systemDark)
    val appDark = resolvedReaderTheme == "dark"
    val screenState = ScreenState(selectedTab, document)
    val activity = LocalActivity.current as? ComponentActivity
    val renderOptions = ReaderRenderOptions(
        fontScale = preferences.fontScale,
        useSerif = preferences.useSerif,
        readerTheme = resolvedReaderTheme,
        markdownEnhanced = preferences.markdownEnhanced,
        layoutMode = preferences.layoutMode,
        typographyStyle = preferences.typographyStyle,
        allowRemoteImages = (readerViewModel.state as? ReaderUiState.Loaded)?.allowRemoteImages == true
    )

    SideEffect {
        IosColors.useDark(appDark)
        val transparent = Color.Transparent.toArgb()
        activity?.enableEdgeToEdge(
            statusBarStyle = if (appDark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            },
            navigationBarStyle = if (appDark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            }
        )
    }

    fun goBack() {
        when {
            showReaderOptions -> showReaderOptions = false
            document != null -> {
                document = null
                selectedTab = AppTab.Library
            }
            selectedTab != AppTab.Library -> selectedTab = AppTab.Library
        }
    }

    fun resetReaderPreferences() {
        appViewModel.resetReaderPreferences()
    }

    fun openLibraryDocument(item: LibraryDocument) {
        appViewModel.openLibraryDocument(item, loadDocument) { loaded ->
            document = loaded
        }
    }

    fun importDocument(uri: Uri) {
        appViewModel.importDocument(uri, loadDocument) {
            selectedTab = AppTab.Library
            document = null
        }
    }

    LaunchedEffect(incomingUri) {
        val uri = incomingUri ?: return@LaunchedEffect
        appViewModel.openIncomingDocument(uri, loadDocument) { loaded ->
            document = loaded
        }
    }

    if (appViewModel.errorMessage != null) {
        AlertDialog(
            onDismissRequest = appViewModel::clearError,
            title = { Text("无法打开文档") },
            text = { Text(appViewModel.errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = appViewModel::clearError) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }

    LaunchedEffect(document?.sourceUri) {
        if (document != null) {
            lastReadingDocument = document
            readerViewModel.openDocument(requireNotNull(document), renderOptions)
        }
    }

    LaunchedEffect(preferences, resolvedReaderTheme) {
        readerViewModel.updateRenderOptions(renderOptions)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDocument(uri)
    }

    BackHandler(enabled = showReaderOptions || document != null || selectedTab != AppTab.Library) {
        goBack()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize(),
        color = IosColors.Background
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = document != null,
                    enter = slideInVertically(animationSpec = tween(220)) { -it / 3 } + fadeIn(animationSpec = tween(180)),
                    exit = slideOutVertically(animationSpec = tween(180)) { -it / 3 } + fadeOut(animationSpec = tween(140))
                ) {
                    ViewerTopBar(
                        document = document ?: lastReadingDocument,
                        onBack = ::goBack,
                        onFont = { showReaderOptions = true }
                    )
                }

                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = screenState,
                        transitionSpec = { screenTransition(initialState, targetState) },
                        label = "folio-screen"
                    ) { target ->
                        if (!target.reading) {
                            when (target.tab) {
                                AppTab.Library -> LibraryHome(
                                    importedDocuments = importedDocuments,
                                    onPick = { filePicker.launch(SupportedDocumentMimeTypes) },
                                    onSettings = { selectedTab = AppTab.Settings },
                                    onDeleteImported = appViewModel::deleteDocuments,
                                    onOpenDocument = ::openLibraryDocument
                                )

                                AppTab.Settings -> SettingsHome(
                                    readerTheme = preferences.theme,
                                    useSerif = preferences.useSerif,
                                    markdownEnhanced = preferences.markdownEnhanced,
                                    onThemeChange = { appViewModel.updatePreferences(preferences.copy(theme = it)) },
                                    onUseSerifChange = { appViewModel.updatePreferences(preferences.copy(useSerif = it)) },
                                    onMarkdownEnhancedChange = { appViewModel.updatePreferences(preferences.copy(markdownEnhanced = it)) },
                                    onResetReaderPreferences = ::resetReaderPreferences,
                                    onBack = ::goBack,
                                    onLibrary = { selectedTab = AppTab.Library }
                                )
                            }
                        } else {
                            DocumentCanvas(
                                document = requireNotNull(target.document),
                                readerTheme = resolvedReaderTheme,
                                readerState = readerViewModel.state,
                                onPreviousPage = readerViewModel::previousPage,
                                onNextPage = readerViewModel::nextPage,
                                onAllowRemoteImages = { readerViewModel.setRemoteImagesAllowed(true) }
                            )
                        }
                    }
                }
            }
            if (document != null || selectedTab != AppTab.Library) {
                LeftEdgeBackGesture(
                    onBack = ::goBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
        }
    }

    if (showReaderOptions && document != null) {
        ReaderOptionsSheet(
            fontScale = preferences.fontScale,
            useSerif = preferences.useSerif,
            readerTheme = resolvedReaderTheme,
            layoutMode = preferences.layoutMode,
            typographyStyle = preferences.typographyStyle,
            onFontScaleChange = { appViewModel.updatePreferences(preferences.copy(fontScale = it)) },
            onUseSerifChange = { appViewModel.updatePreferences(preferences.copy(useSerif = it)) },
            onThemeChange = { appViewModel.updatePreferences(preferences.copy(theme = it)) },
            onLayoutModeChange = { appViewModel.updatePreferences(preferences.copy(layoutMode = it)) },
            onTypographyStyleChange = { appViewModel.updatePreferences(preferences.copy(typographyStyle = it)) },
            onDismiss = { showReaderOptions = false }
        )
    }

}

private fun screenTransition(initial: ScreenState, target: ScreenState): ContentTransform {
    val forward = screenOrder(target) >= screenOrder(initial)
    val enterOffset: (Int) -> Int = { width -> if (forward) width else -width }
    val exitOffset: (Int) -> Int = { width -> if (forward) -width / 4 else width / 4 }
    return (slideInHorizontally(
        animationSpec = tween(260),
        initialOffsetX = enterOffset
    ) + fadeIn(animationSpec = tween(180))).togetherWith(
        slideOutHorizontally(
            animationSpec = tween(220),
            targetOffsetX = exitOffset
        ) + fadeOut(animationSpec = tween(160))
    )
}

private fun screenOrder(screen: ScreenState): Int {
    if (screen.reading) return 3
    return when (screen.tab) {
        AppTab.Library -> 0
        AppTab.Settings -> 1
    }
}

@Composable
private fun LeftEdgeBackGesture(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val triggerDistance = with(density) { 72.dp.toPx() }
    Box(
        modifier = modifier
            .width(26.dp)
            .fillMaxHeight()
            .pointerInput(onBack) {
                var dragDistance = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        dragDistance += dragAmount
                    },
                    onDragEnd = {
                        if (dragDistance > triggerDistance) {
                            onBack()
                        }
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f }
                )
            }
    )
}

@Composable
private fun ViewerTopBar(
    document: DocumentState?,
    onBack: () -> Unit,
    onFont: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(IosColors.Bar)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (document != null) {
                BackNavigationButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = document?.title.orEmpty(),
                    color = IosColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
            }

            if (document != null) {
                Text(
                    text = "Aa",
                    color = IosColors.Blue,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable(onClick = onFont)
                )
            }
        }
        HorizontalDivider(color = IosColors.Separator, thickness = 0.6.dp)
    }
}

@Composable
private fun LibraryHome(
    importedDocuments: List<LibraryDocument>,
    onPick: () -> Unit,
    onSettings: () -> Unit,
    onDeleteImported: (Collection<String?>) -> Unit,
    onOpenDocument: (LibraryDocument) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showImportMenu by rememberSaveable { mutableStateOf(false) }
    var libraryView by rememberSaveable { mutableStateOf(LibraryView.Recent) }
    val normalizedQuery = normalizeSearchQuery(query)
    val allDisplayDocuments = importedDocuments.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    val filteredAll = if (normalizedQuery.isBlank()) {
        allDisplayDocuments
    } else {
        allDisplayDocuments.filter { it.matchesLibraryQuery(normalizedQuery) }
    }
    val filteredRecent = if (normalizedQuery.isBlank()) {
        importedDocuments
    } else {
        importedDocuments.filter { it.matchesLibraryQuery(normalizedQuery) }
    }
    fun deleteDocument(item: LibraryDocument) {
        onDeleteImported(listOf(item.sourceUri))
    }

    Box(
        Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(WindowInsets.statusBars.asPaddingValues()),
            contentPadding = WindowInsets.navigationBars.asPaddingValues()
        ) {
            item {
                LibraryHeader(
                    query = query,
                    onQueryChange = { query = it },
                    onImport = { showImportMenu = true }
                )
            }
            item {
                LibraryViewTabs(
                    selected = libraryView,
                    onSelect = {
                        libraryView = it
                    }
                )
            }
            item {
                when (libraryView) {
                    LibraryView.Recent -> LibrarySection(
                        documents = filteredRecent,
                        onOpen = onOpenDocument,
                        onDelete = ::deleteDocument
                    )

                    LibraryView.All -> LibrarySection(
                        documents = filteredAll,
                        onOpen = onOpenDocument,
                        onDelete = ::deleteDocument
                    )
                }
            }
            item {
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "${importedDocuments.size} 份文档",
                    color = IosColors.Outline,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(118.dp))
            }
        }

        LibraryBottomBar(
            onLibrary = {},
            onSettings = onSettings,
            selectedTab = AppTab.Library,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (showImportMenu) {
            ImportMenuOverlay(
                onDismiss = { showImportMenu = false },
                onLocalImport = {
                    showImportMenu = false
                    onPick()
                }
            )
        }
    }
}

@Composable
private fun LibraryHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onImport: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var searchFocused by remember { mutableStateOf(false) }
    val showPlaceholder = query.isEmpty() && !searchFocused

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(IosColors.Background)
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(IosColors.SearchField)
                .clickable {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showPlaceholder) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search_slanted_left),
                    contentDescription = "搜索文档",
                    tint = IosColors.OnSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = IosColors.Text,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { }
                )
                if (showPlaceholder) {
                    Text(stringResource(R.string.search_documents), color = IosColors.OnSurfaceVariant, fontSize = 13.sp)
                }
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .semantics { contentDescription = "导入文档" }
                    .clickable(role = Role.Button) {
                        focusManager.clearFocus()
                        onImport()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    color = IosColors.Blue,
                    fontSize = 22.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ImportMenuOverlay(
    onDismiss: () -> Unit,
    onLocalImport: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 26.dp)
            .padding(top = 112.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(IosColors.Card)
                .clickable(onClick = {})
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.import_document),
                color = IosColors.Text,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.import_markdown_support),
                color = IosColors.Outline,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = IosColors.Separator.copy(alpha = 0.36f), thickness = 0.6.dp)
            ImportMenuRow(onClick = onLocalImport)
        }
    }
}

@Composable
private fun ImportMenuRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(46.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_file_search_import),
                contentDescription = "从本地导入",
                tint = IosColors.Text,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = stringResource(R.string.import_from_device),
            color = IosColors.Text,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun LibraryViewTabs(
    selected: LibraryView,
    onSelect: (LibraryView) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp, bottom = 18.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(IosColors.SearchField)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LibraryViewTab(
            text = stringResource(R.string.recent_documents),
            selected = selected == LibraryView.Recent,
            onClick = { onSelect(LibraryView.Recent) }
        )
        LibraryViewTab(
            text = stringResource(R.string.all_documents),
            selected = selected == LibraryView.All,
            onClick = { onSelect(LibraryView.All) }
        )
    }
}

@Composable
private fun RowScope.LibraryViewTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) IosColors.SelectedControl else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) IosColors.Text else IosColors.OnSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BackHeader(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(IosColors.Background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BackNavigationButton(onClick = onBack)
        }
        HorizontalDivider(color = IosColors.Separator.copy(alpha = 0.45f), thickness = 0.6.dp)
    }
}

@Composable
private fun BackNavigationButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "‹",
            color = IosColors.Blue,
            fontSize = 31.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.Light
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.back),
            color = IosColors.Blue,
            fontSize = 17.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SettingsHome(
    readerTheme: String,
    useSerif: Boolean,
    markdownEnhanced: Boolean,
    onThemeChange: (String) -> Unit,
    onUseSerifChange: (Boolean) -> Unit,
    onMarkdownEnhancedChange: (Boolean) -> Unit,
    onResetReaderPreferences: () -> Unit,
    onBack: () -> Unit,
    onLibrary: () -> Unit
) {
    val context = LocalContext.current
    val versionLabel = remember(context) { appVersionLabel(context) }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(bottom = 118.dp)
        ) {
            BackHeader(onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 34.dp)
            ) {
                SettingsSection("外观") {
                    SettingsThemeRow(readerTheme = readerTheme, onThemeChange = onThemeChange)
                    SettingsDivider()
                    SettingsValueRow(
                        label = "排版",
                        value = if (useSerif) "衬线字体" else "系统字体",
                        onClick = { onUseSerifChange(!useSerif) }
                    )
                }

                Spacer(Modifier.height(30.dp))
                SettingsSection("阅读") {
                    SettingsValueRow(
                        label = "Markdown",
                        value = if (markdownEnhanced) "完整渲染" else "基础语法",
                        onClick = { onMarkdownEnhancedChange(!markdownEnhanced) }
                    )
                }

                Spacer(Modifier.height(22.dp))
                SettingsSection("关于") {
                    SettingsInfoRow(label = "视觉体系", value = FolioVisualSystemName)
                    SettingsDivider()
                    SettingsInfoRow(label = "版本", value = versionLabel)
                }

                Spacer(Modifier.height(34.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(IosColors.Card)
                        .clickable(onClick = onResetReaderPreferences),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.reset_defaults), color = IosColors.Error, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        LibraryBottomBar(
            onLibrary = onLibrary,
            onSettings = {},
            selectedTab = AppTab.Settings,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            color = IosColors.OnSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 14.dp, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(1.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(IosColors.Card),
            content = content
        )
    }
}

@Composable
private fun SettingsThemeRow(readerTheme: String, onThemeChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("主题", color = IosColors.Text, fontSize = 16.sp, lineHeight = 20.sp)
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(IosColors.Control)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsSegment("浅色", "light", readerTheme, onThemeChange)
            SettingsSegment("纸张", "paper", readerTheme, onThemeChange)
            SettingsSegment("深色", "dark", readerTheme, onThemeChange)
            SettingsSegment("系统", "system", readerTheme, onThemeChange)
        }
    }
}

@Composable
private fun SettingsSegment(text: String, value: String, selected: String, onClick: (String) -> Unit) {
    Box(
        modifier = Modifier
            .width(58.dp)
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected == value) IosColors.SelectedControl else Color.Transparent)
            .clickable { onClick(value) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected == value) IosColors.Blue else IosColors.OnSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = if (selected == value) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SettingsValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = IosColors.Text, fontSize = 16.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        Text(value, color = IosColors.Outline, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.End)
        Spacer(Modifier.width(12.dp))
        Text("›", color = IosColors.Chevron, fontSize = 25.sp)
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String = "", onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = IosColors.Text, fontSize = 16.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, color = IosColors.Outline, fontSize = 15.sp, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = IosColors.Separator.copy(alpha = 0.36f),
        thickness = 0.6.dp,
        modifier = Modifier.padding(start = 16.dp)
    )
}

@Composable
private fun LibrarySection(
    documents: List<LibraryDocument>,
    onOpen: (LibraryDocument) -> Unit,
    onDelete: (LibraryDocument) -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        if (documents.isEmpty()) {
            LibraryEmptyState()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(BorderStroke(0.6.dp, IosColors.Separator.copy(alpha = 0.42f)), RoundedCornerShape(12.dp))
                    .background(IosColors.Card)
            ) {
                documents.forEachIndexed { index, item ->
                    LibraryRow(
                        item = item,
                        onClick = { onOpen(item) },
                        onDelete = { onDelete(item) }
                    )
                    if (index != documents.lastIndex) {
                        HorizontalDivider(
                            color = IosColors.Separator.copy(alpha = 0.34f),
                            thickness = 0.6.dp,
                            modifier = Modifier.padding(start = 54.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(0.6.dp, IosColors.Separator.copy(alpha = 0.42f)), RoundedCornerShape(12.dp))
            .background(IosColors.Card)
            .padding(horizontal = 22.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.empty_documents),
            color = IosColors.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LibraryRow(
    item: LibraryDocument,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val deleteWidth = 86.dp
    val density = LocalDensity.current
    val deleteWidthPx = with(density) { deleteWidth.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(deleteWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                .background(Color(0xFFFF3B30))
                .semantics { contentDescription = "删除 ${item.title}" }
                .clickable(role = Role.Button, onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_delete_trash),
                    contentDescription = "删除文档",
                    tint = Color.White,
                    modifier = Modifier.size(23.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.delete),
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .clip(RoundedCornerShape(12.dp))
                .background(IosColors.Card)
                .pointerInput(item.title) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            offsetX = if (offsetX < -deleteWidthPx * 0.42f) -deleteWidthPx else 0f
                        },
                        onDragCancel = {
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(-deleteWidthPx, 0f)
                        }
                    )
                }
                .clickable {
                    if (offsetX < 0f) {
                        offsetX = 0f
                    } else {
                        onClick()
                    }
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 28.dp, height = 34.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_doc_markdown),
                    contentDescription = "Markdown 文档",
                    tint = IosColors.Blue,
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = IosColors.Text,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = libraryDocumentMeta(item),
                    color = IosColors.OnSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("›", color = IosColors.Chevron, fontSize = 20.sp, fontWeight = FontWeight.Light)
        }
    }
}

private fun libraryDocumentMeta(item: LibraryDocument): String = item.subtitle

@Composable
private fun LibraryBottomBar(
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    selectedTab: AppTab,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(IosColors.Bar.copy(alpha = 0.96f))
            .border(BorderStroke(0.6.dp, IosColors.Separator.copy(alpha = 0.55f)))
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .height(58.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomTab(AppTab.Library, selectedTab == AppTab.Library, onLibrary)
        BottomTab(AppTab.Settings, selectedTab == AppTab.Settings, onSettings)
    }
}

@Composable
private fun BottomTab(tab: AppTab, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) IosColors.Blue else IosColors.OnSurfaceVariant
    val tabScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bottom-tab-scale"
    )
    Column(
        modifier = Modifier
            .width(88.dp)
            .scale(tabScale)
            .semantics {
                contentDescription = when (tab) {
                    AppTab.Library -> "文库"
                    AppTab.Settings -> "设置"
                }
                stateDescription = if (selected) "已选中" else "未选中"
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BottomTabIcon(tab = tab, color = color, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun BottomTabIcon(tab: AppTab, color: Color, modifier: Modifier = Modifier) {
    val iconRes = when (tab) {
        AppTab.Library -> R.drawable.ic_nav_library
        AppTab.Settings -> R.drawable.ic_nav_settings
    }
    Icon(
        painter = painterResource(id = iconRes),
        contentDescription = when (tab) {
            AppTab.Library -> "文档"
            AppTab.Settings -> "设置"
        },
        tint = color,
        modifier = modifier
    )
}

@Composable
private fun ReaderOptionsSheet(
    fontScale: Float,
    useSerif: Boolean,
    readerTheme: String,
    layoutMode: ReaderLayoutMode,
    typographyStyle: ReaderTypographyStyle,
    onFontScaleChange: (Float) -> Unit,
    onUseSerifChange: (Boolean) -> Unit,
    onThemeChange: (String) -> Unit,
    onLayoutModeChange: (ReaderLayoutMode) -> Unit,
    onTypographyStyleChange: (ReaderTypographyStyle) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(IosColors.Sheet)
                .clickable(onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp)
                .padding(WindowInsets.navigationBars.asPaddingValues()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(IosColors.Placeholder)
            )
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.reader_zoom), color = IosColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(IosColors.Surface)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("30%", color = IosColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Slider(
                    value = fontScale,
                    onValueChange = onFontScaleChange,
                    valueRange = 0.3f..2.0f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp)
                )
                Text(
                    text = "${(fontScale * 100).toInt()}%",
                    color = IosColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.End
                )
                Spacer(Modifier.width(10.dp))
                Text("200%", color = IosColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IosColors.Surface)
            ) {
                ReaderChoiceRow(
                    text = "手机适配",
                    selected = layoutMode == ReaderLayoutMode.Mobile,
                    onClick = { onLayoutModeChange(ReaderLayoutMode.Mobile) }
                )
                HorizontalDivider(color = IosColors.Separator.copy(alpha = 0.42f), thickness = 0.6.dp)
                ReaderChoiceRow(
                    text = "原版缩放",
                    selected = layoutMode == ReaderLayoutMode.Original,
                    onClick = { onLayoutModeChange(ReaderLayoutMode.Original) }
                )
            }
            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IosColors.Surface)
            ) {
                ReaderChoiceRow(
                    text = "Folio 原版",
                    selected = typographyStyle == ReaderTypographyStyle.Folio,
                    onClick = { onTypographyStyleChange(ReaderTypographyStyle.Folio) }
                )
                HorizontalDivider(color = IosColors.Separator.copy(alpha = 0.42f), thickness = 0.6.dp)
                ReaderChoiceRow(
                    text = "GitHub Markdown",
                    selected = typographyStyle == ReaderTypographyStyle.GitHub,
                    onClick = { onTypographyStyleChange(ReaderTypographyStyle.GitHub) }
                )
            }
            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IosColors.Surface)
            ) {
                ReaderChoiceRow(
                    text = "衬线体 (原稿)",
                    selected = useSerif,
                    onClick = { onUseSerifChange(true) }
                )
                HorizontalDivider(color = IosColors.Separator.copy(alpha = 0.42f), thickness = 0.6.dp)
                ReaderChoiceRow(
                    text = "无衬线体 (系统)",
                    selected = !useSerif,
                    onClick = { onUseSerifChange(false) }
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ReaderThemeButton("明亮", "light", readerTheme, Color.White, IosColors.Text, onThemeChange)
                ReaderThemeButton("羊皮纸", "paper", readerTheme, Color(0xFFF4ECD8), Color(0xFF5B4636), onThemeChange)
                ReaderThemeButton("深色", "dark", readerTheme, Color(0xFF1C1C1E), Color.White, onThemeChange)
            }
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = IosColors.Blue),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(stringResource(R.string.done), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ReaderChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = IosColors.OnSurfaceVariant, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) {
            Text("✓", color = IosColors.Blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RowScope.ReaderThemeButton(
    label: String,
    value: String,
    selected: String,
    background: Color,
    content: Color,
    onClick: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(
                BorderStroke(1.5.dp, if (selected == value) IosColors.Blue else Color.Transparent),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick(value) },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

internal fun isSupportedDocumentName(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return lower.endsWith(".md") ||
        lower.endsWith(".markdown")
}

private fun normalizeSearchQuery(value: String): String =
    value.trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)

private fun LibraryDocument.matchesLibraryQuery(query: String): Boolean {
    val searchableText = normalizeSearchQuery(
        listOf(title, subtitle, libraryDocumentMeta(this)).joinToString(" ")
    )
    return searchableText.contains(query)
}

internal fun readerBackground(theme: String): Color = when (theme) {
    "paper" -> Color(0xFFF4ECD8)
    "dark" -> Color(0xFF1C1C1E)
    else -> IosColors.Background
}

private fun resolveReaderTheme(theme: String, systemDark: Boolean): String =
    if (theme == "system") {
        if (systemDark) "dark" else "light"
    } else {
        theme
    }

private suspend fun loadReaderPreferences(context: android.content.Context): ReaderPreferences {
    return context.readerPreferencesDataStore.data.map { prefs ->
        ReaderPreferences(
            fontScale = (prefs[floatPreferencesKey(ReaderFontScaleKey)] ?: DefaultReaderPreferences.fontScale).coerceIn(0.3f, 2.0f),
            useSerif = prefs[booleanPreferencesKey(ReaderUseSerifKey)] ?: DefaultReaderPreferences.useSerif,
            theme = prefs[stringPreferencesKey(ReaderThemeKey)]
            ?.takeIf { it in setOf("light", "paper", "dark", "system") }
            ?: DefaultReaderPreferences.theme,
            markdownEnhanced = prefs[booleanPreferencesKey(ReaderMarkdownEnhancedKey)] ?: DefaultReaderPreferences.markdownEnhanced,
            layoutMode = prefs.enumValue(ReaderLayoutModeKey, DefaultReaderPreferences.layoutMode),
            typographyStyle = prefs.enumValue(ReaderTypographyStyleKey, DefaultReaderPreferences.typographyStyle)
        )
    }.first()
}

private suspend fun saveReaderPreferences(context: android.content.Context, preferences: ReaderPreferences) {
    context.readerPreferencesDataStore.edit { prefs ->
        prefs[floatPreferencesKey(ReaderFontScaleKey)] = preferences.fontScale.coerceIn(0.3f, 2.0f)
        prefs[booleanPreferencesKey(ReaderUseSerifKey)] = preferences.useSerif
        prefs[stringPreferencesKey(ReaderThemeKey)] = preferences.theme
        prefs[booleanPreferencesKey(ReaderMarkdownEnhancedKey)] = preferences.markdownEnhanced
        prefs[stringPreferencesKey(ReaderLayoutModeKey)] = preferences.layoutMode.name
        prefs[stringPreferencesKey(ReaderTypographyStyleKey)] = preferences.typographyStyle.name
    }
}

private inline fun <reified T : Enum<T>> androidx.datastore.preferences.core.Preferences.enumValue(key: String, default: T): T =
    this[stringPreferencesKey(key)]
        ?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() }
        ?: default

private fun appVersionLabel(context: android.content.Context): String =
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName ?: "1.0"} (稳定版)"
    }.getOrDefault("1.0 (稳定版)")

internal fun handleReaderUrl(uri: Uri?, onExternalUrl: (Uri) -> Unit): Boolean {
    if (uri == null) return true
    if (uri.scheme == "file" && uri.toString().startsWith("file:///android_asset/")) {
        return false
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    return if (scheme in setOf("http", "https", "mailto")) {
        onExternalUrl(uri)
        true
    } else {
        true
    }
}

internal fun openExternalReaderUrl(context: android.content.Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}

internal fun externalUrlPrompt(uri: Uri): String {
    val scheme = uri.scheme.orEmpty()
    val host = uri.host.orEmpty()
    val target = when {
        host.isNotBlank() -> "$scheme://$host"
        scheme.isNotBlank() -> scheme
        else -> uri.toString()
    }
    return "即将离开 Folio 打开：$target"
}

internal object IosColors {
    var Background by mutableStateOf(Color(0xFFF2F2F7))
    var Bar by mutableStateOf(Color(0xFFF7F7FA))
    var Control by mutableStateOf(Color(0xFFE5E5EA))
    var Separator by mutableStateOf(Color(0xFFC6C6C8))
    var Blue by mutableStateOf(Color(0xFF007AFF))
    var Text by mutableStateOf(Color(0xFF1D1D1F))
    var TertiaryText by mutableStateOf(Color(0xFFA1A1A6))
    var SearchField by mutableStateOf(Color(0xFFE9E7ED))
    var Placeholder by mutableStateOf(Color(0xFFC1C6D7))
    var Outline by mutableStateOf(Color(0xFF717786))
    var Chevron by mutableStateOf(Color(0xFFC1C6D7))
    var SectionLabel by mutableStateOf(Color(0xFF303545))
    var OnSurfaceVariant by mutableStateOf(Color(0xFF414755))
    var Surface by mutableStateOf(Color(0xFFFAF9FE))
    var Sheet by mutableStateOf(Color(0xFFE9E7ED))
    var Error by mutableStateOf(Color(0xFFBA1A1A))
    var Card by mutableStateOf(Color(0xFFFFFFFF))
    var SelectedControl by mutableStateOf(Color(0xFFFFFFFF))

    fun useDark(dark: Boolean) {
        if (dark) {
            Background = Color(0xFF1C1C1E)
            Bar = Color(0xFF161618)
            Control = Color(0xFF2C2C2E)
            Separator = Color(0xFF3A3A3C)
            Blue = Color(0xFF0A84FF)
            Text = Color(0xFFF5F5F7)
            TertiaryText = Color(0xFF8E8E93)
            SearchField = Color(0xFF2C2C2E)
            Placeholder = Color(0xFF6E6E73)
            Outline = Color(0xFFA1A1A6)
            Chevron = Color(0xFF6E6E73)
            SectionLabel = Color(0xFFD1D1D6)
            OnSurfaceVariant = Color(0xFFC7C7CC)
            Surface = Color(0xFF2C2C2E)
            Sheet = Color(0xFF1C1C1E)
            Error = Color(0xFFFF453A)
            Card = Color(0xFF2C2C2E)
            SelectedControl = Color(0xFF3A3A3C)
        } else {
            Background = Color(0xFFF2F2F7)
            Bar = Color(0xFFF7F7FA)
            Control = Color(0xFFE5E5EA)
            Separator = Color(0xFFC6C6C8)
            Blue = Color(0xFF007AFF)
            Text = Color(0xFF1D1D1F)
            TertiaryText = Color(0xFFA1A1A6)
            SearchField = Color(0xFFE9E7ED)
            Placeholder = Color(0xFFC1C6D7)
            Outline = Color(0xFF717786)
            Chevron = Color(0xFFC1C6D7)
            SectionLabel = Color(0xFF303545)
            OnSurfaceVariant = Color(0xFF414755)
            Surface = Color(0xFFFAF9FE)
            Sheet = Color(0xFFE9E7ED)
            Error = Color(0xFFBA1A1A)
            Card = Color(0xFFFFFFFF)
            SelectedControl = Color(0xFFFFFFFF)
        }
    }
}

private val DocumentSaver = androidx.compose.runtime.saveable.Saver<DocumentState?, List<String>>(
    save = { state ->
        when {
            state == null -> emptyList()
            else -> listOf(state.title, state.kind.name, state.sourceUri, state.sizeBytes?.toString().orEmpty())
        }
    },
    restore = { values ->
        if (values.size < 3) {
            null
        } else {
            runCatching {
                DocumentState(
                    title = values[0],
                    kind = DocumentKind.valueOf(values[1]),
                    sourceUri = values[2],
                    sizeBytes = values.getOrNull(3)?.toLongOrNull()
                )
            }.getOrNull()
        }
    }
)

@Preview(showBackground = true, widthDp = 390, heightDp = 820)
@Composable
private fun EmptyPreview() {
    FolioTheme(dynamicColor = false) {
        DocumentViewerApp()
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 820)
@Composable
private fun ReaderPreview() {
    FolioTheme(dynamicColor = false) {
        DocumentViewerApp(
            incomingUri = "content://preview/design.md".toUri(),
            loadDocument = {
                Result.success(
                    DocumentState(
                        title = "DESIGN.md",
                        kind = DocumentKind.Markdown,
                        sourceUri = "content://preview/design.md",
                        sizeBytes = 256
                    )
                )
            }
        )
    }
}

@Preview(showBackground = true, widthDp = 820, heightDp = 390)
@Composable
private fun LandscapePreview() {
    FolioTheme(dynamicColor = false) {
        DocumentViewerApp()
    }
}

@Preview(showBackground = true, widthDp = 1024, heightDp = 768)
@Composable
private fun TabletPreview() {
    FolioTheme(dynamicColor = false) {
        DocumentViewerApp()
    }
}
