package app.mangareader.mobile.data.model

data class DownloadProgress(
    val logMessage: String,
    val chaptersFound: Int = 0,
    val chaptersInQueue: Int = 0,
    val totalImagesInCurrentChapter: Int = 0,
    val imagesDownloadedForCurrentChapter: Int = 0
)