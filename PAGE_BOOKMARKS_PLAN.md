# Page Bookmarks Feature — Implementation Plan

## Overview

Add the ability to bookmark individual pages (or visible regions of long-strip pages) while reading. Bookmarked pages are browsable from a new "Bookmarks" tab on the manga detail screen, with thumbnail previews and one-tap navigation back to the exact scroll position.

---

## Hard Constraints

| # | Constraint | Why |
|---|-----------|-----|
| 1 | **No changes to the main SQLDelight schema** (`data/src/main/sqldelight/`) | Must remain compatible with stock Komikku so you can revert cleanly. |
| 2 | **No changes to the serializable `Chapter` class** | Same reason — chapter-level bookmarks are a boolean on that class and must stay untouched. |
| 3 | **Webtoon / long-strip pages can be very tall** | A single "page" may be 4–10× screen height. Bookmarking the whole page is meaningless — we must capture the **visible viewport region** only. |

---

## Key Design Decisions

### 1. Storage — Separate SQLite database

Since we can't alter the main schema, we create a **second, independent SQLite database** (`page_bookmarks.db`) managed by a separate SQLDelight definition.

You've already started this with `data/src/main/sqldelight-pagebookmarks/`. The approach:

- Add a second `sqldelight` database block in `data/build.gradle.kts`:
  ```kotlin
  create("PageBookmarksDatabase") {
      packageName.set("tachiyomi.data.pagebookmarks")
      sourceFolders.set(listOf("sqldelight-pagebookmarks"))
      dialect(libs.sqldelight.dialects.sql)
  }
  ```
- This generates a completely separate `PageBookmarksDatabase` class with its own `.db` file on disk — zero interaction with the main database.

**Refined schema** (`PageBookmarks.sq`):

```sql
CREATE TABLE page_bookmarks (
    _id           INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    manga_id      INTEGER NOT NULL,
    chapter_id    INTEGER NOT NULL,
    chapter_url   TEXT    NOT NULL,   -- fallback identifier for migration
    chapter_name  TEXT    NOT NULL,   -- display in bookmarks list
    page_index    INTEGER NOT NULL,   -- 0-based page index in reader
    scroll_offset REAL    NOT NULL DEFAULT 0.0,
        -- normalised 0.0–1.0 representing how far down the page the
        -- viewport was when the bookmark was created
    image_url     TEXT    NOT NULL DEFAULT '',
        -- the image URL (or file URI) of the page at bookmark time
    crop_top      REAL    NOT NULL DEFAULT 0.0,
    crop_bottom   REAL    NOT NULL DEFAULT 1.0,
        -- normalised 0.0–1.0 of the top/bottom edges of the visible
        -- region within the page image (for generating thumbnails)
    added_at      INTEGER NOT NULL,   -- epoch millis
    note          TEXT    NOT NULL DEFAULT ''
        -- optional user note / annotation
);
```

Key differences from your earlier attempt:
- **`scroll_offset`** (float 0–1) replaces `y_progress` (integer). Normalised means it's resolution-independent.
- **`crop_top` / `crop_bottom`** tell us which vertical slice of the full image to show as the preview thumbnail. This eliminates the need for screenshots entirely.
- **`chapter_url`** is kept for cross-source matching during migration.
- **`chapter_name`** is stored so we can display it without joining the main DB.
- **`image_url`** lets us re-fetch or find the cached image for the thumbnail later.

---

### 2. Capturing the bookmark — No screenshots

**The previous screenshot approach failed** because UI elements were composited on top. The correct approach:

#### For **paged readers** (LTR, RTL, vertical pager)
- The current `ReaderPage` is known; its `imageUrl` (or `stream`) gives direct access to the raw page image.
- `crop_top = 0.0`, `crop_bottom = 1.0` (the whole page is visible, so the full image is the preview).
- `scroll_offset = 0.0` (not meaningful for paged mode — the page *is* the position).

#### For **webtoon / continuous vertical** readers
- The current `ReaderPage` is still known from the adapter position.
- We compute the **visible region** of that page's image:

  ```
  pageView = the WebtoonPageHolder's frame (ReaderPageImageView)
  visibleRect = Rect()
  pageView.getLocalVisibleRect(visibleRect)
  imageHeight = actual decoded image height (from SubsamplingScaleImageView or Glide)

  crop_top    = visibleRect.top  / imageHeight   (clamped 0..1)
  crop_bottom = visibleRect.bottom / imageHeight (clamped 0..1)
  scroll_offset = crop_top  -- or the midpoint: (crop_top + crop_bottom) / 2
  ```

- This gives us:
  1. The exact region for the **preview thumbnail** (crop the source image).
  2. The exact **scroll position** to restore later.

No screenshots. No UI contamination. We work purely with the source image data which is already decoded/cached.

---

### 3. Thumbnail generation

When displaying bookmarks, we need a preview image. Strategy:

1. **Try chapter cache first** — `ChapterCache.getImageFile(imageUrl)` may still have the image if it was recently read.
2. **Try download directory** — if the chapter is downloaded, the image file is on disk at a known path via `DownloadProvider`.
3. **Fallback: re-fetch** — use the source's `getImageUrl()` / HTTP to re-download just that one image on-demand (lazy, in background, with placeholder).
4. **Crop** — once we have the full image bytes, decode only the region `[crop_top..crop_bottom]` using `BitmapRegionDecoder` (very memory-efficient — never decodes the full bitmap):
   ```kotlin
   val decoder = BitmapRegionDecoder.newInstance(inputStream, false)
   val fullH = decoder.height
   val fullW = decoder.width
   val region = Rect(0, (crop_top * fullH).toInt(), fullW, (crop_bottom * fullH).toInt())
   val thumb = decoder.decodeRegion(region, BitmapFactory.Options().apply {
       inSampleSize = 4 // scale down for thumbnail
   })
   ```
5. **Cache thumbnails** — after first generation, save the cropped thumbnail to `<app-files>/page_bookmark_thumbs/<bookmark_id>.webp` so subsequent loads are instant. Invalidate on delete.

This solves the **black preview** issue from your previous attempt — we're reading the actual image source, not a screenshot.

---

### 4. Scroll position restoration — Solving the "too far ahead/behind" problem

The core issue: when you navigate to a bookmarked page in webtoon mode, images haven't loaded yet. Each placeholder is ~screen height, but the real image may be 4× that. So a pixel-based scroll offset is wrong.

**Solution: two-phase positioning**

1. **Phase 1 — Navigate to page index**
   - Use `WebtoonViewer.moveToPage(page)` to scroll to the correct *page* (adapter position). This is guaranteed correct regardless of image loading.

2. **Phase 2 — Wait for image load, then apply sub-page offset**
   - After `moveToPage`, observe the `ReaderPage.statusFlow` for `Page.State.Ready`.
   - Once the page image is loaded and laid out (`view.post {}` or `doOnLayout {}`), compute the pixel offset:
     ```kotlin
     val imageView = findPageHolder(pageIndex)?.frame
     val imageHeight = imageView?.measuredHeight ?: return
     val targetY = (scroll_offset * imageHeight).toInt()
     recyclerView.scrollBy(0, targetY)
     ```
   - Because we wait for the image to be **decoded and laid out**, the `measuredHeight` is the *real* height, not the placeholder. The scroll is pixel-perfect.

3. **Edge case: continuous/adjacent pages loading late**
   - Use `RecyclerView.OnScrollListener` + a one-shot flag to re-adjust after the first layout pass if needed. After the first successful adjustment, remove the listener.

This reliably solves the drift problem.

---

### 5. UI / UX Design

#### A. Creating a bookmark (Reader screen)

**Option: Long-press menu extension**

The reader already has a long-press → "Page actions" dialog (`Dialog.PageActions`). We add a new action:

> **☆ Bookmark this page**

For webtoon mode, the label could say **"Bookmark visible region"** to be clear.

When tapped:
1. Compute `page_index`, `scroll_offset`, `crop_top`, `crop_bottom`, `image_url` as described above.
2. Insert into `page_bookmarks` table.
3. Show a brief Toast / Snackbar: *"Page bookmarked"*.
4. Optionally show a small bookmark icon overlay on the page (subtle, non-intrusive).

**Alternative: Quick-action button**

Add a small bookmark icon in the **reader bottom bar** (next to the existing chapter bookmark toggle). Tap = bookmark current page/viewport. The icon fills in when the current page is already bookmarked.

> *Recommendation*: Implement **both**. The bottom bar button for quick access, and the long-press menu for discoverability + "unbookmark" + adding a note.

#### B. Viewing bookmarks (Manga detail screen)

Add a **"Bookmarks" tab** alongside the existing chapter list on the manga detail screen (or a button/icon in the top bar that opens a bottom sheet).

**Layout:**
```
┌──────────────────────────────────┐
│  [Chapters]   [Bookmarks (3)]   │  ← Tab bar
├──────────────────────────────────┤
│                                  │
│  ┌────────┐  Ch. 42 "Title..."  │
│  │ thumb  │  Page 7             │
│  │ preview│  Feb 21, 2026       │
│  └────────┘  "my note..."       │
│  ─────────────────────────────  │
│  ┌────────┐  Ch. 38 "Title..."  │
│  │ thumb  │  Page 2             │
│  │ preview│  Feb 20, 2026       │
│  └────────┘                     │
│                                  │
└──────────────────────────────────┘
```

Each bookmark item shows:
- **Cropped thumbnail** (the visible region at bookmark time).
- **Chapter name** + **page number**.
- **Date** bookmarked.
- **Note** (if any).

**Actions:**
- **Tap** → open the reader at that chapter + page + scroll offset.
- **Long-press** → delete / edit note.

#### C. Reader bookmark indicator

When reading a page that has a bookmark, show a small filled bookmark icon in the **page number indicator** area (e.g., "Page 7 🔖"). This gives passive awareness without clutter.

---

### 6. Architecture — Layers

```
┌─────────────────────────────────────────────────┐
│  UI Layer                                       │
│  ├─ ReaderActivity / ReaderViewModel            │
│  │   └─ togglePageBookmark()                    │
│  │   └─ pageBookmarks: StateFlow<List<...>>     │
│  ├─ MangaScreen / MangaDetailViewModel          │
│  │   └─ Bookmarks tab / bottom sheet            │
│  └─ PageBookmarkList composable                 │
├─────────────────────────────────────────────────┤
│  Domain Layer                                   │
│  ├─ model/PageBookmark (data class)             │
│  ├─ interactor/GetPageBookmarks                 │
│  ├─ interactor/InsertPageBookmark               │
│  ├─ interactor/DeletePageBookmark               │
│  └─ repository/PageBookmarkRepository           │
├─────────────────────────────────────────────────┤
│  Data Layer                                     │
│  ├─ PageBookmarksDatabase (separate .db)        │
│  ├─ PageBookmarkRepositoryImpl                  │
│  └─ PageBookmarkMapper                          │
└─────────────────────────────────────────────────┘
```

---

### 7. Migration / Source-switching considerations

Since different scanlation groups may have different page counts (extra intro pages, etc.):

- We store **`chapter_url`** — this identifies the chapter across sources. During migration, chapter URLs change, so bookmarks created under the old source won't automatically map.
- We also store **`page_index`** — this is best-effort. After migration, page indices may shift.
- **Pragmatic approach**: Page bookmarks are inherently tied to a specific source's chapter. On migration, we **keep the bookmarks** attached to the manga (by `manga_id`, which stays the same), but show a subtle warning: *"These bookmarks were created with a different source — page positions may be approximate."*
- We do **not** attempt to auto-correct page offsets. That's an unsolvable problem in general (no reliable way to match visual content across sources).
- The user can manually delete stale bookmarks if they no longer make sense.

This is honest and functional — the same approach any real-world reader app would take.

---

## Implementation Order

### Phase 1: Data foundation
1. Configure second SQLDelight database in `data/build.gradle.kts`.
2. Finalize `PageBookmarks.sq` schema.
3. Create `PageBookmark` domain model in `domain/`.
4. Create `PageBookmarkRepository` interface in `domain/`.
5. Create `PageBookmarkRepositoryImpl` in `data/`.
6. Create interactors: `GetPageBookmarks`, `InsertPageBookmark`, `DeletePageBookmark`.
7. Wire up DI (Injekt modules).

### Phase 2: Reader integration
8. Add `togglePageBookmark()` to `ReaderViewModel`.
   - Compute `page_index`, `scroll_offset`, `crop_top`, `crop_bottom`, `image_url`.
   - Insert/delete via interactor.
   - Expose `currentPageBookmarked: StateFlow<Boolean>`.
9. Add bookmark button to reader bottom bar (`ReaderAppBars.kt`).
10. Add bookmark option to long-press page actions dialog.
11. Add small bookmark indicator on bookmarked pages.

### Phase 3: Bookmark browsing UI
12. Create `PageBookmarkListScreen` composable.
13. Generate thumbnails using `BitmapRegionDecoder` + caching.
14. Add "Bookmarks" tab to manga detail screen.
15. Implement tap-to-navigate: open reader → `moveToPage()` → wait for load → apply `scroll_offset`.

### Phase 4: Polish
16. Swipe-to-delete on bookmark list items.
17. Handle edge cases: deleted chapters, cleared cache (show placeholder + re-fetch).
18. Add note editing on long-press.
19. Test with paged mode, webtoon mode, and split-page mode.

---

## Risk Mitigations

| Risk | Mitigation |
|------|-----------|
| Separate DB file gets out of sync if app data is partially cleared | On startup, validate `manga_id` and `chapter_id` still exist in main DB; prune orphans silently. |
| Image no longer in cache when generating thumbnail | Fall back to download directory → re-fetch from source → show placeholder. |
| Scroll position imprecise on slow devices | Use `doOnLayout` + `post {}` double-check; add a 200ms debounce re-adjustment. |
| User reverts to stock Komikku | The extra `page_bookmarks.db` file is simply ignored by stock Komikku. No harm. When they come back, bookmarks are still there. |
| Very tall webtoon pages with `splitTallImages` enabled | If split is enabled, the split sub-pages behave like normal pages (reasonable height). `page_index` refers to the *split* page index which is fine since that's what the adapter uses. `crop_top`/`crop_bottom` may need to reference the sub-page, not the original. Handle by checking `InsertPage` type. |

---

## Backup & Restore

### Strategy: Option C — Metadata in `.tachibk` + Optional Companion Zip for Images

Page bookmark **metadata** is always included in the standard `.tachibk` backup file. It's tiny (a few KB for hundreds of bookmarks) and uses the same protobuf serialization as chapters, history, and tracking.

Thumbnail **images** are optionally exported as a separate companion file (Phase 2, not yet implemented).

### Phase 1: Metadata Backup (in `.tachibk`)

#### `BackupPageBookmark` model
```kotlin
@Serializable
data class BackupPageBookmark(
    @ProtoNumber(1) var chapterUrl: String,
    @ProtoNumber(2) var chapterName: String,
    @ProtoNumber(3) var pageIndex: Int,
    @ProtoNumber(4) var scrollOffset: Double = 0.0,
    @ProtoNumber(5) var imageUrl: String = "",
    @ProtoNumber(6) var cropTop: Double = 0.0,
    @ProtoNumber(7) var cropBottom: Double = 1.0,
    @ProtoNumber(8) var addedAt: Long = 0,
    @ProtoNumber(9) var note: String = "",
)
```

- Stored as `@ProtoNumber(620) var pageBookmarks: List<BackupPageBookmark>` on `BackupManga`.
- Uses `chapterUrl` (not `chapterId`) for cross-device matching — chapter IDs are local auto-increments.
- On restore, matches chapter URL to find the local `chapterId`, then inserts via `PageBookmarkRepository`.

#### Backup flow
1. `PageBookmarksBackupCreator` queries all page bookmarks for a manga via `GetPageBookmarks.awaitForManga(mangaId)`.
2. Converts each `PageBookmark` → `BackupPageBookmark`, looking up chapter URL from the chapters already queried.
3. Added to `BackupManga.pageBookmarks`.

#### Restore flow
1. `MangaRestorer.restoreMangaDetails()` receives `pageBookmarks: List<BackupPageBookmark>`.
2. For each `BackupPageBookmark`, finds the matching local chapter by URL.
3. Checks for duplicates (same manga + chapter + page index).
4. Inserts new bookmarks via `PageBookmarkRepository.insert()`.

#### UI options
- `BackupOptions` gets `pageBookmarks: Boolean = true` field.
- Shows as "Page bookmarks" in the library backup options, enabled when `libraryEntries` is true.
- `RestoreOptions` is unchanged — page bookmarks restore automatically with library entries.

### Phase 2: Image Backup (companion `.pagebookmarks.zip`)

When `pageBookmarkImages` is enabled in `BackupOptions`, a companion zip is created alongside the `.tachibk`:

- **Zip structure**: `{source}_{md5(mangaUrl)}/{md5(chapterUrl)}_{pageIndex}.webp`
- **Backup**: `BackupCreator.createPageBookmarkImagesZip()` iterates backed-up manga, finds thumbnail files by bookmark ID, writes to zip with stable entry paths.
- **Restore**: `BackupRestorer.restorePageBookmarkImages()` runs after all manga metadata is restored. Finds companion zip by name convention, builds `entryPath → newBookmarkId` mapping, extracts matching entries.
- **Auto-backup cleanup**: Old companion zips are deleted when old `.tachibk` files are rotated out.
- **Graceful fallback**: If no companion zip exists (manual backup without images, or old backup), thumbnails show placeholders and can be regenerated by viewing the bookmark.

---

## Summary

- **Screenshot-based thumbnails** — uses `PixelCopy` with hidden UI overlays for accurate zoom-level captures.
- **No schema changes** to main DB — fully separate `page_bookmarks.db`.
- **Normalised float offsets** — resolution-independent, survives screen size changes.
- **Two-phase scroll restoration** — navigate to page first, then pixel-adjust after image loads.
- **Webtoon-aware** — captures visible viewport region only, not the full 4000px-tall strip.
- **Migration-safe** — bookmarks survive but with an honest "positions may be approximate" warning.
- **Backup-safe** — metadata always in `.tachibk`, thumbnails regenerated on restore.
