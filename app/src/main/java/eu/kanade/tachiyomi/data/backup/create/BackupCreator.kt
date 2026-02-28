package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionRepoBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.FeedBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SavedSearchBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.pagebookmarks.interactor.GetPageBookmarks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupCreator(
    private val context: Context,
    private val isAutoBackup: Boolean,

    private val parser: ProtoBuf = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val backupPreferences: BackupPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),

    private val categoriesBackupCreator: CategoriesBackupCreator = CategoriesBackupCreator(),
    private val mangaBackupCreator: MangaBackupCreator = MangaBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator = PreferenceBackupCreator(),
    private val extensionRepoBackupCreator: ExtensionRepoBackupCreator = ExtensionRepoBackupCreator(),
    private val sourcesBackupCreator: SourcesBackupCreator = SourcesBackupCreator(),
    // KMK -->
    private val feedBackupCreator: FeedBackupCreator = FeedBackupCreator(),
    // KMK <--
    // SY -->
    private val savedSearchBackupCreator: SavedSearchBackupCreator = SavedSearchBackupCreator(),
    private val getMergedManga: GetMergedManga = Injekt.get(),
    // SY <--
    // KMK -->
    private val getPageBookmarks: GetPageBookmarks = Injekt.get(),
    // KMK <--
) {

    suspend fun backup(uri: Uri, options: BackupOptions, zipUri: Uri? = null): String {
        var file: UniFile? = null
        try {
            file = if (isAutoBackup) {
                // Get dir of file and create
                val dir = UniFile.fromUri(context, uri)

                // Delete older backups
                dir?.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }
                    .orEmpty()
                    .sortedByDescending { it.name }
                    .drop(MAX_AUTO_BACKUPS - 1)
                    .forEach { tachibkFile ->
                        // KMK: Also delete companion page bookmark images zip
                        val zipName = tachibkFile.name?.replace(".tachibk", ".pagebookmarks.zip")
                        if (zipName != null) {
                            dir?.findFile(zipName)?.delete()
                        }
                        // KMK <--
                        tachibkFile.delete()
                    }

                // Create new file to place backup
                dir?.createFile(getFilename())
            } else {
                UniFile.fromUri(context, uri)
            }

            if (file == null || !file.isFile) {
                throw IllegalStateException(context.stringResource(MR.strings.create_backup_file_error))
            }

            val nonFavoriteManga = if (options.readEntries) mangaRepository.getReadMangaNotInLibrary() else emptyList()
            // SY -->
            val mergedManga = getMergedManga.await()
            // SY <--
            val allMangas = getFavorites.await() + nonFavoriteManga /* SY --> */ + mergedManga /* SY <-- */
            val backupManga =
                backupMangas(allMangas, options)

            val backup = Backup(
                backupManga = backupManga,
                backupCategories = backupCategories(options),
                backupSources = backupSources(backupManga),
                backupPreferences = backupAppPreferences(options),
                backupExtensionRepo = backupExtensionRepos(options),
                backupSourcePreferences = backupSourcePreferences(options),

                // SY -->
                backupSavedSearches = backupSavedSearches(options),
                // SY <--

                // KMK -->
                backupFeeds = backupFeeds(options),
                // KMK <--
            )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            file.openOutputStream()
                .also {
                    // Force overwrite old file
                    (it as? FileOutputStream)?.channel?.truncate(0)
                }
                .sink().gzip().buffer().use {
                    it.write(byteArray)
                }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator(context).validate(fileUri)

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp().set(Instant.now().toEpochMilli())
            }

            // KMK: Create companion zip for page bookmark thumbnail images
            if (options.pageBookmarkImages && options.pageBookmarks && options.libraryEntries) {
                createPageBookmarkImagesZip(uri, file, allMangas, backupManga, zipUri)
            }
            // KMK <--

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        if (!options.libraryEntries) return emptyList()

        return mangaBackupCreator(mangas, options)
    }

    fun backupSources(mangas: List<BackupManga>): List<BackupSource> {
        return sourcesBackupCreator(mangas)
    }

    /* KMK --> */ suspend /* KMK <-- */ fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    suspend fun backupExtensionRepos(options: BackupOptions): List<BackupExtensionRepos> {
        if (!options.extensionRepoSettings) return emptyList()

        return extensionRepoBackupCreator()
    }

    fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.privateSettings)
    }

    // SY -->
    suspend fun backupSavedSearches(options: BackupOptions): List<BackupSavedSearch> {
        if (!options.savedSearchesFeeds) return emptyList()

        return savedSearchBackupCreator()
    }
    // SY <--

    // KMK -->
    /**
     * Backup global Popular/Latest feeds
     */
    suspend fun backupFeeds(options: BackupOptions): List<BackupFeed> {
        if (!options.savedSearchesFeeds) return emptyList()

        return feedBackupCreator()
    }

    /**
     * Create a companion .pagebookmarks.zip alongside the .tachibk file
     * containing page bookmark thumbnail images.
     *
     * @param originalUri the original URI passed to backup() — for auto-backups this is
     *        the backup directory; for manual backups this is the .tachibk file URI itself.
     * @param tachibkFile the UniFile for the .tachibk file that was written.
     * @param zipUri for manual backups, the URI of the companion zip file (from a second file picker).
     *        For auto-backups, this is null (the zip is created as a sibling in the directory).
     */
    private suspend fun createPageBookmarkImagesZip(
        originalUri: Uri,
        tachibkFile: UniFile,
        mangas: List<Manga>,
        backupMangas: List<BackupManga>,
        zipUri: Uri? = null,
    ) {
        val thumbsDir = File(context.filesDir, "page_bookmark_thumbs")
        if (!thumbsDir.exists()) return

        val mangasWithBookmarks = backupMangas.filter { it.pageBookmarks.isNotEmpty() }
        if (mangasWithBookmarks.isEmpty()) return

        val zipFile: UniFile?

        if (isAutoBackup) {
            // For auto-backups, originalUri is the backup directory (tree URI).
            // We can create sibling files directly.
            val parentDir = UniFile.fromUri(context, originalUri)
            val zipFileName = tachibkFile.name?.replace(".tachibk", ".pagebookmarks.zip") ?: return

            // Clean up old companion zips
            parentDir?.listFiles { _, filename -> ZIP_FILENAME_REGEX.matches(filename) }
                .orEmpty()
                .forEach { it.delete() }

            zipFile = parentDir?.createFile(zipFileName)
        } else {
            // For manual backups, use the zip URI provided by the second file picker.
            if (zipUri == null) {
                logcat(LogPriority.WARN) { "No zip URI provided for manual backup — skipping page bookmark images" }
                return
            }
            zipFile = UniFile.fromUri(context, zipUri)
        }

        if (zipFile == null) {
            logcat(LogPriority.WARN) { "Could not create companion zip file" }
            return
        }

        try {
            val mangaByKey = mangas.associateBy { Pair(it.source, it.url) }
            var entriesWritten = 0
            ZipOutputStream(zipFile.openOutputStream()).use { zipOut ->
                for (backupManga in mangasWithBookmarks) {
                    // Find the original manga to get its ID for thumbnail lookup
                    val manga = mangaByKey[Pair(backupManga.source, backupManga.url)] ?: continue

                    val bookmarks = getPageBookmarks.awaitForManga(manga.id)
                    if (bookmarks.isEmpty()) continue

                    val mangaDirName = "${backupManga.source}_${md5Hash(backupManga.url)}"

                    for (bookmark in bookmarks) {
                        val thumbFile = File(thumbsDir, "${bookmark.id}.webp")
                        if (!thumbFile.exists()) continue

                        val entryPath = "$mangaDirName/${md5Hash(bookmark.chapterUrl)}_${bookmark.pageIndex}.webp"
                        zipOut.putNextEntry(ZipEntry(entryPath))
                        thumbFile.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        entriesWritten++
                    }
                }
            }

            // Delete empty zip files (no thumbnails found on disk)
            if (entriesWritten == 0) {
                zipFile.delete()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to create page bookmark images zip" }
            zipFile.delete()
        }
    }
    // KMK <--

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4
        private val FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}.tachibk""".toRegex()
        // KMK -->
        private val ZIP_FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}.pagebookmarks.zip""".toRegex()
        // KMK <--

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }

        // KMK -->
        private fun md5Hash(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        }
        // KMK <--
    }
}
