package tachiyomi.data.pagebookmarks

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import tachiyomi.domain.pagebookmarks.model.PageBookmark
import tachiyomi.domain.pagebookmarks.repository.PageBookmarkRepository

class PageBookmarkRepositoryImpl(
    private val db: PageBookmarksDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PageBookmarkRepository {

    override fun getBookmarksForMangaAsFlow(mangaId: Long): Flow<List<PageBookmark>> {
        return db.pageBookmarksQueries.getBookmarksForManga(mangaId, PageBookmarkMapper::map)
            .asFlow()
            .mapToList(dispatcher)
    }

    override suspend fun getBookmarksForManga(mangaId: Long): List<PageBookmark> {
        return withContext(dispatcher) {
            db.pageBookmarksQueries.getBookmarksForManga(mangaId, PageBookmarkMapper::map)
                .executeAsList()
        }
    }

    override suspend fun getBookmark(id: Long): PageBookmark? {
        return withContext(dispatcher) {
            db.pageBookmarksQueries.getBookmark(id, PageBookmarkMapper::map)
                .executeAsOneOrNull()
        }
    }

    override suspend fun findExisting(mangaId: Long, chapterId: Long, pageIndex: Int): PageBookmark? {
        return withContext(dispatcher) {
            db.pageBookmarksQueries.findExisting(
                mangaId = mangaId,
                chapterId = chapterId,
                pageIndex = pageIndex.toLong(),
                mapper = PageBookmarkMapper::map,
            ).executeAsOneOrNull()
        }
    }

    override suspend fun insert(bookmark: PageBookmark) {
        withContext(dispatcher) {
            db.pageBookmarksQueries.insert(
                mangaId = bookmark.mangaId,
                chapterId = bookmark.chapterId,
                chapterUrl = bookmark.chapterUrl,
                chapterName = bookmark.chapterName,
                pageIndex = bookmark.pageIndex.toLong(),
                scrollOffset = bookmark.scrollOffset,
                imageUrl = bookmark.imageUrl,
                cropTop = bookmark.cropTop,
                cropBottom = bookmark.cropBottom,
                addedAt = bookmark.addedAt,
                note = bookmark.note,
            )
        }
    }

    override suspend fun delete(id: Long) {
        withContext(dispatcher) {
            db.pageBookmarksQueries.delete(id)
        }
    }

    override suspend fun deleteForManga(mangaId: Long) {
        withContext(dispatcher) {
            db.pageBookmarksQueries.deleteForManga(mangaId)
        }
    }

    override suspend fun deleteForChapter(chapterId: Long) {
        withContext(dispatcher) {
            db.pageBookmarksQueries.deleteForChapter(chapterId)
        }
    }

    override suspend fun updateNote(id: Long, note: String) {
        withContext(dispatcher) {
            db.pageBookmarksQueries.updateNote(note = note, id = id)
        }
    }

    override suspend fun getAll(): List<PageBookmark> {
        return withContext(dispatcher) {
            db.pageBookmarksQueries.getAll(PageBookmarkMapper::map)
                .executeAsList()
        }
    }
}
