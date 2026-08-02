package app.mobile.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface Screen {

    @Serializable
    data object Startup : Screen

    @Serializable
    data object Home : Screen

    @Serializable
    data object Scraper : Screen

    @Serializable
    data object Notifications : Screen

    @Serializable
    data class Reader(
        val seriesTitle: String,
        val seriesUriStr: String
    ) : Screen
}