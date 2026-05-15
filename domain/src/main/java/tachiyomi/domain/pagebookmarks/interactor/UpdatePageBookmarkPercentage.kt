package tachiyomi.domain.pagebookmarks.interactor

import tachiyomi.domain.pagebookmarks.repository.PageBookmarkRepository

class UpdatePageBookmarkPercentage(
    private val repository: PageBookmarkRepository,
) {
    suspend fun await(id: Long, chapterPercentage: Double) {
        repository.updateChapterPercentage(id, chapterPercentage)
    }
}
