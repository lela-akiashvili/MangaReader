package app.mangareader.mobile

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

enum class ConflictAction {
    OVERWRITE, SKIP, OVERWRITE_ALL, SKIP_ALL
}

object ScrapeState {
    val isScraping = MutableStateFlow(false)
    val logs = MutableStateFlow<List<String>>(emptyList())
    val outputDirectoryUri = MutableStateFlow<Uri?>(null)

    // UI Interaction states for Folder Conflicts
    val showConflictDialog = MutableStateFlow<String?>(null)
    var conflictResolution: CompletableDeferred<ConflictAction>? = null

    fun log(message: String) {
        val currentLogs = logs.value.toMutableList()
        currentLogs.add(0, message)
        if (currentLogs.size > 100) {
            currentLogs.removeLast()
        }
        logs.value = currentLogs
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}