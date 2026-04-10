package app.mangareader.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import app.mangareader.mobile.ui.screens.HomeScreen
import app.mangareader.mobile.ui.screens.ReaderScreen
import app.mangareader.mobile.ui.screens.ScraperScreen
import app.mangareader.mobile.ui.screens.StartupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)
        val savedUriString = prefs.getString("root_folder_uri", null)

        val initialScreen = if (savedUriString != null) "home" else "startup"
        val initialUri = if (savedUriString != null) Uri.parse(savedUriString) else null

        if (initialUri != null) {
            ScrapeState.outputDirectoryUri.value = initialUri
        }

        setContent {
            var isDarkTheme by remember { mutableStateOf(true) }

            MaterialTheme(colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()) {
                var currentScreen by remember { mutableStateOf(initialScreen) }
                var selectedUri by remember { mutableStateOf<Uri?>(initialUri) }

                var selectedSeriesTitle by remember { mutableStateOf("") }
                var selectedSeriesFile by remember { mutableStateOf<DocumentFile?>(null) }

                val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    if (uri != null) {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                        var finalUri = uri
                        val pickedFolder = DocumentFile.fromTreeUri(this, uri)
                        val mangasSubDir = pickedFolder?.listFiles()?.find { it.isDirectory && it.name.equals("mangas", ignoreCase = true) }

                        if (mangasSubDir != null) {
                            finalUri = mangasSubDir.uri
                        }

                        prefs.edit().putString("root_folder_uri", finalUri.toString()).apply()
                        selectedUri = finalUri
                        ScrapeState.outputDirectoryUri.value = finalUri
                        currentScreen = "home"
                    }
                }

                when (currentScreen) {
                    "startup" -> {
                        StartupScreen(
                            onSyncClick = { folderPickerLauncher.launch(null) },
                            onThemeToggle = { isDarkTheme = !isDarkTheme },
                            isDark = isDarkTheme
                        )
                    }
                    "home" -> {
                        if (selectedUri != null) {
                            HomeScreen(
                                rootFolderUri = selectedUri!!,
                                onSeriesClick = { series ->
                                    selectedSeriesTitle = series.title
                                    selectedSeriesFile = series.documentFile
                                    currentScreen = "reader"
                                },
                                onThemeToggle = { isDarkTheme = !isDarkTheme },
                                isDark = isDarkTheme,
                                onResyncClick = { folderPickerLauncher.launch(null) },
                                onScraperClick = { currentScreen = "scraper" }
                            )
                        }
                    }
                    "scraper" -> {
                        ScraperScreen(onBackClick = { currentScreen = "home" })
                    }
                    "reader" -> {
                        var finalFile = selectedSeriesFile

                        // Dynamically re-fetch the DocumentFile if it was omitted from the JSON cache
                        if (finalFile == null && selectedUri != null) {
                            val root = DocumentFile.fromTreeUri(this, selectedUri!!)
                            finalFile = root?.findFile(selectedSeriesTitle)
                        }

                        if (finalFile != null) {
                            ReaderScreen(
                                seriesTitle = selectedSeriesTitle,
                                seriesFile = finalFile,
                                onBackClick = { currentScreen = "home" }
                            )
                        }
                    }
                }
            }
        }
    }
}