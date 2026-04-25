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
import java.net.InetAddress
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ScraperService : Service() {

    private val CHANNEL_ID = "ScraperChannel"
    private val COMPLETION_CHANNEL_ID = "ScraperCompletionChannel"
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

                // Massive 4K Viewport to Guarantee Highest Res Canvas captures (Untouched as requested)
                layoutParams = ViewGroup.LayoutParams(2560, 3500)
                measure(
                    View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(3500, View.MeasureSpec.EXACTLY)
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
        // Safe extraction before launch to prevent Coroutine context errors
        val seriesUrl = intent?.getStringExtra("URL") ?: return START_NOT_STICKY

        // CHANGED: Made this a 'var' instead of 'val' so we can override it if we find a match!
        var seriesTitle = intent.getStringExtra("SERIES_TITLE") ?: "Unknown_Series"

        val startChap = intent.getIntExtra("START_CHAPTER", 1)
        val baseChap = intent.getIntExtra("BASE_CHAPTER", startChap)
        val maxChaps = intent.getIntExtra("MAX_CHAPTERS", 99999)
        val isAutoUpdate = intent.getBooleanExtra("IS_AUTO_UPDATE", false)

        startForeground(NOTIFICATION_ID, buildNotification("Scraper Initializing..."))
        ScrapeState.isScraping.value = true
        ScrapeState.clearLogs()

        val prefs = getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)
        val cookies = prefs.getString("saved_cookie", "") ?: ""

        serviceScope.launch {
            try {
                ScrapeState.log("[System] Initializing scraper for $seriesTitle...")

                // --- FOLDER RESOLUTION LOGIC ---
                val rootUri = ScrapeState.outputDirectoryUri.value
                val rootFolder = rootUri?.let { DocumentFile.fromTreeUri(applicationContext, it) }
                var targetDir: DocumentFile? = null

                if (rootFolder != null) {

                    // NEW ULTIMATE FOOLPROOF LOGIC: Cross-reference URL with Tracker JSON instantly
                    if (isAutoUpdate) {
                        val trackerData = app.mangareader.mobile.utils.FileUtils.getTracker(applicationContext, rootUri)

                        // Sanitize the URL to ignore 'http', 'www', and trailing slashes
                        val cleanIntentUrl = seriesUrl.replace(Regex("^https?://(www\\.)?"), "").trimEnd('/')

                        val matchedEntry = trackerData.find {
                            val cleanTrackerUrl = it.url.replace(Regex("^https?://(www\\.)?"), "").trimEnd('/')
                            cleanTrackerUrl.isNotEmpty() && cleanTrackerUrl == cleanIntentUrl
                        }

                        if (matchedEntry != null) {
                            seriesTitle = matchedEntry.title // OVERRIDE the title with your exact local folder name!
                            ScrapeState.log("[System] URL Match! Rerouting to exact local folder: '$seriesTitle'")
                        }
                    }

                    var mangasDir: DocumentFile? = null

                    if (rootFolder.name?.equals("mangas", ignoreCase = true) == true) {
                        mangasDir = rootFolder
                    } else {
                        mangasDir = rootFolder.listFiles().find { it.isDirectory && it.name.equals("mangas", ignoreCase = true) }
                        if (mangasDir == null) {
                            mangasDir = rootFolder.createDirectory("Mangas")
                        }
                    }

                    if (mangasDir != null) {
                        // 1. Standard search (Exact Match for manual ScraperScreen usage)
                        var seriesFolder = mangasDir.findFile(seriesTitle)

                        // 2. Aggressive search & Fuzzy Tag Matcher (Only triggered from Notification Tracker)
                        if (seriesFolder == null && isAutoUpdate) {
                            seriesFolder = mangasDir.listFiles().find {
                                val folderName = it.name ?: ""
                                if (!it.isDirectory) return@find false

                                val isExactIgnoreCase = folderName.equals(seriesTitle, ignoreCase = true)

                                // Handles Mangago adding tags like "(Yaoi)" or "(Official)" to the end of titles
                                // If Mangago title is "Series A (Yaoi)", and local folder is "Series A", this catches it!
                                val isTagMismatch = folderName.length > 5 && seriesTitle.contains(folderName, ignoreCase = true)

                                isExactIgnoreCase || isTagMismatch
                            }

                            if (seriesFolder != null) {
                                seriesTitle = seriesFolder.name ?: seriesTitle // Lock in the physical folder name for future UI logs
                                ScrapeState.log("[System] Fuzzy Match! Found existing folder: '$seriesTitle'")
                            }
                        }

                        // 3. Fallback to creation
                        if (seriesFolder == null) {
                            seriesFolder = mangasDir.createDirectory(seriesTitle)
                        }
                        targetDir = seriesFolder
                    }
                }

                if (targetDir == null) {
                    throw Exception("Could not find or create output directory.")
                }

                ScrapeState.log("[System] Output folder: ${targetDir.name}")

                // --- FETCH MANGAGO HTML ---
                val doc = Jsoup.connect(seriesUrl)
                    .header("Cookie", cookies)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .get()

                data class ChapterInfo(val url: String, val name: String)
                val chapters = mutableListOf<ChapterInfo>()

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
                    return@launch
                }

                val selectedChapters = chapters.subList(startIdx, endIdx)
                var perfectlyDownloaded = 0
                var failedChapters = 0
                val incompleteList = mutableListOf<String>()
                var applyToAllConflict: ConflictAction? = null

                var highestSuccessfulNumber = 0
                var highestSuccessfulName = ""

                // --- CHAPTER LOOP ---
                for ((index, chapter) in selectedChapters.withIndex()) {
                    if (checkPauseOrCancel()) break

                    val chapterNum = baseChap + index
                    val folderName = "Ch$chapterNum - ${chapter.name}"

                    updateNotification("Scraping $folderName...")
                    ScrapeState.log("\n[Chapter ${index + 1}/${selectedChapters.size}] Starting: $folderName")

                    var jsonResult = ""
                    var imagesList = JSONArray()
                    var totalImages = 0
                    var htmlAttempts = 0

                    while (htmlAttempts < 4) {
                        jsonResult = suspendCoroutine<String> { continuation ->
                            jsExtractionCallback = { result -> continuation.resume(result) }
                            currentChapterUrl = chapter.url
                            scriptInjected = false
                            Handler(Looper.getMainLooper()).post { webView?.loadUrl(chapter.url) }
                        }

                        imagesList = JSONArray(jsonResult)
                        totalImages = imagesList.length()

                        if (totalImages > 0 && imagesList.optJSONObject(0)?.optString("type") != "error") {
                            break
                        }

                        htmlAttempts++
                        ScrapeState.log("  > [Warn] Awaiting images... (Attempt $htmlAttempts/4)")
                        delay(2000)
                    }

                    if (totalImages == 0 || imagesList.optJSONObject(0)?.optString("type") == "error") {
                        ScrapeState.log("[Error] Page failed to completely render. Skipping chapter.")
                        failedChapters++
                        incompleteList.add("Chapter ${index + 1} (${chapter.name}): Load Timeout.")
                        continue
                    }

                    var chapterFolder = targetDir.findFile(folderName)

                    if (chapterFolder != null && chapterFolder.listFiles().isNotEmpty()) {
                        var action = applyToAllConflict
                        if (action == null) {
                            ScrapeState.conflictResolution = CompletableDeferred()
                            ScrapeState.showConflictDialog.value = folderName

                            action = withTimeoutOrNull(60_000L) {
                                ScrapeState.conflictResolution?.await()
                            }

                            if (action == null) {
                                ScrapeState.log("[Warn] Conflict timeout. Defaulting to SKIP.")
                                action = ConflictAction.SKIP
                            }

                            ScrapeState.conflictResolution = null
                            ScrapeState.showConflictDialog.value = null

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
                        chapterFolder = targetDir.createDirectory(folderName)
                    }

                    var imagesFailedInChapter = 0

                    // --- IMAGE LOOP ---
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

                                    val urlExt = data.substringAfterLast('.', "").substringBefore("?").substringBefore("#").lowercase()
                                    when (urlExt) {
                                        "png" -> { mimeType = "image/png"; extension = ".png" }
                                        "webp" -> { mimeType = "image/webp"; extension = ".webp" }
                                        "gif" -> { mimeType = "image/gif"; extension = ".gif" }
                                        "jpeg" -> { mimeType = "image/jpeg"; extension = ".jpeg" }
                                        "bmp" -> { mimeType = "image/bmp"; extension = ".bmp" }
                                        "avif" -> { mimeType = "image/avif"; extension = ".avif" }
                                    }

                                    var responseToClose: Response? = null

                                    try {
                                        var clientToUse = okHttpClient
                                        var finalUrl = data
                                        var usedDomainMasking = false

                                        val parsedUrl = URL(data)
                                        val originalHost = parsedUrl.host

                                        if (originalHost.contains("_")) {
                                            usedDomainMasking = true
                                            val decoyHost = originalHost.replace("_", "-")
                                            finalUrl = data.replaceFirst(originalHost, decoyHost)

                                            clientToUse = okHttpClient.newBuilder()
                                                .dns(object : okhttp3.Dns {
                                                    override fun lookup(hostname: String): List<InetAddress> {
                                                        if (hostname == decoyHost) {
                                                            return okhttp3.Dns.SYSTEM.lookup(originalHost)
                                                        }
                                                        return okhttp3.Dns.SYSTEM.lookup(hostname)
                                                    }
                                                })
                                                .hostnameVerifier { hostname, session ->
                                                    if (hostname == decoyHost) {
                                                        javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(originalHost, session)
                                                    } else {
                                                        javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
                                                    }
                                                }
                                                .build()
                                        }

                                        val requestBuilder = Request.Builder()
                                            .url(finalUrl)
                                            .header("Cookie", cookies)
                                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

                                        if (usedDomainMasking) {
                                            requestBuilder.header("Host", originalHost)
                                        }

                                        val response = clientToUse.newCall(requestBuilder.build()).execute()
                                        if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code}")

                                        val contentType = response.header("Content-Type")?.lowercase() ?: ""
                                        if (contentType.contains("png")) { mimeType = "image/png"; extension = ".png" }
                                        else if (contentType.contains("webp")) { mimeType = "image/webp"; extension = ".webp" }
                                        else if (contentType.contains("gif")) { mimeType = "image/gif"; extension = ".gif" }
                                        else if (contentType.contains("jpeg") && extension != ".jpeg") { mimeType = "image/jpeg"; extension = ".jpg" }

                                        responseStream = response.body?.byteStream()
                                        responseToClose = response

                                        if (responseStream != null) {
                                            val safeImageName = "${chapter.name}_${String.format("%03d", imgDisplayNum)}$extension"
                                            currentFile = chapterFolder?.createFile(mimeType, safeImageName)
                                                ?: throw Exception("Failed to create file")

                                            applicationContext.contentResolver.openOutputStream(currentFile.uri)?.use { outStream ->
                                                responseStream?.copyTo(outStream)
                                            }
                                            ScrapeState.log("  > Downloaded Page: $safeImageName")
                                        }
                                    } finally {
                                        responseToClose?.close()
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
                                        ScrapeState.log("  > Captured Canvas DRM: $safeImageName")
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
                        highestSuccessfulNumber = chapterNum
                        highestSuccessfulName = chapter.name
                    } else {
                        failedChapters++
                        incompleteList.add("Chapter ${index + 1} (${chapter.name}): Incomplete (${totalImages - imagesFailedInChapter}/$totalImages pages)")
                    }
                }

                // --- POST-DOWNLOAD CACHE & TRACKER SYNC ---
                if (rootUri != null) {
                    ScrapeState.log("[System] Auto-syncing library cache...")
                    app.mangareader.mobile.utils.FileUtils.syncLibrary(applicationContext, rootUri)

                    if (highestSuccessfulNumber > 0) {
                        ScrapeState.log("[System] Updating permanent manga tracker on drive...")
                        app.mangareader.mobile.utils.FileUtils.updateTrackerEntry(
                            applicationContext,
                            rootUri,
                            seriesTitle,
                            seriesUrl,
                            highestSuccessfulName,
                            highestSuccessfulNumber
                        )
                    }
                }

                if (!ScrapeState.isCancelled.value) {
                    ScrapeState.log("\n>>> ALL CHAPTERS PROCESSED SUCCESSFULLY <<<")

                    if (incompleteList.isNotEmpty()) {
                        ScrapeState.log("\n--- EXTRACTION SUMMARY WITH ERRORS ---")
                        for (issue in incompleteList) {
                            ScrapeState.log(" > $issue")
                        }
                        sendCompletionNotification("Scraping Finished (With Errors)", "Completed $seriesTitle, but $failedChapters chapters had missing pages.")
                    } else {
                        val titleOrFallback = seriesTitle.ifBlank { "requested series" }
                        sendCompletionNotification("Scraping Complete!", "Successfully downloaded $titleOrFallback.")
                    }
                } else {
                    sendCompletionNotification("Scraping Stopped", "Download was cancelled for $seriesTitle.")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                ScrapeState.log("[Error] Critical failure: ${e.message}")
            } finally {
                ScrapeState.isScraping.value = false
                stopForeground(true)
            }
        }

        return START_NOT_STICKY
    }

    private fun sendCompletionNotification(title: String, text: String) {
        wakeScreen()

        val notification = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2, notification)
    }

    private fun wakeScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "MangaScraper::CompletionWakeUp"
            )
            wakeLock.acquire(3000) // Temporarily turn on screen for 3 seconds
        } catch (e: Exception) {
            e.printStackTrace()
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
                    // Dispatch synthetic events to trick lazy-loader into firing
                    window.dispatchEvent(new Event('scroll'));
                    window.dispatchEvent(new Event('resize'));
                    
                    window.scrollBy(0, window.innerHeight > 0 ? window.innerHeight * 0.8 : 1000);
                    
                    var currentScrollY = window.scrollY;
                    if (Math.abs(currentScrollY - lastScrollY) < 5) {
                        stuckCounter++;
                        
                        if (stuckCounter % 2 === 0) {
                            window.scrollBy(0, -400);
                            setTimeout(function() { 
                                window.dispatchEvent(new Event('scroll'));
                                window.scrollBy(0, 400); 
                            }, 150);
                        }

                        // Parse the current DOM to see what we actually have right now
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

                        // NEW: Strict Expected Total logic implementation
                        var conditionMet = false;
                        if (expectedTotal > 0) {
                            // If we parsed the total correctly, stubbornly wait for all images
                            // and completely ignore the soft 'stuckCounter' exit.
                            if (validCount >= expectedTotal) {
                                conditionMet = true;
                            }
                        } else {
                            // Fallback: If we couldn't parse the total, rely on the stuck counter
                            if (isLoaded && stuckCounter >= 5) {
                                conditionMet = true;
                            }
                        }
                        
                        // Hard Exit: If we are completely deadlocked for ~32 seconds without scrolling,
                        // force exit to prevent an infinite background loop.
                        var hardExit = (stuckCounter >= 40);
                        
                        if (conditionMet || hardExit) {
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
                                 results.push({ type: 'url', data: srcUrl });
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
            val manager = getSystemService(NotificationManager::class.java)

            // Ongoing (Silent) Channel
            val ongoingChannel = NotificationChannel(
                CHANNEL_ID,
                "Manga Scraper Running",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(ongoingChannel)

            // Completed (Loud/Wake) Channel
            val completionChannel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                "Manga Scraper Completed",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a manga finishes downloading"
                enableVibration(true)
            }
            manager.createNotificationChannel(completionChannel)
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
        serviceScope.cancel()

        Handler(Looper.getMainLooper()).post {
            try {
                webView?.stopLoading()
                webView?.removeAllViews()
                webView?.clearHistory()
                webView?.destroy()
                webView = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}