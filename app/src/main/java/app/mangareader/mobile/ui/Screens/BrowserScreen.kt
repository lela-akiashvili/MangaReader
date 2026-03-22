package app.mangareader.mobile.ui.Screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.mangareader.mobile.data.network.CookieExtractor

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String,
    onCookiesGrabbed: (String, String) -> Unit
) {
    // Keep track of the URL the user is currently on so we grab the right cookies
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var userAgent by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Log in, then grab cookies.")

            Button(onClick = {
                CookieManager.getInstance().flush() // Ensure cookies are written to disk
                // Grab the cookie for whatever page the user is currently viewing
                val cookieStr = CookieExtractor.getCookieForUrl(currentUrl)
                onCookiesGrabbed(cookieStr, userAgent)
            }) {
                Text("Grab Cookies")
            }
        }

        // Built-in Browser (WebView)
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { context ->
                WebView(context).apply {
                    // Modern websites require JS and DOM storage to log in properly
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    userAgent = settings.userAgentString

                    webViewClient = object : WebViewClient() {
                        // Update our state whenever the user navigates to a new page
                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            if (url != null) {
                                currentUrl = url
                            }
                        }
                    }
                    loadUrl(initialUrl)
                }
            }
        )
    }
}