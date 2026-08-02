package app.data.repository

import app.data.datasource.LocalFileDataSource
import app.domain.model.MangaChapter
import app.domain.model.MangaSeries
import app.domain.model.MangaTrackerEntry
import app.domain.repository.MangaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.collections.isNotEmpty

class MangaRepositoryImpl(
    private val localFileDataSource: LocalFileDataSource
) : MangaRepository {

    private val logFlow = MutableStateFlow<List<String>>(emptyList())

    override suspend fun getLibrary(rootUriStr: String): List<MangaSeries> {
        val cached = localFileDataSource.getCachedLibrary(rootUriStr)
        return if (cached.isNotEmpty()) cached else localFileDataSource.syncLibrary(rootUriStr)
    }

    override suspend fun syncLibrary(rootUriStr: String): List<MangaSeries> {
        return localFileDataSource.syncLibrary(rootUriStr)
    }

    override suspend fun getChapters(seriesTitle: String, seriesUriStr: String): List<MangaChapter> {
        return localFileDataSource.getChapters(seriesTitle, seriesUriStr)
    }

    override suspend fun getTracker(rootUriStr: String): List<MangaTrackerEntry> {
        return localFileDataSource.getTracker(rootUriStr)
    }

    override suspend fun bindUrlToTracker(rootUriStr: String, title: String, url: String) {
        localFileDataSource.bindUrlToTracker(rootUriStr, title, url)
    }

    override suspend fun syncToCloud(rootUriStr: String, deviceId: String): Result<Unit> {
        return try {
            val localTracker = localFileDataSource.getTracker(rootUriStr)
            // Call Supabase cloud syncing logic here
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeScrapeLogs(): Flow<List<String>> = logFlow
}