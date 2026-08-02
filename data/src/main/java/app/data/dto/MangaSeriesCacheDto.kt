package app.data.dto

data class MangaSeriesCacheDto(
    val title: String,
    val folderUriStr: String,
    val lastModified: Long,
    val coverUriStr: String?
)