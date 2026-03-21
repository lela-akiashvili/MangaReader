package app.mangareader.mobile.scraper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class ChapterDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder().build()
    private val standardUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    suspend fun downloadChapterAsFolder(
        chapterTitle: String,
        pages: List<ExtractedPage>,
        sourceUrl: String,
        outputDirUri: Uri?,
        onLog: suspend (String) -> Unit,
        onProgress: suspend (Int, Int) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext null

        val safeTitle = chapterTitle.replace(Regex("[\\\\/*?:\"<>|]"), "_").trim()
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(sourceUrl) ?: ""

        onLog("[System] Creating folder for: $safeTitle")

        val isCustomDir = outputDirUri != null
        val chapterDirUri: Uri?
        val chapterDirFile: File?
        val savePath: String

        // 1. Create the Chapter Directory
        if (isCustomDir) {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(outputDirUri!!, DocumentsContract.getTreeDocumentId(outputDirUri))
            chapterDirUri = DocumentsContract.createDocument(context.contentResolver, docUri, DocumentsContract.Document.MIME_TYPE_DIR, safeTitle)
                ?: throw Exception("Storage permission denied. Could not create chapter folder.")
            chapterDirFile = null
            savePath = "Custom Folder -> $safeTitle"
        } else {
            val downloadsDir = context.getExternalFilesDir(null) ?: return@withContext null
            chapterDirFile = File(downloadsDir, safeTitle)
            chapterDirFile.mkdirs()
            chapterDirUri = null
            savePath = chapterDirFile.absolutePath
        }

        // 2. Download and save images into the directory
        pages.forEachIndexed { index, page ->
            val pageNumber = index + 1
            try {
                if (page.type == "base64") {
                    onLog("[Network] Processing Canvas Data (Page $pageNumber/${pages.size})...")
                    val cleanBase64 = if (page.data.contains(",")) page.data.substringAfter(",") else page.data
                    val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)

                    saveFile("page_${String.format("%03d", pageNumber)}.jpg", decodedBytes, isCustomDir, chapterDirUri, chapterDirFile)
                } else if (page.type == "url") {
                    val cleanUrl = page.data.trim()
                    onLog("[Network] Downloading Page $pageNumber/${pages.size} -> ${cleanUrl.takeLast(20)}...")
                    val request = Request.Builder()
                        .url(cleanUrl)
                        .header("User-Agent", standardUserAgent)
                        .header("Referer", sourceUrl)
                        .header("Cookie", cookies)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val contentType = response.body?.contentType()?.subtype ?: ""
                            val extension = when {
                                contentType.contains("webp", true) || cleanUrl.lowercase().endsWith(".webp") -> "webp"
                                contentType.contains("png", true) || cleanUrl.lowercase().endsWith(".png") -> "png"
                                contentType.contains("gif", true) || cleanUrl.lowercase().endsWith(".gif") -> "gif"
                                contentType.contains("jpeg", true) || cleanUrl.lowercase().endsWith(".jpeg") -> "jpeg"
                                contentType.contains("bmp", true) || cleanUrl.lowercase().endsWith(".bmp") -> "bmp"
                                else -> "jpg"
                            }

                            val fileName = "page_${String.format("%03d", pageNumber)}.$extension"
                            val bodyBytes = response.body?.bytes()

                            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                                saveFile(fileName, bodyBytes, isCustomDir, chapterDirUri, chapterDirFile)
                            } else {
                                onLog("[Error] Server returned 0 bytes for page $pageNumber.")
                            }
                        } else {
                            onLog("[Error] HTTP ${response.code} Forbidden on page $pageNumber.")
                        }
                    }
                }
                withContext(Dispatchers.Main) { onProgress(pageNumber, pages.size) }
            } catch (e: Exception) {
                val errMsg = e.message ?: "Unknown Error"
                onLog("[Error] Failed page $pageNumber: $errMsg")
            }
        }

        onLog("[System] Chapter folder complete.")
        return@withContext savePath
    }

    private fun saveFile(fileName: String, bytes: ByteArray, isCustomDir: Boolean, chapterDirUri: Uri?, chapterDirFile: File?) {
        val outputStream: OutputStream?

        if (isCustomDir && chapterDirUri != null) {
            val mimeType = when {
                fileName.endsWith("webp") -> "image/webp"
                fileName.endsWith("png") -> "image/png"
                fileName.endsWith("gif") -> "image/gif"
                else -> "image/jpeg"
            }
            val fileUri = DocumentsContract.createDocument(context.contentResolver, chapterDirUri, mimeType, fileName)
                ?: throw Exception("Failed to create file $fileName in Scoped Storage.")
            outputStream = context.contentResolver.openOutputStream(fileUri)
        } else if (chapterDirFile != null) {
            val file = File(chapterDirFile, fileName)
            outputStream = FileOutputStream(file)
        } else {
            throw Exception("No valid directory to save.")
        }

        outputStream?.use { it.write(bytes) }
    }
}