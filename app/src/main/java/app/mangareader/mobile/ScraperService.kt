package app.mangareader.mobile // CHANGE TO YOUR PACKAGE NAME

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import org.json.JSONArray
import org.jsoup.Jsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ScraperService : Service() {

    private val CHANNEL_ID = "ScraperChannel"
    private val NOTIFICATION_ID = 1

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var jsExtractionCallback: ((String) -> Unit)? = null
    private var currentChapterUrl = ""
    private var scriptInjected = false

    private val okHttpClient = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebView.enableSlowWholeDocumentDraw()
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MangaScraper::BackgroundScrapeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)

        Handler(Looper.getMainLooper()).post {
            webView = WebView(applicationContext).apply {
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.blockNetworkImage = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true

                // Realistic viewport size to enable actual scrolling
                layoutParams = ViewGroup.LayoutParams(1080, 1920)
                measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, measuredWidth, measuredHeight)

                addJavascriptInterface(WebAppInterface(), "Android")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url != null && url == currentChapterUrl && !scriptInjected) {
                            scriptInjected = true
                            view?.resumeTimers()
                            injectExtractionScript()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seriesUrl = intent?.getStringExtra("URL") ?: return START_NOT_STICKY
        val seriesTitle = intent.getStringExtra("SERIES_TITLE") ?: "Unknown_Series"
        val manualCookie = intent.getStringExtra("COOKIE") ?: ""
        val startChap = intent.getIntExtra("START_CHAPTER", 1)
        val maxChaps = intent.getIntExtra("MAX_CHAPTERS", 99999)
        val zipOnSuccess = intent.getBooleanExtra("ZIP_ON_SUCCESS", true)

        startForeground(NOTIFICATION_ID, buildNotification("Scraper Initializing..."))
        ScrapeState.isScraping.value = true
        ScrapeState.clearLogs()

        serviceScope.launch {
            try {
                scrapeSeries(seriesUrl, seriesTitle, manualCookie, startChap, maxChaps, zipOnSuccess)
            } catch (e: Exception) {
                ScrapeState.log("[Error] Critical System Failure: ${e.message}")
            } finally {
                ScrapeState.isScraping.value = false
                stopForeground(true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun scrapeSeries(
        seriesUrl: String,
        seriesTitle: String,
        manualCookie: String,
        startChap: Int,
        maxChaps: Int,
        zipOnSuccess: Boolean
    ) {
        val cookies = CookieManager.getInstance().getCookie(seriesUrl) ?: manualCookie
        ScrapeState.log("[System] Analyzing Series Page for chapters...")

        val doc = Jsoup.connect(seriesUrl)
            .header("Cookie", cookies)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .get()

        data class ChapterInfo(val url: String, val name: String)
        val chapters = mutableListOf<ChapterInfo>()
        val rootUri = ScrapeState.outputDirectoryUri.value

        if (rootUri == null) {
            ScrapeState.log("[Error] Output folder missing! Aborting.")
            return
        }

        val rootFolder = DocumentFile.fromTreeUri(applicationContext, rootUri)

        // Create the master folder for the series inside the root
        var seriesDir = rootFolder?.findFile(seriesTitle)
        if (seriesDir == null) {
            seriesDir = rootFolder?.createDirectory(seriesTitle)
        }

        val rows = doc.select("table#chapter_table tbody tr")
        for (row in rows) {
            val anchor = row.select("h4 a.chico")
            val link = anchor.attr("href")
            val safeTitle = anchor.text().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (link.isNotEmpty()) chapters.add(ChapterInfo(link, safeTitle))
        }

        chapters.reverse()
        ScrapeState.log("[System] Successfully found ${chapters.size} chapters.")

        val startIdx = (startChap - 1).coerceAtLeast(0)
        val endIdx = (startIdx + maxChaps).coerceAtMost(chapters.size)

        if (startIdx >= chapters.size) {
            ScrapeState.log("[Warn] Start chapter is greater than total chapters. Stopping.")
            return
        }

        val selectedChapters = chapters.subList(startIdx, endIdx)
        var perfectlyDownloaded = 0
        var failedChapters = 0
        val incompleteList = mutableListOf<String>()
        var applyToAllConflict: ConflictAction? = null

        for ((index, chapter) in selectedChapters.withIndex()) {
            if (checkPauseOrCancel()) break

            val chapterNum = startIdx + index + 1
            val folderName = "Ch$chapterNum - ${chapter.name}"

            updateNotification("Scraping $folderName...")
            ScrapeState.log("\n[Chapter ${index + 1}/${selectedChapters.size}] Starting: $folderName")

            val jsonResult = suspendCoroutine<String> { continuation ->
                jsExtractionCallback = { result ->
                    continuation.resume(result)
                }

                currentChapterUrl = chapter.url
                scriptInjected = false
                Handler(Looper.getMainLooper()).post {
                    webView?.loadUrl(chapter.url)
                }
            }

            val imagesList = JSONArray(jsonResult)
            val totalImages = imagesList.length()

            if (totalImages == 0) {
                ScrapeState.log("[Error] 0 images found. Skipping chapter.")
                failedChapters++
                incompleteList.add("Chapter ${index + 1} (${chapter.name}): Page failed to load completely.")
                continue
            }

            // Ensure chapter goes inside the specific Series folder, not the Root!
            var chapterFolder = seriesDir?.findFile(folderName)

            if (chapterFolder != null && chapterFolder.listFiles().isNotEmpty()) {
                var action = applyToAllConflict
                if (action == null) {
                    ScrapeState.conflictResolution = CompletableDeferred()
                    ScrapeState.showConflictDialog.value = folderName
                    action = ScrapeState.conflictResolution?.await()
                    ScrapeState.conflictResolution = null

                    if (action == ConflictAction.OVERWRITE_ALL || action == ConflictAction.SKIP_ALL) {
                        applyToAllConflict = action
                    }
                }

                when (action) {
                    ConflictAction.SKIP, ConflictAction.SKIP_ALL -> {
                        ScrapeState.log("[Warn] SKIPPED: '$folderName' (User selected No)")
                        continue
                    }
                    ConflictAction.OVERWRITE, ConflictAction.OVERWRITE_ALL -> {
                        ScrapeState.log("[Warn] OVERWRITING: '$folderName'")
                        for (file in chapterFolder.listFiles()) {
                            file.delete()
                        }
                    }
                    else -> {}
                }
            } else if (chapterFolder == null) {
                chapterFolder = seriesDir?.createDirectory(folderName)
            }

            var imagesFailedInChapter = 0

            for (i in 0 until totalImages) {
                if (checkPauseOrCancel()) break

                val item = imagesList.getJSONObject(i)
                val type = item.optString("type", "unknown")
                val imgDisplayNum = i + 1

                var success = false
                var attempts = 0
                var currentFile: DocumentFile? = null

                while (!success && attempts < 3) {
                    attempts++
                    try {
                        if (type == "url") {
                            val data = item.optString("data", "")
                            if (data.isEmpty()) throw Exception("URL string was empty")

                            var responseStream: InputStream? = null
                            var mimeType = "image/jpeg"
                            var extension = ".jpg"
                            var responseToClose: Response? = null
                            var connectionToDisconnect: HttpURLConnection? = null

                            try {
                                try {
                                    val request = Request.Builder()
                                        .url(data)
                                        .header("Cookie", cookies)
                                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                        .build()

                                    val response = okHttpClient.newCall(request).execute()
                                    if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code}")

                                    val contentType = response.header("Content-Type")?.lowercase() ?: ""
                                    if (contentType.contains("png")) { mimeType = "image/png"; extension = ".png" }
                                    else if (contentType.contains("webp")) { mimeType = "image/webp"; extension = ".webp" }
                                    else if (contentType.contains("gif")) { mimeType = "image/gif"; extension = ".gif" }

                                    responseStream = response.body?.byteStream()
                                    responseToClose = response

                                } catch (e: IllegalArgumentException) {
                                    val javaUrl = URL(data)
                                    val host = javaUrl.host
                                    val ip = InetAddress.getByName(host).hostAddress
                                    val ipUrl = data.replaceFirst(host, ip)

                                    val connection = URL(ipUrl).openConnection() as HttpURLConnection
                                    connection.setRequestProperty("Host", host)
                                    connection.setRequestProperty("Cookie", cookies)
                                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                                    connection.connectTimeout = 10000
                                    connection.readTimeout = 10000
                                    connection.connect()

                                    val contentType = connection.contentType?.lowercase() ?: ""
                                    if (contentType.contains("png")) { mimeType = "image/png"; extension = ".png" }
                                    else if (contentType.contains("webp")) { mimeType = "image/webp"; extension = ".webp" }
                                    else if (contentType.contains("gif")) { mimeType = "image/gif"; extension = ".gif" }

                                    responseStream = connection.inputStream
                                    connectionToDisconnect = connection
                                }

                                if (responseStream != null) {
                                    val safeImageName = "${chapter.name}_${String.format("%03d", imgDisplayNum)}$extension"
                                    currentFile = chapterFolder?.createFile(mimeType, safeImageName)
                                        ?: throw Exception("Failed to create file")

                                    applicationContext.contentResolver.openOutputStream(currentFile.uri)?.use { outStream ->
                                        responseStream.copyTo(outStream)
                                    }
                                    ScrapeState.log("  > Downloaded Fallback Image: $safeImageName")
                                }
                            } finally {
                                responseToClose?.close()
                                connectionToDisconnect?.disconnect()
                            }

                        } else if (type == "canvas_rect") {
                            val x = item.optDouble("x", 0.0)
                            val y = item.optDouble("y", 0.0)
                            val w = item.optDouble("w", 0.0)
                            val h = item.optDouble("h", 0.0)

                            if (w <= 0 || h <= 0) throw Exception("Canvas dimensions are 0")

                            val safeImageName = "${chapter.name}_${String.format("%03d", imgDisplayNum)}.webp"
                            currentFile = chapterFolder?.createFile("image/webp", safeImageName)
                                ?: throw Exception("Failed to create file")

                            val bitmap = withContext(Dispatchers.Main) {
                                captureWebViewRect(x, y, w, h)
                            }

                            if (bitmap != null) {
                                applicationContext.contentResolver.openOutputStream(currentFile.uri)?.use { outStream ->
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, outStream)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        bitmap.compress(Bitmap.CompressFormat.WEBP, 100, outStream)
                                    }
                                }
                                bitmap.recycle()
                                ScrapeState.log("  > Captured Canvas (PNG->WEBP): $safeImageName")
                            } else {
                                throw Exception("Bitmap capture returned null")
                            }
                        }
                        success = true
                    } catch (e: Exception) {
                        currentFile?.delete()
                        if (attempts < 3) {
                            ScrapeState.log("  > [Warn] Retrying Page_$imgDisplayNum (Attempt ${attempts+1}/3)")
                            if (checkPauseOrCancel()) break
                            delay(2000)
                        } else {
                            ScrapeState.log("  > [Error] Skipped Page_$imgDisplayNum (Server Error: ${e.message})")
                            imagesFailedInChapter++
                        }
                    }
                }
            }

            if (ScrapeState.isCancelled.value) {
                ScrapeState.log("[Error] EXTRACTION CANCELLED by user.")
                break
            }

            ScrapeState.log("[System] Finished. Total pages saved: ${totalImages - imagesFailedInChapter}/$totalImages")

            if (imagesFailedInChapter == 0) {
                perfectlyDownloaded++
            } else {
                failedChapters++
                incompleteList.add("Chapter ${index + 1} (${chapter.name}): Incomplete (${totalImages - imagesFailedInChapter}/$totalImages pages)")
            }
        }

        updateNotification("Scraping Complete!")

        if (!ScrapeState.isCancelled.value) {
            ScrapeState.log("\n>>> ALL CHAPTERS PROCESSED SUCCESSFULLY <<<")

            if (incompleteList.isNotEmpty()) {
                ScrapeState.log("\n--- EXTRACTION SUMMARY WITH ERRORS ---")
                for (issue in incompleteList) {
                    ScrapeState.log(" > $issue")
                }
            }
        }

        if (zipOnSuccess && failedChapters == 0 && perfectlyDownloaded > 0 && !ScrapeState.isCancelled.value) {
            ScrapeState.log("[System] No errors detected! Compressing folders to .zip...")
            if (seriesDir != null) {
                try {
                    val zipFileName = "MangaScrape_${seriesTitle}_${System.currentTimeMillis()}.zip"
                    val zipFile = seriesDir.createFile("application/zip", zipFileName)

                    if (zipFile != null) {
                        withContext(Dispatchers.IO) {
                            applicationContext.contentResolver.openOutputStream(zipFile.uri)?.use { os ->
                                ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                                    for (chapterDir in seriesDir.listFiles()) {
                                        if (chapterDir.isDirectory) {
                                            for (imageFile in chapterDir.listFiles()) {
                                                if (imageFile.isFile && imageFile.name != null) {
                                                    val entry = ZipEntry("${chapterDir.name}/${imageFile.name}")
                                                    zos.putNextEntry(entry)
                                                    applicationContext.contentResolver.openInputStream(imageFile.uri)?.use { ins ->
                                                        ins.copyTo(zos)
                                                    }
                                                    zos.closeEntry()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        ScrapeState.log("[Success] Successfully created zip: $zipFileName")
                    } else {
                        ScrapeState.log("[Warn] Warning: Failed to create zip file.")
                    }
                } catch (e: Exception) {
                    ScrapeState.log("[Error] Zipping failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun checkPauseOrCancel(): Boolean {
        if (ScrapeState.isPaused.value) {
            updateNotification("Scraping Paused")
            ScrapeState.log("[Warn] Scraping Paused...")
            while (ScrapeState.isPaused.value && !ScrapeState.isCancelled.value) {
                delay(500)
            }
            if (!ScrapeState.isCancelled.value) {
                ScrapeState.log("[Success] Scraping Resumed!")
                updateNotification("Scraping Resumed")
            }
        }
        return ScrapeState.isCancelled.value
    }

    private fun captureWebViewRect(x: Double, y: Double, w: Double, h: Double): Bitmap? {
        val wv = webView ?: return null
        val scale = wv.scale

        val scaledX = (x * scale).toInt()
        val scaledY = (y * scale).toInt()
        val scaledW = (w * scale).toInt().coerceAtLeast(1)
        val scaledH = (h * scale).toInt().coerceAtLeast(1)

        return try {
            val bitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
            val graphicsCanvas = Canvas(bitmap)

            graphicsCanvas.translate(-scaledX.toFloat(), -scaledY.toFloat())
            wv.draw(graphicsCanvas)

            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun injectExtractionScript() {
        val js = """
            javascript:(function() {
                var expectedTotal = -1;
                try {
                    var container = document.getElementById('pic_container');
                    if (container) {
                        var html = container.innerHTML;
                        var matches = html.match(/\(\d+\/(\d+)\)/g);
                        if (matches) {
                            var totals = matches.map(function(m) { 
                                var exec = /\(\d+\/(\d+)\)/.exec(m);
                                return exec ? parseInt(exec[1]) : 0;
                            });
                            expectedTotal = Math.max.apply(Math, totals);
                        }
                    }
                } catch(e) {}

                var totalStr = expectedTotal !== -1 ? expectedTotal : "?";
                Android.logMessage("[System] Detected " + totalStr + " total pages.");
                Android.logMessage("[System] Scrolling through chapter to assemble and extract pages...");

                var lastScrollY = -1;
                var stuckCounter = 0;

                var scrollInterval = setInterval(function() {
                    window.scrollBy(0, window.innerHeight * 0.8);
                    
                    var currentScrollY = window.scrollY;
                    if (Math.abs(currentScrollY - lastScrollY) < 5) {
                        stuckCounter++;
                        
                        // Jiggle logic
                        if (stuckCounter % 2 === 0) {
                            window.scrollBy(0, -400);
                            setTimeout(function() { window.scrollBy(0, 400); }, 150);
                        }

                        var loaders = document.querySelectorAll('img[src*="ajax-loader"]');
                        var isLoaded = true;
                        for(var i=0; i<loaders.length; i++) {
                            if (window.getComputedStyle(loaders[i].parentNode).display !== 'none') {
                                isLoaded = false;
                                break;
                            }
                        }

                        var elements = document.getElementById('pic_container') ? document.getElementById('pic_container').querySelectorAll('img:not(.loading), canvas') : [];
                        var validCount = 0;
                        for (var j=0; j<elements.length; j++) {
                            var src = elements[j].src || "";
                            if (src.indexOf('ajax-loader') === -1) validCount++;
                        }

                        var conditionMet = (isLoaded && stuckCounter >= 5) || (expectedTotal > 0 && validCount >= expectedTotal);
                        
                        if (conditionMet || stuckCounter >= 15) {
                            clearInterval(scrollInterval);
                            clearTimeout(safetyTimeout);
                            extractImages();
                        }
                    } else {
                        stuckCounter = 0;
                        lastScrollY = currentScrollY;
                    }
                }, 800);

                var safetyTimeout = setTimeout(function() {
                    clearInterval(scrollInterval);
                    extractImages();
                }, 90000);

                function extractImages() {
                    var results = [];
                    var container = document.getElementById('pic_container');
                    if (!container) {
                        Android.onImagesExtracted(JSON.stringify([{type: 'error', data: 'No pic_container found'}]));
                        return;
                    }
                    
                    var elements = container.querySelectorAll('img:not(.loading), canvas');
                    for (var i = 0; i < elements.length; i++) {
                        var el = elements[i];
                        if (el.tagName.toLowerCase() === 'img') {
                             var srcUrl = el.src || el.getAttribute('src') || "";
                             if (srcUrl && !srcUrl.includes('ajax-loader')) {
                                 var hasUnderscoreDomain = false;
                                 try {
                                     var urlObj = new URL(srcUrl);
                                     hasUnderscoreDomain = urlObj.hostname.includes('_');
                                 } catch(e) {}
                                 
                                 if (hasUnderscoreDomain) {
                                     var rect = el.getBoundingClientRect();
                                     var x = rect.left + window.scrollX;
                                     var y = rect.top + window.scrollY;
                                     var w = rect.width;
                                     var h = rect.height;
                                     
                                     if (w > 0 && h > 0) {
                                         results.push({ type: 'canvas_rect', x: x, y: y, w: w, h: h });
                                     } else {
                                         results.push({ type: 'error', data: 'Image dimensions are 0' });
                                     }
                                 } else {
                                     results.push({ type: 'url', data: srcUrl });
                                 }
                             }
                        } else if (el.tagName.toLowerCase() === 'canvas') {
                             var rect = el.getBoundingClientRect();
                             var x = rect.left + window.scrollX;
                             var y = rect.top + window.scrollY;
                             var w = rect.width;
                             var h = rect.height;
                             
                             if (w > 0 && h > 0) {
                                 results.push({ type: 'canvas_rect', x: x, y: y, w: w, h: h });
                             } else {
                                 results.push({ type: 'error', data: 'Canvas dimensions are 0' });
                             }
                        }
                    }
                    Android.onImagesExtracted(JSON.stringify(results));
                }
            })();
        """.trimIndent()

        Handler(Looper.getMainLooper()).post {
            webView?.evaluateJavascript(js, null)
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onImagesExtracted(jsonResult: String) {
            jsExtractionCallback?.invoke(jsonResult)
            jsExtractionCallback = null
        }

        @JavascriptInterface
        fun logMessage(msg: String) {
            ScrapeState.log(msg)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Manga Scraper Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Scraper Running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        // Release WakeLock when the service completely stops
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}