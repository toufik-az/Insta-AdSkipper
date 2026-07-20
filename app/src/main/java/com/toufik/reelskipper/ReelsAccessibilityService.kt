package com.toufik.reelskipper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ReelsAccessibilityService : AccessibilityService() {

    private lateinit var settings: SettingsRepository
    private val handler = Handler(Looper.getMainLooper())

    private enum class Screen { REELS, STORIES, HOME_FEED, OTHER }
    private var currentScreen = Screen.OTHER

    private var skipForward = true
    private var currentIndex = -1
    private var automaticGestureInProgress = false
    private var automaticEchoUntil = 0L
    private var lastAutomaticDirection = true
    private var suppressUntil = 0L
    private var lastUserScrollAt = 0L
    private var lastWindowClassName = ""
    private var contentScanScheduled = false
    private var lastContentScanAt = 0L
    private var adSkipScheduled = false

    private val fastScanRunnable = Runnable {
        val now = SystemClock.elapsedRealtime()
        if (!automaticGestureInProgress &&
            now - lastUserScrollAt >= FAST_SCAN_IDLE_MS
        ) {
            checkAndSkip()
        }
    }

    private val contentScanRunnable = Runnable {
        contentScanScheduled = false
        lastContentScanAt = SystemClock.elapsedRealtime()
        if (!automaticGestureInProgress) checkAndSkip()
    }

    private val adSkipRunnable = Runnable {
        adSkipScheduled = false
        executeScheduledSkip()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                val now = SystemClock.elapsedRealtime()
                val idle = (now - lastUserScrollAt) >= IDLE_BEFORE_SCAN_MS
                // Instagram frequently changes fragments without sending a useful
                // WINDOW_STATE_CHANGED event. Always inspect the foreground root;
                // checkAndSkip() cheaply returns when Instagram is not foreground.
                if (idle) checkAndSkip()
            } catch (t: Throwable) {
                Log.w(TAG, "poll", t)
            } finally {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = SettingsRepository(this)
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        Log.d(TAG, "Service connected. keywords=${settings.adKeywords}")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(fastScanRunnable)
        handler.removeCallbacks(contentScanRunnable)
        handler.removeCallbacks(adSkipRunnable)
        contentScanScheduled = false
        adSkipScheduled = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != INSTAGRAM_PACKAGE) return

        when (event.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = event.className?.toString() ?: ""
                lastWindowClassName = cls
                updateCurrentScreen(detectScreen(cls))
                Log.d(TAG, "WINDOW_STATE cls=$cls -> screen=$currentScreen")
                scheduleContentScan()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleContentScan()

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (currentScreen == Screen.STORIES) {
                    lastUserScrollAt = SystemClock.elapsedRealtime()
                    suppressUntil = 0L
                    skipForward = true
                    scheduleContentScan()
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val now = SystemClock.elapsedRealtime()
                val deltaX = event.scrollDeltaX
                val deltaY = event.scrollDeltaY
                val direction = when {
                    kotlin.math.abs(deltaY) >= MIN_DIRECTION_DELTA_PX -> deltaY > 0
                    currentScreen == Screen.STORIES &&
                            kotlin.math.abs(deltaX) >= MIN_DIRECTION_DELTA_PX -> deltaX > 0
                    else -> null
                }

                if (automaticGestureInProgress) {
                    return
                }

                handler.removeCallbacks(fastScanRunnable)

                // Instagram reports a few scroll events after dispatchGesture's
                // completion callback. Ignore only echoes continuing in the
                // automatic direction; an immediate reverse swipe is real input.
                if (now < automaticEchoUntil &&
                    (direction == null || direction == lastAutomaticDirection)
                ) {
                    Log.d(TAG, "Ignoring automatic scroll echo dx=$deltaX dy=$deltaY")
                    return
                }

                lastUserScrollAt = now
                automaticEchoUntil = 0L
                // A real user gesture must immediately override the previous
                // automatic skip, including when the user reverses direction.
                suppressUntil = 0L

                if (direction != null) {
                    skipForward = direction
                    Log.d(
                        TAG,
                        "Direction from scroll delta: forward=$skipForward " +
                                "dx=$deltaX dy=$deltaY screen=$currentScreen"
                    )

                    val primaryDelta = if (currentScreen == Screen.STORIES) deltaX else deltaY
                    if (kotlin.math.abs(primaryDelta) <= SETTLING_SCROLL_DELTA_PX) {
                        // Instagram sends one small final delta as the page snaps
                        // into place. Scan then instead of waiting for the poll.
                        handler.postDelayed(fastScanRunnable, FAST_SCAN_IDLE_MS)
                    }
                }

                val idx = when {
                    event.toIndex >= 0   -> event.toIndex
                    event.fromIndex >= 0 -> event.fromIndex
                    else                 -> -1
                }

                Log.d(
                    TAG,
                    "SCROLL dx=$deltaX dy=$deltaY idx=$idx " +
                            "current=$currentIndex screen=$currentScreen"
                )

                if (idx >= 0 && idx != currentIndex) {
                    // Nested Instagram pagers publish unrelated indices, so they
                    // are useful for diagnostics but never for direction.
                    currentIndex = idx
                }
            }

            else -> Unit
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(fastScanRunnable)
        handler.removeCallbacks(contentScanRunnable)
        handler.removeCallbacks(adSkipRunnable)
        contentScanScheduled = false
        adSkipScheduled = false
        super.onDestroy()
    }

    private fun scheduleContentScan(delayMs: Long = CONTENT_SCAN_DELAY_MS) {
        if (contentScanScheduled) return
        val sinceLastScan = SystemClock.elapsedRealtime() - lastContentScanAt
        val throttleDelay = (MIN_CONTENT_SCAN_INTERVAL_MS - sinceLastScan).coerceAtLeast(0L)
        contentScanScheduled = true
        handler.postDelayed(contentScanRunnable, maxOf(delayMs, throttleDelay))
    }

    private fun executeScheduledSkip() {
        if (automaticGestureInProgress || !settings.adSkipEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now < suppressUntil) return

        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != INSTAGRAM_PACKAGE) return
            updateCurrentScreen(detectScreen(root, lastWindowClassName))
            if (currentScreen == Screen.OTHER) return
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }

        suppressUntil = now + SETTLE_MS
        Log.d(TAG, "Confirmed ad -> skip forward=$skipForward screen=$currentScreen")
        skip(skipForward, currentScreen)
    }

    private fun detectScreen(className: String): Screen {
        return when {
            className.contains("reel", ignoreCase = true)            -> Screen.REELS
            className.contains("story", ignoreCase = true) ||
            className.contains("stories", ignoreCase = true)         -> Screen.STORIES
            // MainTabActivity hosts Home, Reels-tab edge cases, Search, etc.
            // Once the Reels tab isn't confirmed selected (checked by the
            // caller before falling back here), this is overwhelmingly the
            // Home feed, so scan+skip runs there too instead of being ignored.
            className.contains("MainTabActivity", ignoreCase = true) -> Screen.HOME_FEED
            else -> Screen.OTHER
        }
    }

    private fun updateCurrentScreen(screen: Screen) {
        val previous = currentScreen
        if (screen == Screen.STORIES && currentScreen != Screen.STORIES) {
            // Story taps do not expose their screen coordinate through
            // accessibility events, so entering Stories defaults to "next".
            skipForward = true
        }
        currentScreen = screen
        if (screen != previous) Log.d(TAG, "Screen changed: $previous -> $screen")
    }

    private fun detectScreen(root: AccessibilityNodeInfo, className: String): Screen {
        for (id in STORY_ROOT_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val storyVisible = nodes.any { it.isVisibleToUser }
            nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
            if (storyVisible) return Screen.STORIES
        }

        if (className.contains("story", ignoreCase = true) ||
            className.contains("stories", ignoreCase = true)
        ) {
            return Screen.STORIES
        }

        for (id in REELS_TAB_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val selected = nodes.any { it.isSelected }
            nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
            if (selected) return Screen.REELS
        }

        // Reels tab not confirmed selected: this is Home feed (most common),
        // Search/Explore, or a Reels experiment still hosted directly in
        // MainTabActivity. detectScreen(className) maps all of these to
        // HOME_FEED so ad scanning/skipping runs there too.
        return detectScreen(className)
    }

    private fun checkAndSkip() {
        if (automaticGestureInProgress) return
        if (adSkipScheduled) return
        if (!settings.adSkipEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now < suppressUntil) return
        val keywords = settings.adKeywords
        if (keywords.isEmpty()) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != INSTAGRAM_PACKAGE) return

            // Reels, Home and other tabs can all live in MainTabActivity, so
            // recover the current screen from the actual foreground tree.
            updateCurrentScreen(detectScreen(root, lastWindowClassName))
            if (currentScreen == Screen.OTHER) return

            if (adVisibleOnScreen(root, keywords)) {
                adSkipScheduled = true
                handler.postDelayed(adSkipRunnable, AD_DIRECTION_DECISION_MS)
            }
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
    }

    private fun adVisibleOnScreen(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val bounds = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val t = node.text?.toString()?.trim() ?: ""
            val d = node.contentDescription?.toString()?.trim() ?: ""
            val keywordMarker = keywords.firstOrNull { matches(t, it) || matches(d, it) }
            val callToActionMarker = if (node.isClickable) {
                AD_CALL_TO_ACTIONS.firstOrNull {
                    t.equals(it, ignoreCase = true) || d.equals(it, ignoreCase = true)
                }
            } else {
                null
            }

            val marker = keywordMarker ?: callToActionMarker
            if (marker != null) {
                node.getBoundsInScreen(bounds)
                val cx = bounds.centerX()
                val cy = bounds.centerY()
                val onScreen = bounds.width() > 0 && bounds.height() > 0 &&
                        cx in 0..screenW && cy in 0..screenH
                if (onScreen) {
                    Log.d(TAG, "MATCH marker='$marker' text='$t' bounds=$bounds")
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    private fun matches(value: String?, keyword: String): Boolean {
        if (value.isNullOrEmpty()) return false
        val v = value.lowercase()
        val k = keyword.lowercase()
        return if (k.length <= 3) {
            v == k || v.matches(Regex(".*(^|[^a-z])${Regex.escape(k)}([^a-z]|$).*"))
        } else {
            v.contains(k)
        }
    }

    private fun skip(forward: Boolean, screen: Screen) {
        val m: DisplayMetrics = resources.displayMetrics
        val path: Path

        val duration: Long
        if (screen == Screen.STORIES) {
            // Stories navigate with side taps. A swipe changes users and is both
            // slower and less predictable than tapping the next/previous zone.
            val x = if (forward) m.widthPixels * 0.85f else m.widthPixels * 0.15f
            val y = m.heightPixels * 0.50f
            path = Path().apply { moveTo(x, y) }
            duration = STORY_TAP_DURATION_MS
        } else {
            val x      = m.widthPixels * 0.5f
            val startY = if (forward) m.heightPixels * 0.80f else m.heightPixels * 0.20f
            val endY   = if (forward) m.heightPixels * 0.20f else m.heightPixels * 0.80f
            path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
            // Reels is a snapping ViewPager: a fast flick always lands cleanly
            // on the next page regardless of velocity. The Home feed is a
            // plain scrolling list, so the same fast flick would carry extra
            // fling momentum and overshoot past legitimate posts. Use a much
            // slower, deliberate swipe there so it behaves like a controlled
            // scroll instead of a flick.
            duration = if (screen == Screen.HOME_FEED) HOME_FEED_SWIPE_DURATION_MS else REEL_SWIPE_DURATION_MS
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        automaticGestureInProgress = true
        lastAutomaticDirection = forward

        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                finishAutomaticGesture(completed = true)
                Log.d(TAG, "skip done")
            }
            override fun onCancelled(g: GestureDescription?) {
                finishAutomaticGesture(completed = false)
                Log.w(TAG, "skip cancelled")
            }
        }, null)

        if (!accepted) {
            finishAutomaticGesture(completed = false)
            Log.w(TAG, "skip dispatch rejected")
        }
    }

    private fun finishAutomaticGesture(completed: Boolean) {
        automaticGestureInProgress = false
        val now = SystemClock.elapsedRealtime()
        if (completed) {
            automaticEchoUntil = now + AUTOMATIC_ECHO_MS
            lastUserScrollAt = now
        } else {
            suppressUntil = now + RETRY_AFTER_CANCEL_MS
        }
    }

    companion object {
        const val TAG = "ReelSkipper"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private val REELS_TAB_IDS = listOf(
            "$INSTAGRAM_PACKAGE:id/clips_tab",
            "$INSTAGRAM_PACKAGE:id/reels_tab",
        )
        private val STORY_ROOT_IDS = listOf(
            "$INSTAGRAM_PACKAGE:id/reel_viewer_root",
            "$INSTAGRAM_PACKAGE:id/reel_viewer_container",
        )
        private val AD_CALL_TO_ACTIONS = listOf(
            "Visit Instagram profile",
            "Visit website",
            "See details",
            "Shop now",
            "Learn more",
            "Sign up",
            "Install now",
            "Download",
            "Book now",
            "Apply now",
            "Order now",
            "Get offer",
        )
        private const val REEL_SWIPE_DURATION_MS = 30L
        private const val HOME_FEED_SWIPE_DURATION_MS = 280L
        private const val STORY_TAP_DURATION_MS = 5L
        private const val MIN_DIRECTION_DELTA_PX = 2
        private const val SETTLING_SCROLL_DELTA_PX = 40
        private const val FAST_SCAN_IDLE_MS = 35L
        private const val IDLE_BEFORE_SCAN_MS = 75L
        // Instagram can keep the outgoing ad node in its accessibility tree
        // for almost a second. Real user input clears this immediately.
        private const val SETTLE_MS = 1400L
        private const val RETRY_AFTER_CANCEL_MS = 80L
        private const val AUTOMATIC_ECHO_MS = 350L
        private const val CONTENT_SCAN_DELAY_MS = 3L
        private const val MIN_CONTENT_SCAN_INTERVAL_MS = 16L
        private const val AD_DIRECTION_DECISION_MS = 3L
        private const val POLL_MS = 100L
    }
}
