package app.mangareader.mobile.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import app.mangareader.mobile.data.MangaChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
                    e.printStackTrace()
                }

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

        // 2. Assemble and Verify Images (Parallelized for extreme speed!)
        val resultImages = mutableListOf<ReaderImage>()

        for (i in chaptersToLoad) {
            val chapter = allChapters[i]
            val chapDir = File(cacheRoot, "chap_$i")
            val chapterUris = mutableListOf<Uri>()

            if (chapter.file != null && chapter.file.isDirectory) {
                // SAF FOLDER: Read directly from drive, check bounds in parallel, ONLY cache if too tall!
                val rawDocs = chapter.file.listFiles()
                    .filter { FileUtils.isImageFile(it.name ?: "") }
                    .sortedBy { it.name }

                val processedUris = rawDocs.map { doc ->
                    async {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(doc.uri)?.use { ips ->
                            BitmapFactory.decodeStream(ips, null, options)
                        }

                        if (options.outHeight > 4096) {
                            chapDir.mkdirs()
                            val safeName = doc.name ?: "temp_page_${System.currentTimeMillis()}.jpg"
                            val tempFile = File(chapDir, safeName)

                            // Copy just this single giant file to cache
                            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                            }

                            // Slice it up and delete the temp file
                            val slicedFiles = splitLargeImageFile(tempFile, chapDir)
                            tempFile.delete()
                            slicedFiles.map { Uri.fromFile(it) }
                        } else {
                            // Normal page: Zero-copy, just pass the direct SAF Uri!
                            listOf(doc.uri)
                        }
                    }
                }.awaitAll().flatten() // awaitAll waits for parallel checks, flatten strings the lists together

                chapterUris.addAll(processedUris)

            } else if (chapter.file != null && chapter.file.name?.endsWith(".zip", true) == true) {
                // STANDALONE ZIP: Extract to cache, then process cache directory in parallel
                extractStandaloneZip(context, chapter.file, i, cacheRoot)
                chapterUris.addAll(processCacheDirectoryAsync(chapDir))
            } else if (chapter.parentZip != null) {
                // MASTER ZIP: Already extracted in Step 1, just process the cache directory in parallel
                chapterUris.addAll(processCacheDirectoryAsync(chapDir))
            }

            // Finally, map the sequential URIs to ReaderImage models
            chapterUris.forEachIndexed { pageIdx, uri ->
                resultImages.add(ReaderImage(i, pageIdx, uri))
            }
        }

        return@withContext resultImages
    }

    private fun extractStandaloneZip(context: Context, zipFile: DocumentFile, chapterIndex: Int, cacheRoot: File) {
        val chapterDir = File(cacheRoot, "chap_$chapterIndex")
        if (chapterDir.exists() && chapterDir.listFiles()?.isNotEmpty() == true) {
            return
        }

        chapterDir.mkdirs()
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
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // Parallel processing for locally cached folders (ZIP extracts)
    private suspend fun processCacheDirectoryAsync(dir: File): List<Uri> = withContext(Dispatchers.IO) {
        if (!dir.exists()) return@withContext emptyList()
        val files = dir.listFiles()?.filter { FileUtils.isImageFile(it.name) }?.sortedBy { it.name } ?: return@withContext emptyList()

        val uris = files.map { file ->
            async {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)

                if (options.outHeight > 4096) {
                    val slicedFiles = splitLargeImageFile(file, dir)
                    file.delete()
                    slicedFiles.map { Uri.fromFile(it) }
                } else {
                    listOf(Uri.fromFile(file))
                }
            }
        }.awaitAll().flatten()

        return@withContext uris
    }

    private fun splitLargeImageFile(file: File, dir: File): List<File> {
        val slicedFiles = mutableListOf<File>()
        val maxHeight = 4096

        try {
            val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
            if (decoder != null) {
                val width = decoder.width
                val height = decoder.height
                var y = 0
                var part = 1

                while (y < height) {
                    val chunkHeight = minOf(maxHeight, height - y)
                    val rect = Rect(0, y, width, y + chunkHeight)
                    val bitmap = decoder.decodeRegion(rect, BitmapFactory.Options())

                    if (bitmap != null) {
                        val chunkFile = File(dir, "${file.nameWithoutExtension}_part${String.format("%03d", part)}.${file.extension}")
                        FileOutputStream(chunkFile).use { fos ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS, 100, fos)
                            } else {
                                @Suppress("DEPRECATION")
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 100, fos)
                            }
                        }
                        bitmap.recycle()
                        slicedFiles.add(chunkFile)
                    }
                    y += chunkHeight
                    part++
                }
                decoder.recycle()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return listOf(file) // Fallback: return original file if splicing unexpectedly fails
        }

        return slicedFiles
    }
}