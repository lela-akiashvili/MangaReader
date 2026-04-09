package app.mangareader.mobile.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.mangareader.mobile.data.MangaChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

data class ReaderImage(val chapterIndex: Int, val pageIndex: Int, val uri: Uri)

object ChapterCacheManager {

    suspend fun preloadChapters(
        context: Context,
        seriesTitle: String,
        allChapters: List<MangaChapter>,
        currentIndex: Int
    ): List<ReaderImage> = withContext(Dispatchers.IO) {

        val safeTitle = seriesTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val cacheRoot = File(context.cacheDir, "manga_cache/$safeTitle")
        if (!cacheRoot.exists()) cacheRoot.mkdirs()

        val validIndices = listOf(currentIndex - 1, currentIndex, currentIndex + 1)

        cacheRoot.listFiles()?.forEach { folder ->
            val folderIndex = folder.name.replace("chap_", "").toIntOrNull()
            if (folderIndex == null || folderIndex !in validIndices) {
                folder.deleteRecursively()
            }
        }

        val chaptersToLoad = validIndices.filter { it in allChapters.indices }
        val missingMasterZipChapters = mutableListOf<Int>()

        for (i in chaptersToLoad) {
            if (allChapters[i].parentZip != null) {
                val chapDir = File(cacheRoot, "chap_$i")
                if (!chapDir.exists() || chapDir.listFiles()?.isEmpty() != false) {
                    missingMasterZipChapters.add(i)
                }
            }
        }

        // 1. INSTANT EXTRACTION: Fast Random Access for Master Zips
        if (missingMasterZipChapters.isNotEmpty()) {
            val missingByZip = missingMasterZipChapters.groupBy { allChapters[it].parentZip!!.uri }

            for ((_, chapterIndices) in missingByZip) {
                val masterZip = allChapters[chapterIndices.first()].parentZip!!
                var fastMethodSuccess = false

                // Attempt blazing fast Random Access via Apache Commons Compress
                try {
                    context.contentResolver.openFileDescriptor(masterZip.uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                            CommonsZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                                val allEntries = zip.entries.toList()

                                for (matchIdx in chapterIndices) {
                                    val targetPath = allChapters[matchIdx].zipEntryPath
                                    val chapDir = File(cacheRoot, "chap_$matchIdx")
                                    chapDir.mkdirs()

                                    allEntries.forEach { entry ->
                                        val normalizedName = entry.name.replace("\\", "/")
                                        val parentPath = normalizedName.substringBeforeLast("/", "")
                                        val matchesFolder = if (targetPath.isEmpty()) !normalizedName.contains("/") else parentPath == targetPath

                                        if (!entry.isDirectory && matchesFolder && FileUtils.isImageFile(normalizedName)) {
                                            val safeName = normalizedName.substringAfterLast("/")
                                            val outFile = File(chapDir, safeName)
                                            FileOutputStream(outFile).use { fos ->
                                                zip.getInputStream(entry).use { it.copyTo(fos) }
                                            }
                                        }
                                    }
                                }
                                fastMethodSuccess = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace() // Fallback gracefully
                }

                // Fallback to the slow, sequential stream if fast access fails
                if (!fastMethodSuccess) {
                    context.contentResolver.openInputStream(masterZip.uri)?.use { ips ->
                        ZipInputStream(ips).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val normalizedName = entry.name.replace("\\", "/")
                                if (!entry.isDirectory && FileUtils.isImageFile(normalizedName)) {
                                    val parentPath = normalizedName.substringBeforeLast("/", "")
                                    val matchIdx = chapterIndices.find { allChapters[it].zipEntryPath == parentPath }

                                    if (matchIdx != null) {
                                        val chapDir = File(cacheRoot, "chap_$matchIdx")
                                        chapDir.mkdirs()
                                        val outFile = File(chapDir, normalizedName.substringAfterLast("/"))
                                        FileOutputStream(outFile).use { zis.copyTo(it) }
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                }
            }
        }

        // 2. Assemble images
        val resultImages = mutableListOf<ReaderImage>()
        for (i in chaptersToLoad) {
            val chapter = allChapters[i]

            if (chapter.file != null && chapter.file.isDirectory) {
                val rawImages = chapter.file.listFiles()
                    .filter { FileUtils.isImageFile(it.name ?: "") }
                    .sortedBy { it.name }
                    .mapIndexed { pageIdx, doc -> ReaderImage(i, pageIdx, doc.uri) }
                resultImages.addAll(rawImages)
            } else if (chapter.file != null && chapter.file.name?.endsWith(".zip", true) == true) {
                resultImages.addAll(extractStandaloneZip(context, chapter.file, i, cacheRoot))
            } else if (chapter.parentZip != null) {
                val chapDir = File(cacheRoot, "chap_$i")
                if (chapDir.exists()) {
                    val cachedImgs = chapDir.listFiles()
                        ?.sortedBy { it.name }
                        ?.mapIndexed { pageIdx, file -> ReaderImage(i, pageIdx, Uri.fromFile(file)) }
                    if (cachedImgs != null) resultImages.addAll(cachedImgs)
                }
            }
        }

        return@withContext resultImages
    }

    private fun extractStandaloneZip(context: Context, zipFile: DocumentFile, chapterIndex: Int, cacheRoot: File): List<ReaderImage> {
        val chapterDir = File(cacheRoot, "chap_$chapterIndex")
        if (chapterDir.exists() && chapterDir.listFiles()?.isNotEmpty() == true) {
            return chapterDir.listFiles()!!.sortedBy { it.name }.mapIndexed { pageIdx, file -> ReaderImage(chapterIndex, pageIdx, Uri.fromFile(file)) }
        }

        chapterDir.mkdirs()
        val extractedFiles = mutableListOf<File>()

        var fastMethodSuccess = false
        try {
            context.contentResolver.openFileDescriptor(zipFile.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    CommonsZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                        zip.entries.toList().forEach { entry ->
                            val normalizedName = entry.name.replace("\\", "/")
                            if (!entry.isDirectory && FileUtils.isImageFile(normalizedName)) {
                                val safeName = normalizedName.substringAfterLast("/")
                                val outFile = File(chapterDir, safeName)
                                FileOutputStream(outFile).use { fos ->
                                    zip.getInputStream(entry).use { it.copyTo(fos) }
                                }
                                extractedFiles.add(outFile)
                            }
                        }
                        fastMethodSuccess = true
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (!fastMethodSuccess) {
            try {
                context.contentResolver.openInputStream(zipFile.uri)?.use { ips ->
                    ZipInputStream(ips).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val normalizedName = entry.name.replace("\\", "/")
                            if (!entry.isDirectory && FileUtils.isImageFile(normalizedName)) {
                                val safeName = normalizedName.substringAfterLast("/")
                                val outFile = File(chapterDir, safeName)
                                FileOutputStream(outFile).use { zis.copyTo(it) }
                                extractedFiles.add(outFile)
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        return extractedFiles.sortedBy { it.name }.mapIndexed { pageIdx, file -> ReaderImage(chapterIndex, pageIdx, Uri.fromFile(file)) }
    }
}