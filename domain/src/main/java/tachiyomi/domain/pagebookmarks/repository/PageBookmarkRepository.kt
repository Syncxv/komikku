package tachiyomi.domain.pagebookmarks.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.pagebookmarks.model.PageBookmark

interface PageBookmarkRepository {

    fun getBookmarksForMangaAsFlow(mangaId: Long): Flow<List<PageBookmark>>

    suspend fun getBookmarksForManga(mangaId: Long): List<PageBookmark>

    suspend fun getBookmark(id: Long): PageBookmark?

    suspend fun findExisting(mangaId: Long, chapterId: Long, pageIndex: Int, chapterPercentage: Double): PageBookmark?

    suspend fun insert(bookmark: PageBookmark): Long

    suspend fun delete(id: Long)

    suspend fun deleteForManga(mangaId: Long)

    suspend fun deleteForChapter(chapterId: Long)

    suspend fun updateNote(id: Long, note: String)

    suspend fun updateChapterAndInfo(id: Long, chapterId: Long, chapterUrl: String, chapterName: String, chapterNumber: Double, scanlator: String?)

    suspend fun updateChapterPercentage(id: Long, chapterPercentage: Double)

    /** Backfills [chapterPercentage] for legacy bookmarks (percentage < 0) in a chapter, given its page count. */
    suspend fun backfillChapterPercentages(chapterId: Long, pageCount: Int)

    suspend fun updateMangaAndChapterInfo(id: Long, newMangaId: Long, chapterId: Long, chapterUrl: String, chapterName: String, chapterNumber: Double, scanlator: String?)

    suspend fun getAll(): List<PageBookmark>
}
