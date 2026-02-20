package tachiyomi.domain.pagebookmarks.interactor

import tachiyomi.domain.pagebookmarks.repository.PageBookmarkRepository

class DeletePageBookmark(
    private val repository: PageBookmarkRepository,
) {

    suspend fun awaitById(id: Long) {
        repository.delete(id)
    }

    suspend fun awaitByManga(mangaId: Long) {
        repository.deleteForManga(mangaId)
    }

    suspend fun awaitByChapter(chapterId: Long) {
        repository.deleteForChapter(chapterId)
    }
}
