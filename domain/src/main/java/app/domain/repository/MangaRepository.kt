package app.domain.repository

import app.domain.model.MangaChapter
import app.domain.model.MangaSeries
import app.domain.model.MangaTrackerEntry
import kotlinx.coroutines.flow.Flow

interface MangaRepository {
    suspend fun getLibrary(rootUriStr: String): List<MangaSeries>
    suspend fun syncLibrary(rootUriStr: String): List<MangaSeries>
    suspend fun getChapters(seriesTitle: String, seriesUriStr: String): List<MangaChapter>

    suspend fun getTracker(rootUriStr: String): List<MangaTrackerEntry>
    suspend fun bindUrlToTracker(rootUriStr: String, title: String, url: String)

    suspend fun syncToCloud(rootUriStr: String, deviceId: String): Result<Unit>
    fun observeScrapeLogs(): Flow<List<String>>
}