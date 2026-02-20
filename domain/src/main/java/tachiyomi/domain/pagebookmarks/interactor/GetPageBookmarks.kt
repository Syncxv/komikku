package tachiyomi.domain.pagebookmarks.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.pagebookmarks.model.PageBookmark
import tachiyomi.domain.pagebookmarks.repository.PageBookmarkRepository

class GetPageBookmarks(
    private val repository: PageBookmarkRepository,
) {

    fun subscribeForManga(mangaId: Long): Flow<List<PageBookmark>> {
        return repository.getBookmarksForMangaAsFlow(mangaId)
    }

    suspend fun awaitForManga(mangaId: Long): List<PageBookmark> {
        return repository.getBookmarksForManga(mangaId)
    }

    suspend fun awaitAll(): List<PageBookmark> {
        return repository.getAll()
    }
}
