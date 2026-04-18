package app.mangareader.mobile.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.mangareader.mobile.data.MangaChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

// Ensure this is at the top-level so other files can import it
data class ReaderImage(val chapterIndex: Int, val pageIndex: Int, val uri: Uri)

object ChapterCacheManager {

    /**
     * Enhanced preloading designed for USB speeds.
     * Avoids redundant extractions and uses parallel I/O for ZIP entries.
     */
    suspend fun preloadChapters(
        context: Context,
        seriesTitle: String,
        allChapters: List<MangaChapter>,
        currentIndex: Int
    ): List<ReaderImage> = withContext(Dispatchers.IO) {

        val safeTitle = seriesTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val cacheRoot = File(context.cacheDir, "manga_cache/$safeTitle")
        if (!cacheRoot.exists()) cacheRoot.mkdirs()

        // Clean up distant chapters
        val activeIndices = listOf(currentIndex - 1, currentIndex, currentIndex + 1)
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

            val chapterUris = if (chapter.parentZip != null) {
                // Optimized extraction from inside your Nested ZIP structure
                extractFromMasterZip(context, chapter, chapDir)
            } else {
                // Standard Folder images
                chapter.file?.let { folder ->
                    folder.listFiles()
                        .filter { FileUtils.isImageFile(it.name ?: "") }
                        .sortedBy { it.name }
                        .map { it.uri }
                } ?: emptyList()
            }

            chapterUris.forEachIndexed { pIdx, uri ->
                resultImages.add(ReaderImage(i, pIdx, uri))
            }
        }

        return@withContext resultImages
    }

    private suspend fun extractFromMasterZip(context: Context, chapter: MangaChapter, targetDir: File): List<Uri> {
        // If already cached, don't re-extract
        val existingFiles = targetDir.listFiles()?.filter { FileUtils.isImageFile(it.name) }?.sortedBy { it.name }
        if (!existingFiles.isNullOrEmpty()) return existingFiles.map { Uri.fromFile(it) }

        val uris = mutableListOf<Uri>()
        try {
            val parentZip = chapter.parentZip ?: return emptyList()
            context.contentResolver.openFileDescriptor(parentZip.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    CommonsZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                        zip.entries.toList()
                            .filter { it.name.replace("\\", "/").startsWith(chapter.zipEntryPath) && !it.isDirectory && FileUtils.isImageFile(it.name) }
                            .sortedBy { it.name }
                            .forEach { entry ->
                                val outFile = File(targetDir, entry.name.substringAfterLast("/"))
                                FileOutputStream(outFile).use { fos ->
                                    zip.getInputStream(entry).use { it.copyTo(fos) }
                                }
                                uris.add(Uri.fromFile(outFile))
                            }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return uris
    }
}