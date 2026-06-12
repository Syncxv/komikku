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

    suspend fun awaitMangaAndChapter(
        id: Long,
        newMangaId: Long,
        chapterId: Long,
        chapterUrl: String,
        chapterName: String,
        chapterNumber: Double,
        scanlator: String?,
    ) {
        repository.updateMangaAndChapterInfo(id, newMangaId, chapterId, chapterUrl, chapterName, chapterNumber, scanlator)
    }

    /**
     * Inserts a copy of [source] re-pointed at another manga/chapter, leaving the original intact.
     * Used by migration's "Copy" path so the source manga keeps its own bookmarks.
     */
    suspend fun awaitCopyToMangaAndChapter(
        source: PageBookmark,
        newMangaId: Long,
        chapterId: Long,
        chapterUrl: String,
        chapterName: String,
        chapterNumber: Double,
        scanlator: String?,
    ): Long {
        return repository.insert(
            source.copy(
                id = 0,
                mangaId = newMangaId,
                chapterId = chapterId,
                chapterUrl = chapterUrl,
                chapterName = chapterName,
                chapterNumber = chapterNumber,
                scanlator = scanlator,
            ),
        )
    }
}
