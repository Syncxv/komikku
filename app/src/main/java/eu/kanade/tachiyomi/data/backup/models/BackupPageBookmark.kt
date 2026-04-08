package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.pagebookmarks.model.PageBookmark

// KMK -->
@Serializable
data class BackupPageBookmark(
    @ProtoNumber(1) var chapterUrl: String,
    @ProtoNumber(2) var chapterName: String,
    @ProtoNumber(3) var pageIndex: Int,
    @ProtoNumber(4) var scrollOffset: Double = 0.0,
    @ProtoNumber(5) var imageUrl: String = "",
    @ProtoNumber(6) var cropTop: Double = 0.0,
    @ProtoNumber(7) var cropBottom: Double = 1.0,
    @ProtoNumber(8) var addedAt: Long = 0,
    @ProtoNumber(9) var note: String = "",
    @ProtoNumber(10) var chapterNumber: Double = -1.0,
    @ProtoNumber(11) var scanlator: String? = null,
) {
    fun toPageBookmark(mangaId: Long, chapterId: Long, fallbackChapterNumber: Double = -1.0, fallbackScanlator: String? = null): PageBookmark {
        return PageBookmark(
            mangaId = mangaId,
            chapterId = chapterId,
            chapterUrl = this@BackupPageBookmark.chapterUrl,
            chapterName = this@BackupPageBookmark.chapterName,
            chapterNumber = if (this@BackupPageBookmark.chapterNumber != -1.0) this@BackupPageBookmark.chapterNumber else fallbackChapterNumber,
            scanlator = this@BackupPageBookmark.scanlator ?: fallbackScanlator,
            pageIndex = this@BackupPageBookmark.pageIndex,
            scrollOffset = this@BackupPageBookmark.scrollOffset,
            imageUrl = this@BackupPageBookmark.imageUrl,
            cropTop = this@BackupPageBookmark.cropTop,
            cropBottom = this@BackupPageBookmark.cropBottom,
            addedAt = this@BackupPageBookmark.addedAt,
            note = this@BackupPageBookmark.note,
        )
    }

    companion object {
        fun fromPageBookmark(bookmark: PageBookmark): BackupPageBookmark {
            return BackupPageBookmark(
                chapterUrl = bookmark.chapterUrl,
                chapterName = bookmark.chapterName,
                chapterNumber = bookmark.chapterNumber,
                scanlator = bookmark.scanlator,
                pageIndex = bookmark.pageIndex,
                scrollOffset = bookmark.scrollOffset,
                imageUrl = bookmark.imageUrl,
                cropTop = bookmark.cropTop,
                cropBottom = bookmark.cropBottom,
                addedAt = bookmark.addedAt,
                note = bookmark.note,
            )
        }
    }
}
// KMK <--
