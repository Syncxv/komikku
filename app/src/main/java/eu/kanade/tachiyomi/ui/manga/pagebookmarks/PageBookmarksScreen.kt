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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.pagebookmarks.interactor.DeletePageBookmark
import tachiyomi.domain.pagebookmarks.interactor.GetPageBookmarks
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
            getThumbnailFile = screenModel::getThumbnailFile,
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
        private val getManga: GetManga = Injekt.get(),
        private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    ) : StateScreenModel<State>(State()) {

        private val thumbnailProvider = PageBookmarkThumbnailProvider(context)
        private var manga: Manga? = null

        init {
            screenModelScope.launch(Dispatchers.IO) {
                manga = getManga.await(mangaId)
                mutableState.update { it.copy(mangaTitle = manga?.title.orEmpty()) }
                // Load chapter sort order (no filter so all chapters are included)
                val chapters = getChaptersByMangaId.await(mangaId, applyFilter = false)
                val orderMap = chapters.mapIndexed { index, chapter -> chapter.id to index }.toMap()
                mutableState.update { it.copy(chapterIdToOrder = orderMap) }
            }

            screenModelScope.launch {
                getPageBookmarks.subscribeForManga(mangaId)
                    .collect { bookmarks ->
                        mutableState.update { it.copy(bookmarks = bookmarks, isLoading = false) }
                    }
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

        fun getThumbnailFile(bookmark: PageBookmark): File? {
            return thumbnailProvider.getThumbnailFile(bookmark, manga)
        }
    }

    @Immutable
    data class State(
        val bookmarks: List<PageBookmark> = emptyList(),
        val mangaTitle: String = "",
        val isLoading: Boolean = true,
        val chapterIdToOrder: Map<Long, Int> = emptyMap(),
    )
}

