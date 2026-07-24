package mihon.domain.migration.usecases

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import android.app.Application
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.manga.pagebookmarks.PageBookmarkThumbnailProvider
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.pagebookmarks.interactor.DeletePageBookmark
import tachiyomi.domain.pagebookmarks.interactor.GetPageBookmarks
import tachiyomi.domain.pagebookmarks.interactor.UpdatePageBookmarkChapter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.core.common.util.system.logcat
import java.time.Instant

class MigrateMangaUseCase(
    private val sourcePreferences: SourcePreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val coverCache: CoverCache,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    // KMK -->
    private val getHistory: GetHistory,
    private val upsertHistory: UpsertHistory,
    private val getPageBookmarks: GetPageBookmarks,
    private val updatePageBookmarkChapter: UpdatePageBookmarkChapter,
    private val deletePageBookmark: DeletePageBookmark,
    private val context: Application,
    // KMK <--
) {
    private val enhancedServices by lazy { trackerManager.trackers.filterIsInstance<EnhancedTracker>() }

    // KMK -->
    private val pageBookmarkThumbnailProvider by lazy { PageBookmarkThumbnailProvider(context) }
    // KMK <--

    suspend operator fun invoke(
        current: Manga,
        target: Manga,
        replace: Boolean,
        // KMK -->
        presetFlags: Set<MigrationFlag>? = null,
        // KMK <--
        // SY -->
        throttleFunc: suspend () -> Unit = {},
        // SY <--
    ) {
        val targetSource = sourceManager.get(target.source) ?: return
        val currentSource = sourceManager.get(current.source)
        val flags = /* KMK --> */ presetFlags ?: /* KMK <-- */ sourcePreferences.migrationFlags().get()

        try {
            updateMangaFromRemote(
                manga = target,
                fetchChapters = true,
                // SY -->
                throttleFunc = throttleFunc,
                // SY <--
            ).getOrThrow()

            // Update chapters read, bookmark and dateFetch
            if (MigrationFlag.CHAPTER in flags) {
                val prevMangaChapters = getChaptersByMangaId.await(current.id)
                val mangaChapters = getChaptersByMangaId.await(target.id)

                val maxChapterRead = prevMangaChapters
                    .filter { it.read }
                    .maxOfOrNull { it.chapterNumber }

                // SY -->
                val historyUpdates = mutableListOf<HistoryUpdate>()
                val prevHistoryList = getHistory.await(current.id)
                    // SY <--
                    // KMK -->
                    .associateBy { it.chapterId }
                // KMK <--

                val updatedMangaChapters = mangaChapters.map { mangaChapter ->
                    var updatedChapter = mangaChapter
                    if (updatedChapter.isRecognizedNumber) {
                        val prevChapter = prevMangaChapters
                            .find { it.isRecognizedNumber && it.chapterNumber == updatedChapter.chapterNumber }

                        if (prevChapter != null) {
                            updatedChapter = updatedChapter.copy(
                                // SY -->
                                // If chapters match then mark new manga's chapters read/unread as old one
                                read = prevChapter.read,
                                // SY <--
                                dateFetch = prevChapter.dateFetch,
                                bookmark = prevChapter.bookmark,
                                lastPageRead = prevChapter.lastPageRead,
                            )
                            // SY -->
                            // KMK -->
                            prevHistoryList[prevChapter.id]?.let { prevHistory ->
                                // KMK <--
                                historyUpdates += HistoryUpdate(
                                    mangaChapter.id,
                                    prevHistory.readAt ?: return@let,
                                    prevHistory.readDuration,
                                )
                            }
                            // SY <--
                        }
                        // KMK -->
                        // If chapters which only present on new manga then mark read up to latest read chapter number
                        else /* KMK <-- */ if (maxChapterRead != null && updatedChapter.chapterNumber <= maxChapterRead) {
                            updatedChapter = updatedChapter.copy(read = true)
                        }
                    }

                    updatedChapter
                }

                val chapterUpdates = updatedMangaChapters.map { it.toChapterUpdate() }
                updateChapter.awaitAll(chapterUpdates)
                // SY -->
                upsertHistory.awaitAll(historyUpdates)
                // SY <--
            }

            // Update categories
            if (MigrationFlag.CATEGORY in flags) {
                val categoryIds = getCategories.await(current.id).map { it.id }
                setMangaCategories.await(target.id, categoryIds)
            }

            // Update track
            // SY -->
            if (MigrationFlag.TRACK in flags) {
                // SY <--
                getTracks.await(current.id).mapNotNull { track ->
                    val updatedTrack = track.copy(mangaId = target.id)

                    val service = enhancedServices
                        .firstOrNull { it.isTrackFrom(updatedTrack, current, currentSource) }

                    if (service != null) {
                        service.migrateTrack(updatedTrack, target, targetSource)
                    } else {
                        updatedTrack
                    }
                }
                    .takeIf { it.isNotEmpty() }
                    ?.let { insertTrack.awaitAll(it) }
            }

            // Delete downloaded
            if (MigrationFlag.REMOVE_DOWNLOAD in flags && currentSource != null) {
                downloadManager.deleteManga(current, currentSource)
            }

            // Update custom cover (recheck if custom cover exists)
            if (MigrationFlag.CUSTOM_COVER in flags && current.hasCustomCover()) {
                coverCache.setCustomCoverToCache(target, coverCache.getCustomCoverFile(current.id).inputStream())
            }

            // KMK -->
            // Migrate page bookmarks
            if (MigrationFlag.PAGE_BOOKMARKS in flags) {
                val bookmarks = getPageBookmarks.awaitForManga(current.id)
                logcat(LogPriority.INFO, tag = "PageBookmarkMigration") {
                    "Expected bookmarks to migrate: ${bookmarks.size} (mangaId=${current.id})"
                }
                if (bookmarks.isNotEmpty()) {
                    val targetChapters = getChaptersByMangaId.await(target.id)
                    var migratedCount = 0
                    var orphanedCount = 0
                    var duplicateCount = 0
                    // Track which (chapter, page/percentage) slots the target already has so we don't
                    // create duplicate bookmarks. Mirrors PageBookmarks.sq `findExisting` matching.
                    val takenSlots = getPageBookmarks.awaitForManga(target.id)
                        .mapTo(mutableSetOf()) { bookmarkSlotKey(it.chapterId, it.pageIndex, it.chapterPercentage) }
                    for (bookmark in bookmarks) {
                        val matchedChapter = if (bookmark.chapterNumber >= 0.0) {
                            val candidates = targetChapters.filter {
                                it.isRecognizedNumber && it.chapterNumber == bookmark.chapterNumber
                            }
                            when {
                                candidates.size == 1 -> candidates.first()
                                candidates.size > 1 -> candidates.find { it.scanlator == bookmark.scanlator }
                                    ?: candidates.firstOrNull()
                                else -> null
                            }
                        } else {
                            null
                        }

                        // A matched bookmark re-points to the target's chapter; an unmatched one keeps
                        // its original chapter info so it surfaces as an orphan on the target manga.
                        val chapterId = matchedChapter?.id ?: bookmark.chapterId
                        val chapterUrl = matchedChapter?.url ?: bookmark.chapterUrl
                        val chapterName = matchedChapter?.name ?: bookmark.chapterName
                        val chapterNumber = matchedChapter?.chapterNumber ?: bookmark.chapterNumber
                        val scanlator = matchedChapter?.scanlator ?: bookmark.scanlator

                        // Skip if the target already has a bookmark in this slot (from a prior
                        // migration or its own bookmarking); avoids piling up duplicates.
                        val slotKey = bookmarkSlotKey(chapterId, bookmark.pageIndex, bookmark.chapterPercentage)
                        if (!takenSlots.add(slotKey)) {
                            logcat(LogPriority.DEBUG, tag = "PageBookmarkMigration") {
                                "Skipping duplicate bookmark id=${bookmark.id}: target already has slot $slotKey"
                            }
                            // On replace the source manga is going away, so drop the redundant row
                            // (and its now-orphaned thumbnail).
                            if (replace) {
                                deletePageBookmark.awaitById(bookmark.id)
                                pageBookmarkThumbnailProvider.deleteThumbnail(bookmark.id)
                            }
                            duplicateCount++
                            continue
                        }

                        logcat(LogPriority.DEBUG, tag = "PageBookmarkMigration") {
                            if (matchedChapter != null) {
                                "Migrating bookmark id=${bookmark.id}: ch ${bookmark.chapterNumber} -> matched ch ${matchedChapter.chapterNumber} (chapterId=${matchedChapter.id})"
                            } else {
                                "Orphaning bookmark id=${bookmark.id}: ch ${bookmark.chapterNumber} '${bookmark.chapterName}' - no match on target manga"
                            }
                        }

                        if (replace) {
                            // Move the existing bookmark onto the target manga.
                            updatePageBookmarkChapter.awaitMangaAndChapter(
                                id = bookmark.id,
                                newMangaId = target.id,
                                chapterId = chapterId,
                                chapterUrl = chapterUrl,
                                chapterName = chapterName,
                                chapterNumber = chapterNumber,
                                scanlator = scanlator,
                            )
                        } else {
                            // Copy the bookmark so the source manga keeps its own.
                            val newBookmarkId = updatePageBookmarkChapter.awaitCopyToMangaAndChapter(
                                source = bookmark,
                                newMangaId = target.id,
                                chapterId = chapterId,
                                chapterUrl = chapterUrl,
                                chapterName = chapterName,
                                chapterNumber = chapterNumber,
                                scanlator = scanlator,
                            )
                            // Thumbnails are keyed by bookmark id, so carry the cached image to the copy
                            // (the target manga isn't downloaded, so it can't be regenerated otherwise).
                            pageBookmarkThumbnailProvider.copyThumbnail(bookmark.id, newBookmarkId)
                        }

                        if (matchedChapter != null) {
                            // Mirror the manual remap flow: flag the target chapter as bookmarked.
                            updateChapter.await(ChapterUpdate(id = matchedChapter.id, bookmark = true))
                            migratedCount++
                        } else {
                            orphanedCount++
                        }
                    }

                    if (BuildConfig.DEBUG) {
                        val finalBookmarks = getPageBookmarks.awaitForManga(target.id)
                        logcat(LogPriority.INFO, tag = "PageBookmarkMigration") {
                            "Migration complete: $migratedCount migrated, $orphanedCount orphaned, " +
                                "$duplicateCount skipped. Target manga now has ${finalBookmarks.size} bookmarks"
                        }
                    }
                }
            }
            // KMK <--

            val currentMangaUpdate = MangaUpdate(
                id = current.id,
                favorite = false,
                dateAdded = 0,
            )
                .takeIf { replace }
            val targetMangaUpdate = MangaUpdate(
                id = target.id,
                favorite = true,
                chapterFlags = current.chapterFlags
                    // KMK -->
                    .takeIf { MigrationFlag.EXTRA in flags },
                // KMK <--
                viewerFlags = current.viewerFlags
                    // KMK -->
                    .takeIf { MigrationFlag.EXTRA in flags },
                // KMK <--
                dateAdded = if (replace) current.dateAdded else Instant.now().toEpochMilli(),
                notes = if (MigrationFlag.NOTES in flags) current.notes else null,
            )

            updateManga.awaitAll(listOfNotNull(currentMangaUpdate, targetMangaUpdate))
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
        }
    }

    // KMK -->
    /**
     * Identifies a page-bookmark "slot" for dedup. Percentage-based bookmarks bucket to the
     * nearest 0.001 (matching `findExisting`'s tolerance); older page-based ones key on page index.
     */
    private fun bookmarkSlotKey(chapterId: Long, pageIndex: Int, chapterPercentage: Double): String {
        return if (chapterPercentage >= 0.0) {
            "$chapterId:pct:${Math.round(chapterPercentage * 1000)}"
        } else {
            "$chapterId:page:$pageIndex"
        }
    }
    // KMK <--
}
