package tachiyomi.domain.pagebookmarks.model

data class PageBookmark(
    val id: Long = 0,
    val mangaId: Long,
    val chapterId: Long,
    val chapterUrl: String,
    val chapterName: String,
    val pageIndex: Int,
    val scrollOffset: Double = 0.0,
    val imageUrl: String = "",
    val cropTop: Double = 0.0,
    val cropBottom: Double = 1.0,
    val addedAt: Long = System.currentTimeMillis(),
    val note: String = "",
)
