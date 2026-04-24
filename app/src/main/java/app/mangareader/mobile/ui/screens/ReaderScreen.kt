package app.mangareader.mobile.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView // NEW: Imported to access the Android View
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import app.mangareader.mobile.ScrapeState
import app.mangareader.mobile.data.MangaChapter
import app.mangareader.mobile.utils.ReaderImage
import app.mangareader.mobile.utils.ChapterCacheManager
import app.mangareader.mobile.utils.FileUtils
import app.mangareader.mobile.ui.components.ZoomableContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DarkBlueStart = Color(0xFF0B101E)
private val PurpleEnd = Color(0xFF311545)

@Composable
fun ReaderScreen(
    seriesTitle: String,
    seriesFile: DocumentFile,
    onBackClick: () -> Unit
) {
    BackHandler { onBackClick() }

    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("manga_progress", Context.MODE_PRIVATE) }
    val snackbarHostState = remember { SnackbarHostState() }

    var allChapters by remember { mutableStateOf<List<MangaChapter>>(emptyList()) }
    var displayImages by remember { mutableStateOf<List<ReaderImage>>(emptyList()) }
    var currentBaseChapter by remember { mutableIntStateOf(0) }

    var isLoading by remember { mutableStateOf(true) }
    var areBarsVisible by remember { mutableStateOf(true) }
    var isZoomedIn by remember { mutableStateOf(false) }
    var isChapterMenuExpanded by remember { mutableStateOf(false) }

    var keepAwake by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()

    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(Unit) {
        ScrapeState.latestDownload.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(seriesFile) {
        isLoading = true

        val chapters = withContext(Dispatchers.IO) { FileUtils.getChapters(context, seriesFile) }
        allChapters = chapters

        if (chapters.isEmpty()) {
            isLoading = false
            return@LaunchedEffect
        }

        val savedChapter = prefs.getInt("last_chapter_$seriesTitle", 0).coerceIn(0, chapters.lastIndex)
        val savedPage = prefs.getInt("last_page_$seriesTitle", 0)

        currentBaseChapter = savedChapter
        displayImages = ChapterCacheManager.preloadChapters(context, seriesTitle, chapters, savedChapter)

        isLoading = false

        launch {
            if (displayImages.isNotEmpty()) {
                val targetAbsoluteIndex = displayImages.indexOfFirst {
                    it.chapterIndex == savedChapter && it.pageIndex == savedPage
                }.coerceAtLeast(0)
                listState.scrollToItem(targetAbsoluteIndex)
            }
        }
    }

    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    LaunchedEffect(firstVisibleIndex, areBarsVisible, isZoomedIn) {
        keepAwake = true
        delay(10 * 60 * 1000L)
        keepAwake = false
    }

    LaunchedEffect(firstVisibleIndex) {
        if (displayImages.isNotEmpty() && !isLoading) {
            val currentVisibleImage = displayImages.getOrNull(firstVisibleIndex)

            if (currentVisibleImage != null) {
                prefs.edit()
                    .putInt("last_chapter_$seriesTitle", currentVisibleImage.chapterIndex)
                    .putInt("last_page_$seriesTitle", currentVisibleImage.pageIndex)
                    .apply()

                if (currentVisibleImage.chapterIndex != currentBaseChapter) {
                    currentBaseChapter = currentVisibleImage.chapterIndex
                    scope.launch {
                        val newImages = ChapterCacheManager.preloadChapters(context, seriesTitle, allChapters, currentBaseChapter)
                        if (newImages.isNotEmpty()) {
                            val newAbsoluteIndex = newImages.indexOfFirst {
                                it.chapterIndex == currentVisibleImage.chapterIndex &&
                                        it.pageIndex == currentVisibleImage.pageIndex
                            }.coerceAtLeast(0)

                            displayImages = newImages
                            listState.scrollToItem(newAbsoluteIndex, listState.firstVisibleItemScrollOffset)
                        }
                    }
                }
            }
        }
    }

    val headerFooterGradient = Brush.horizontalGradient(colors = listOf(DarkBlueStart, PurpleEnd))
    val currentReadingChapter = displayImages.getOrNull(firstVisibleIndex)?.chapterIndex ?: 0

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = areBarsVisible,
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it })
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headerFooterGradient)
                        .statusBarsPadding()
                        .padding(10.dp)
                        .heightIn(min = 45.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "← Back",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onBackClick() }.padding(8.dp)
                    )

                    Text(
                        text = seriesTitle,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )

                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable(enabled = allChapters.isNotEmpty()) { isChapterMenuExpanded = true }
                                .padding(8.dp)
                        ) {
                            Text(text = "Ch ${currentReadingChapter + 1}", color = Color.White, fontWeight = FontWeight.Bold)
                            Text(text = " ▼", color = Color.White, fontSize = 10.sp)
                        }

                        DropdownMenu(
                            expanded = isChapterMenuExpanded,
                            onDismissRequest = { isChapterMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 400.dp)
                        ) {
                            allChapters.forEachIndexed { index, chapter ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = chapter.name,
                                            fontWeight = if (index == currentReadingChapter) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        isChapterMenuExpanded = false
                                        if (index != currentReadingChapter) {
                                            isLoading = true
                                            scope.launch {
                                                currentBaseChapter = index
                                                displayImages = ChapterCacheManager.preloadChapters(context, seriesTitle, allChapters, currentBaseChapter)

                                                isLoading = false

                                                launch {
                                                    if (displayImages.isNotEmpty()) {
                                                        val targetAbsoluteIndex = displayImages.indexOfFirst { it.chapterIndex == index }.coerceAtLeast(0)
                                                        listState.scrollToItem(targetAbsoluteIndex)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = areBarsVisible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headerFooterGradient)
                        .navigationBarsPadding()
                        .padding(15.dp)
                ) {
                    val scrollProgress = if (displayImages.isNotEmpty()) {
                        firstVisibleIndex.toFloat() / displayImages.size
                    } else 0f

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                brush = Brush.horizontalGradient(colors = listOf(Color(0xFF963819), Color(0xFFFCDC2A))),
                                shape = RoundedCornerShape(2.dp)
                            )
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(scrollProgress.coerceIn(0f, 1f)).height(8.dp)) {
                            Box(modifier = Modifier.align(Alignment.CenterEnd).size(8.dp).background(Color.White, shape = CircleShape))
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Page ${displayImages.getOrNull(firstVisibleIndex)?.pageIndex?.plus(1) ?: 1}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color(0xFFFCDC2A))
            } else if (displayImages.isEmpty()) {
                Text("No images found in chapter.", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            } else {
                ZoomableContainer(onZoomStateChanged = { isZoomedIn = it }) {
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = !isZoomedIn,
                        modifier = Modifier.fillMaxSize().clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { areBarsVisible = !areBarsVisible }
                    ) {
                        items(displayImages, key = { it.uri.toString() }) { readerImg ->
                            AsyncImage(
                                model = readerImg.uri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                        item { Spacer(modifier = Modifier.height(150.dp)) }
                    }
                }
            }
        }
    }
}