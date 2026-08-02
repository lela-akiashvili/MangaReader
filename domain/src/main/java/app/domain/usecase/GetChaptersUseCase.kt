package app.domain.usecase

import app.domain.model.MangaChapter
import app.domain.repository.MangaRepository

class GetChaptersUseCase(
    private val mangaRepository: MangaRepository
) {
    suspend operator fun invoke(seriesTitle: String, seriesUriStr: String): List<MangaChapter> {
        if (seriesTitle.isBlank() || seriesUriStr.isBlank()) return emptyList()
        return mangaRepository.getChapters(seriesTitle, seriesUriStr)
    }
}