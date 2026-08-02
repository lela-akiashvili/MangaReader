package app.domain.model

data class MangaChapter(
    val name: String,
    val zipEntryPath: String = "",
    val isZip: Boolean = false
)