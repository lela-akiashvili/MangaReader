package app.mangareader.mobile.ui.screens

import android.content.Context
import android.content.Intent
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
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import app.mangareader.mobile.ScraperService
import app.mangareader.mobile.data.MangaSeries
import app.mangareader.mobile.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    onScraperClick: () -> Unit
) {
    val context = LocalContext.current
    var mangaList by remember { mutableStateOf<List<MangaSeries>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var sortType by remember { mutableStateOf(SortType.DATE_DESC) }
    var isSortMenuExpanded by remember { mutableStateOf(false) }

    var showScraperDialog by remember { mutableStateOf(false) }
    var scrapeUrl by remember { mutableStateOf("") }
    var scrapeTitle by remember { mutableStateOf("") }
    var isBrowserVisible by remember { mutableStateOf(false) }
    var validCookie by remember { mutableStateOf("") }

    var isMenuExpanded by remember { mutableStateOf(false) }
    val headerFooterGradient = Brush.horizontalGradient(colors = listOf(DarkBlueStart, PurpleEnd))

    LaunchedEffect(rootFolderUri) {
        withContext(Dispatchers.IO) {
            mangaList = FileUtils.scanMangaFolders(context, rootFolderUri)
        }
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
                    Box {
                        Text(
                            text = "⚙️",
                            fontSize = 24.sp,
                            modifier = Modifier.clickable { isMenuExpanded = true }
                        )
                        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Resync Files") },
                                onClick = { isMenuExpanded = false; onResyncClick() },
                                leadingIcon = { Text("🔄", fontSize = 16.sp) }
                            )
                            DropdownMenuItem(
                                text = { Text("Fetch Series") },
                                onClick = { isMenuExpanded = false; onScraperClick() },
                                leadingIcon = { Text("⚙️", fontSize = 16.sp) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search series...", color = Color.White.copy(alpha = 0.5f)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.1f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.1f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box {
                        Button(
                            onClick = { isSortMenuExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                            modifier = Modifier.height(50.dp)
                        ) { Text(sortType.label) }

                        DropdownMenu(expanded = isSortMenuExpanded, onDismissRequest = { isSortMenuExpanded = false }) {
                            SortType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.label) },
                                    onClick = { sortType = type; isSortMenuExpanded = false }
                                )
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
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize().background(if(isDark) Color(0xFF121212) else Color(0xFFF5F5F5))) {

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
                    MangaCard(manga = manga, onClick = { onSeriesClick(manga) })
                }
            }
        }

        if (showScraperDialog) {
            AlertDialog(
                onDismissRequest = { showScraperDialog = false },
                title = { Text("Fetch New Series") },
                text = {
                    Column {
                        Button(
                            onClick = { isBrowserVisible = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
                        ) { Text("1. Login (Optional)") }

                        if (validCookie.isNotEmpty()) Text("✅ Login Saved!", color = Color.Green, fontSize = 12.sp)

                        Spacer(modifier = Modifier.height(15.dp))
                        TextField(value = scrapeUrl, onValueChange = { scrapeUrl = it }, placeholder = { Text("URL (mangago.me/...)") })
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(value = scrapeTitle, onValueChange = { scrapeTitle = it }, placeholder = { Text("Manga Title (e.g. Naruto)") })

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("This will run silently in the background. You can read while it downloads!", fontSize = 11.sp, color = Color.Gray)
                    }
                },
                confirmButton = {
                    Button(
                        enabled = scrapeUrl.isNotEmpty() && scrapeTitle.isNotEmpty(),
                        onClick = {
                            val intent = Intent(context, ScraperService::class.java).apply {
                                putExtra("URL", scrapeUrl)
                                putExtra("SERIES_TITLE", scrapeTitle)
                                putExtra("COOKIE", validCookie)
                            }
                            ContextCompat.startForegroundService(context, intent)
                            showScraperDialog = false
                        }
                    ) { Text("Start Background Fetch") }
                },
                dismissButton = { Button(onClick = { showScraperDialog = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
fun MangaCard(manga: MangaSeries, onClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("manga_progress", Context.MODE_PRIVATE) }

    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var lastChapter by remember { mutableIntStateOf(0) }

    LaunchedEffect(manga.folderUri) {
        lastChapter = prefs.getInt("last_chapter_${manga.title}", 0) + 1
        withContext(Dispatchers.IO) { coverUri = FileUtils.getCoverImage(context, manga.documentFile) }
    }

    Box(
        modifier = Modifier.width(110.dp).height(160.dp).clip(RoundedCornerShape(8.dp)).clickable { onClick() }
    ) {
        if (coverUri != null) {
            AsyncImage(model = coverUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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