package app.mangareader.mobile.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class ExtractedPage(val index: Int, val type: String, val data: String)

class WebViewScraper(private val context: Context) {

    private var webView: WebView? = null
    private val extractedPages = mutableListOf<ExtractedPage>()
    private var isExtractionComplete = false

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun scrapeChapter(url: String): List<ExtractedPage> = withContext(Dispatchers.Main) {
        extractedPages.clear()
        isExtractionComplete = false

        // 1. Initialize Headless WebView
        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                blockNetworkImage = false
                // Fake User Agent to bypass basic bot checks
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            // Allow third party cookies (often needed for image CDNs)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            // Inject the Kotlin-JS Bridge
            addJavascriptInterface(ScraperBridge(), "AndroidBridge")
        }

        // 2. Load the page and wait for it to finish
        val pageLoaded = suspendCancellableCoroutine<Boolean> { continuation ->
            webView?.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                // Optional: Intercept requests here (similar to Python's handle_response)
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView?.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d("ScraperJS", consoleMessage?.message() ?: "")
                    return true
                }
            }

            webView?.loadUrl(url)
        }

        if (pageLoaded) {
            // 3. Inject Javascript to handle scrolling and canvas extraction
            val jsScript = """
                (async function() {
                    try {
                        console.log("Starting JS extraction...");
                        
                        // Scroll logic to trigger lazy loading (like Python script)
                        let lastScroll = -1;
                        let stuckCounter = 0;
                        
                        while (true) {
                            window.scrollBy(0, window.innerHeight * 0.8);
                            await new Promise(r => setTimeout(r, 500)); // Wait 500ms
                            
                            let currentScroll = window.scrollY;
                            if (Math.abs(currentScroll - lastScroll) < 5) {
                                stuckCounter++;
                                if (stuckCounter >= 3) break; // Reached bottom
                            } else {
                                stuckCounter = 0;
                                lastScroll = currentScroll;
                            }
                        }
                        
                        console.log("Scrolling finished. Extracting images...");
                        
                        // Select images or canvas elements
                        let elements = document.querySelectorAll('#pic_container img, #pic_container canvas, .page-chapter img');
                        
                        if (elements.length === 0) {
                             elements = document.querySelectorAll('img'); // Fallback
                        }

                        for(let i = 0; i < elements.length; i++) {
                            let el = elements[i];
                            let tag = el.tagName.toLowerCase();
                            
                            // Filter out UI icons
                            let src = el.src || "";
                            if(src.includes("ajax-loader") || src.includes("logo") || src.includes("icon")) {
                                continue;
                            }
                            
                            if (tag === 'canvas') {
                                let b64 = el.toDataURL('image/jpeg', 0.95);
                                AndroidBridge.onPageFound(i, 'base64', b64);
                            } else if (tag === 'img' && src !== "") {
                                AndroidBridge.onPageFound(i, 'url', src);
                            }
                        }
                        
                        AndroidBridge.onExtractionComplete();
                    } catch (e) {
                        console.error("JS Error: " + e.message);
                        AndroidBridge.onExtractionComplete();
                    }
                })();
            """.trimIndent()

            webView?.evaluateJavascript(jsScript, null)

            // 4. Wait for JS to finish passing data back to Kotlin
            while (!isExtractionComplete) {
                delay(200)
            }
        }

        webView?.destroy()
        webView = null

        return@withContext extractedPages.toList()
    }

    private inner class ScraperBridge {
        @JavascriptInterface
        fun onPageFound(index: Int, type: String, data: String) {
            extractedPages.add(ExtractedPage(index, type, data))
        }

        @JavascriptInterface
        fun onExtractionComplete() {
            isExtractionComplete = true
        }
    }
}

