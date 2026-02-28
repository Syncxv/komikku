# Page Bookmarks Feature Implementation

This document details the complete implementation and polish of the **Page Bookmarks** feature in Komikku.

## 1. Core Functionality & Scroll Offsets (`a6e842331e` - `55d6db34f6`)
*   **Initial Implementation**: Added the ability to bookmark specific pages within a chapter.
*   **Scroll Offset Restoration**: Fixed issues with restoring the exact scroll position when opening a page bookmark in Webtoon mode.
    *   Added logic to wait for the image to fully decode and layout before applying the scroll offset.
    *   Calculated the exact normalized scroll offset (`0.0` to `1.0`) relative to the page item's height.
    *   Ensured that even if a page is shorter than the viewport, the scroll offset accurately represents the position (e.g., aligning the bottom of the item with the bottom of the screen).

## 2. Screenshot Capture for Thumbnails (`34dc4925a5`, `124839f516`)
*   **Cross-Page Panels**: Implemented a screenshot mechanism to capture exactly what the user sees, solving the issue where panels split across multiple pages would only save half the image.
*   **UI Overlay Removal**: Fixed a bug where UI elements (like the top app bar, navigation overlays, and page indicators) were captured in the screenshot.
    *   Temporarily hides `compose_overlay` and `navigation_overlay` before capturing.
    *   Waits one frame to ensure overlays are hidden.
    *   Restores the overlays immediately after the capture.
*   **Accurate Zoom Capture**: Updated the `PixelCopy` target from the `recycler` to the `frame` (the fixed-size viewport).
    *   This ensures that if the user is zoomed in, the screenshot captures exactly the zoomed-in view.
    *   If zoomed out, it captures the full frame size.

## 3. Auto-Bookmark Chapter (`dfc7504941`)
*   **Quality of Life**: When a user adds a page bookmark, the system now automatically sets the `bookmark` boolean on the parent chapter to `true` if it isn't already.

## 4. Page Bookmarks Screen UI Overhaul (`0c909be791`, `124839f516`, `a398a8f28e`)
*   **Chapter Grouping**: Bookmarks are now grouped by their respective chapters.
*   **Sticky Headers**: Added sticky headers for each chapter group.
*   **Sorting**:
    *   Chapter groups respect the user's configured chapter sort settings (e.g., ascending/descending).
    *   Within each chapter group, bookmarks are sorted chronologically by their page index (from lowest to highest page number).
*   **Thumbnail Grid**: Replaced the old list view with a compact 3-column grid layout.
    *   Uses `fillMaxWidth(1f / 3f)` for exact 3-per-row sizing.
    *   Uses `ContentScale.FillWidth` to display screenshots at their natural aspect ratio (full height, not cropped).
    *   Added a fallback placeholder (bookmark icon on a tinted background) with a fixed 160dp height when no thumbnail is available.
*   **Long-Press Context Menu**: Tapping a thumbnail opens the reader, but long-pressing opens an `AlertDialog` with:
    *   Chapter name, page number, and saved date.
    *   **Open**: Jump to that page in the reader.
    *   **Edit Note**: Inline `OutlinedTextField` dialog to write or update a custom note for the bookmark.
    *   **Delete**: Removes the bookmark (with a confirmation dialog).

## 5. Reader UI Integration (`52b451f8fa`, `6a41c0e8de`)
*   **Top App Bar Action**: Changed the bookmark icon in the reader's top app bar. Clicking it now creates a **Page Bookmark** for the currently visible page, rather than just toggling the chapter bookmark.
*   **Visual Feedback**: Added a toast notification ("Page bookmarked") that appears at the bottom of the screen when a page bookmark is successfully created. This is handled via the `ReaderViewModel`'s event channel (`Event.PageBookmarkCreated`).

## 6. Backup & Restore — Phase 1: Metadata (`39900d25a2`)
*   **Protobuf Model**: Created `BackupPageBookmark` with `@ProtoNumber` annotations for all bookmark fields (`chapterUrl`, `chapterName`, `pageIndex`, `scrollOffset`, `imageUrl`, `cropTop`, `cropBottom`, `addedAt`, `note`).
*   **BackupManga Integration**: Added `@ProtoNumber(620) var pageBookmarks: List<BackupPageBookmark>` to `BackupManga` (KMK-specific proto number range).
*   **Backup Creator**: New `PageBookmarksBackupCreator` queries bookmarks per manga and converts to backup models. Wired into `MangaBackupCreator`.
*   **Restore Logic**: Extended `MangaRestorer` with `restorePageBookmarks()` method:
    *   Matches backed-up bookmarks to local chapters by `chapterUrl` (not `chapterId`, which is a local auto-increment).
    *   Deduplicates by `(mangaId, chapterId, pageIndex)` triple to avoid re-inserting existing bookmarks.
    *   Inserts via `PageBookmarkRepository`.
*   **Backup Options**: Added `pageBookmarks: Boolean = true` field to `BackupOptions` with a UI entry labeled "Page bookmarks", enabled when `libraryEntries` is true. Restore is automatic with library entries (no separate toggle).

## 7. Backup & Restore — Phase 2: Thumbnail Images (companion zip) (`7eefb98367`)

### Overview
When the "Page bookmark images" backup option is enabled, a companion `.pagebookmarks.zip` file is created containing all page bookmark thumbnail WebP images. This is a separate file from the `.tachibk` because Android's SAF (Storage Access Framework) only grants access to individual files via `ACTION_CREATE_DOCUMENT` — no parent directory access is available to create sibling files.

### Backup (Creating the Zip)

*   **Backup Option**: Added `pageBookmarkImages: Boolean = false` to `BackupOptions` (off by default to keep regular backups small). Enabled in the UI only when both `libraryEntries` and `pageBookmarks` are true.
*   **Stable Naming**: Zip entries use deterministic paths based on truncated MD5 hashes: `{source}_{md5(mangaUrl)}/{md5(chapterUrl)}_{pageIndex}.webp`. This allows matching thumbnails to bookmarks across devices where local auto-increment IDs differ.
*   **Dual File Picker (Manual Backup)**: When the user creates a manual backup with page bookmark images enabled, `CreateBackupScreen` chains two `ActivityResultContracts.CreateDocument` pickers:
    1. First picker: user chooses the `.tachibk` save location (existing behavior).
    2. Second picker: automatically launched for the companion `.pagebookmarks.zip` file with a matching timestamp filename.
    3. If the user cancels the second picker, the backup proceeds without images.
    4. Both URIs are passed to `BackupCreateJob` via `LOCATION_URI_KEY` and `ZIP_LOCATION_URI_KEY`.
*   **Auto-Backup**: For auto-backups, the backup directory is a tree URI with full directory access, so the companion zip is created as a sibling file directly — no second picker needed.
*   **Empty Zip Cleanup**: If no thumbnail files exist on disk (e.g., thumbnails were cleared), the zip is deleted after creation to avoid saving an empty file.
*   **Auto-Backup Rotation**: Old companion zips are deleted alongside old `.tachibk` files when the auto-backup count exceeds `MAX_AUTO_BACKUPS`.

### Restore (Extracting the Zip)

*   **Restore Screen Zip Picker**: `RestoreBackupScreen` shows an optional "Page bookmark images" section with a file picker button (`ActivityResultContracts.GetContent`). The user can optionally select the companion `.pagebookmarks.zip` before restoring. The zip URI is stored in the screen model state and passed through `BackupRestoreJob` → `BackupRestorer`.
*   **Auto-Backup Restore**: For restores from the auto-backup directory (tree URI), the restorer can find the companion zip as a sibling file automatically via `UniFile.parentFile.findFile()`.
*   **Entry Matching**: After all manga metadata is restored, `restorePageBookmarkImages()` builds a mapping of `{entryPath → newBookmarkId}` by querying restored bookmarks and recomputing the same MD5-based paths. Zip entries are then extracted to `page_bookmark_thumbs/{newBookmarkId}.webp`.
*   **Graceful Fallback**: If no companion zip is provided or found, thumbnails simply show placeholders. They can be regenerated by viewing the bookmarked page in the reader.

### Files Modified
*   `CreateBackupScreen.kt` — Dual `CreateDocument` picker chain, `pendingTachibkUri` state, zip URI passed to `createBackup()`.
*   `BackupCreateJob.kt` — `ZIP_LOCATION_URI_KEY` input data, passed to `BackupCreator.backup()`.
*   `BackupCreator.kt` — `createPageBookmarkImagesZip()` with auto-backup vs. manual-backup branching, efficient `(source, url)` composite-key lookup, empty-zip cleanup, `ZIP_FILENAME_REGEX`.
*   `BackupOptions.kt` — `pageBookmarkImages: Boolean = false` field at array index 13.
*   `RestoreBackupScreen.kt` — `GetContent` zip picker, `zipUri` in model state, `CheckCircle` icon for selected state.
*   `BackupRestoreJob.kt` — `ZIP_LOCATION_URI_KEY` input data, passed to `BackupRestorer.restore()`.
*   `BackupRestorer.kt` — `restorePageBookmarkImages(zipUri)` with explicit URI or sibling-file fallback, `md5Hash()` utility.
*   `strings.xml` — `page_bookmark_images`, `page_bookmark_images_restore_pick`, `page_bookmark_images_restore_selected`.
