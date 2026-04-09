package app.mangareader.mobile.data

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class MangaSeries(
    val title: String,
    val folderUri: Uri,
    val documentFile: DocumentFile,
    val downloadTimestamp: Long = 0L
)

data class MangaChapter(
    val name: String,
    val file: DocumentFile?,
    val parentZip: DocumentFile?,
    val zipEntryPath: String = "" // Tracks exact nested folder path inside a master ZIP
)