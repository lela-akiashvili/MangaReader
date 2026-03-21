package app.mangareader.mobile.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import kotlin.coroutines.resume

data class ExtractedPage(val index: Int, val type: String, val data: String)

class HybridScraper(private val context: Context) {
    private val client = OkHttpClient.Builder().build()
    private var webView: WebView? = null
    private val extractedPages = mutableListOf<ExtractedPage>()
    private var extractionDeferred: CompletableDeferred<Unit>? = null
    private var logCallback: (suspend (String) -> Unit)? = null

    private val standardUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    suspend fun parseSeriesPage(url: String, cookieString: String, onLog: suspend (String) -> Unit): List<String> = withContext(Dispatchers.IO) {
        onLog("[Jsoup] Establishing fast connection to Series URL...")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", standardUserAgent)
            .header("Cookie", cookieString)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 503 || response.code == 403) {
                throw Exception("Cloudflare blocked the scanner! Please click 'Login'.")
            }
            val html = response.body?.string() ?: return@withContext emptyList()
            onLog("[Jsoup] HTML downloaded. Parsing DOM...")
            val doc = Jsoup.parse(html, url)

            // Matches the exact tables you provided in your snippet
            val links = doc.select("table.uk-table a.chico, table#chapter_table a.chico, .chapter-list a")
                .map { it.absUrl("href") }
                .filter { it.isNotBlank() }

            return@withContext links.distinct().reversed()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun scrapeChapter(url: String, cookieString: String, onLog: suspend (String) -> Unit): List<ExtractedPage> = withContext(Dispatchers.Main) {
        try {
            logCallback = onLog
            onLog("[Ghost] Spinning up invisible WebView...")
            extractedPages.clear()
            extractionDeferred = CompletableDeferred()

            setupWebView()
            injectCookies(url, cookieString)

            onLog("[Ghost] Requesting page load...")
            val pageLoaded = suspendCancellableCoroutine<Boolean> { continuation ->
                webView?.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (continuation.isActive) continuation.resume(true)
                    }
                }
                webView?.loadUrl(url)
            }

            if (pageLoaded) {
                onLog("[Ghost] Page loaded. Executing Static DOM Extraction...")
                val jsScript = """
                    (async function() {
                        try {
                            AndroidBridge.sendLog("[JS] Waiting for static DOM to fully load...");
                            let waitAttempts = 0;
                            let elements = [];
                            let lastCount = -1;
                            let stableCount = 0;
                            
                            while(waitAttempts < 40) {
                                // Explicitly target the elements from your snippet
                                elements = document.querySelectorAll('#pic_container img[id^="page"], .page-wrap img[id^="page"], canvas');
                                
                                if (elements.length > 0) {
                                    if (elements.length === lastCount) {
                                        stableCount++;
                                        // Wait until the number of images hasn't changed for 2 full seconds
                                        if (stableCount >= 4) break;
                                    } else {
                                        stableCount = 0;
                                        lastCount = elements.length;
                                    }
                                }
                                
                                await new Promise(r => setTimeout(r, 500));
                                waitAttempts++;
                            }

                            if(elements.length === 0) {
                                AndroidBridge.sendLog("[JS-Error] Timeout. No images found in #pic_container or .page-wrap.");
                                AndroidBridge.onExtractionComplete();
                                return;
                            }

                            AndroidBridge.sendLog("[JS] DOM Stabilized! Extracting " + elements.length + " elements...");

                            let validCount = 0;
                            for(let i = 0; i < elements.length; i++) {
                                let el = elements[i];
                                let tag = el.tagName.toLowerCase();
                                
                                if (tag === 'canvas') {
                                    let b64 = el.toDataURL('image/jpeg', 0.95);
                                    AndroidBridge.onPageFound(validCount++, 'base64', b64);
                                } else if (tag === 'img') {
                                    let src = el.getAttribute('data-src') || el.getAttribute('data-original') || el.src;
                                    if(src && !src.includes("ajax") && !src.includes("blank") && !src.includes("loading") && !src.includes("logo")) {
                                        if (src.startsWith('//')) src = 'https:' + src;
                                        else if (src.startsWith('/')) src = window.location.origin + src;
                                        AndroidBridge.onPageFound(validCount++, 'url', src.trim());
                                    }
                                }
                            }
                            AndroidBridge.sendLog("[JS] Successfully verified " + validCount + " source URLs.");
                            AndroidBridge.onExtractionComplete();
                        } catch (e) {
                            AndroidBridge.sendLog("[JS-Error] " + e.message);
                            AndroidBridge.onExtractionComplete();
                        }
                    })();
                """.trimIndent()

                webView?.evaluateJavascript(jsScript, null)
                extractionDeferred?.await()
            }
        } finally {
            cleanupWebView()
        }
        return@withContext extractedPages.toList()
    }

    private fun injectCookies(url: String, cookieString: String) {
        if (cookieString.isNotBlank()) {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val domain = try { android.net.Uri.parse(url).host ?: ".mangago.me" } catch (e: Exception) { ".mangago.me" }
            cookieString.split(";").forEach { if (it.isNotBlank()) cookieManager.setCookie(domain, it.trim()) }
            cookieManager.flush()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                userAgentString = standardUserAgent
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(ScraperBridge(), "AndroidBridge")
        }
    }

    private fun cleanupWebView() { webView?.destroy(); webView = null }

    private inner class ScraperBridge {
        @JavascriptInterface
        fun onPageFound(index: Int, type: String, data: String) { extractedPages.add(ExtractedPage(index, type, data)) }
        @JavascriptInterface
        fun onExtractionComplete() { extractionDeferred?.complete(Unit) }
        @JavascriptInterface
        fun sendLog(msg: String) {
            CoroutineScope(Dispatchers.Main).launch {
                logCallback?.invoke(msg)
            }
        }
    }
}