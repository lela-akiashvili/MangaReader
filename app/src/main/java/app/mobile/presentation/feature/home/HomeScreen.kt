package app.mobile.presentation.feature.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells.Adaptive
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.designsystem.component.MangaCard
import app.designsystem.theme.HeaderFooterGradient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    rootFolderUri: Uri,
    viewModel: HomeViewModel,
    onSeriesClick: (String, String) -> Unit,
    onScraperClick: () -> Unit,
    onNotificationsClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(rootFolderUri) {
        viewModel.loadLibrary(rootFolderUri.toString())
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HeaderFooterGradient)
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Manga Reader", color = Color.White)
                Row {
                    IconButton(onClick = onScraperClick) { Text("⚙️") }
                    IconButton(onClick = onNotificationsClick) { Text("🔔") }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    items(uiState.mangaList, key = { it.folderUriStr }) { series ->
                        MangaCard(
                            title = series.title,
                            coverUri = series.coverUriStr?.let { Uri.parse(it) },
                            chapterNumber = 1,
                            onClick = { onSeriesClick(series.title, series.folderUriStr) },
                            onLongClick = { }
                        )
                    }
                }
            }
        }
    }
}