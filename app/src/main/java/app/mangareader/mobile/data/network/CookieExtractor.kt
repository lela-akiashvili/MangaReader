package app.mangareader.mobile.data.network

import android.webkit.CookieManager

object CookieExtractor {
    /**
     * Grabs the current cookies for a specific URL from the WebView's CookieManager.
     */
    fun getCookieForUrl(url: String): String {
        val cookieManager = CookieManager.getInstance()
        // This returns a string like "session_id=12345; user_pref=dark"
        return cookieManager.getCookie(url) ?: ""
    }
}