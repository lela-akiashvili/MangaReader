package app.domain.model

data class MangaTrackerEntry(
    val title: String,
    val url: String,
    val lastChapterName: String,
    val lastChapterNumber: Int
)