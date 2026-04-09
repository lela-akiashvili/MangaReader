package app.mangareader.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setContent {
            MaterialTheme {
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

@Composable
fun ScraperScreen() {
    val context = LocalContext.current
    val isScraping by ScrapeState.isScraping.collectAsState()
    val logs by ScrapeState.logs.collectAsState()
    val outputUri by ScrapeState.outputDirectoryUri.collectAsState()
    val conflictFolder by ScrapeState.showConflictDialog.collectAsState()

    var url by remember { mutableStateOf("https://www.mangago.me/read-manga/threads_of_love/") }
    var startChapter by remember { mutableStateOf("1") }
    var maxChapters by remember { mutableStateOf("") } // Default empty
    var zipOnSuccess by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var currentDialogUrl by remember { mutableStateOf("") }
    var showStartWarning by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ScrapeState.outputDirectoryUri.value = uri
        }
    }

    // Function to physically launch the scraping service
    fun startScraping() {
        ScrapeState.isPaused.value = false
        ScrapeState.isCancelled.value = false
        val serviceIntent = Intent(context, ScraperService::class.java).apply {
            putExtra("URL", url)
            putExtra("START_CHAPTER", startChapter.toIntOrNull() ?: 1)
            // Use 99999 as a safe "Download All" trigger to prevent negative index crashes
            putExtra("MAX_CHAPTERS", maxChapters.toIntOrNull() ?: 99999)
            putExtra("ZIP_ON_SUCCESS", zipOnSuccess)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Manga Scraper", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Series URL") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isScraping
        )

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startChapter,
                onValueChange = { startChapter = it },
                label = { Text("Start Chapter") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                enabled = !isScraping
            )
            OutlinedTextField(
                value = maxChapters,
                onValueChange = { maxChapters = it },
                label = { Text("Max (Leave empty for All)") },
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

                    // PRE-CHECK: See if the parent folder is dirty
                    val rootFolder = DocumentFile.fromTreeUri(context, outputUri!!)
                    if (rootFolder != null && rootFolder.listFiles().any { it.isDirectory }) {
                        showStartWarning = true
                    } else {
                        startScraping()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = outputUri != null
            ) {
                Text("Start Scraping")
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isPaused by ScrapeState.isPaused.collectAsState()
                Button(
                    onClick = { ScrapeState.isPaused.value = !isPaused },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isPaused) "Resume" else "Pause")
                }
                Button(
                    onClick = { ScrapeState.isCancelled.value = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Logs:", style = MaterialTheme.typography.titleMedium)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(logs) { logMsg ->
                Text(text = logMsg, color = Color.Green, style = MaterialTheme.typography.bodySmall)
                Divider(color = Color.DarkGray, thickness = 0.5.dp)
            }
        }
    }

    // --- DIALOGS ---

    // 1. Initial Dirty Folder Warning
    if (showStartWarning) {
        AlertDialog(
            onDismissRequest = { showStartWarning = false },
            title = { Text("Folder Not Empty") },
            text = { Text("There are already sub-folders inside your selected output directory. Are you sure you want to proceed?") },
            confirmButton = {
                Button(onClick = {
                    showStartWarning = false
                    startScraping()
                }) { Text("Proceed") }
            },
            dismissButton = {
                Button(onClick = { showStartWarning = false }) { Text("Cancel") }
            }
        )
    }

    // 2. Live Folder Conflict Resolution (Triggered by Service)
    if (conflictFolder != null) {
        AlertDialog(
            onDismissRequest = { /* Must explicitly click a button */ },
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
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }

    // 3. Login Dialog
    if (showLoginDialog) {
        AlertDialog(
            onDismissRequest = { showLoginDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.95f),
            confirmButton = {
                Button(onClick = {
                    showLoginDialog = false
                    ScrapeState.log("Browser closed. Cookies saved automatically.")
                }) { Text("Done") }
            },
            dismissButton = {
                Button(onClick = {
                    if (currentDialogUrl.isNotEmpty()) {
                        url = currentDialogUrl
                    }
                    showLoginDialog = false
                    ScrapeState.log("Grabbed URL: $currentDialogUrl")
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
                                    if (url != null) {
                                        currentDialogUrl = url
                                    }
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