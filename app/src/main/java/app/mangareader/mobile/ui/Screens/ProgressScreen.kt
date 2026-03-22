package app.mangareader.mobile.ui.Screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mangareader.mobile.viewmodel.ScraperViewModel

@Composable
fun ProgressScreen(
    viewModel: ScraperViewModel,
    onBackClicked: () -> Unit
) {
    // Observe the live progress updates from the parallel downloader
    val progress by viewModel.downloadProgress.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Download Engine", style = MaterialTheme.typography.headlineMedium)

        // The main log message (e.g., "Scraping images for Vol.2 Ch.4...")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                text = progress.logMessage,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Divider()

        // Detailed Statistics
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Chapters in Queue:")
            Text("${progress.chaptersInQueue}", fontWeight = FontWeight.Bold)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Current Chapter Pages:")
            Text("${progress.imagesDownloadedForCurrentChapter} / ${progress.totalImagesInCurrentChapter}", fontWeight = FontWeight.Bold)
        }

        // A visual progress bar for the current chapter's images
        if (progress.totalImagesInCurrentChapter > 0) {
            val progressFloat = progress.imagesDownloadedForCurrentChapter.toFloat() / progress.totalImagesInCurrentChapter.toFloat()
            LinearProgressIndicator(
                progress = progressFloat,
                modifier = Modifier.fillMaxWidth().height(12.dp)
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(12.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = onBackClicked, modifier = Modifier.fillMaxWidth()) {
            Text("Back to Setup")
        }
    }
}