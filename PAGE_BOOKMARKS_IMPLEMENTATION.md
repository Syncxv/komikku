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
