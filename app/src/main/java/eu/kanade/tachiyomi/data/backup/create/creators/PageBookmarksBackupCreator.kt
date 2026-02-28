package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupPageBookmark
import tachiyomi.domain.pagebookmarks.interactor.GetPageBookmarks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK -->
class PageBookmarksBackupCreator(
    private val getPageBookmarks: GetPageBookmarks = Injekt.get(),
) {

    suspend fun backupPageBookmarks(mangaId: Long): List<BackupPageBookmark> {
        return getPageBookmarks.awaitForManga(mangaId).map { bookmark ->
            BackupPageBookmark.fromPageBookmark(bookmark)
        }
    }
}
// KMK <--
