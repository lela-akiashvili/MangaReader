package app.mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.data.datasource.LocalFileDataSource
import app.data.repository.MangaRepositoryImpl
import app.designsystem.theme.MangaTheme
import app.domain.usecase.GetLibraryUseCase
import app.mobile.presentation.feature.home.HomeScreen
import app.mobile.presentation.feature.notification.NotificationScreen
import app.mobile.presentation.feature.reader.ReaderScreen
import app.mobile.presentation.feature.scraper.ScraperScreen
import app.mobile.presentation.feature.startup.StartupScreen
import app.mobile.presentation.navigation.Screen.Home
import app.mobile.presentation.navigation.Screen.Notifications
import app.mobile.presentation.navigation.Screen.Reader
import app.mobile.presentation.navigation.Screen.Scraper
import app.mobile.presentation.navigation.Screen.Startup

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Manual Dependency Injection Wiring
        val localDataSource = LocalFileDataSource(applicationContext)
        val repository = MangaRepositoryImpl(localDataSource)
        val getLibraryUseCase = GetLibraryUseCase(repository)
        val homeViewModel = _root_ide_package_.app.mobile.presentation.feature.home.HomeViewModel(
            getLibraryUseCase
        )

        setContent {
            var isDarkTheme by remember { mutableStateOf(true) }
            var selectedRootUri by remember { mutableStateOf<Uri?>(null) }
            val navController = rememberNavController()

            MangaTheme(isDark = isDarkTheme) {
                NavHost(
                    navController = navController,
                    startDestination = if (selectedRootUri != null) Home else Startup
                ) {
                    composable<Startup> {
                        StartupScreen(
                            onSyncClick = { uri ->
                                selectedRootUri = uri
                                navController.navigate(Home)
                            },
                            onThemeToggle = { isDarkTheme = !isDarkTheme },
                            isDark = isDarkTheme
                        )
                    }

                    composable<Home> {
                        selectedRootUri?.let { uri ->
                            HomeScreen(
                                rootFolderUri = uri,
                                viewModel = homeViewModel,
                                onSeriesClick = { title, seriesUri ->
                                    navController.navigate(Reader(title, seriesUri))
                                },
                                onScraperClick = { navController.navigate(Scraper) },
                                onNotificationsClick = { navController.navigate(Notifications) }
                            )
                        }
                    }

                    composable<Scraper> {
                        ScraperScreen(onBackClick = { navController.popBackStack() })
                    }

                    composable<Notifications> {
                        NotificationScreen(onBackClick = { navController.popBackStack() })
                    }

                    composable<Reader> { backStackEntry ->
                        val route: Reader = backStackEntry.toRoute()
                        ReaderScreen(
                            seriesTitle = route.seriesTitle,
                            onBackClick = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}