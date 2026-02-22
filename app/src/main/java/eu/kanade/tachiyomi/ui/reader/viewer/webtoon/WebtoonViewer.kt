package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import androidx.core.app.ActivityCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(
    val activity: ReaderActivity,
    val isContinuous: Boolean = true,
    private val tapByPage: Boolean = false,
    // KMK -->
    @param:ColorInt private val seedColor: Int? = null,
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    // KMK <--
) : Viewer {

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(
        this,
        // KMK -->
        seedColor = seedColor,
        // KMK <--
    )

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    /* [EXH] private */
    var currentPage: Any? = null

    private val threshold: Int =
        // KMK -->
        readerPreferences
            // KMK <--
            .readerHideThreshold()
            .get()
            .threshold

    init {
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        // KMK -->
        config.pinchToZoomChangedListener = {
            frame.pinchToZoom = it
        }

        config.webtoonScaleTypeChangedListener = f@{ scaleType ->
            if (!isContinuous && !readerPreferences.longStripGapSmartScale().get()) return@f

            recycler.post {
                recycler.doOnLayout doOnLayout@{
                    val currentWidth = recycler.width
                    val currentHeight = recycler.originalHeight
                    if (currentWidth <= 0 || currentHeight <= 0) return@doOnLayout

                    if (scaleType == ReaderPreferences.WebtoonScaleType.FIT) {
                        recycler.scaleTo(1f)
                        return@doOnLayout
                    }

                    val desiredRatio = scaleType.ratio
                    val screenRatio = currentWidth.toFloat() / currentHeight
                    val desiredWidth = currentHeight * desiredRatio
                    val desiredScale = desiredWidth / currentWidth

                    if (screenRatio > desiredRatio) {
                        recycler.scaleTo(desiredScale)
                    } else {
                        recycler.scaleTo(1f)
                    }
                }
            }
        }
        // KMK <--

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            // KMK -->
            val scrollOffset = activity.viewModel.consumePendingScrollOffset()
            // KMK <--
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
            // KMK -->
            // Apply scroll offset for page bookmark restoration
            if (scrollOffset != null && scrollOffset != 0.0) {
                applyScrollOffsetWhenReady(position, scrollOffset)
            }
            // KMK <--
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    // KMK -->
    /**
     * Waits for the page image at [position] to finish loading, then applies the scroll offset.
     *
     * The problem: when moveToPage is called, the page view initially has the height of the
     * progress/loading placeholder (= screen height). The actual image is loaded asynchronously
     * and causes the view height to change dramatically (e.g., from 2400px to 10000px).
     * If we apply the offset against the placeholder height, we undershoot significantly.
     *
     * Solution: listen for the item view's height to change from its initial value, which
     * indicates the image has been decoded and the view has resized. Then re-position and
     * apply the offset against the final height.
     */
    private fun applyScrollOffsetWhenReady(position: Int, scrollOffset: Double) {
        recycler.doOnLayout {
            recycler.post {
                val holder = recycler.findViewHolderForAdapterPosition(position) ?: return@post
                val page = adapter.items.getOrNull(position) as? ReaderPage ?: return@post
                val itemView = holder.itemView
                val initialHeight = itemView.height

                // If the image is already loaded (height is different from the placeholder height),
                // the page was cached and decoded instantly — apply immediately
                if (initialHeight != recycler.height) {
                    val targetScrollY = (scrollOffset * initialHeight).toInt()
                    recycler.scrollBy(0, targetScrollY)
                    return@post
                }

                // Otherwise, wait for the view height to change (image loading completes)
                val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val currentHeight = itemView.height
                        if (currentHeight != initialHeight) {
                            itemView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            // Re-scroll to the item's top first, since the height change
                            // may have shifted everything
                            layoutManager.scrollToPositionWithOffset(position, 0)
                            recycler.post {
                                val targetScrollY = (scrollOffset * currentHeight).toInt()
                                recycler.scrollBy(0, targetScrollY)
                            }
                        }
                    }
                }
                itemView.viewTreeObserver.addOnGlobalLayoutListener(listener)

                // Safety: remove listener after 3 seconds to prevent leaks
                recycler.postDelayed({
                    itemView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                }, 3_000)
            }
        }
    }
    // KMK <--

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    /**
     * Scrolls one screen over a period of time
     */
    fun linearScroll(duration: Duration) {
        recycler.smoothScrollBy(
            0,
            activity.resources.displayMetrics.heightPixels,
            LinearInterpolator(),
            duration.inWholeMilliseconds.toInt(),
        )
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    /* [EXH] private */
    fun scrollDown() {
        // SY -->
        if (!isContinuous && tapByPage) {
            val currentPage = currentPage
            if (currentPage is ReaderPage) {
                val position = adapter.items.indexOf(currentPage)
                val nextItem = adapter.items.getOrNull(position + 1)
                if (nextItem is ReaderPage) {
                    if (config.usePageTransitions) {
                        recycler.smoothScrollToPosition(position + 1)
                    } else {
                        recycler.scrollToPosition(position + 1)
                    }
                    return
                }
            }
        }
        scrollDownBy()
    }

    private fun scrollDownBy() {
        // SY <--
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    // KMK -->
    /**
     * Data class representing the visible region of the current page in the webtoon viewer.
     * All values are normalized floats (0.0 to 1.0) relative to the page item height.
     */
    data class VisiblePageInfo(
        /** How far down the page the visible region starts (0.0 = top) */
        val scrollOffset: Double,
        /** Normalized top of the visible region for cropping */
        val cropTop: Double,
        /** Normalized bottom of the visible region for cropping */
        val cropBottom: Double,
    )

    /**
     * Computes the visible region of the currently active page item.
     * Returns null if the current item is not a page or the view can't be found.
     */
    fun getVisiblePageInfo(): VisiblePageInfo? {
        val page = currentPage as? ReaderPage ?: return null
        val position = adapter.items.indexOf(page)
        if (position == -1) return null

        val holder = recycler.findViewHolderForAdapterPosition(position) ?: return null
        val itemView = holder.itemView
        val itemHeight = itemView.height
        if (itemHeight <= 0) return null

        // itemView.top is negative when scrolled past the top of the item
        val recyclerHeight = recycler.height
        val visibleTop = (-itemView.top).coerceAtLeast(0)
        val visibleBottom = (recyclerHeight - itemView.top).coerceAtMost(itemHeight)

        // If the item is shorter than the recycler view, and we are at the bottom of the item,
        // the visibleTop might be 0, but we want the scroll offset to reflect that we are at the bottom.
        // We calculate the scroll offset based on how much of the item is above the top of the recycler.
        // If the item is fully visible and pushed to the top, scrollOffset is 0.
        // If the item is fully visible and pushed to the bottom, scrollOffset should be such that
        // when restored, it aligns the bottom of the item with the bottom of the screen.
        // However, the standard scroll restoration logic usually aligns the top.
        // To fix the "bookmarking the end of a short page" issue, we need to ensure the scrollOffset
        // accurately represents the position.

        // The actual scroll offset should be the distance from the top of the item to the top of the viewport,
        // divided by the item height.
        val scrollOffset = (-itemView.top).toDouble() / itemHeight

        val cropTop = visibleTop.toDouble() / itemHeight
        val cropBottom = visibleBottom.toDouble() / itemHeight

        return VisiblePageInfo(
            scrollOffset = scrollOffset,
            cropTop = cropTop.coerceIn(0.0, 1.0),
            cropBottom = cropBottom.coerceIn(0.0, 1.0),
        )
    }

    /**
     * Captures a screenshot of the currently visible portion of the webtoon viewer.
     * This is useful for creating thumbnails that span across multiple pages.
     */
    suspend fun getVisibleScreenshot(): android.graphics.Bitmap? = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        if (recycler.width <= 0 || recycler.height <= 0) {
            continuation.resume(null) {}
            return@suspendCancellableCoroutine
        }
        val bitmap = android.graphics.Bitmap.createBitmap(recycler.width, recycler.height, android.graphics.Bitmap.Config.ARGB_8888)
        val location = IntArray(2)
        recycler.getLocationInWindow(location)
        val rect = android.graphics.Rect(location[0], location[1], location[0] + recycler.width, location[1] + recycler.height)

        try {
            android.view.PixelCopy.request(activity.window, rect, bitmap, { copyResult ->
                if (copyResult == android.view.PixelCopy.SUCCESS) {
                    continuation.resume(bitmap) {}
                } else {
                    bitmap.recycle()
                    continuation.resume(null) {}
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: Exception) {
            bitmap.recycle()
            continuation.resume(null) {}
        }
    }
    // KMK <--

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private const val RECYCLER_VIEW_CACHE_SIZE = 4
