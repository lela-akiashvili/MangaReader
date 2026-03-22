package app.mangareader.mobile.data.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.documentfile.provider.DocumentFile
import app.mangareader.mobile.data.model.Chapter
import app.mangareader.mobile.data.model.DownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The ultimate Cloudflare & Lazy-Load bypasser. Spins up an invisible Chromium browser
 * to natively execute JS challenges, auto-scroll, bypass CORS tainting, and intercept resources.
 */
object HeavyDutyWebScraper {

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun getHtmlForChapterList(context: Context, url: String): String = suspendCancellableCoroutine { cont ->
        var isResumed = false
        val handler = Handler(Looper.getMainLooper())

        // FAILSAFE TIMEOUT: Prevent 45 min lockups if Cloudflare hangs
        val timeoutRunnable = Runnable {
            if (!isResumed) {
                isResumed = true
                if (cont.isActive) cont.resume("")
            }
        }
        handler.postDelayed(timeoutRunnable, 30000) // 30s timeout

        handler.post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            webView.addJavascriptInterface(object : Any() {
                @JavascriptInterface
                fun onHtmlExtracted(base64Html: String) {
                    if (isResumed) return
                    isResumed = true
                    handler.removeCallbacks(timeoutRunnable)
                    try {
                        val cleanBase64 = base64Html.removeSurrounding("\"")
                        if (cleanBase64 != "ERROR" && cleanBase64.isNotEmpty()) {
                            val decodedHtml = String(Base64.decode(cleanBase64, Base64.DEFAULT), Charsets.UTF_8)
                            if (cont.isActive) cont.resume(decodedHtml)
                        } else {
                            if (cont.isActive) cont.resume("")
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume("")
                    } finally {
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                    }
                }
            }, "AndroidBridgeHtml")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val js = """
                        (function() {
                            var attempts = 0;
                            var timer = setInterval(function() {
                                attempts++;
                                var title = document.title || "";
                                if (title.includes("Just a moment") || title.includes("Cloudflare") || title.includes("Attention Required")) {
                                    return; // Still waiting on Cloudflare
                                }
                                
                                var table = document.getElementById('chapter_table');
                                if (table || attempts >= 15) {
                                    clearInterval(timer);
                                    try {
                                        var b64 = btoa(unescape(encodeURIComponent(document.documentElement.outerHTML)));
                                        window.AndroidBridgeHtml.onHtmlExtracted(b64);
                                    } catch(e) {
                                        window.AndroidBridgeHtml.onHtmlExtracted('ERROR');
                                    }
                                }
                            }, 1000);
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js, null)
                }
            }
            webView.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun getHtmlForImages(context: Context, url: String): String = suspendCancellableCoroutine { cont ->
        var isResumed = false
        val handler = Handler(Looper.getMainLooper())

        val timeoutRunnable = Runnable {
            if (!isResumed) {
                isResumed = true
                if (cont.isActive) cont.resume("")
            }
        }
        handler.postDelayed(timeoutRunnable, 45000) // 45s timeout for heavy image page

        handler.post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

            webView.addJavascriptInterface(object : Any() {
                @JavascriptInterface
                fun onHtmlExtracted(base64Html: String) {
                    if (isResumed) return
                    isResumed = true
                    handler.removeCallbacks(timeoutRunnable)
                    try {
                        val cleanBase64 = base64Html.removeSurrounding("\"")
                        if (cleanBase64 != "ERROR" && cleanBase64.isNotEmpty()) {
                            val decodedHtml = String(Base64.decode(cleanBase64, Base64.DEFAULT), Charsets.UTF_8)
                            if (cont.isActive) cont.resume(decodedHtml)
                        } else {
                            if (cont.isActive) cont.resume("")
                        }
                    } catch (e: Exception) { if (cont.isActive) cont.resume("") }
                    finally { Handler(Looper.getMainLooper()).post { webView.destroy() } }
                }
            }, "AndroidBridgeHtml")

            webView.webViewClient = object : WebViewClient() {

                // THE MAGIC PILL FOR JUMBLED CANVASES: We intercept the image network request,
                // download it ourselves, and inject a Fake CORS header so the canvas becomes "untainted".
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val urlStr = request.url.toString()
                    if (request.method.equals("GET", ignoreCase = true) &&
                        (urlStr.contains("mangapicgallery") || urlStr.contains("imgfiles"))) {
                        try {
                            val okReq = Request.Builder()
                                .url(urlStr)
                                .addHeader("Referer", url)
                                .addHeader("User-Agent", view.settings.userAgentString)
                                .build()

                            val response = ParallelDownloader.client.newCall(okReq).execute()
                            if (response.isSuccessful) {
                                val inputStream = response.body?.byteStream()
                                if (inputStream != null) {
                                    val headers = mutableMapOf<String, String>()
                                    headers["Access-Control-Allow-Origin"] = "*" // Bypass Canvas Tainting!
                                    headers["Access-Control-Allow-Methods"] = "GET, OPTIONS"

                                    var mimeType = "image/jpeg"
                                    response.body?.contentType()?.let { mimeType = "${it.type}/${it.subtype}" }

                                    return WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, inputStream)
                                }
                            }
                        } catch (e: Exception) {
                            // Fall through to default behavior if interception fails
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    val scrollJs = """
                        (function() {
                            var scrolls = 0;
                            var maxScrolls = 80;
                            var timer = setInterval(function() {
                                var title = document.title || "";
                                if (title.includes("Just a moment") || title.includes("Cloudflare")) return;
                                
                                window.scrollBy(0, 1500); // Scroll down
                                scrolls++;
                                
                                document.querySelectorAll('img').forEach(img => {
                                    var dSrc = img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy-src');
                                    if (dSrc && img.getAttribute('src') !== dSrc) {
                                        img.setAttribute('src', dSrc);
                                    }
                                });

                                if (scrolls >= maxScrolls || (window.innerHeight + window.scrollY) >= document.body.scrollHeight) {
                                    clearInterval(timer);
                                    
                                    setTimeout(function() {
                                        // 1. STANDARD CANVAS HANDLING (Now Un-tainted!)
                                        document.querySelectorAll('canvas').forEach(function(c) {
                                            try {
                                                var i = document.createElement('img');
                                                i.src = c.toDataURL('image/jpeg', 0.95);
                                                i.className = c.className + ' extracted-canvas'; // Tag it for Kotlin
                                                i.id = c.id;
                                                c.parentNode.replaceChild(i, c);
                                            } catch(e) {} 
                                        });

                                        // 2. BULLETPROOF NETWORK INTERCEPTION
                                        var resources = window.performance.getEntriesByType("resource");
                                        resources.forEach(function(r) {
                                            var u = r.name.toLowerCase();
                                            // Aggressively exclude UI Elements from the injection
                                            if ((r.initiatorType === 'img' || r.initiatorType === 'css' || r.initiatorType === 'xmlhttprequest') && 
                                                (u.includes('imgfiles') || u.includes('picgallery'))) {
                                                
                                                if (!u.includes('icon') && !u.includes('btn') && !u.includes('next') && !u.includes('prev') && !u.includes('bg')) {
                                                    var img = document.createElement('img');
                                                    img.src = r.name;
                                                    img.className = 'injected-perf-img';
                                                    img.style.display = 'none';
                                                    document.body.appendChild(img);
                                                }
                                            }
                                        });

                                        setTimeout(function() {
                                            try {
                                                var b64 = btoa(unescape(encodeURIComponent(document.documentElement.outerHTML)));
                                                window.AndroidBridgeHtml.onHtmlExtracted(b64);
                                            } catch(e) { window.AndroidBridgeHtml.onHtmlExtracted('ERROR'); }
                                        }, 800); // Small wait for DOM to update
                                    }, 1500); // Wait 1.5s for final images to load in
                                }
                            }, 100); 
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(scrollJs, null)
                }
            }
            webView.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun downloadImageAsBase64(
        context: Context,
        imageUrl: String,
        targetFolder: DocumentFile,
        fileName: String,
        referer: String
    ): Boolean = suspendCancellableCoroutine { cont ->
        var isResumed = false
        val handler = Handler(Looper.getMainLooper())

        val timeoutRunnable = Runnable {
            if (!isResumed) {
                isResumed = true
                if (cont.isActive) cont.resume(false)
            }
        }
        handler.postDelayed(timeoutRunnable, 30000) // 30s timeout per fallback image

        handler.post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

            webView.addJavascriptInterface(object : Any() {
                @JavascriptInterface
                fun onImageDownloaded(base64Data: String) {
                    if (isResumed) return
                    isResumed = true
                    handler.removeCallbacks(timeoutRunnable)
                    try {
                        if (base64Data == "ERROR" || base64Data.isEmpty()) {
                            if (cont.isActive) cont.resume(false)
                        } else {
                            val cleanBase64 = base64Data.substringAfter("base64,")
                            val imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                            val imageFile = targetFolder.createFile("image/jpeg", fileName)

                            if (imageFile != null) {
                                context.contentResolver.openOutputStream(imageFile.uri)?.use { it.write(imageBytes) }
                                if (cont.isActive) cont.resume(true)
                            } else {
                                if (cont.isActive) cont.resume(false)
                            }
                        }
                    } catch (e: Exception) { if (cont.isActive) cont.resume(false) }
                    finally { Handler(Looper.getMainLooper()).post { webView.destroy() } }
                }
            }, "AndroidBridge")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    val js = """
                        fetch('$imageUrl', { headers: { 'Referer': '$referer' } })
                        .then(response => response.blob())
                        .then(blob => {
                            var reader = new FileReader();
                            reader.onloadend = function() { window.AndroidBridge.onImageDownloaded(reader.result); }
                            reader.readAsDataURL(blob);
                        })
                        .catch(e => {
                            var img = new Image();
                            img.crossOrigin = 'Anonymous';
                            img.onload = function() {
                                var canvas = document.createElement('canvas');
                                canvas.width = img.width;
                                canvas.height = img.height;
                                canvas.getContext('2d').drawImage(img, 0, 0);
                                window.AndroidBridge.onImageDownloaded(canvas.toDataURL('image/jpeg'));
                            };
                            img.onerror = function() { window.AndroidBridge.onImageDownloaded('ERROR'); };
                            img.src = '$imageUrl';
                        });
                    """.trimIndent()
                    view.evaluateJavascript(js, null)
                }
            }
            webView.loadUrl(referer)
        }
    }
}


object ParallelDownloader {

    // Made public so the WebView Interceptor can use it to bypass CORS
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun downloadChapters(
        context: Context,
        chaptersToDownload: List<Chapter>,
        baseFolderUri: Uri,
        cookieString: String,
        userAgent: String,
        isSequential: Boolean,
        progressFlow: MutableStateFlow<DownloadProgress>
    ) = withContext(Dispatchers.IO) {

        val rootFolder = DocumentFile.fromTreeUri(context, baseFolderUri)
        if (rootFolder == null || !rootFolder.canWrite()) {
            progressFlow.value = DownloadProgress("Error: Cannot write to selected folder.")
            return@withContext
        }

        val seriesFolder = rootFolder.createDirectory("Manga_Downloads") ?: rootFolder

        progressFlow.value = progressFlow.value.copy(
            logMessage = "Starting download... ${chaptersToDownload.size} chapters in queue.",
            chaptersInQueue = chaptersToDownload.size
        )

        chaptersToDownload.forEachIndexed { index, chapter ->
            val remainingQueue = chaptersToDownload.size - index
            progressFlow.value = progressFlow.value.copy(
                logMessage = "Scrolling & Scraping images for ${chapter.title} (Takes a few seconds)...",
                chaptersInQueue = remainingQueue
            )

            val imageUrls = fetchImageUrlsFromChapter(context, chapter.url)

            if (imageUrls.isEmpty()) {
                progressFlow.value = progressFlow.value.copy(logMessage = "No images found for ${chapter.title}.")
                return@forEachIndexed
            }

            val safeChapterName = chapter.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            val chapterFolder = seriesFolder.createDirectory(safeChapterName) ?: seriesFolder

            progressFlow.value = progressFlow.value.copy(
                logMessage = if (isSequential) "Downloading ${chapter.title} sequentially..." else "Downloading ${chapter.title} in parallel...",
                totalImagesInCurrentChapter = imageUrls.size,
                imagesDownloadedForCurrentChapter = 0
            )

            var downloadedCount = 0

            if (isSequential) {
                imageUrls.forEachIndexed { imageIndex, imageUrl ->
                    val success = downloadAndSaveImage(
                        context = context,
                        client = client,
                        imageUrl = imageUrl,
                        cookieString = cookieString,
                        userAgent = userAgent,
                        referer = chapter.url,
                        targetFolder = chapterFolder,
                        fileName = "page_${String.format("%03d", imageIndex + 1)}.jpg"
                    )

                    if (success) {
                        downloadedCount++
                        progressFlow.value = progressFlow.value.copy(
                            imagesDownloadedForCurrentChapter = downloadedCount,
                            logMessage = "Downloaded page ${imageIndex + 1} of ${chapter.title}"
                        )
                    }
                }
            } else {
                val deferredDownloads = imageUrls.mapIndexed { imageIndex, imageUrl ->
                    async(Dispatchers.IO) {
                        val success = downloadAndSaveImage(
                            context = context,
                            client = client,
                            imageUrl = imageUrl,
                            cookieString = cookieString,
                            userAgent = userAgent,
                            referer = chapter.url,
                            targetFolder = chapterFolder,
                            fileName = "page_${String.format("%03d", imageIndex + 1)}.jpg"
                        )

                        if (success) {
                            downloadedCount++
                            progressFlow.value = progressFlow.value.copy(
                                imagesDownloadedForCurrentChapter = downloadedCount,
                                logMessage = "Downloaded page ${imageIndex + 1} of ${chapter.title}"
                            )
                        }
                    }
                }
                deferredDownloads.awaitAll()
            }
        }

        progressFlow.value = progressFlow.value.copy(
            logMessage = "All downloads complete!",
            chaptersInQueue = 0
        )
    }

    private suspend fun fetchImageUrlsFromChapter(context: Context, chapterUrl: String): List<String> {
        return try {
            val html = HeavyDutyWebScraper.getHtmlForImages(context, chapterUrl)
            val document = Jsoup.parse(html, chapterUrl)
            extractImageUrlsFromDocument(document)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractImageUrlsFromDocument(document: org.jsoup.nodes.Document): List<String> {
        // Prioritize our perfectly extracted unscrambled canvases and main containers
        var images = document.select("img.extracted-canvas, a#pic_container img, div#readerarea img, div.reading-content img, div.page-break img, div.reader-images img, div#picture img, div.page-chapter img, img.injected-perf-img")

        if (images.isEmpty()) {
            images = document.select("img").not(".sidebar img, .widget img, header img, footer img, .recommend img, .related img")
        }

        return images.mapNotNull { img ->
            val src = img.attr("abs:data-src")
                .ifEmpty { img.attr("abs:data-lazy-src") }
                .ifEmpty { img.attr("abs:data-original") }
                .ifEmpty { img.attr("abs:src") }
            src
        }.filter { url ->
            val lowerUrl = url.lowercase()
            // Aggressive filtering against UI elements
            lowerUrl.isNotEmpty() &&
                    !lowerUrl.contains("loading") &&
                    !lowerUrl.contains("spinner") &&
                    !lowerUrl.contains("avatar") &&
                    !lowerUrl.contains("logo") &&
                    !lowerUrl.contains("icon") &&
                    !lowerUrl.contains("btn") &&
                    !lowerUrl.contains("button") &&
                    !lowerUrl.contains("next") &&
                    !lowerUrl.contains("prev") &&
                    !lowerUrl.contains("bg") &&
                    !lowerUrl.endsWith(".gif")
        }.distinct()
    }

    private suspend fun downloadAndSaveImage(
        context: Context,
        client: OkHttpClient,
        imageUrl: String,
        cookieString: String,
        userAgent: String,
        referer: String,
        targetFolder: DocumentFile,
        fileName: String
    ): Boolean {
        // FAST PATH FOR CANVAS IMAGES: Unscrambled Base64 data (converted natively by our scraper)
        if (imageUrl.startsWith("data:image")) {
            return try {
                val base64Data = imageUrl.substringAfter("base64,")
                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val imageFile = targetFolder.createFile("image/jpeg", fileName)
                if (imageFile != null) {
                    context.contentResolver.openOutputStream(imageFile.uri)?.use { it.write(imageBytes) }
                    true
                } else false
            } catch (e: Exception) {
                false
            }
        }

        // Standard Web URL download
        var successFastPath = false
        try {
            val request = Request.Builder()
                .url(imageUrl)
                .addHeader("Cookie", cookieString)
                .addHeader("User-Agent", userAgent.ifEmpty { "Mozilla/5.0" })
                .addHeader("Referer", referer)
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Connection", "keep-alive")
                .addHeader("Sec-Fetch-Dest", "image")
                .addHeader("Sec-Fetch-Mode", "no-cors")
                .addHeader("Sec-Fetch-Site", "cross-site")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val contentType = response.body?.contentType()?.type
                    if (contentType == "image" || contentType == "application" || imageUrl.contains(".png") || imageUrl.contains(".jpg")) {
                        val inputStream: InputStream? = response.body?.byteStream()
                        val imageFile = targetFolder.createFile("image/jpeg", fileName)

                        if (inputStream != null && imageFile != null) {
                            context.contentResolver.openOutputStream(imageFile.uri)?.use { outputStream ->
                                inputStream.copyTo(outputStream)
                                successFastPath = true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            successFastPath = false
        }

        if (!successFastPath) {
            return HeavyDutyWebScraper.downloadImageAsBase64(context, imageUrl, targetFolder, fileName, referer)
        }
        return true
    }
}