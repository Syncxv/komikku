package mihon.core.migration.migrations

import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.pagebookmarks.PageBookmarkMapper
import tachiyomi.data.pagebookmarks.PageBookmarksDatabase
import tachiyomi.domain.chapter.repository.ChapterRepository

class PageBookmarksChapterInfoMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        logcat(LogPriority.INFO) { "Running PageBookmarksChapterInfoMigration ALWAYS" }

        val preferenceStore = migrationContext.get<tachiyomi.core.common.preference.PreferenceStore>() ?: return false
        val isMigrated = preferenceStore.getBoolean("page_bookmarks_migration_done", false)
        if (isMigrated.get()) return true

        val chapterRepository = migrationContext.get<ChapterRepository>() ?: return false
        val pageBookmarksDb = migrationContext.get<PageBookmarksDatabase>() ?: return false

        try {
            val bookmarks = pageBookmarksDb.pageBookmarksQueries.getAll(PageBookmarkMapper::map).executeAsList()

            for (bookmark in bookmarks) {
                if (bookmark.chapterNumber != -1.0) continue
                val chapter = chapterRepository.getChapterById(bookmark.chapterId)
                if (chapter != null) {
                    pageBookmarksDb.pageBookmarksQueries.updateChapterInfo(
                        chapterNumber = chapter.chapterNumber,
                        scanlator = chapter.scanlator,
                        id = bookmark.id,
                    )
                }
            }

            // Mark as completed
            isMigrated.set(true)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to migrate page bookmarks chapter info" }
            return false
        }
        return true
    }
}
