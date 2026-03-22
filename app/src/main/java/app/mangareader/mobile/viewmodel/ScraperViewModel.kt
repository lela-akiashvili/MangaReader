package app.mangareader.mobile.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mangareader.mobile.data.downloader.ParallelDownloader
import app.mangareader.mobile.data.model.Chapter
import app.mangareader.mobile.data.model.DownloadProgress
import app.mangareader.mobile.data.network.JsoupParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScraperViewModel : ViewModel() {

    private val _siteCookie = MutableStateFlow("")
    val siteCookie: StateFlow<String> = _siteCookie.asStateFlow()

    private val _userAgent = MutableStateFlow("")
    val userAgent: StateFlow<String> = _userAgent.asStateFlow()

    private val _mangaUrl = MutableStateFlow("")
    val mangaUrl: StateFlow<String> = _mangaUrl.asStateFlow()

    private val _startChapter = MutableStateFlow("")
    val startChapter: StateFlow<String> = _startChapter.asStateFlow()

    private val _maxChapters = MutableStateFlow("")
    val maxChapters: StateFlow<String> = _maxChapters.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _scrapeStatus = MutableStateFlow("Idle")
    val scrapeStatus: StateFlow<String> = _scrapeStatus.asStateFlow()

    private val _selectedFolderUri = MutableStateFlow<Uri?>(null)
    val selectedFolderUri: StateFlow<Uri?> = _selectedFolderUri.asStateFlow()

    private val _downloadProgress = MutableStateFlow(DownloadProgress("Ready to download..."))
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    private val _isSequential = MutableStateFlow(false)
    val isSequential: StateFlow<Boolean> = _isSequential.asStateFlow()

    fun saveCookie(cookie: String) { _siteCookie.value = cookie }
    fun saveUserAgent(agent: String) { _userAgent.value = agent }
    fun updateMangaUrl(url: String) { _mangaUrl.value = url }
    fun updateStartChapter(ch: String) { _startChapter.value = ch }
    fun updateMaxChapters(max: String) { _maxChapters.value = max }
    fun saveSelectedFolder(uri: Uri?) { _selectedFolderUri.value = uri }
    fun toggleSequentialMode(enabled: Boolean) { _isSequential.value = enabled }

    // UPDATED: Now requires context
    fun fetchMangaChapters(context: Context, url: String) {
        viewModelScope.launch {
            val currentCookie = _siteCookie.value
            val currentUserAgent = _userAgent.value
            _scrapeStatus.value = "Connecting to site..."

            val fetchedChapters = JsoupParser.fetchChapters(context, url, currentCookie, currentUserAgent) { statusMsg ->
                _scrapeStatus.value = statusMsg
            }
            _chapters.value = fetchedChapters
        }
    }

    fun startDownloading(context: Context, startChapterInput: String, maxChaptersInput: String) {
        val folderUri = _selectedFolderUri.value
        if (folderUri == null) {
            _downloadProgress.value = DownloadProgress("Error: Please select a save folder first!")
            return
        }

        val allChapters = _chapters.value
        if (allChapters.isEmpty()) {
            _downloadProgress.value = DownloadProgress("Error: No chapters found to download.")
            return
        }

        val startIdx = (startChapterInput.toIntOrNull() ?: 1) - 1
        val maxCount = maxChaptersInput.toIntOrNull() ?: allChapters.size

        val safeStart = startIdx.coerceIn(0, allChapters.size)
        val safeEnd = (safeStart + maxCount).coerceAtMost(allChapters.size)

        val chaptersToDownload = allChapters.subList(safeStart, safeEnd)

        if (chaptersToDownload.isEmpty()) {
            _downloadProgress.value = DownloadProgress("Error: Limits resulted in 0 chapters.")
            return
        }

        viewModelScope.launch {
            ParallelDownloader.downloadChapters(
                context = context,
                chaptersToDownload = chaptersToDownload,
                baseFolderUri = folderUri,
                cookieString = _siteCookie.value,
                userAgent = _userAgent.value,
                isSequential = _isSequential.value,
                progressFlow = _downloadProgress
            )
        }
    }
}