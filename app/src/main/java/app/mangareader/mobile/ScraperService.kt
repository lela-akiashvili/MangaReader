package app.mangareader.mobile

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
import android.util.Base64
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

        // Acquire Partial WakeLock to keep CPU running when screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MangaScraper::BackgroundScrapeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L /*10 hours max*/)

        Handler(Looper.getMainLooper()).post {
            webView = WebView(applicationContext).apply {
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.blockNetworkImage = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true

                layoutParams = ViewGroup.LayoutParams(1920, 10000)
                measure(
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(10000, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, measuredWidth, measuredHeight)

                addJavascriptInterface(WebAppInterface(), "Android")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url != null && url == currentChapterUrl && !scriptInjected) {
                            scriptInjected = true
                            ScrapeState.log("Page loaded. Waiting for canvas/images to render...")
                            // Force WebView to keep JS timers running even in background
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
        val startChap = intent.getIntExtra("START_CHAPTER", 1)
        val maxChaps = intent.getIntExtra("MAX_CHAPTERS", 1)
        val zipOnSuccess = intent.getBooleanExtra("ZIP_ON_SUCCESS", false)

        startForeground(NOTIFICATION_ID, buildNotification("Scraper Initializing..."))
        ScrapeState.isScraping.value = true
        ScrapeState.clearLogs()

        serviceScope.launch {
            try {
                scrapeSeries(seriesUrl, startChap, maxChaps, zipOnSuccess)
            } catch (e: Exception) {
                ScrapeState.log("Fatal Error: ${e.message}")
            } finally {
                ScrapeState.isScraping.value = false
                stopForeground(true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun scrapeSeries(seriesUrl: String, startChap: Int, maxChaps: Int, zipOnSuccess: Boolean) {
        val cookies = CookieManager.getInstance().getCookie(seriesUrl) ?: ""
        ScrapeState.log("Fetching series page...")

        val doc = Jsoup.connect(seriesUrl)
            .header("Cookie", cookies)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .get()

        val chapters = mutableListOf<String>()
        val rows = doc.select("table#chapter_table tbody tr")
        for (row in rows) {
            val link = row.select("h4 a.chico").attr("href")
            if (link.isNotEmpty()) {
                chapters.add(link)
            }
        }

        chapters.reverse()

        val totalDetected = chapters.size
        ScrapeState.log("Detected $totalDetected total chapters in series.")

        val startIdx = (startChap - 1).coerceAtLeast(0)
        val endIdx = (startIdx + maxChaps).coerceAtMost(chapters.size)

        if (startIdx >= chapters.size) {
            ScrapeState.log("Start chapter is greater than total chapters. Stopping.")
            return
        }

        val selectedChapters = chapters.subList(startIdx, endIdx)
        ScrapeState.log("Will scrape ${selectedChapters.size} chapters (from index $startIdx to ${endIdx-1})")

        var perfectlyDownloaded = 0
        var failedChapters = 0

        for ((index, chapterUrl) in selectedChapters.withIndex()) {
            val chapterNum = startIdx + index + 1
            updateNotification("Scraping Chapter $chapterNum...")
            ScrapeState.log("--- Starting Chapter $chapterNum (${index + 1}/${selectedChapters.size}) ---")

            val jsonResult = suspendCoroutine<String> { continuation ->
                jsExtractionCallback = { result ->
                    continuation.resume(result)
                }

                currentChapterUrl = chapterUrl
                scriptInjected = false
                Handler(Looper.getMainLooper()).post {
                    webView?.loadUrl(chapterUrl)
                }
            }

            val imagesList = JSONArray(jsonResult)
            val totalImages = imagesList.length()
            ScrapeState.log("Extracted $totalImages elements from Chapter $chapterNum.")

            if (totalImages == 0) {
                ScrapeState.log("WARNING: 0 images found. Skipping chapter.")
                failedChapters++
                continue
            }

            val rootUri = ScrapeState.outputDirectoryUri.value
            if (rootUri == null) {
                ScrapeState.log("Output folder missing! Aborting.")
                return
            }
            val rootFolder = DocumentFile.fromTreeUri(applicationContext, rootUri)
            var chapterFolder = rootFolder?.findFile("Chapter_$chapterNum")
            if (chapterFolder == null) {
                chapterFolder = rootFolder?.createDirectory("Chapter_$chapterNum")
            }

            var imagesFailedInChapter = 0
            val failedImageDetails = mutableListOf<String>()

            for (i in 0 until totalImages) {
                val item = imagesList.getJSONObject(i)
                val type = item.optString("type", "unknown")
                val imgDisplayNum = i + 1

                var success = false
                var attempts = 0
                var currentFile: DocumentFile? = null

                while (!success && attempts < 3) {
                    attempts++
                    try {
                        val fileName = String.format("%03d.png", imgDisplayNum)
                        currentFile = chapterFolder?.createFile("image/png", fileName)
                            ?: throw Exception("Failed to create file")

                        applicationContext.contentResolver.openOutputStream(currentFile.uri)?.use { outStream ->

                            if (type == "url") {
                                val data = item.optString("data", "")
                                if (data.isEmpty()) throw Exception("URL string was empty")

                                try {
                                    val request = Request.Builder()
                                        .url(data)
                                        .header("Cookie", cookies)
                                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                        .build()

                                    val response = okHttpClient.newCall(request).execute()
                                    if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code}")

                                    response.body?.byteStream()?.use { inStream ->
                                        inStream.copyTo(outStream)
                                    }
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

                                    connection.inputStream.use { inStream ->
                                        inStream.copyTo(outStream)
                                    }
                                }

                            } else if (type == "base64") {
                                val base64Data = item.optString("data", "")
                                val base64String = base64Data.substringAfter(",")
                                val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                                outStream.write(decodedBytes)

                            } else if (type == "canvas_rect") {
                                val x = item.optDouble("x", 0.0)
                                val y = item.optDouble("y", 0.0)
                                val w = item.optDouble("w", 0.0)
                                val h = item.optDouble("h", 0.0)

                                if (w <= 0 || h <= 0) throw Exception("Canvas dimensions are 0")

                                val bitmap = withContext(Dispatchers.Main) {
                                    captureWebViewRect(x, y, w, h)
                                }

                                if (bitmap != null) {
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outStream)
                                    bitmap.recycle()
                                } else {
                                    throw Exception("Bitmap capture returned null")
                                }

                            } else if (type == "error") {
                                val err = item.optString("data", "Unknown JS error")
                                throw Exception("JS Error: $err")
                            }
                        }
                        success = true
                    } catch (e: Exception) {
                        currentFile?.delete()
                        if (attempts < 3) {
                            ScrapeState.log("$imgDisplayNum/$totalImages failed ($type), try $attempts/3...")
                            delay(2000)
                        } else {
                            ScrapeState.log("FAILED permanently: Image $imgDisplayNum. Reason: ${e.message}")
                            imagesFailedInChapter++
                            failedImageDetails.add("#$imgDisplayNum")
                        }
                    }
                }
            }

            if (imagesFailedInChapter == 0) {
                perfectlyDownloaded++
                ScrapeState.log("Chapter $chapterNum downloaded perfectly ($totalImages/$totalImages).")
            } else {
                failedChapters++
                val successCount = totalImages - imagesFailedInChapter
                ScrapeState.log("Chapter $chapterNum finished with errors. ($successCount/$totalImages). Failed: ${failedImageDetails.joinToString()}")
            }
        }

        updateNotification("Scraping Complete!")
        ScrapeState.log("=== SCRAPING SESSION COMPLETE ===")
        ScrapeState.log("Perfect Chapters: $perfectlyDownloaded")
        ScrapeState.log("Chapters with Errors/Failed: $failedChapters")

        if (zipOnSuccess && failedChapters == 0 && perfectlyDownloaded > 0) {
            ScrapeState.log("No errors detected! Compressing folders to .zip...")
            val rootUri = ScrapeState.outputDirectoryUri.value
            val rootFolder = rootUri?.let { DocumentFile.fromTreeUri(applicationContext, it) }

            if (rootFolder != null) {
                try {
                    val zipFileName = "MangaScrape_${System.currentTimeMillis()}.zip"
                    val zipFile = rootFolder.createFile("application/zip", zipFileName)

                    if (zipFile != null) {
                        withContext(Dispatchers.IO) {
                            applicationContext.contentResolver.openOutputStream(zipFile.uri)?.use { os ->
                                ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                                    for (chapterDir in rootFolder.listFiles()) {
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
                        ScrapeState.log("Successfully created zip: $zipFileName")
                    } else {
                        ScrapeState.log("Warning: Failed to create zip file.")
                    }
                } catch (e: Exception) {
                    ScrapeState.log("Zipping failed: ${e.message}")
                }
            }
        }
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
                var checkInterval = setInterval(function() {
                    var loaders = document.querySelectorAll('img[src*="ajax-loader"]');
                    var isLoaded = true;
                    for(var i=0; i<loaders.length; i++) {
                        if (window.getComputedStyle(loaders[i].parentNode).display !== 'none') {
                            isLoaded = false;
                            break;
                        }
                    }
                    if (isLoaded || loaders.length === 0) {
                        clearInterval(checkInterval);
                        clearTimeout(safetyTimeout);
                        extractImages();
                    }
                }, 2000);

                var safetyTimeout = setTimeout(function() {
                    clearInterval(checkInterval);
                    extractImages();
                }, 60000);

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
                                         results.push({ type: 'error', data: 'Image dimensions are 0 (Screenshot fallback failed)' });
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