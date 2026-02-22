package eu.kanade.tachiyomi.ui.manga.pagebookmarks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.pagebookmarks.model.PageBookmark
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream

/**
 * Provides thumbnails for page bookmarks by cropping the original page image
 * to the bookmarked visible region. Uses BitmapRegionDecoder for memory efficiency.
 *
 * Thumbnail cache: <app-files>/page_bookmark_thumbs/<bookmark_id>.webp
 */
class PageBookmarkThumbnailProvider(
    private val context: Context,
    private val chapterCache: ChapterCache = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {
    private val thumbDir: File by lazy {
        File(context.filesDir, "page_bookmark_thumbs").apply { mkdirs() }
    }

    /**
     * Get the thumbnail file for a bookmark. Returns the file if it exists in cache,
     * or attempts to generate it from the source image.
     *
     * @return File pointing to the thumbnail, or null if the image isn't available.
     */
    fun getThumbnailFile(bookmark: PageBookmark, manga: Manga? = null): File? {
        val cachedThumb = File(thumbDir, "${bookmark.id}.webp")
        if (cachedThumb.exists() && cachedThumb.length() > 0) {
            return cachedThumb
        }

        // If the thumbnail doesn't exist, it might be because it was a screenshot that failed to save,
        // or it's an older bookmark. We can try to generate it from the source image as a fallback.
        val sourceImageInfo = findSourceImage(bookmark, manga) ?: return null
        return try {
            generateThumbnail(sourceImageInfo.file, bookmark, cachedThumb, sourceImageInfo.partCount, sourceImageInfo.targetPartIndex)
        } catch (e: Exception) {
            logcat { "Failed to generate thumbnail for bookmark ${bookmark.id}: ${e.message}" }
            null
        } finally {
            if (sourceImageInfo.isTemp) {
                sourceImageInfo.file.delete()
            }
        }
    }

    /**
     * Find the source image file for a bookmark.
     * Checks: 1) Chapter cache, 2) Download directory
     */
    private fun findSourceImage(bookmark: PageBookmark, manga: Manga?): SourceImageInfo? {
        // 1. Try chapter cache (by image URL)
        if (bookmark.imageUrl.isNotBlank()) {
            val cacheFile = chapterCache.getImageFile(bookmark.imageUrl)
            if (cacheFile.exists()) {
                return SourceImageInfo(cacheFile, 1, 0, isTemp = false)
            }
        }

        // 2. Try download directory
        if (manga != null) {
            try {
                val source = sourceManager.getOrStub(manga.source)
                val downloadDir = downloadProvider.findChapterDir(
                    chapterName = bookmark.chapterName,
                    chapterScanlator = null,
                    chapterUrl = bookmark.chapterUrl,
                    mangaTitle = manga.ogTitle,
                    source = source,
                )
                if (downloadDir != null) {
                    val pageNumber = bookmark.pageIndex + 1

                    if (downloadDir.isFile) {
                        // Handle archive downloads (.cbz, .zip)
                        val archiveReader = downloadDir.archiveReader(context)
                        val entries = archiveReader.useEntries { entries ->
                            entries
                                .filter { it.isFile && ImageUtil.isImage(it.name) { archiveReader.getInputStream(it.name)!! } }
                                .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                                .toList()
                        }

                        val pageEntries = entries.filter { entry ->
                            val fileName = entry.name.substringAfterLast('/')
                            val baseName = fileName.substringBeforeLast('.')
                            val mainNumber = baseName.split("__").firstOrNull() ?: baseName
                            mainNumber.trimStart('0').ifEmpty { "0" } == pageNumber.toString()
                        }

                        if (pageEntries.isNotEmpty()) {
                            val partCount = pageEntries.size
                            val targetPartIndex = (bookmark.cropTop * partCount).toInt().coerceIn(0, partCount - 1)
                            val targetEntry = pageEntries[targetPartIndex]

                            // Extract the specific image to a temporary file
                            val tmpFile = File(context.cacheDir, "bookmark_thumb_tmp_${bookmark.id}_${System.currentTimeMillis()}")
                            tmpFile.outputStream().use { output ->
                                archiveReader.getInputStream(targetEntry.name)?.use { input ->
                                    input.copyTo(output)
                                }
                            }
                            archiveReader.close()
                            return SourceImageInfo(tmpFile, partCount, targetPartIndex, isTemp = true)
                        }
                        archiveReader.close()
                    } else {
                        // Handle directory downloads
                        // Download files are named by 1-based page number: 001.jpg, 002.png, etc.
                        // Format: %0Xd where X is max(3, digitCount of total pages)
                        val allFiles = downloadDir.listFiles()
                        val pageFiles = allFiles
                            ?.filter { name ->
                                val fileName = name.name ?: return@filter false
                                // Match files starting with the page number (with any zero-padding)
                                val baseName = fileName.substringBeforeLast('.')
                                // Handle both "001" and "001__001" (split tall image) formats
                                val mainNumber = baseName.split("__").firstOrNull() ?: baseName
                                mainNumber.trimStart('0').ifEmpty { "0" } == pageNumber.toString()
                            }
                            ?.sortedBy { it.name }

                        if (!pageFiles.isNullOrEmpty()) {
                            // If there are multiple split files, find the one that contains the cropTop
                            val partCount = pageFiles.size
                            val targetPartIndex = (bookmark.cropTop * partCount).toInt().coerceIn(0, partCount - 1)
                            val pageFile = pageFiles[targetPartIndex]

                            val javaFile = pageFile.filePath?.let { File(it) }
                            if (javaFile?.exists() == true) {
                                return SourceImageInfo(javaFile, partCount, targetPartIndex, isTemp = false)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logcat { "Failed to find download for bookmark ${bookmark.id}: ${e.message}" }
            }
        }

        return null
    }

    private data class SourceImageInfo(val file: File, val partCount: Int, val targetPartIndex: Int, val isTemp: Boolean)

    /**
     * Generate a cropped thumbnail from the source image.
     * Uses BitmapRegionDecoder for memory-efficient partial decoding.
     * Adds a small padding around the crop region to give visual context.
     */
    private fun generateThumbnail(sourceFile: File, bookmark: PageBookmark, outputFile: File, partCount: Int, targetPartIndex: Int): File? {
        val decoder = BitmapRegionDecoder.newInstance(sourceFile.inputStream(), false) ?: return null
        try {
            val fullH = decoder.height
            val fullW = decoder.width

            // Calculate the crop region relative to this specific split part
            val splitCropTop = (bookmark.cropTop * partCount) - targetPartIndex
            val splitCropBottom = (bookmark.cropBottom * partCount) - targetPartIndex

            // Add ~10% padding above and below the visible region for context
            val cropHeight = splitCropBottom - splitCropTop
            val padding = (cropHeight * 0.1).coerceAtMost(0.05) // max 5% of full image
            var top = ((splitCropTop - padding) * fullH).toInt().coerceIn(0, fullH)
            var bottom = ((splitCropBottom + padding) * fullH).toInt().coerceIn(top, fullH)

            // Enforce a minimum height for the crop region so it doesn't look like a thin sliver.
            // Target a roughly 3:4 aspect ratio (portrait) or at least the full height if the image is short.
            val minHeight = (fullW * 0.75).toInt().coerceAtMost(fullH)
            if (bottom - top < minHeight) {
                val center = (top + bottom) / 2
                top = (center - minHeight / 2).coerceAtLeast(0)
                bottom = (top + minHeight).coerceAtMost(fullH)
                // Adjust top again in case bottom was clamped to fullH
                top = (bottom - minHeight).coerceAtLeast(0)
            }

            val region = Rect(0, top, fullW, bottom)

            val options = BitmapFactory.Options().apply {
                // Calculate sample size to keep thumbnail reasonable (~400px wide)
                inSampleSize = calculateSampleSize(fullW, bottom - top, 400, 600)
            }

            val bitmap = decoder.decodeRegion(region, options) ?: return null
            try {
                FileOutputStream(outputFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, fos)
                }
                return outputFile
            } finally {
                bitmap.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    /**
     * Delete the cached thumbnail for a bookmark.
     */
    fun deleteThumbnail(bookmarkId: Long) {
        File(thumbDir, "$bookmarkId.webp").delete()
    }

    /**
     * Clear all cached thumbnails.
     */
    fun clearAll() {
        thumbDir.listFiles()?.forEach { it.delete() }
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
