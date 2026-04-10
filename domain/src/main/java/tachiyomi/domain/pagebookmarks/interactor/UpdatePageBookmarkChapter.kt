package tachiyomi.domain.pagebookmarks.interactor

import tachiyomi.domain.pagebookmarks.model.PageBookmark
import tachiyomi.domain.pagebookmarks.repository.PageBookmarkRepository

class UpdatePageBookmarkChapter(
    private val repository: PageBookmarkRepository,
) {

    suspend fun await(
        id: Long,
        chapterId: Long,
        chapterUrl: String,
        chapterName: String,
        chapterNumber: Double,
        scanlator: String?,
    ) {
        repository.updateChapterAndInfo(id, chapterId, chapterUrl, chapterName, chapterNumber, scanlator)
    }
}
