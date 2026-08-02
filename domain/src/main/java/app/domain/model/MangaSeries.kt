package app.domain.model

data class MangaSeries(
    val title: String,
    val folderUriStr: String,
    val downloadTimestamp: Long = 0L,
    val coverUriStr: String? = null
)