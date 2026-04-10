package eu.kanade.tachiyomi.ui.manga.pagebookmarks

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.manga.PageBookmarksScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.pagebookmarks.interactor.DeletePageBookmark
import tachiyomi.domain.pagebookmarks.interactor.GetPageBookmarks
import tachiyomi.domain.pagebookmarks.interactor.UpdatePageBookmarkChapter
import tachiyomi.domain.pagebookmarks.interactor.UpdatePageBookmarkNote
import tachiyomi.domain.pagebookmarks.model.PageBookmark
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class PageBookmarksScreen(
    private val mangaId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = androidx.compose.ui.platform.LocalContext.current

        val screenModel = rememberScreenModel { Model(mangaId, context) }
        val state by screenModel.state.collectAsState()

        PageBookmarksScreen(
            state = state,
            navigateUp = navigator::pop,
            onBookmarkClick = { bookmark ->
                openReaderAtBookmark(context, bookmark)
            },
            onDeleteBookmark = screenModel::deleteBookmark,
            onUpdateNote = screenModel::updateNote,
            onMigrateBookmarks = screenModel::migrateBookmarks,
            getThumbnailFile = screenModel::getThumbnailFile,
            getAutoMatchPreview = screenModel::getAutoMatchPreview,
        )
    }

    private fun openReaderAtBookmark(context: Context, bookmark: PageBookmark) {
        val intent = ReaderActivity.newIntent(
            context = context,
            mangaId = bookmark.mangaId,
            chapterId = bookmark.chapterId,
            page = bookmark.pageIndex,
        ).apply {
            putExtra("scroll_offset", bookmark.scrollOffset)
        }
        context.startActivity(intent)
    }

    private class Model(
        private val mangaId: Long,
        private val context: Context,
        private val getPageBookmarks: GetPageBookmarks = Injekt.get(),
        private val deletePageBookmark: DeletePageBookmark = Injekt.get(),
        private val updatePageBookmarkNote: UpdatePageBookmarkNote = Injekt.get(),
        private val updatePageBookmarkChapter: UpdatePageBookmarkChapter = Injekt.get(),
        private val getManga: GetManga = Injekt.get(),
        private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
        private val updateChapter: UpdateChapter = Injekt.get(),
) : StateScreenModel<State>(State()) {

        private val thumbnailProvider = PageBookmarkThumbnailProvider(context)
        private var manga: Manga? = null

        init {
            screenModelScope.launch(Dispatchers.IO) {
                manga = getManga.await(mangaId)
                val currentManga = manga ?: return@launch
                mutableState.update { it.copy(mangaTitle = currentManga.title) }
                // Load chapter sort order (no filter so all chapters are included)
                val chapters = getChaptersByMangaId.await(mangaId, applyFilter = false)
                val orderMap = chapters.sortedWith(tachiyomi.domain.chapter.service.getChapterSort(currentManga))
                    .mapIndexed { index, chapter -> chapter.id to index }
                    .toMap()
                mutableState.update { it.copy(chapterIdToOrder = orderMap) }
            }

            screenModelScope.launch {
                combine(
                    getPageBookmarks.subscribeForManga(mangaId),
                    getChaptersByMangaId.subscribe(mangaId, applyFilter = false),
                ) { bookmarks, chapters ->
                    val chapterIds = chapters.map { it.id }.toSet()
                    val validBookmarks = bookmarks.filter { it.chapterId in chapterIds }
                    val orphanedBookmarks = bookmarks.filterNot { it.chapterId in chapterIds }

                    val sortedChapters = manga?.let { currentManga ->
                        chapters.sortedWith(tachiyomi.domain.chapter.service.getChapterSort(currentManga))
                    } ?: chapters

                    mutableState.update {
                        it.copy(
                            bookmarks = validBookmarks,
                            orphanedBookmarks = orphanedBookmarks,
                            chapters = sortedChapters,
                            isLoading = false
                        )
                    }
                }.collect { }
            }
        }

        fun deleteBookmark(bookmark: PageBookmark) {
            screenModelScope.launchNonCancellable {
                deletePageBookmark.awaitById(bookmark.id)
                thumbnailProvider.deleteThumbnail(bookmark.id)
            }
        }

        fun updateNote(bookmarkId: Long, note: String) {
            screenModelScope.launchNonCancellable {
                updatePageBookmarkNote.await(bookmarkId, note)
            }
        }

        fun migrateBookmarks(mappings: Map<Long, Chapter>) {
            screenModelScope.launchNonCancellable {
                state.value.orphanedBookmarks.forEach { bookmark ->
                    val newChapter = mappings[bookmark.id]
                    if (newChapter != null) {
                        updatePageBookmarkChapter.await(
                            id = bookmark.id,
                            chapterId = newChapter.id,
                            chapterUrl = newChapter.url,
                            chapterName = newChapter.name,
                            chapterNumber = newChapter.chapterNumber,
                            scanlator = newChapter.scanlator,
                        )
                        updateChapter.await(
                            ChapterUpdate(
                                id = newChapter.id,
                                bookmark = true,
                            )
                        )
                    }
                }
            }
        }

        fun getAutoMatchPreview(orphans: List<PageBookmark>): Map<PageBookmark, Chapter?> {
            val chaptersByNumber = state.value.chapters.groupBy { it.chapterNumber }
            val chaptersByName = state.value.chapters.groupBy { it.name }
            val map = mutableMapOf<PageBookmark, Chapter?>()

            for (orphan in orphans) {
                // If chapter number is -1.0, fallback to matching by exact chapter name
                if (orphan.chapterNumber == -1.0) {
                    val nameCandidates = chaptersByName[orphan.chapterName] ?: emptyList()
                    if (nameCandidates.size == 1) {
                        map[orphan] = nameCandidates.first()
                    } else if (nameCandidates.size > 1) {
                        map[orphan] = nameCandidates.find { it.scanlator == orphan.scanlator } ?: nameCandidates.firstOrNull()
                    } else {
                        map[orphan] = null
                    }
                    continue
                }

                val candidates = chaptersByNumber[orphan.chapterNumber] ?: emptyList()
                if (candidates.size == 1) {
                    map[orphan] = candidates.first()
                } else if (candidates.size > 1) {
                    // Try to match exact scanlator if multiple sharing same number
                    map[orphan] = candidates.find { it.scanlator == orphan.scanlator } ?: candidates.firstOrNull()
                } else {
                    map[orphan] = null
                }
            }
            return map
        }

        fun getThumbnailFile(bookmark: PageBookmark): File? {
            return thumbnailProvider.getThumbnailFile(bookmark, manga)
        }
    }

    @Immutable
    data class State(
        val bookmarks: List<PageBookmark> = emptyList(),
        val orphanedBookmarks: List<PageBookmark> = emptyList(),
        val chapters: List<Chapter> = emptyList(),
        val mangaTitle: String = "",
        val isLoading: Boolean = true,
        val chapterIdToOrder: Map<Long, Int> = emptyMap(),
    )
}
