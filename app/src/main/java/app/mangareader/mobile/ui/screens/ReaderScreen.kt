package app.mangareader.mobile.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    var currentChapterIndex by remember { mutableIntStateOf(0) }

    var isLoading by remember { mutableStateOf(true) }
    var isLoadingNext by remember { mutableStateOf(false) }
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

    // --- HARD NAVIGATION FUNCTION (For Buttons & Dropdowns) ---
    val navigateToChapter: (Int, Int) -> Unit = { targetChapterIdx, targetPageIdx ->
        if (targetChapterIdx in allChapters.indices) {
            scope.launch {
                isLoading = true
                isLoadingNext = false
                currentChapterIndex = targetChapterIdx

                // CRITICAL FIX: Temporarily wipe the images to completely destroy the LazyColumn.
                // This forces Android Compose to forget the old page index so it doesn't carry over!
                displayImages = emptyList()

                val indicesToKeep = listOf(targetChapterIdx - 1, targetChapterIdx, targetChapterIdx + 1)
                    .filter { it in allChapters.indices }

                val allPreloaded = ChapterCacheManager.preloadChapters(
                    context,
                    seriesTitle,
                    allChapters,
                    indicesToKeep
                )

                val newImages = allPreloaded.filter { it.chapterIndex == targetChapterIdx }

                displayImages = newImages
                isLoading = false

                // Give Compose a solid moment to remount the list entirely
                delay(150)

                val safePageIndex = targetPageIdx.coerceIn(0, maxOf(0, displayImages.lastIndex))
                listState.scrollToItem(safePageIndex)

                prefs.edit()
                    .putInt("last_chapter_$seriesTitle", targetChapterIdx)
                    .putInt("last_page_$seriesTitle", safePageIndex)
                    .apply()
            }
        }
    }

    // --- INITIALIZATION ---
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

        navigateToChapter(savedChapter, savedPage)
    }

    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    // --- INACTIVITY TIMER ---
    LaunchedEffect(firstVisibleIndex, areBarsVisible, isZoomedIn) {
        keepAwake = true
        delay(10 * 60 * 1000L)
        keepAwake = false
    }

    // --- SEAMLESS AUTO-LOAD & PROGRESS TRACKER ---
    LaunchedEffect(firstVisibleIndex) {
        val currentVisibleImage = displayImages.getOrNull(firstVisibleIndex)

        // 1. Update UI and Memory Progress dynamically as we scroll
        if (currentVisibleImage != null && !isLoading) {
            currentChapterIndex = currentVisibleImage.chapterIndex

            prefs.edit()
                .putInt("last_chapter_$seriesTitle", currentVisibleImage.chapterIndex)
                .putInt("last_page_$seriesTitle", currentVisibleImage.pageIndex)
                .apply()
        }

        // 2. Continuous Scroll Auto-Appender
        if (!isLoading && !isLoadingNext && displayImages.isNotEmpty()) {
            // Trigger background load when we are 4 pages away from the bottom
            if (firstVisibleIndex >= displayImages.size - 4) {
                val highestLoadedChapter = displayImages.maxOf { it.chapterIndex }
                val nextChapterIdx = highestLoadedChapter + 1

                if (nextChapterIdx < allChapters.size) {
                    isLoadingNext = true
                    scope.launch {
                        val currentlyDisplayedIndices = displayImages.map { it.chapterIndex }.distinct()

                        val indicesToKeep = (currentlyDisplayedIndices + listOf(nextChapterIdx, nextChapterIdx + 1))
                            .filter { it in allChapters.indices }
                            .distinct()

                        val allPreloaded = ChapterCacheManager.preloadChapters(
                            context,
                            seriesTitle,
                            allChapters,
                            indicesToKeep
                        )

                        val newImages = allPreloaded.filter { it.chapterIndex == nextChapterIdx }

                        if (newImages.isNotEmpty()) {
                            displayImages = displayImages + newImages
                        }
                        isLoadingNext = false
                    }
                }
            }
        }
    }

    val headerFooterGradient = Brush.horizontalGradient(colors = listOf(DarkBlueStart, PurpleEnd))

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
                                .clickable(enabled = allChapters.isNotEmpty() && !isLoading) { isChapterMenuExpanded = true }
                                .padding(8.dp)
                        ) {
                            Text(text = "Ch ${currentChapterIndex + 1}", color = Color.White, fontWeight = FontWeight.Bold)
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
                                            fontWeight = if (index == currentChapterIndex) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        isChapterMenuExpanded = false
                                        // CRITICAL FIX: We removed the check that prevented you from clicking
                                        // the chapter you are currently reading! Now you can always jump to Page 1.
                                        navigateToChapter(index, 0)
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
                    val currentChapPages = displayImages.count { it.chapterIndex == currentChapterIndex }
                    val currentVisibleImg = displayImages.getOrNull(firstVisibleIndex)
                    val currentPageDisplay = (currentVisibleImg?.pageIndex ?: 0) + 1

                    val scrollProgress = if (displayImages.size > 1) {
                        firstVisibleIndex.toFloat() / (displayImages.size - 1)
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
                        text = "Page $currentPageDisplay / $currentChapPages",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hasPrev = currentChapterIndex > 0
                        val hasNext = currentChapterIndex < allChapters.lastIndex

                        TextButton(
                            onClick = { if (hasPrev) navigateToChapter(currentChapterIndex - 1, 0) },
                            enabled = hasPrev && !isLoading,
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) {
                            Text("← Prev", color = if (hasPrev) Color.White else Color.Gray, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            text = allChapters.getOrNull(currentChapterIndex)?.name ?: "",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )

                        TextButton(
                            onClick = { if (hasNext) navigateToChapter(currentChapterIndex + 1, 0) },
                            enabled = hasNext && !isLoading,
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) {
                            Text("Next →", color = if (hasNext) Color.White else Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (displayImages.isEmpty() && !isLoading) {
                Text("No images found in chapter.", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            } else if (displayImages.isNotEmpty()) {
                ZoomableContainer(onZoomStateChanged = { isZoomedIn = it }) {
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = !isZoomedIn && !isLoading,
                        modifier = Modifier.fillMaxSize().clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { areBarsVisible = !areBarsVisible }
                    ) {
                        items(displayImages, key = { it.uri.toString() }) { readerImg ->
                            // Replaced AsyncImage with our native Subsampling handler
                            AdaptiveWebtoonImage(uri = readerImg.uri)
                        }

                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(150.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingNext) {
                                    CircularProgressIndicator(color = Color(0xFFFCDC2A))
                                } else if (currentChapterIndex >= allChapters.lastIndex) {
                                    Text("End of Series", color = Color.DarkGray)
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f))
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xFFFCDC2A)
                    )
                }
            }
        }
    }
}

@Composable
fun AdaptiveWebtoonImage(uri: Uri) {
    val context = LocalContext.current
    var imageChunks by remember { mutableStateOf<List<android.graphics.Rect>?>(null) }
    var originalSize by remember { mutableStateOf(Pair(0, 0)) }

    // 1. Instantly read dimensions without loading into RAM
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
                val height = options.outHeight
                val width = options.outWidth
                originalSize = Pair(width, height)

                // Hardware texture limit is ~8192 on most phones.
                // We slice if height is > 4000 to be perfectly safe and memory efficient.
                if (height > 4000) {
                    val chunks = mutableListOf<android.graphics.Rect>()
                    var top = 0
                    while (top < height) {
                        val bottom = (top + 4000).coerceAtMost(height)
                        chunks.add(android.graphics.Rect(0, top, width, bottom))
                        top += 4000
                    }
                    imageChunks = chunks
                } else {
                    imageChunks = emptyList() // Normal size
                }
            } catch (e: Exception) {
                e.printStackTrace()
                imageChunks = emptyList() // Fallback to normal loading if bounds fail
            }
        }
    }

    if (imageChunks == null) {
        Box(modifier = Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFFCDC2A))
        }
    } else if (imageChunks!!.isEmpty()) {
        // Normal Image - Coil handles it perfectly
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth
        )
    } else {
        // Massive Image - Sub-sample chunks natively!
        Column(modifier = Modifier.fillMaxWidth()) {
            imageChunks!!.forEach { rect ->
                ChunkRenderer(uri = uri, rect = rect, width = originalSize.first)
            }
        }
    }
}

@Composable
fun ChunkRenderer(uri: Uri, rect: android.graphics.Rect, width: Int) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val aspectRatio = width.toFloat() / rect.height().toFloat()

    LaunchedEffect(uri, rect) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val decoder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        BitmapRegionDecoder.newInstance(stream)
                    } else {
                        @Suppress("DEPRECATION")
                        BitmapRegionDecoder.newInstance(stream, false)
                    }
                    decoder?.let {
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        val bmp = it.decodeRegion(rect, options)
                        it.recycle()
                        withContext(Dispatchers.Main) {
                            bitmap = bmp?.asImageBitmap()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            // Faint loading indicator for the specific chunk
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.LightGray, strokeWidth = 2.dp)
            }
        }
    }
}