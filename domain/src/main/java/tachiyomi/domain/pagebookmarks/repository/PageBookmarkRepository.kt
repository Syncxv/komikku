package tachiyomi.domain.pagebookmarks.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.pagebookmarks.model.PageBookmark

interface PageBookmarkRepository {

    fun getBookmarksForMangaAsFlow(mangaId: Long): Flow<List<PageBookmark>>

    suspend fun getBookmarksForManga(mangaId: Long): List<PageBookmark>

    suspend fun getBookmark(id: Long): PageBookmark?

    suspend fun findExisting(mangaId: Long, chapterId: Long, pageIndex: Int): PageBookmark?

    suspend fun insert(bookmark: PageBookmark)

    suspend fun delete(id: Long)

    suspend fun deleteForManga(mangaId: Long)

    suspend fun deleteForChapter(chapterId: Long)

    suspend fun updateNote(id: Long, note: String)

    suspend fun getAll(): List<PageBookmark>
}
