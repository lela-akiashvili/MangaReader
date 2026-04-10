package app.mangareader.mobile.data

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class MangaSeries(
    val title: String,
    val folderUri: Uri,
    val documentFile: DocumentFile?, // Made Nullable to support instant caching
    val downloadTimestamp: Long = 0L,
    val coverUri: Uri? = null
)

data class MangaChapter(
    val name: String,
    val file: DocumentFile?,
    val parentZip: DocumentFile?,
    val zipEntryPath: String = "" // Tracks exact nested folder path inside a master ZIP
)

// DTO for Multi-Drive JSON Serialization
data class MangaSeriesCache(
    val title: String,
    val folderUriStr: String,
    val lastModified: Long,
    val coverUriStr: String?
)