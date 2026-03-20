package app.mangareader.mobile.scraper

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChapterDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder().build()

    suspend fun downloadChapterAsCbz(
        chapterTitle: String,
        pages: List<ExtractedPage>,
        sourceUrl: String,
        onProgress: suspend (Int, Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext null

        val safeTitle = chapterTitle.replace(Regex("[\\\\/*?:\"<>|]"), "_").trim()
        val downloadsDir = context.getExternalFilesDir(null) ?: return@withContext null
        val cbzFile = File(downloadsDir, "$safeTitle.cbz")

        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(sourceUrl) ?: ""

        ZipOutputStream(FileOutputStream(cbzFile)).use { zos ->
            pages.forEachIndexed { index, page ->
                try {
                    val fileName = String.format("page_%03d.jpg", page.index)
                    val zipEntry = ZipEntry(fileName)
                    zos.putNextEntry(zipEntry)

                    if (page.type == "base64") {
                        val cleanBase64 = if (page.data.contains(",")) {
                            page.data.substringAfter(",")
                        } else {
                            page.data
                        }
                        val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                        zos.write(decodedBytes)
                    } else if (page.type == "url") {
                        val request = Request.Builder()
                            .url(page.data)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                            .header("Referer", sourceUrl)
                            .header("Cookie", cookies)
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                response.body?.byteStream()?.copyTo(zos)
                            } else {
                                throw Exception("HTTP ${response.code}")
                            }
                        }
                    }
                    zos.closeEntry()

                    withContext(Dispatchers.Main) {
                        onProgress(index + 1, pages.size)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return@withContext cbzFile
    }
}