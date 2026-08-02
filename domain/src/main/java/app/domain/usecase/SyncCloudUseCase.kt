package app.domain.usecase

import app.domain.repository.MangaRepository

class SyncCloudUseCase(
    private val mangaRepository: MangaRepository
) {
    suspend operator fun invoke(rootUriStr: String, deviceId: String): Result<Unit> {
        if (rootUriStr.isBlank()) {
            return Result.failure(IllegalArgumentException("Storage URI cannot be empty."))
        }
        if (deviceId.isBlank()) {
            return Result.failure(IllegalArgumentException("Device ID must be named before syncing."))
        }
        return mangaRepository.syncToCloud(rootUriStr, deviceId)
    }
}