package app.mangareader.mobile

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A simple singleton to share state between the Service (which does the work)
 * and the MainActivity (which shows the UI).
 */
object ScrapeState {
    val isScraping = MutableStateFlow(false)
    val logs = MutableStateFlow<List<String>>(emptyList())
    val outputDirectoryUri = MutableStateFlow<Uri?>(null)

    fun log(message: String) {
        // Keeps the last 100 logs to prevent memory issues
        val currentLogs = logs.value.toMutableList()
        currentLogs.add(0, message) // Add to top
        if (currentLogs.size > 100) {
            currentLogs.removeLast()
        }
        logs.value = currentLogs
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}