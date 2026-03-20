package app.mangareader.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.mangareader.mobile.scraper.WebViewScraper
import app.mangareader.mobile.scraper.ChapterDownloader
import app.mangareader.mobile.ui.theme.MangaReaderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MangaReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScraperScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperScreen() {
    var urlInput by remember { mutableStateOf("https://example-manga-site.com/chapter/1") }
    var logs by remember { mutableStateOf(listOf<String>("System Ready.")) }
    var isScraping by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    fun addLog(msg: String) {
        logs = logs + msg
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Manga Scraper Engine (Android)", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("Chapter URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (urlInput.isBlank()) return@Button
                isScraping = true
                progress = 0f
                addLog("--> 1. Starting extraction for: $urlInput")

                coroutineScope.launch {
                    try {
                        val scraper = WebViewScraper(context)
                        val results = scraper.scrapeChapter(urlInput)

                        addLog("<-- 2. Extraction Complete. Found ${results.size} pages.")
                        addLog("--> 3. Starting Download & Zipping...")

                        val downloader = ChapterDownloader(context)

                        // Use a dummy title for now.
                        val cbzFile = downloader.downloadChapterAsCbz(
                            chapterTitle = "Downloaded_Chapter",
                            pages = results,
                            sourceUrl = urlInput
                        ) { current, total ->
                            progress = current.toFloat() / total.toFloat()
                            if (current % 5 == 0 || current == total) {
                                addLog("   > Saved page $current of $total")
                            }
                        }

                        if (cbzFile != null) {
                            addLog("SUCCESS: Saved to ${cbzFile.absolutePath}")
                        } else {
                            addLog("ERROR: Failed to create CBZ file.")
                        }

                    } catch (e: Exception) {
                        addLog("Critical Error: ${e.message}")
                    } finally {
                        isScraping = false
                    }
                }
            },
            enabled = !isScraping,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isScraping) "Processing..." else "START EXTRACTION")
        }

        if (isScraping && progress > 0f) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Log Console
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
                .padding(8.dp)
        ) {
            LazyColumn {
                items(logs) { logMsg ->
                    Text(
                        text = logMsg,
                        color = Color(0xFFD4D4D4),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}