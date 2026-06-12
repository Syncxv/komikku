package tachiyomi.domain.pagebookmarks.interactor

import tachiyomi.domain.pagebookmarks.repository.PageBookmarkRepository

class UpdatePageBookmarkPercentage(
    private val repository: PageBookmarkRepository,
) {
    suspend fun await(id: Long, chapterPercentage: Double) {
        repository.updateChapterPercentage(id, chapterPercentage)
    }

    /**
     * Backfills the chapter percentage for legacy bookmarks (percentage < 0) in [chapterId], using
     * the chapter's now-known [pageCount]. Fires wherever a page count becomes available (reader
     * load, download complete) so legacy bookmarks don't depend on the user scrolling past each page.
     */
    suspend fun awaitBackfillForChapter(chapterId: Long, pageCount: Int) {
        if (pageCount <= 0) return
        repository.backfillChapterPercentages(chapterId, pageCount)
    }
}
