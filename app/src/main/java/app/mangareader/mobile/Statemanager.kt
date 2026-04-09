package app.mangareader.mobile

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class ConflictAction {
    OVERWRITE, SKIP, OVERWRITE_ALL, SKIP_ALL
}

object ScrapeState {
    val isScraping = MutableStateFlow(false)
    val isPaused = MutableStateFlow(false)
    val isCancelled = MutableStateFlow(false)
    val logs = MutableStateFlow<List<String>>(emptyList())
    val outputDirectoryUri = MutableStateFlow<Uri?>(null)

    // UI Interaction states for Folder Conflicts
    val showConflictDialog = MutableStateFlow<String?>(null)
    var conflictResolution: CompletableDeferred<ConflictAction>? = null

    // NEW: Background Notification Trigger for the Reader
    val latestDownload = MutableSharedFlow<String>(extraBufferCapacity = 1)

    fun log(message: String) {
        val currentLogs = logs.value.toMutableList()
        currentLogs.add(0, message)
        if (currentLogs.size > 100) {
            currentLogs.removeLast()
        }
        logs.value = currentLogs

        // Silently notify the reader if a batch or chapter finishes
        if (message.contains("downloaded perfectly") || message.contains("SUCCESSFULLY")) {
            latestDownload.tryEmit(message.substringAfter("]").trim())
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}