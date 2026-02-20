package tachiyomi.data.pagebookmarks

import tachiyomi.domain.pagebookmarks.model.PageBookmark

object PageBookmarkMapper {
    fun map(
        id: Long,
        mangaId: Long,
        chapterId: Long,
        chapterUrl: String,
        chapterName: String,
        pageIndex: Long,
        scrollOffset: Double,
        imageUrl: String,
        cropTop: Double,
        cropBottom: Double,
        addedAt: Long,
        note: String,
    ): PageBookmark = PageBookmark(
        id = id,
        mangaId = mangaId,
        chapterId = chapterId,
        chapterUrl = chapterUrl,
        chapterName = chapterName,
        pageIndex = pageIndex.toInt(),
        scrollOffset = scrollOffset,
        imageUrl = imageUrl,
        cropTop = cropTop,
        cropBottom = cropBottom,
        addedAt = addedAt,
        note = note,
    )
}
