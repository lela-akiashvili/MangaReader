package app.mangareader.mobile.ui.Screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mangareader.mobile.viewmodel.ScraperViewModel

@Composable
fun MainScreen(
    viewModel: ScraperViewModel,
    onNavigateToBrowser: () -> Unit,
    onNavigateToProgress: (String, String) -> Unit
) {
    val context = LocalContext.current

    val mangaUrl by viewModel.mangaUrl.collectAsState()
    val startChapter by viewModel.startChapter.collectAsState()
    val maxChapters by viewModel.maxChapters.collectAsState()
    val siteCookie by viewModel.siteCookie.collectAsState()
    val isSequential by viewModel.isSequential.collectAsState()

    val chapters by viewModel.chapters.collectAsState()
    val selectedFolderUri by viewModel.selectedFolderUri.collectAsState()
    val scrapeStatus by viewModel.scrapeStatus.collectAsState()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            viewModel.saveSelectedFolder(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Manga Downloader Setup", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = mangaUrl,
            onValueChange = { viewModel.updateMangaUrl(it) },
            label = { Text("Manga Series URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = startChapter,
                onValueChange = { viewModel.updateStartChapter(it) },
                label = { Text("Start Ch.") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            OutlinedTextField(
                value = maxChapters,
                onValueChange = { viewModel.updateMaxChapters(it) },
                label = { Text("Max Chs.") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }

        OutlinedTextField(
            value = siteCookie,
            onValueChange = { viewModel.saveCookie(it) },
            label = { Text("Site Cookie (Auto-filled or manual)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Sequential Download Mode (safer)")
            Switch(
                checked = isSequential,
                onCheckedChange = { viewModel.toggleSequentialMode(it) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { folderPickerLauncher.launch(null) }) {
                Text("Select Save Folder")
            }

            Text(
                text = if (selectedFolderUri != null) "Folder Selected ✓" else "No Folder Set",
                style = MaterialTheme.typography.bodySmall,
                color = if (selectedFolderUri != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = onNavigateToBrowser) {
                Text("Login / Get Cookie")
            }

            // UPDATED: Now passing context
            Button(
                onClick = { viewModel.fetchMangaChapters(context, mangaUrl) },
                enabled = mangaUrl.isNotBlank() && siteCookie.isNotBlank()
            ) {
                Text("Find Chapters")
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = "Status: $scrapeStatus",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Text("Found ${chapters.size} Chapters:")

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(chapters) { chapter ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(text = chapter.title, style = MaterialTheme.typography.bodyLarge)
                        if (chapter.date.isNotEmpty()) {
                            Text(text = chapter.date, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Button(
            onClick = { onNavigateToProgress(startChapter, maxChapters) },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedFolderUri != null && chapters.isNotEmpty()
        ) {
            Text("Start Download")
        }
    }
}