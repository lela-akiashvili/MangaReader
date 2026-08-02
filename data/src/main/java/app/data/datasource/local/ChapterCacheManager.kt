package app.data.datasource.local

import android.content.Context
import android.net.Uri
import app.domain.model.MangaChapter // Domain entity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

data class ReaderImage(val chapterIndex: Int, val pageIndex: Int, val uri: Uri)

object ChapterCacheManager {
    suspend fun preloadChapters(
        context: Context,
        seriesTitle: String,
        allChapters: List<MangaChapter>,
        activeIndices: List<Int>
    ): List<ReaderImage> = withContext(Dispatchers.IO) {
        val safeTitle = seriesTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val cacheRoot = File(context.cacheDir, "manga_cache/$safeTitle")
        if (!cacheRoot.exists()) cacheRoot.mkdirs()

        cacheRoot.listFiles()?.forEach { folder ->
            val idx = folder.name.substringAfter("chap_").toIntOrNull()
            if (idx == null || idx !in activeIndices) folder.deleteRecursively()
        }

        val resultImages = mutableListOf<ReaderImage>()

        for (i in activeIndices) {
            if (i !in allChapters.indices) continue

            val chapter = allChapters[i]
            val chapDir = File(cacheRoot, "chap_$i")
            if (!chapDir.exists()) chapDir.mkdirs()

            val chapterUris = if (chapter.isZip) {
                extractFromMasterZip(context, chapter, chapDir)
            } else {
                emptyList()
            }

            chapterUris.forEachIndexed { pIdx, uri ->
                resultImages.add(ReaderImage(i, pIdx, uri))
            }
        }

        return@withContext resultImages
    }

    private suspend fun extractFromMasterZip(context: Context, chapter: MangaChapter, targetDir: File): List<Uri> {
        val existingFiles = targetDir.listFiles()?.filter { FileUtils.isImageFile(it.name) }?.sortedBy { it.name }
        if (!existingFiles.isNullOrEmpty()) return existingFiles.map { Uri.fromFile(it) }

        return emptyList()
    }
}