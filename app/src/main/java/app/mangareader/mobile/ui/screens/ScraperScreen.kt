package app.mangareader.mobile.ui.screens

import androidx.compose.ui.unit.sp
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import app.mangareader.mobile.ConflictAction
import app.mangareader.mobile.ScrapeState
import app.mangareader.mobile.ScraperService

private val DarkBlueStart = Color(0xFF0B101E)
private val PurpleEnd = Color(0xFF311545)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val isScraping by ScrapeState.isScraping.collectAsState()
    val logs by ScrapeState.logs.collectAsState()
    val outputUri by ScrapeState.outputDirectoryUri.collectAsState()
    val conflictFolder by ScrapeState.showConflictDialog.collectAsState()

    var url by remember { mutableStateOf("https://www.mangago.me/read-manga/threads_of_love/") }
    var seriesTitle by remember { mutableStateOf("") }
    var startChapter by remember { mutableStateOf("1") }
    var maxChapters by remember { mutableStateOf("") }
    var zipOnSuccess by remember { mutableStateOf(true) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var currentDialogUrl by remember { mutableStateOf("") }
    var showStartWarning by remember { mutableStateOf(false) }
    var validCookie by remember { mutableStateOf("") }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ScrapeState.outputDirectoryUri.value = uri
        }
    }

    fun startScraping() {
        ScrapeState.isPaused.value = false
        ScrapeState.isCancelled.value = false
        val serviceIntent = Intent(context, ScraperService::class.java).apply {
            putExtra("URL", url)
            putExtra("SERIES_TITLE", seriesTitle.ifEmpty { "Unknown_Series" })
            putExtra("START_CHAPTER", startChapter.toIntOrNull() ?: 1)
            putExtra("MAX_CHAPTERS", maxChapters.toIntOrNull() ?: 99999)
            putExtra("ZIP_ON_SUCCESS", zipOnSuccess)
            putExtra("COOKIE", validCookie)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    val headerGradient = Brush.horizontalGradient(colors = listOf(DarkBlueStart, PurpleEnd))

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerGradient)
                    .statusBarsPadding()
                    .padding(10.dp)
                    .heightIn(min = 45.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "← Back",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onBackClick() }.padding(8.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = "Manga Scraper Pro", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxSize()) {

            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Series URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isScraping
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = seriesTitle, onValueChange = { seriesTitle = it },
                label = { Text("Series Title (Creates Master Folder)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isScraping
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startChapter, onValueChange = { startChapter = it },
                    label = { Text("Start Ch.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    enabled = !isScraping
                )
                OutlinedTextField(
                    value = maxChapters, onValueChange = { maxChapters = it },
                    label = { Text("Max Ch.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    enabled = !isScraping
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = { showLoginDialog = true }, enabled = !isScraping) {
                    Text("Login to Mangago")
                }
                Button(onClick = { folderLauncher.launch(null) }, enabled = !isScraping) {
                    Text(if (outputUri == null) "Set Output Folder" else "Folder Selected ✓")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!isScraping) {
                Button(
                    onClick = {
                        if (outputUri == null) {
                            Toast.makeText(context, "Please select an output folder first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val rootFolder = DocumentFile.fromTreeUri(context, outputUri!!)
                        if (rootFolder != null && rootFolder.listFiles().any { it.isDirectory }) {
                            showStartWarning = true
                        } else {
                            startScraping()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = outputUri != null
                ) { Text("Start Background Scraping") }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isPaused by ScrapeState.isPaused.collectAsState()
                    Button(onClick = { ScrapeState.isPaused.value = !isPaused }, modifier = Modifier.weight(1f)) {
                        Text(if (isPaused) "Resume" else "Pause")
                    }
                    Button(
                        onClick = { ScrapeState.isCancelled.value = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Stop") }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Live Logs:", style = MaterialTheme.typography.titleMedium)

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(logs) { logMsg ->
                    Text(text = logMsg, color = Color.Green, fontSize = 12.sp)
                    Divider(color = Color.DarkGray, thickness = 0.5.dp)
                }
            }
        }
    }

    if (showStartWarning) {
        AlertDialog(
            onDismissRequest = { showStartWarning = false },
            title = { Text("Folder Not Empty") },
            text = { Text("There are already sub-folders inside your selected output directory. Are you sure you want to proceed?") },
            confirmButton = { Button(onClick = { showStartWarning = false; startScraping() }) { Text("Proceed") } },
            dismissButton = { Button(onClick = { showStartWarning = false }) { Text("Cancel") } }
        )
    }

    if (conflictFolder != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Folder Already Exists") },
            text = {
                Column {
                    Text("The folder '$conflictFolder' already exists. What would you like to do?")
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        ScrapeState.conflictResolution?.complete(ConflictAction.OVERWRITE)
                        ScrapeState.showConflictDialog.value = null
                    }) { Text("Overwrite This Chapter") }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        ScrapeState.conflictResolution?.complete(ConflictAction.SKIP)
                        ScrapeState.showConflictDialog.value = null
                    }) { Text("Skip This Chapter") }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        ScrapeState.conflictResolution?.complete(ConflictAction.OVERWRITE_ALL)
                        ScrapeState.showConflictDialog.value = null
                    }) { Text("Overwrite All Future Conflicts") }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        ScrapeState.conflictResolution?.complete(ConflictAction.SKIP_ALL)
                        ScrapeState.showConflictDialog.value = null
                    }) { Text("Skip All Future Conflicts") }
                }
            },
            confirmButton = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }

    if (showLoginDialog) {
        AlertDialog(
            onDismissRequest = { showLoginDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.95f),
            confirmButton = {
                Button(onClick = {
                    val cookies = CookieManager.getInstance().getCookie("https://www.mangago.me")
                    if (!cookies.isNullOrEmpty()) validCookie = cookies
                    showLoginDialog = false
                    ScrapeState.log("[System] Browser closed. Cookies saved.")
                }) { Text("Save & Close") }
            },
            dismissButton = {
                Button(onClick = {
                    if (currentDialogUrl.isNotEmpty()) url = currentDialogUrl
                    showLoginDialog = false
                }) { Text("Use Current URL") }
            },
            title = { Text("Login & Browse") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(500.dp)) {
                    AndroidView(factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (url != null) currentDialogUrl = url
                                }
                            }
                            loadUrl("https://www.mangago.me/home/accounts/login/")
                        }
                    })
                }
            }
        )
    }
}