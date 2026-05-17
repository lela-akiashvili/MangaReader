package app.mangareader.mobile.ui.screens

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import app.mangareader.mobile.data.MangaSeries
import app.mangareader.mobile.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import app.mangareader.mobile.utils.CloudSyncButton

private val DarkBlueStart = Color(0xFF0B101E)
private val PurpleEnd = Color(0xFF311545)

enum class SortType(val label: String) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_DESC("Newest"),
    DATE_ASC("Oldest")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    rootFolderUri: Uri,
    onSeriesClick: (MangaSeries) -> Unit,
    onThemeToggle: () -> Unit,
    isDark: Boolean,
    onResyncClick: () -> Unit,
    onScraperClick: () -> Unit,
    onNotificationsClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val sharedPrefs = remember { context.getSharedPreferences("manga_search_history", Context.MODE_PRIVATE) }

    var mangaList by remember { mutableStateOf<List<MangaSeries>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var sortType by remember { mutableStateOf(SortType.DATE_DESC) }

    // UI States
    var isSortMenuExpanded by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var validCookie by remember { mutableStateOf("") }
    var isBrowserVisible by remember { mutableStateOf(false) }
    var isMenuExpanded by remember { mutableStateOf(false) }
    var isSearchFocused by remember { mutableStateOf(false) }

    var seriesToBind by remember { mutableStateOf<MangaSeries?>(null) }
    var inputUrl by remember { mutableStateOf("") }

    // Search History State (Limit 4)
    var searchHistory by remember {
        val savedString = sharedPrefs.getString("history_list", "") ?: ""
        mutableStateOf(if (savedString.isEmpty()) emptyList<String>() else savedString.split("|||"))
    }

    fun saveSearchHistory(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return
        val newList = searchHistory.toMutableList()
        newList.remove(cleanQuery)
        newList.add(0, cleanQuery) // Add to top
        val trimmedList = newList.take(4) // Keep max 4
        searchHistory = trimmedList
        sharedPrefs.edit().putString("history_list", trimmedList.joinToString("|||")).apply()
    }

    val headerFooterGradient = Brush.horizontalGradient(colors = listOf(DarkBlueStart, PurpleEnd))

    // Background function to lazily load covers without blocking the UI
    suspend fun processMissingCovers(listToProcess: List<MangaSeries>) {
        withContext(Dispatchers.IO) {
            var cacheNeedsUpdate = false

            // Sort the background queue so the series at the top of the screen load first!
            val sortedList = when (sortType) {
                SortType.NAME_ASC -> listToProcess.sortedBy { it.title.lowercase() }
                SortType.NAME_DESC -> listToProcess.sortedByDescending { it.title.lowercase() }
                SortType.DATE_DESC -> listToProcess.sortedByDescending { it.downloadTimestamp }
                SortType.DATE_ASC -> listToProcess.sortedBy { it.downloadTimestamp }
            }

            for (manga in sortedList) {
                if (manga.coverUri == null) {
                    val newCover = FileUtils.fetchCoverImage(context, manga)
                    if (newCover != null) {
                        withContext(Dispatchers.Main) {
                            val index = mangaList.indexOfFirst { it.folderUri == manga.folderUri }
                            if (index != -1) {
                                val updatedList = mangaList.toMutableList()
                                updatedList[index] = updatedList[index].copy(coverUri = newCover)
                                mangaList = updatedList
                            }
                        }
                        cacheNeedsUpdate = true
                    }
                }
            }

            if (cacheNeedsUpdate) {
                FileUtils.updateCacheFile(context, rootFolderUri, mangaList)
            }
        }
    }

    // JSON Startup Cache Hook
    LaunchedEffect(rootFolderUri) {
        val prefs = context.getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_root_uri", rootFolderUri.toString()).apply()

        isSyncing = true
        val loadedList = withContext(Dispatchers.IO) {
            val cached = FileUtils.getCachedLibrary(context, rootFolderUri)
            if (cached.isNotEmpty()) cached else FileUtils.syncLibrary(context, rootFolderUri)
        }
        mangaList = loadedList
        isSyncing = false

        processMissingCovers(loadedList)
    }

    // Auto-refresh hook that listens to the background scraper
    val isScraping by app.mangareader.mobile.ScrapeState.isScraping.collectAsState()
    var previousIsScraping by remember { mutableStateOf(isScraping) }

    LaunchedEffect(isScraping) {
        if (previousIsScraping && !isScraping) {
            val cached = withContext(Dispatchers.IO) { FileUtils.getCachedLibrary(context, rootFolderUri) }
            if (cached.isNotEmpty()) {
                mangaList = cached
                processMissingCovers(cached)
            }
        }
        previousIsScraping = isScraping
    }

    val filteredList by remember {
        derivedStateOf {
            var temp = mangaList
            if (searchQuery.isNotEmpty()) {
                temp = temp.filter { it.title.contains(searchQuery, ignoreCase = true) }
            }
            when (sortType) {
                SortType.NAME_ASC -> temp.sortedBy { it.title.lowercase() }
                SortType.NAME_DESC -> temp.sortedByDescending { it.title.lowercase() }
                SortType.DATE_DESC -> temp.sortedByDescending { it.downloadTimestamp }
                SortType.DATE_ASC -> temp.sortedBy { it.downloadTimestamp }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(modifier = Modifier.background(headerFooterGradient).statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isDark) "🌙" else "☀️",
                        fontSize = 24.sp,
                        modifier = Modifier.clickable { onThemeToggle() }
                    )

                    if (isSyncing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CloudSyncButton(rootUri = rootFolderUri)

                            Spacer(modifier = Modifier.width(8.dp))

                            Box {
                                Text(
                                    text = "⚙️",
                                    fontSize = 24.sp,
                                    modifier = Modifier.clickable { isMenuExpanded = true }
                                )
                                DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Resync Files") },
                                        onClick = {
                                            isMenuExpanded = false
                                            scope.launch {
                                                isSyncing = true
                                                val newList = withContext(Dispatchers.IO) { FileUtils.syncLibrary(context, rootFolderUri) }
                                                mangaList = newList
                                                isSyncing = false
                                                processMissingCovers(newList)
                                            }
                                            onResyncClick()
                                        },
                                        leadingIcon = { Text("🔄", fontSize = 16.sp) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Fetch Series") },
                                        onClick = { isMenuExpanded = false; onScraperClick() },
                                        leadingIcon = { Text("⚙️", fontSize = 16.sp) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Notifications Center") },
                                        onClick = { isMenuExpanded = false; onNotificationsClick() },
                                        leadingIcon = { Text("🔔", fontSize = 16.sp) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth().background(headerFooterGradient).navigationBarsPadding().height(25.dp))
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize().background(if(isDark) Color(0xFF121212) else Color(0xFFF5F5F5))) {

            // --- NEW: SEARCH AND SORT BAR ---
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search Input with Dropdown
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search series...", color = if (isDark) Color.LightGray else Color.DarkGray) },
                        leadingIcon = { Text("🔍") },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                Text(
                                    text = "✖️",
                                    modifier = Modifier.clickable { searchQuery = ""; focusManager.clearFocus() }
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            saveSearchHistory(searchQuery)
                            focusManager.clearFocus()
                        }),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = if (isDark) Color(0xFF1E1E1E) else Color.White,
                            unfocusedContainerColor = if (isDark) Color(0xFF1E1E1E) else Color.White,
                            focusedTextColor = if (isDark) Color.White else Color.Black,
                            unfocusedTextColor = if (isDark) Color.White else Color.Black,
                            focusedIndicatorColor = Color(0xFFFCDC2A),
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isSearchFocused = it.isFocused }
                    )

                    // History Dropdown
                    DropdownMenu(
                        expanded = isSearchFocused && searchHistory.isNotEmpty() && searchQuery.isEmpty(),
                        onDismissRequest = { isSearchFocused = false },
                        modifier = Modifier.fillMaxWidth(0.85f).background(if (isDark) Color(0xFF2C2C2C) else Color.White)
                    ) {
                        searchHistory.forEach { historyItem ->
                            DropdownMenuItem(
                                text = { Text(historyItem, color = if (isDark) Color.White else Color.Black) },
                                leadingIcon = { Text("🕒") },
                                onClick = {
                                    searchQuery = historyItem
                                    saveSearchHistory(historyItem)
                                    focusManager.clearFocus()
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Sort Dropdown
                Box {
                    Surface(
                        color = if (isDark) Color(0xFF1E1E1E) else Color.White,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(54.dp).clickable { isSortMenuExpanded = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = "↕️", fontSize = 24.sp)
                        }
                    }

                    DropdownMenu(
                        expanded = isSortMenuExpanded,
                        onDismissRequest = { isSortMenuExpanded = false },
                        modifier = Modifier.background(if (isDark) Color(0xFF2C2C2C) else Color.White)
                    ) {
                        SortType.values().forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.label, color = if (isDark) Color.White else Color.Black) },
                                onClick = {
                                    sortType = type
                                    isSortMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (sortType == type) Text("✓", color = Color(0xFFFCDC2A))
                                }
                            )
                        }
                    }
                }
            }
            // --- END SEARCH AND SORT BAR ---

            if (isBrowserVisible) {
                Box(modifier = Modifier.fillMaxSize().zIndex(10f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = WebViewClient()
                                loadUrl("https://www.mangago.me/home/accounts/login/")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Button(
                        onClick = {
                            val cookies = CookieManager.getInstance().getCookie("https://www.mangago.me")
                            if (!cookies.isNullOrEmpty()) validCookie = cookies
                            isBrowserVisible = false
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp)
                    ) { Text("Save Login & Close") }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(15.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredList, key = { it.folderUri.toString() }) { manga ->
                    MangaCard(
                        manga = manga,
                        onClick = {
                            if (searchQuery.isNotEmpty()) saveSearchHistory(searchQuery)
                            onSeriesClick(manga)
                        },
                        onLongClick = {
                            seriesToBind = manga
                            inputUrl = ""
                        }
                    )
                }
            }
        }

        if (seriesToBind != null) {
            AlertDialog(
                onDismissRequest = { seriesToBind = null },
                title = { Text("Bind Mangago URL", color = Color.White) },
                text = {
                    Column {
                        Text("Link a URL to '${seriesToBind?.title}' so the auto-tracker always finds it.", color = Color.LightGray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            label = { Text("Mangago Series URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val title = seriesToBind?.title ?: ""
                        if (title.isNotEmpty() && inputUrl.isNotEmpty()) {
                            scope.launch(Dispatchers.IO) {
                                FileUtils.bindUrlToTracker(context, rootFolderUri, title, inputUrl)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "URL Bound Successfully!", Toast.LENGTH_SHORT).show()
                                    seriesToBind = null
                                }
                            }
                        }
                    }) {
                        Text("Save Link")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { seriesToBind = null }) { Text("Cancel", color = Color.White) }
                },
                containerColor = Color(0xFF1E1E1E)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MangaCard(manga: MangaSeries, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("manga_progress", Context.MODE_PRIVATE) }
    var lastChapter by remember { mutableIntStateOf(0) }

    LaunchedEffect(manga.folderUri) {
        lastChapter = prefs.getInt("last_chapter_${manga.title}", 0) + 1
    }

    Box(
        modifier = Modifier
            .width(110.dp)
            .height(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        if (manga.coverUri != null) {
            AsyncImage(model = manga.coverUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            }
        }

        Box(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                .padding(6.dp)
        ) {
            Column {
                Text(text = manga.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(text = "Ch. $lastChapter", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }
    }
}