package app.mangareader.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mangareader.mobile.ui.Screens.BrowserScreen
import app.mangareader.mobile.ui.Screens.MainScreen
import app.mangareader.mobile.ui.Screens.ProgressScreen
import app.mangareader.mobile.ui.theme.MangaReaderTheme
import app.mangareader.mobile.viewmodel.ScraperViewModel

enum class CurrentScreen { MAIN, BROWSER, PROGRESS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MangaReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val scraperViewModel: ScraperViewModel = viewModel()

                    // This is the ONLY navigation state we need now
                    var currentScreen by remember { mutableStateOf(CurrentScreen.MAIN) }

                    when (currentScreen) {
                        CurrentScreen.BROWSER -> {
                            BrowserScreen(
                                initialUrl = "https://www.mangago.me",
                                onCookiesGrabbed = { cookieString, userAgent ->
                                    scraperViewModel.saveCookie(cookieString)
                                    scraperViewModel.saveUserAgent(userAgent)
                                    val message = if (cookieString.isNotEmpty()) {
                                        "Cookie grabbed successfully!"
                                    } else {
                                        "No cookies found."
                                    }
                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                                    currentScreen = CurrentScreen.MAIN
                                }
                            )
                        }
                        CurrentScreen.PROGRESS -> {
                            ProgressScreen(
                                viewModel = scraperViewModel,
                                onBackClicked = { currentScreen = CurrentScreen.MAIN }
                            )
                        }
                        CurrentScreen.MAIN -> {
                            MainScreen(
                                viewModel = scraperViewModel,
                                onNavigateToBrowser = { currentScreen = CurrentScreen.BROWSER },
                                onNavigateToProgress = { startChap, maxChap ->
                                    scraperViewModel.startDownloading(applicationContext, startChap, maxChap)
                                    currentScreen = CurrentScreen.PROGRESS
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}