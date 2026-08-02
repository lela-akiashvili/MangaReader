package app.domain.usecase

import app.domain.model.MangaSeries
import app.domain.repository.MangaRepository

enum class SortType {
    NAME_ASC, NAME_DESC, DATE_DESC, DATE_ASC
}

class GetLibraryUseCase(
    private val mangaRepository: MangaRepository
) {
    suspend operator fun invoke(
        rootUriStr: String,
        query: String = "",
        sortType: SortType = SortType.NAME_ASC
    ): List<MangaSeries> {
        val library = mangaRepository.getLibrary(rootUriStr)

        // 1. Search Query Filter
        var filtered = if (query.isBlank()) {
            library
        } else {
            library.filter { it.title.contains(query, ignoreCase = true) }
        }

        // 2. Sorting Logic
        filtered = when (sortType) {
            SortType.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            SortType.NAME_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            SortType.DATE_DESC -> filtered.sortedByDescending { it.downloadTimestamp }
            SortType.DATE_ASC -> filtered.sortedBy { it.downloadTimestamp }
        }

        return filtered
    }
}