package app.mangareader.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Notification Permission for Android 13+
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

    var url by remember { mutableStateOf("https://www.mangago.me/read-manga/threads_of_love/") }
    var startChapter by remember { mutableStateOf("1") }
    var maxChapters by remember { mutableStateOf("3") }
    var showLoginDialog by remember { mutableStateOf(false) }

    // Launcher for selecting output folder
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ScrapeState.outputDirectoryUri.value = uri
        }
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
                label = { Text("Max Chapters") },
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

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (outputUri == null) {
                    Toast.makeText(context, "Please select an output folder first", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                // Start Foreground Service
                val serviceIntent = Intent(context, ScraperService::class.java).apply {
                    putExtra("URL", url)
                    putExtra("START_CHAPTER", startChapter.toIntOrNull() ?: 1)
                    putExtra("MAX_CHAPTERS", maxChapters.toIntOrNull() ?: 1)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isScraping && outputUri != null
        ) {
            Text(if (isScraping) "Scraping in Progress..." else "Start Scraping")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Logs:", style = MaterialTheme.typography.titleMedium)

        // Log Console
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

    // Login Dialog with visible WebView
    if (showLoginDialog) {
        AlertDialog(
            onDismissRequest = { showLoginDialog = false },
            confirmButton = {
                Button(onClick = {
                    showLoginDialog = false
                    ScrapeState.log("Login dialog closed. Cookies saved automatically.")
                }) { Text("Done") }
            },
            title = { Text("Login to grab Cookies") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                    AndroidView(factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl("https://www.mangago.me/home/accounts/login/")
                        }
                    })
                }
            }
        )
    }
}