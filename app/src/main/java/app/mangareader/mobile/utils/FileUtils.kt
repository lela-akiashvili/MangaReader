package app.mangareader.mobile.utils

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.mangareader.mobile.data.MangaChapter
import app.mangareader.mobile.data.MangaSeries
import app.mangareader.mobile.data.MangaSeriesCache
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

// NEW: Data class for our permanent USB Tracker
data class MangaTrackerEntry(
    val title: String,
    var url: String,
    var lastChapterName: String,
    var lastChapterNumber: Int
)

object FileUtils {

    /**
     * Optimized folder query using raw ContentResolver.
     * Bypasses the slow DocumentFile.listFiles() overhead.
     */
    private fun queryChildren(context: Context, parentUri: Uri): List<Pair<Uri, String>> {
        val result = mutableListOf<Pair<Uri, String>>()
        try {
            // Safely extract the Document ID whether it's a Tree URI or a standard URI
            val docId = if (DocumentsContract.isDocumentUri(context, parentUri)) {
                DocumentsContract.getDocumentId(parentUri)
            } else {
                DocumentsContract.getTreeDocumentId(parentUri)
            }

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, docId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val childDocId = cursor.getString(idIdx)
                    val name = cursor.getString(nameIdx)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(parentUri, childDocId)
                    result.add(uri to name)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun getCachedLibrary(context: Context, rootUri: Uri): List<MangaSeries> {
        val file = File(context.cacheDir, "lib_cache_${rootUri.toString().hashCode()}.json")
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<MangaSeriesCache>>() {}.type
            val cacheList: List<MangaSeriesCache> = Gson().fromJson(json, type)

            cacheList.map {
                val folderUri = Uri.parse(it.folderUriStr)
                MangaSeries(
                    title = it.title,
                    folderUri = folderUri,
                    documentFile = DocumentFile.fromTreeUri(context, folderUri),
                    downloadTimestamp = it.lastModified,
                    coverUri = it.coverUriStr?.let { uri -> Uri.parse(uri) }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * High-speed sync for massive USB drives (400+ series).
     * Uses Fast-Sync logic: Indices first, generates covers on-demand or in background.
     */
    fun syncLibrary(context: Context, rootUri: Uri): List<MangaSeries> {
        val list = mutableListOf<MangaSeries>()

        // Use optimized query instead of DocumentFile.listFiles()
        val children = queryChildren(context, rootUri)

        children.forEach { (uri, name) ->

            // NEW: Explicitly ignore our JSON tracker files so they don't show up on the Home Screen!
            if (name.endsWith(".json", ignoreCase = true)) return@forEach

            val isZip = name.endsWith(".zip", ignoreCase = true) || name.endsWith(".cbz", ignoreCase = true)
            val cleanTitle = if (isZip) name.substringBeforeLast(".") else name

            // Optimization: Don't fetch covers during the initial heavy loop.
            list.add(MangaSeries(
                title = cleanTitle,
                folderUri = uri,
                documentFile = DocumentFile.fromTreeUri(context, uri),
                downloadTimestamp = System.currentTimeMillis()
            ))
        }

        updateCacheFile(context, rootUri, list)
        initializeTrackerIfMissing(context, rootUri, list)
        return list
    }

    /**
     * Updates the local JSON cache file for a specific drive.
     */
    fun updateCacheFile(context: Context, rootUri: Uri, list: List<MangaSeries>) {
        try {
            val cacheList = list.map { MangaSeriesCache(it.title, it.folderUri.toString(), it.downloadTimestamp, it.coverUri?.toString()) }
            val file = File(context.cacheDir, "lib_cache_${rootUri.toString().hashCode()}.json")
            file.writeText(Gson().toJson(cacheList))
        } catch (e: Exception) {}
    }

    /**
     * Background Cover Fetcher logic:
     * Extracts the first image it finds within a series folder or ZIP.
     */
    fun fetchCoverImage(context: Context, series: MangaSeries): Uri? {
        val seriesFile = series.documentFile ?: return null
        val cacheFile = File(context.cacheDir, "cover_${series.title.hashCode()}.jpg")
        if (cacheFile.exists()) return Uri.fromFile(cacheFile)

        try {
            if (seriesFile.name?.endsWith(".zip", true) == true) {
                // Seek into ZIP for the first image
                context.contentResolver.openFileDescriptor(seriesFile.uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        CommonsZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                            val firstImgEntry = zip.entries.toList().firstOrNull {
                                !it.isDirectory && isImageFile(it.name)
                            }
                            if (firstImgEntry != null) {
                                FileOutputStream(cacheFile).use { fos ->
                                    zip.getInputStream(firstImgEntry).use { it.copyTo(fos) }
                                }
                                return Uri.fromFile(cacheFile)
                            }
                        }
                    }
                }
            } else {
                // Peek into the first folder of a nested series directory
                val children = queryChildren(context, seriesFile.uri)
                val firstFolder = children.firstOrNull() ?: return null

                // Get images inside that first folder
                val pages = queryChildren(context, firstFolder.first)
                val firstPage = pages.find { isImageFile(it.second) }

                if (firstPage != null) {
                    context.contentResolver.openInputStream(firstPage.first)?.use { input ->
                        FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                    }
                    return Uri.fromFile(cacheFile)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getChapters(context: Context, seriesFile: DocumentFile): List<MangaChapter> {
        val list = mutableListOf<MangaChapter>()

        if (seriesFile.name?.endsWith(".zip", true) == true) {
            try {
                context.contentResolver.openFileDescriptor(seriesFile.uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        CommonsZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                            val paths = zip.entries.toList()
                                .filter { !it.isDirectory && isImageFile(it.name) }
                                .map { it.name.replace("\\", "/").substringBeforeLast("/", "") }
                                .distinct()

                            paths.forEach { path ->
                                val displayName = path.substringAfterLast("/")
                                list.add(MangaChapter(displayName, null, seriesFile, path))
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        } else {
            val children = queryChildren(context, seriesFile.uri)
            children.forEach { (uri, name) ->
                list.add(MangaChapter(name, DocumentFile.fromTreeUri(context, uri), null))
            }
        }

        return list.sortedWith(compareBy({
            Regex("\\d+").find(it.name)?.value?.toIntOrNull() ?: 0
        }, { it.name }))
    }

    fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".webp") ||
                lower.endsWith(".jpeg") || lower.endsWith(".avif")
    }

    // --- NEW TRACKER LOGIC ---

    fun getTracker(context: Context, rootUri: Uri): MutableList<MangaTrackerEntry> {
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return mutableListOf()
        val trackerFile = rootDoc.findFile("manga_tracker.json") ?: return mutableListOf()
        return try {
            val json = context.contentResolver.openInputStream(trackerFile.uri)?.bufferedReader()?.use { it.readText() } ?: "[]"
            val type = object : TypeToken<MutableList<MangaTrackerEntry>>() {}.type
            Gson().fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveTracker(context: Context, rootUri: Uri, trackerList: List<MangaTrackerEntry>) {
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return
        var trackerFile = rootDoc.findFile("manga_tracker.json")
        if (trackerFile == null) {
            trackerFile = rootDoc.createFile("application/json", "manga_tracker.json")
        }
        try {
            trackerFile?.uri?.let { uri ->
                context.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(Gson().toJson(trackerList).toByteArray())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initializeTrackerIfMissing(context: Context, rootUri: Uri, library: List<MangaSeries>) {
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return
        if (rootDoc.findFile("manga_tracker.json") != null) return // Only run if it doesn't exist

        val trackerList = mutableListOf<MangaTrackerEntry>()
        for (series in library) {
            if (series.documentFile == null) continue
            val chapters = getChapters(context, series.documentFile)
            if (chapters.isNotEmpty()) {
                val highestChap = chapters.last()
                val nameRaw = highestChap.name

                var exactLocalName = nameRaw
                if (exactLocalName.contains("-")) {
                    exactLocalName = exactLocalName.substringAfter("-").trim()
                }
                exactLocalName = exactLocalName.substringBeforeLast(".").trim()

                val localNumMatch = Regex("\\d+").find(nameRaw)
                val chapterNumber = localNumMatch?.value?.toIntOrNull() ?: 1

                trackerList.add(MangaTrackerEntry(
                    title = series.title,
                    url = "", // Will be filled naturally when worker checks it
                    lastChapterName = exactLocalName,
                    lastChapterNumber = chapterNumber
                ))
            }
        }
        saveTracker(context, rootUri, trackerList)
    }

    fun updateTrackerEntry(context: Context, rootUri: Uri, title: String, url: String, chapterName: String, chapterNumber: Int) {
        val tracker = getTracker(context, rootUri)
        val existing = tracker.find { it.title.equals(title, ignoreCase = true) }

        if (existing != null) {
            // Only update if the chapter number is higher
            if (chapterNumber >= existing.lastChapterNumber) {
                existing.lastChapterName = chapterName
                existing.lastChapterNumber = chapterNumber
                if (url.isNotEmpty()) existing.url = url
            }
        } else {
            tracker.add(MangaTrackerEntry(title, url, chapterName, chapterNumber))
        }
        saveTracker(context, rootUri, tracker)
    }

    fun bindUrlToTracker(context: Context, rootUri: Uri, title: String, url: String) {
        val tracker = getTracker(context, rootUri)
        val existing = tracker.find { it.title.equals(title, ignoreCase = true) }

        if (existing != null) {
            existing.url = url
        } else {
            // Fallback: If it somehow doesn't exist yet, create a blank entry with the URL
            tracker.add(MangaTrackerEntry(title, url, "", 0))
        }
        saveTracker(context, rootUri, tracker)
    }
}