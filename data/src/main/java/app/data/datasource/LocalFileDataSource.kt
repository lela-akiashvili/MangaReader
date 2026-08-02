package app.data.datasource

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.data.dto.MangaSeriesCacheDto
import app.domain.model.MangaChapter
import app.domain.model.MangaSeries
import app.domain.model.MangaTrackerEntry
import com.google.gson.Gson
import java.io.File
import kotlin.collections.map
import kotlin.collections.toMutableList

class LocalFileDataSource(private val context: Context) {

    fun getCachedLibrary(rootUriStr: String): List<MangaSeries> {
        val rootUri = Uri.parse(rootUriStr)
        val file = File(context.cacheDir, "lib_cache_${rootUri.toString().hashCode()}.json")
        if (!file.exists()) return emptyList()

        return try {
            val json = file.readText()
            val type = object : com.google.gson.reflect.TypeToken<List<MangaSeriesCacheDto>>() {}.type
            val cacheList: List<MangaSeriesCacheDto> = Gson().fromJson(json, type)

            cacheList.map {
                MangaSeries(
                    title = it.title,
                    folderUriStr = it.folderUriStr,
                    downloadTimestamp = it.lastModified,
                    coverUriStr = it.coverUriStr
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun syncLibrary(rootUriStr: String): List<MangaSeries> {
        val rootUri = Uri.parse(rootUriStr)
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val list = mutableListOf<MangaSeries>()

        rootDoc.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            if (name.endsWith(".json", ignoreCase = true)) return@forEach

            val isZip = name.endsWith(".zip", ignoreCase = true) || name.endsWith(".cbz", ignoreCase = true)
            val cleanTitle = if (isZip) name.substringBeforeLast(".") else name

            list.add(
                MangaSeries(
                    title = cleanTitle,
                    folderUriStr = file.uri.toString(),
                    downloadTimestamp = System.currentTimeMillis()
                )
            )
        }

        updateCacheFile(rootUriStr, list)
        return list
    }

    private fun updateCacheFile(rootUriStr: String, list: List<MangaSeries>) {
        try {
            val rootUri = Uri.parse(rootUriStr)
            val cacheList = list.map {
                MangaSeriesCacheDto(it.title, it.folderUriStr, it.downloadTimestamp, it.coverUriStr)
            }
            val file = File(context.cacheDir, "lib_cache_${rootUri.toString().hashCode()}.json")
            file.writeText(Gson().toJson(cacheList))
        } catch (_: Exception) {}
    }

    fun getChapters(seriesTitle: String, seriesUriStr: String): List<MangaChapter> {
        val seriesUri = Uri.parse(seriesUriStr)
        val seriesFile = DocumentFile.fromTreeUri(context, seriesUri) ?: return emptyList()
        val list = mutableListOf<MangaChapter>()

        seriesFile.listFiles().forEach { file ->
            val name = file.name ?: ""
            list.add(MangaChapter(name = name, isZip = name.endsWith(".zip", true)))
        }

        return list.sortedBy { it.name }
    }

    fun getTracker(rootUriStr: String): List<MangaTrackerEntry> {
        val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(rootUriStr)) ?: return emptyList()
        val trackerFile = rootDoc.findFile("manga_tracker.json") ?: return emptyList()

        return try {
            val json = context.contentResolver.openInputStream(trackerFile.uri)?.bufferedReader()?.use { it.readText() } ?: "[]"
            val type = object : com.google.gson.reflect.TypeToken<List<MangaTrackerEntry>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun bindUrlToTracker(rootUriStr: String, title: String, url: String) {
        val tracker = getTracker(rootUriStr).toMutableList()
        val existing = tracker.find { it.title.equals(title, ignoreCase = true) }

        if (existing != null) {
            val updated = existing.copy(url = url)
            tracker[tracker.indexOf(existing)] = updated
        } else {
            tracker.add(MangaTrackerEntry(title, url, "", 0))
        }
        saveTracker(rootUriStr, tracker)
    }

    private fun saveTracker(rootUriStr: String, trackerList: List<MangaTrackerEntry>) {
        val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(rootUriStr)) ?: return
        var trackerFile = rootDoc.findFile("manga_tracker.json") ?: rootDoc.createFile("application/json", "manga_tracker.json")

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
}