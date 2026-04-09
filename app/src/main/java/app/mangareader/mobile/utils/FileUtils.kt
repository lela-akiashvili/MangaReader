package app.mangareader.mobile.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.mangareader.mobile.data.MangaChapter
import app.mangareader.mobile.data.MangaSeries
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

object FileUtils {

    fun scanMangaFolders(context: Context, rootUri: Uri): List<MangaSeries> {
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val list = mutableListOf<MangaSeries>()

        rootFolder.listFiles().forEach { file ->
            val isZip = file.name?.endsWith(".zip", ignoreCase = true) == true
            if (file.isDirectory || isZip) {
                val cleanTitle = if (isZip) file.name!!.dropLast(4) else file.name ?: "Unknown"
                list.add(MangaSeries(
                    title = cleanTitle,
                    folderUri = file.uri,
                    documentFile = file,
                    downloadTimestamp = file.lastModified()
                ))
            }
        }
        return list
    }

    fun getChapters(context: Context, seriesFile: DocumentFile): List<MangaChapter> {
        val list = mutableListOf<MangaChapter>()

        if (seriesFile.isDirectory) {
            val subDirs = seriesFile.listFiles().filter { it.isDirectory || it.name?.endsWith(".zip", true) == true }
            if (subDirs.isNotEmpty()) {
                subDirs.forEach { list.add(MangaChapter(it.name ?: "Unknown", it, null)) }
            } else {
                val hasImages = seriesFile.listFiles().any { isImageFile(it.name ?: "") }
                if (hasImages) list.add(MangaChapter("Chapter 1", seriesFile, null))
            }
        } else if (seriesFile.name?.endsWith(".zip", true) == true) {
            var fastMethodSuccess = false

            // INSTANT EXTRACTION via Apache Commons Compress (1.5GB+ ZIPs in milliseconds)
            try {
                context.contentResolver.openFileDescriptor(seriesFile.uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        CommonsZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                            val chapterPaths = mutableSetOf<String>()
                            zip.entries.toList().forEach { entry ->
                                val normalizedName = entry.name.replace("\\", "/")
                                if (!entry.isDirectory && isImageFile(normalizedName)) {
                                    chapterPaths.add(normalizedName.substringBeforeLast("/", ""))
                                }
                            }
                            chapterPaths.forEach { folderPath ->
                                val displayName = if (folderPath.isEmpty()) "Root Images" else folderPath.substringAfterLast("/")
                                list.add(MangaChapter(displayName, null, seriesFile, folderPath))
                            }
                            fastMethodSuccess = true
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            // Slow Fallback (for older Android versions without PFD support)
            if (!fastMethodSuccess) {
                try {
                    context.contentResolver.openInputStream(seriesFile.uri)?.use { ips ->
                        ZipInputStream(ips).use { zis ->
                            val chapterPaths = mutableSetOf<String>()
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val normalizedName = entry.name.replace("\\", "/")
                                if (!entry.isDirectory && isImageFile(normalizedName)) {
                                    chapterPaths.add(normalizedName.substringBeforeLast("/", ""))
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }

                            chapterPaths.forEach { folderPath ->
                                val displayName = if (folderPath.isEmpty()) "Root Images" else folderPath.substringAfterLast("/")
                                list.add(MangaChapter(displayName, null, seriesFile, folderPath))
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        val smartSort = compareBy<MangaChapter> { chapter ->
            Regex("\\d+").find(chapter.name)?.value?.toIntOrNull() ?: 0
        }.thenBy { it.name }

        return list.sortedWith(smartSort)
    }

    fun getCoverImage(context: Context, seriesFile: DocumentFile): Uri? {
        try {
            val cacheFile = File(context.cacheDir, "cover_${seriesFile.name}.jpg")
            if (cacheFile.exists()) return Uri.fromFile(cacheFile)

            if (seriesFile.isDirectory) {
                val firstChap = seriesFile.listFiles()
                    .filter { it.isDirectory || it.name?.endsWith(".zip", true) == true }
                    .minByOrNull { it.name ?: "" }

                val targetFolder = firstChap ?: seriesFile
                val img = targetFolder.listFiles().find { isImageFile(it.name ?: "") }
                return img?.uri
            } else if (seriesFile.name?.endsWith(".zip", true) == true) {
                var fastMethodSuccess = false

                try {
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
                                    fastMethodSuccess = true
                                    return Uri.fromFile(cacheFile)
                                }
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }

                if (!fastMethodSuccess) {
                    context.contentResolver.openInputStream(seriesFile.uri)?.use { ips ->
                        ZipInputStream(ips).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && isImageFile(entry.name)) {
                                    FileOutputStream(cacheFile).use { fos -> zis.copyTo(fos) }
                                    return Uri.fromFile(cacheFile)
                                }
                                entry = zis.nextEntry
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") ||
                lower.endsWith(".png") ||
                lower.endsWith(".webp") ||
                lower.endsWith(".jpeg") ||
                lower.endsWith(".gif") ||
                lower.endsWith(".bmp") ||
                lower.endsWith(".avif")
    }
}