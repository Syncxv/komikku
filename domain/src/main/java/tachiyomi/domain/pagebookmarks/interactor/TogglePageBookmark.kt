package tachiyomi.domain.pagebookmarks.interactor

import tachiyomi.domain.pagebookmarks.model.PageBookmark
import tachiyomi.domain.pagebookmarks.repository.PageBookmarkRepository

class TogglePageBookmark(
    private val repository: PageBookmarkRepository,
) {

    /**
     * Toggles a page bookmark. If one already exists for the same manga/chapter/page,
     * it is removed. Otherwise, a new one is inserted.
     *
     * @return true if a bookmark was added, false if an existing one was removed.
     */
    suspend fun await(bookmark: PageBookmark): Boolean {
        val existing = repository.findExisting(
            mangaId = bookmark.mangaId,
            chapterId = bookmark.chapterId,
            pageIndex = bookmark.pageIndex,
        )
        return if (existing != null) {
            repository.delete(existing.id)
            false
        } else {
            repository.insert(bookmark)
            true
        }
    }
}
