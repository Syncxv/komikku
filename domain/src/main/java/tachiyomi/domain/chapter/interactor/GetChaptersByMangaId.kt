package tachiyomi.domain.chapter.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository

class GetChaptersByMangaId(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(mangaId: Long, applyFilter: Boolean = false): List<Chapter> {
        return try {
            chapterRepository.getChapterByMangaId(mangaId, applyFilter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    suspend fun subscribe(mangaId: Long, applyFilter: Boolean = false): kotlinx.coroutines.flow.Flow<List<Chapter>> {
        return try {
            chapterRepository.getChapterByMangaIdAsFlow(mangaId, applyFilter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            kotlinx.coroutines.flow.emptyFlow()
        }
    }
}
