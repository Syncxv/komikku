package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionRepoRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.FeedRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.pagebookmarks.interactor.GetPageBookmarks
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val extensionRepoRestorer: ExtensionRepoRestorer = ExtensionRepoRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(isSync),
    // SY -->
    private val savedSearchRestorer: SavedSearchRestorer = SavedSearchRestorer(),
    // SY <--
    // KMK -->
    private val feedRestorer: FeedRestorer = FeedRestorer(),
    private val getPageBookmarks: GetPageBookmarks = Injekt.get(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    // KMK <--
) {

    private var restoreAmount = 0
    private var restoreProgress = 0
    private val errors = mutableListOf<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions, zipUri: Uri? = null) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options, zipUri)

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions, zipUri: Uri? = null) {
        val backup = BackupDecoder(context).decode(uri)

        // Store source mapping for error messages
        val backupMaps = backup.backupSources
        sourceMapping = backupMaps.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += backup.backupManga.size
        }
        if (options.categories) {
            restoreAmount += 1
        }
        // SY -->
        if (options.savedSearchesFeeds) {
            restoreAmount += 1
        }
        // SY <--
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionRepoSettings) {
            restoreAmount += backup.backupExtensionRepo.size
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }

        coroutineScope {
            if (options.categories) {
                restoreCategories(backup.backupCategories)
            }
            // SY -->
            if (options.savedSearchesFeeds) {
                restoreSavedSearches(
                    backup.backupSavedSearches,
                    // KMK -->
                    backup.backupFeeds,
                    // KMK <--
                )
            }
            // SY <--
            if (options.appSettings) {
                restoreAppPreferences(backup.backupPreferences, backup.backupCategories.takeIf { options.categories })
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(backup.backupSourcePreferences)
            }
            if (options.libraryEntries) {
                restoreManga(backup.backupManga, if (options.categories) backup.backupCategories else emptyList())
            }
            if (options.extensionRepoSettings) {
                restoreExtensionRepos(backup.backupExtensionRepo)
            }

            // TODO: optionally trigger online library + tracker update
        }

        // KMK: After all manga are restored, restore page bookmark thumbnail images
        if (options.libraryEntries) {
            restorePageBookmarkImages(uri, backup.backupManga, zipUri)
        }
        // KMK <--
    }

    context(scope: CoroutineScope)
    private /* KMK --> */suspend /* KMK <-- */ fun restoreCategories(backupCategories: List<BackupCategory>) {
        scope.ensureActive()
        categoriesRestorer(backupCategories)

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.categories),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    // SY -->
    private fun CoroutineScope.restoreSavedSearches(
        backupSavedSearches: List<BackupSavedSearch>,
        // KMK -->
        backupFeeds: List<BackupFeed>,
        // KMK <--
    ) = launch {
        ensureActive()
        savedSearchRestorer.restoreSavedSearches(backupSavedSearches)
        // KMK -->
        feedRestorer.restoreFeeds(backupFeeds)
        // KMK <--

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(KMR.strings.saved_searches_feeds),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }
    // SY <--

    private fun CoroutineScope.restoreManga(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) = launch {
        mangaRestorer.sortByNew(backupMangas)
            .forEach {
                ensureActive()

                try {
                    mangaRestorer.restore(it, backupCategories)
                } catch (e: Exception) {
                    val sourceName = sourceMapping[it.source] ?: it.source.toString()
                    errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                }

                restoreProgress += 1
                with(notifier) {
                    showRestoreProgress(it.title, restoreProgress, restoreAmount, isSync)
                        // KMK -->
                        .show(Notifications.ID_RESTORE_PROGRESS)
                    // KMK <--
                }
            }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.app_settings),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.source_settings),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreExtensionRepos(
        backupExtensionRepo: List<BackupExtensionRepos>,
    ) = launch {
        backupExtensionRepo
            .forEach {
                ensureActive()

                try {
                    extensionRepoRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error Adding Repo: ${it.name} : ${e.message}")
                }

                restoreProgress += 1
                with(notifier) {
                    showRestoreProgress(
                        context.stringResource(MR.strings.extensionRepo_settings),
                        restoreProgress,
                        restoreAmount,
                        isSync,
                    )
                        // KMK -->
                        .show(Notifications.ID_RESTORE_PROGRESS)
                    // KMK <--
                }
            }
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("komikku_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }

    // KMK -->
    /**
     * Restore page bookmark thumbnail images from a companion .pagebookmarks.zip file.
     * The zip must be located alongside the .tachibk file with a matching name.
     * If no companion zip is found, this is a no-op (thumbnails will show placeholders).
     */
    private suspend fun restorePageBookmarkImages(tachibkUri: Uri, backupMangas: List<BackupManga>, zipUri: Uri? = null) {
        // Only proceed if there are page bookmarks to restore
        val mangasWithBookmarks = backupMangas.filter { it.pageBookmarks.isNotEmpty() }
        if (mangasWithBookmarks.isEmpty()) return

        // Use explicitly provided zip URI (manual restore) or try to find sibling (auto-backup dir)
        val resolvedZipUri = if (zipUri != null) {
            zipUri
        } else {
            val tachibkFile = UniFile.fromUri(context, tachibkUri) ?: return
            val parentDir = tachibkFile.parentFile ?: return
            val zipFileName = tachibkFile.name?.replace(".tachibk", ".pagebookmarks.zip") ?: return
            parentDir.findFile(zipFileName)?.uri ?: return
        }

        try {
            val thumbsDir = File(context.filesDir, "page_bookmark_thumbs").also { it.mkdirs() }

            // Build a mapping of expected zip entry paths -> new bookmark IDs
            val pathToBookmarkId = mutableMapOf<String, Long>()
            for (backupManga in mangasWithBookmarks) {
                val manga = getMangaByUrlAndSourceId.await(backupManga.url, backupManga.source) ?: continue
                val bookmarks = getPageBookmarks.awaitForManga(manga.id)

                val mangaDirName = "${backupManga.source}_${md5Hash(backupManga.url)}"
                for (bookmark in bookmarks) {
                    val entryPath = "$mangaDirName/${md5Hash(bookmark.chapterUrl)}_${bookmark.pageIndex}.webp"
                    pathToBookmarkId[entryPath] = bookmark.id
                }
            }

            if (pathToBookmarkId.isEmpty()) return

            // Extract matching zip entries to the thumbnails directory
            val inputStream = context.contentResolver.openInputStream(resolvedZipUri) ?: return
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val bookmarkId = pathToBookmarkId[entry.name]
                    if (bookmarkId != null) {
                        val thumbFile = File(thumbsDir, "$bookmarkId.webp")
                        thumbFile.outputStream().use { out -> zipIn.copyTo(out) }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to restore page bookmark images from companion zip" }
        }
    }

    private fun md5Hash(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }
    // KMK <--
}
