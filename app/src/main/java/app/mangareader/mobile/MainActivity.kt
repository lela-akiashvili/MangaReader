package app.mangareader.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mangareader.mobile.scraper.ChapterDownloader
import app.mangareader.mobile.scraper.HybridScraper
import app.mangareader.mobile.ui.theme.MangaReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MangaReaderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ScraperScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperScreen() {
    var seriesUrlInput by remember { mutableStateOf("") }
    var baseNameInput by remember { mutableStateOf("") }
    var cookieInput by remember { mutableStateOf("") }
    var showLoginBrowser by remember { mutableStateOf(false) }

    // CRITICAL FIX: Using a highly optimized StateList instead of rapidly recreating the list
    val logs = remember { mutableStateListOf("System Ready. Bulk Engine Engaged...") }
    var isScraping by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var selectedDirectoryUri by remember { mutableStateOf<Uri?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()

    val directoryPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            selectedDirectoryUri = uri
        }
    }

    fun addLog(msg: String) {
        // Enforce main thread to safely mutate Compose states and prevent IndexOutOfBounds
        coroutineScope.launch(Dispatchers.Main) {
            logs.add(msg)
            try {
                if (logs.isNotEmpty()) {
                    // CRITICAL FIX: Use instant snap instead of animation.
                    // Animating 50 rapid logs/sec is what crashed the LazyColumn!
                    listState.scrollToItem(logs.size - 1)
                }
            } catch (e: Exception) {
                // Silently swallow scroll measurement errors so they never kill the app
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Manga Scraper Pro - Bulk Engine", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = seriesUrlInput,
            onValueChange = { seriesUrlInput = it },
            label = { Text("Series URL (e.g., https://www.mangago.me/.../)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = baseNameInput,
            onValueChange = { baseNameInput = it },
            label = { Text("Base Folder Name (e.g., Naruto)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cookieInput,
                onValueChange = { cookieInput = it },
                label = { Text("Session Cookie") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { showLoginBrowser = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Login") }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { directoryPickerLauncher.launch(null) }) { Text("Select Save Folder") }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (selectedDirectoryUri != null) "Custom Folder Selected" else "Default (App Storage)",
                style = MaterialTheme.typography.bodySmall,
                color = if (selectedDirectoryUri != null) MaterialTheme.colorScheme.primary else Color.Gray
            )
        }

        Button(
            onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                    if (selectedDirectoryUri != null) {
                        intent.setDataAndType(selectedDirectoryUri, "vnd.android.document/root")
                        intent.addCategory(Intent.CATEGORY_DEFAULT)
                    } else {
                        val uriToOpen = Uri.parse(context.getExternalFilesDir(null)?.absolutePath)
                        intent.setDataAndType(uriToOpen, "*/*")
                    }
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.startActivity(Intent.createChooser(intent, "Open Folder"))
                } catch (e: Exception) {
                    addLog("[Warning] Android blocked direct folder access. Please open your 'My Files' app manually.")
                }
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
        ) { Text("📂 Open Master Folder") }

        Button(
            onClick = {
                if (seriesUrlInput.isBlank() || baseNameInput.isBlank()) { addLog("[Error] Series URL and Base Name are required."); return@Button }
                isScraping = true; progress = 0f
                addLog("\n=== STARTING BULK EXTRACTION ===")

                coroutineScope.launch {
                    try {
                        val scraper = HybridScraper(context)
                        val downloader = ChapterDownloader(context)

                        addLog("[System] Parsing Series with Jsoup...")
                        val chapterUrls = scraper.parseSeriesPage(seriesUrlInput, cookieInput) { msg -> addLog(msg) }
                        addLog("[System] Found ${chapterUrls.size} chapters.")

                        if (chapterUrls.isEmpty()) {
                            addLog("[Warning] 0 chapters found. Check URL or Cloudflare.")
                        } else {
                            chapterUrls.forEachIndexed { index, chapterUrl ->
                                val chNumber = index + 1
                                val formattedName = "${baseNameInput.trim()} - Ch $chNumber"
                                addLog("\n>>> INITIALIZING: $formattedName <<<")

                                val results = scraper.scrapeChapter(chapterUrl, cookieInput) { msg -> addLog(msg) }

                                if (results.isEmpty()) {
                                    addLog("[Warning] 0 images extracted. Check cookie or URL.")
                                } else {
                                    downloader.downloadChapterAsFolder(formattedName, results, chapterUrl, selectedDirectoryUri,
                                        onLog = { msg -> addLog(msg) },
                                        onProgress = { current, total -> progress = current.toFloat() / total.toFloat() }
                                    )
                                    addLog("[Success] Finished -> $formattedName")
                                }
                            }
                        }
                        addLog("\n=== ALL BATCHES COMPLETED ===")
                    } catch (e: Exception) { addLog("[Critical Failure] ${e.message}") } finally { isScraping = false }
                }
            },
            enabled = !isScraping, modifier = Modifier.fillMaxWidth()
        ) { Text(if (isScraping) "Processing..." else "START EXTRACTION") }

        if (isScraping && progress > 0f) LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) else Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).padding(8.dp)) {
            LazyColumn(state = listState) {
                items(logs) { logMsg ->
                    val textColor = when {
                        logMsg.contains("[Error]") || logMsg.contains("Failure") -> Color(0xFFF44747)
                        logMsg.contains("[Success]") || logMsg.contains("COMPLETED") -> Color(0xFF4CAF50)
                        logMsg.contains("[JS]") || logMsg.contains("[Ghost]") -> Color(0xFF569CD6)
                        logMsg.contains("[Network]") -> Color(0xFFDCDCAA)
                        else -> Color(0xFFD4D4D4)
                    }
                    Text(text = logMsg, color = textColor, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (showLoginBrowser) {
            val loginUrl = try {
                val uri = Uri.parse(seriesUrlInput)
                if (uri.host != null) "${uri.scheme}://${uri.host}" else "https://www.mangago.me/"
            } catch (e: Exception) { "https://www.mangago.me/" }

            Dialog(onDismissRequest = { showLoginBrowser = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("1. Solve Cloudflare\n2. Press Save Cookie", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                cookieInput = CookieManager.getInstance().getCookie(loginUrl) ?: ""
                                showLoginBrowser = false
                                addLog("[System] Cookie updated from visual browser!")
                            }) { Text("Save & Close") }
                        }
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = android.view.ViewGroup.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webViewClient = WebViewClient()
                                    webChromeClient = WebChromeClient()
                                    loadUrl(loginUrl)
                                }
                            },
                            onRelease = { webView ->
                                webView.destroy()
                            },
                            update = { },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}