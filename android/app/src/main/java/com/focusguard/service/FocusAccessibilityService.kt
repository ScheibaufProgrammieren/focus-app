package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.focusguard.app.BlockOverlayActivity

/**
 * FocusAccessibilityService — Content blocking engine for FocusGuard.
 *
 * Detects and blocks short-form video feeds across:
 * - YouTube Shorts (app + browser)
 * - Instagram Reels (app + browser)
 * - Snapchat Spotlight, Discover, and Highlights (app only)
 * - All major mobile browsers (Chrome, Firefox, Brave, Samsung, Edge, Opera, DuckDuckGo, etc.)
 *
 * Detection strategy:
 * 1. Activity class name matching (instant, zero-cost for known Activities)
 * 2. Browser URL bar scanning (for youtube.com/shorts, instagram.com/reels)
 * 3. Layout tree heuristic scanning with depth-limited traversal (for dynamic feeds)
 * 4. Debounced event processing to prevent Android watchdog kills
 */
class FocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FocusGuard"
        private const val DEBUG = true // Set false for production release

        // Cooldown between overlay triggers to prevent double-fire
        private const val BLOCK_COOLDOWN_MS = 2000L

        // Debounce window for scroll/content events to prevent CPU storms
        private const val DEBOUNCE_MS = 300L

        // Maximum depth for recursive tree scanning (Instagram can have 2000+ nodes)
        private const val MAX_SCAN_DEPTH = 12

        // Browser package identifiers (partial match against packageName)
        private val BROWSER_IDENTIFIERS = listOf(
            "chrome", "firefox", "fenix", "brave", "browser", "sbrowser",
            "emmx", "opera", "duckduckgo", "vivaldi", "kiwibrowser",
            "ucmobile", "via.gp"
        )

        // Known YouTube Shorts Activity class names (instant detection, no tree walk needed)
        private val YOUTUBE_SHORT_ACTIVITIES = listOf(
            "ReelWatchActivity",
            "ShortsWatchActivity",
            "ReelPlayerActivity",
            "ShortsActivity",
            "ReelPlayer"
        )

        // Known Instagram Reels Activity class names
        private val INSTAGRAM_REELS_ACTIVITIES = listOf(
            "ClipsViewerActivity",
            "ReelsViewerActivity",
            "ClipsTabActivity",
            "ReelsFragment"
        )

        // Browser URL bar resource ID patterns (covers Chrome, Firefox, Brave, Samsung, Edge, Opera, etc.)
        private val URL_BAR_RESOURCE_IDS = listOf(
            "url_bar", "url_bar_title", "mozac_browser_toolbar_url_view",
            "url_edit_text", "address_bar_edit_text", "search_src_text",
            "location_bar_edit_text", "url_field", "addressbarEdit",
            "bro_omnibar_address_title_text", "omnibox_url_field",
            "url_bar_title_text", "url_input"
        )

        // Blocked URL patterns in browser address bars
        private val BLOCKED_URL_PATTERNS = listOf(
            "youtube.com/shorts",
            "youtube.com/short/",
            "m.youtube.com/shorts",
            "instagram.com/reels",
            "instagram.com/reel/",
            "snapchat.com/spotlight",
            "snapchat.com/discover"
        )
    }

    private var lastBlockTime: Long = 0
    private var lastProcessedTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDebounceRunnable: Runnable? = null

    // Broadcast receiver: BlockOverlayActivity sends this to trigger back navigation
    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.focusguard.ACTION_EXIT_FEED") {
                executeBackNavigation()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("com.focusguard.ACTION_EXIT_FEED")
        registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        log("Service created and broadcast receiver registered")
    }

    // ========================================================================
    // EVENT DISPATCH
    // ========================================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val eventType = event.eventType

        // Classify the source app
        val appType = classifyApp(packageName)
        if (appType == AppType.IRRELEVANT) return

        // Check if this app category is enabled in user preferences
        if (!isBlockingEnabled(appType)) return

        // FAST PATH: Activity class name matching (zero-cost, instant detection)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (matchActivityClassName(appType, className)) {
                log("BLOCKED via Activity class match: $className (pkg=$packageName)")
                triggerBlockerOverlay()
                return
            }
        }

        // DEBOUNCED PATH: Tree scanning is expensive — debounce scroll events
        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < DEBOUNCE_MS) {
            // Schedule a deferred scan so we don't miss the final state
            scheduleDebouncedScan(packageName, appType)
            return
        }

        lastProcessedTime = now
        performContentScan(packageName, appType)
    }

    // ========================================================================
    // APP CLASSIFICATION
    // ========================================================================

    private enum class AppType {
        YOUTUBE, INSTAGRAM, SNAPCHAT, BROWSER, IRRELEVANT
    }

    private fun classifyApp(packageName: String): AppType {
        val lower = packageName.lowercase()
        return when {
            lower.contains("youtube") -> AppType.YOUTUBE
            lower.contains("instagram") -> AppType.INSTAGRAM
            lower.contains("snapchat") -> AppType.SNAPCHAT
            BROWSER_IDENTIFIERS.any { lower.contains(it) } -> AppType.BROWSER
            else -> AppType.IRRELEVANT
        }
    }

    private fun isBlockingEnabled(appType: AppType): Boolean {
        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        return when (appType) {
            AppType.YOUTUBE -> prefs.getBoolean("youtube_enabled", true)
            AppType.INSTAGRAM -> prefs.getBoolean("instagram_enabled", true)
            AppType.SNAPCHAT -> prefs.getBoolean("snapchat_enabled", true)
            AppType.BROWSER -> prefs.getBoolean("browser_enabled", true)
            AppType.IRRELEVANT -> false
        }
    }

    // ========================================================================
    // FAST PATH: ACTIVITY CLASS NAME MATCHING
    // ========================================================================

    private fun matchActivityClassName(appType: AppType, className: String): Boolean {
        if (className.isEmpty()) return false
        return when (appType) {
            AppType.YOUTUBE -> YOUTUBE_SHORT_ACTIVITIES.any {
                className.contains(it, ignoreCase = true)
            }
            AppType.INSTAGRAM -> INSTAGRAM_REELS_ACTIVITIES.any {
                className.contains(it, ignoreCase = true)
            }
            // Snapchat doesn't use distinct Activity names for Spotlight/Discover
            else -> false
        }
    }

    // ========================================================================
    // DEBOUNCED SCAN SCHEDULING
    // ========================================================================

    private fun scheduleDebouncedScan(packageName: String, appType: AppType) {
        pendingDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            lastProcessedTime = System.currentTimeMillis()
            performContentScan(packageName, appType)
        }
        pendingDebounceRunnable = runnable
        mainHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    // ========================================================================
    // CONTENT SCAN ENGINE
    // ========================================================================

    private fun performContentScan(packageName: String, appType: AppType) {
        val rootNode = acquireRootNode() ?: run {
            log("Root node is null — skipping scan for $packageName")
            return
        }

        try {
            val shouldBlock = when (appType) {
                AppType.BROWSER -> scanBrowser(rootNode)
                AppType.YOUTUBE -> scanYouTube(rootNode)
                AppType.INSTAGRAM -> scanInstagram(rootNode)
                AppType.SNAPCHAT -> scanSnapchat(rootNode)
                AppType.IRRELEVANT -> false
            }

            if (shouldBlock) {
                log("BLOCKED via content scan: appType=$appType, pkg=$packageName")
                triggerBlockerOverlay()
            }
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Acquire the root node of the active window.
     * Uses multiple fallback strategies for Android 12+ compatibility.
     */
    private fun acquireRootNode(): AccessibilityNodeInfo? {
        // Strategy 1: Direct root (works on most Android versions)
        rootInActiveWindow?.let { return it }

        // Strategy 2: Iterate accessible windows and find the active app window
        try {
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        window.root?.let { return it }
                    }
                }
            }
        } catch (e: Exception) {
            log("Window fallback failed: ${e.message}")
        }

        return null
    }

    // ========================================================================
    // BROWSER DETECTION
    // ========================================================================

    /**
     * Scans browser UI for blocked URLs in the address bar.
     * Also falls back to heuristic text scanning for WebView content.
     */
    private fun scanBrowser(root: AccessibilityNodeInfo): Boolean {
        // Primary: URL bar scanning
        if (scanUrlBar(root, 0)) return true

        // Secondary: WebView content heuristics (catches embedded players)
        if (scanForBrowserContentIndicators(root, 0)) return true

        return false
    }

    private fun scanUrlBar(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_SCAN_DEPTH) return false

        val resId = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""

        // Check if this node is a URL bar
        val isUrlBar = URL_BAR_RESOURCE_IDS.any { resId.contains(it, ignoreCase = true) }

        if (isUrlBar && text.isNotEmpty()) {
            val lowerText = text.lowercase()
            if (BLOCKED_URL_PATTERNS.any { lowerText.contains(it) }) {
                log("URL bar match: $text")
                return true
            }
        }

        // Also check raw text nodes that contain full URLs (some browsers render URL in title)
        if (text.isNotEmpty()) {
            val lowerText = text.lowercase()
            if (BLOCKED_URL_PATTERNS.any { lowerText.contains(it) }) {
                // Validate this isn't just a link in page content — check if it looks like a URL
                if (lowerText.startsWith("http") || lowerText.contains("://") ||
                    lowerText.startsWith("youtube.com") || lowerText.startsWith("instagram.com") ||
                    lowerText.startsWith("m.youtube") || lowerText.startsWith("www.")) {
                    log("URL text node match: $text")
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (scanUrlBar(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    /**
     * Catches browser-embedded YouTube Shorts and Instagram Reels players
     * by scanning WebView content for player-specific UI elements.
     */
    private fun scanForBrowserContentIndicators(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_SCAN_DEPTH) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        // YouTube Shorts in-browser player indicators
        if (text.contains("Shorts", ignoreCase = true) && (
            desc.contains("player", ignoreCase = true) ||
            desc.contains("Dislike", ignoreCase = true) ||
            text.contains("Subscribe", ignoreCase = true)
        )) {
            log("Browser Shorts player detected via content heuristic")
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (scanForBrowserContentIndicators(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    // ========================================================================
    // YOUTUBE SHORTS DETECTION
    // ========================================================================

    private fun scanYouTube(root: AccessibilityNodeInfo): Boolean {
        return scanYouTubeNode(root, 0)
    }

    private fun scanYouTubeNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_SCAN_DEPTH) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""

        // 1. Selected tab detection — "Shorts" tab is active in bottom nav
        if ((node.isSelected || node.isChecked) && isShortsFeedIndicator(text, desc, resId)) {
            log("YT: Shorts tab is selected — text=$text desc=$desc resId=$resId")
            return true
        }

        // 2. Shorts player viewport detection (resource IDs)
        val hasPlayerResId = resId.contains("reel_recycler", ignoreCase = true) ||
                resId.contains("shorts_video_player", ignoreCase = true) ||
                resId.contains("reel_multi_format_post_container", ignoreCase = true) ||
                resId.contains("reel_player_page_container", ignoreCase = true) ||
                resId.contains("shorts_surface_container", ignoreCase = true)
        if (hasPlayerResId) {
            log("YT: Shorts player viewport resId=$resId")
            return true
        }

        // 3. Shorts content description indicators
        val hasPlayerDesc = desc.contains("shorts player", ignoreCase = true) ||
                desc.contains("shorts video", ignoreCase = true) ||
                (desc.contains("Dislike this video", ignoreCase = true) &&
                        !resId.contains("regular", ignoreCase = true))
        if (hasPlayerDesc) {
            log("YT: Shorts player desc=$desc")
            return true
        }

        // 4. Shorts-specific UI elements (Remix button only exists in Shorts)
        if (text.equals("Remix", ignoreCase = true) && className.contains("Button", ignoreCase = true)) {
            log("YT: Remix button detected — Shorts confirmed")
            return true
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (scanYouTubeNode(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun isShortsFeedIndicator(text: String, desc: String, resId: String): Boolean {
        val indicators = listOf("shorts")
        return indicators.any { indicator ->
            text.equals(indicator, ignoreCase = true) ||
            desc.contains(indicator, ignoreCase = true) ||
            resId.contains(indicator, ignoreCase = true)
        }
    }

    // ========================================================================
    // INSTAGRAM REELS DETECTION
    // ========================================================================

    private fun scanInstagram(root: AccessibilityNodeInfo): Boolean {
        return scanInstagramNode(root, 0)
    }

    private fun scanInstagramNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_SCAN_DEPTH) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""

        // 1. Selected tab detection — "Reels" tab is active in bottom nav
        if ((node.isSelected || node.isChecked) && isReelsFeedIndicator(text, desc, resId)) {
            log("IG: Reels tab is selected — text=$text desc=$desc resId=$resId")
            return true
        }

        // 2. Reels player viewport detection (resource IDs — Instagram uses "clips" internally)
        val hasReelsResId = resId.contains("clips_viewer", ignoreCase = true) ||
                resId.contains("reels_viewer", ignoreCase = true) ||
                resId.contains("clips_tab", ignoreCase = true) ||
                resId.contains("clips_surface", ignoreCase = true) ||
                resId.contains("reel_viewer", ignoreCase = true)
        if (hasReelsResId) {
            log("IG: Reels viewport resId=$resId")
            return true
        }

        // 3. Reels content description indicators
        if (desc.contains("Reel by", ignoreCase = true) ||
            desc.contains("Share Reel", ignoreCase = true) ||
            desc.contains("Double tap to Like", ignoreCase = true)) {
            log("IG: Reels content desc=$desc")
            return true
        }

        // 4. Reels-specific text indicators
        if (text.contains("Reel by", ignoreCase = true) ||
            (text.equals("Reels", ignoreCase = true) && !resId.contains("tab", ignoreCase = true) &&
                    (className.contains("TextView", ignoreCase = true) || className.contains("Header", ignoreCase = true)))) {
            log("IG: Reels header/title text=$text")
            return true
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (scanInstagramNode(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun isReelsFeedIndicator(text: String, desc: String, resId: String): Boolean {
        val indicators = listOf("reels", "clips")
        return indicators.any { indicator ->
            text.equals(indicator, ignoreCase = true) ||
            desc.contains(indicator, ignoreCase = true) ||
            resId.contains(indicator, ignoreCase = true)
        }
    }

    // ========================================================================
    // SNAPCHAT DETECTION (Spotlight + Discover + Stories Highlights)
    // ========================================================================

    private fun scanSnapchat(root: AccessibilityNodeInfo): Boolean {
        return scanSnapchatNode(root, 0)
    }

    private fun scanSnapchatNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_SCAN_DEPTH) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""

        // 1. Selected tab detection — Spotlight or Discover tab is active
        if ((node.isSelected || node.isChecked) && isSnapchatFeedIndicator(text, desc, resId)) {
            log("SC: Feed tab selected — text=$text desc=$desc resId=$resId")
            return true
        }

        // 2. Spotlight player resource IDs
        val hasSpotlightResId = resId.contains("spotlight", ignoreCase = true) ||
                resId.contains("spotlight_feed", ignoreCase = true) ||
                resId.contains("spotlight_player", ignoreCase = true)
        if (hasSpotlightResId) {
            // Exclude navigation buttons/tabs (we only want the actual feed viewport)
            if (!resId.contains("tab", ignoreCase = true) &&
                !resId.contains("button", ignoreCase = true) &&
                !resId.contains("icon", ignoreCase = true)) {
                log("SC: Spotlight viewport resId=$resId")
                return true
            }
        }

        // 3. Discover feed detection
        val hasDiscoverResId = resId.contains("discover", ignoreCase = true) ||
                resId.contains("discover_feed", ignoreCase = true) ||
                resId.contains("df_large_card", ignoreCase = true) ||
                resId.contains("publisher_card", ignoreCase = true)
        if (hasDiscoverResId) {
            if (!resId.contains("tab", ignoreCase = true) &&
                !resId.contains("button", ignoreCase = true) &&
                !resId.contains("icon", ignoreCase = true)) {
                log("SC: Discover feed resId=$resId")
                return true
            }
        }

        // 4. Snapchat text/description indicators
        if (desc.contains("Spotlight", ignoreCase = true) ||
            desc.contains("Discover", ignoreCase = true)) {
            // Only match if not a navigation element
            if (!desc.contains("tab", ignoreCase = true) &&
                !desc.contains("button", ignoreCase = true)) {
                log("SC: Feed content desc=$desc")
                return true
            }
        }

        // 5. Snapchat Highlights / Stories page detection
        if (text.contains("Highlights", ignoreCase = true) ||
            desc.contains("Highlights", ignoreCase = true) ||
            resId.contains("highlights", ignoreCase = true) ||
            resId.contains("story_highlights", ignoreCase = true)) {
            if (!resId.contains("tab", ignoreCase = true) &&
                !resId.contains("button", ignoreCase = true)) {
                log("SC: Highlights detected — text=$text desc=$desc resId=$resId")
                return true
            }
        }

        // 6. Spotlight-specific text (e.g., "Send message in Spotlight")
        if (text.contains("Send message in Spotlight", ignoreCase = true) ||
            text.contains("Spotlight & Sounds", ignoreCase = true)) {
            log("SC: Spotlight UI text=$text")
            return true
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (scanSnapchatNode(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun isSnapchatFeedIndicator(text: String, desc: String, resId: String): Boolean {
        val indicators = listOf("spotlight", "discover", "highlights")
        return indicators.any { indicator ->
            text.equals(indicator, ignoreCase = true) ||
            desc.contains(indicator, ignoreCase = true) ||
            resId.contains(indicator, ignoreCase = true)
        }
    }

    // ========================================================================
    // BLOCKER OVERLAY TRIGGER
    // ========================================================================

    private fun triggerBlockerOverlay() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockTime < BLOCK_COOLDOWN_MS) {
            log("Block skipped — cooldown active (${currentTime - lastBlockTime}ms since last)")
            return
        }
        lastBlockTime = currentTime

        // Increment persistent block counter for dashboard stats
        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        val count = prefs.getInt("blocked_count", 0)
        prefs.edit().putInt("blocked_count", count + 1).apply()

        log(">>> OVERLAY TRIGGERED — total blocks: ${count + 1}")

        val intent = Intent(this, BlockOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    // ========================================================================
    // BACK NAVIGATION
    // ========================================================================

    private fun executeBackNavigation() {
        log("Executing back navigation sequence")
        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
        }, 150)
    }

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    override fun onInterrupt() {
        log("Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            unregisterReceiver(exitReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        log("Service destroyed")
    }

    // ========================================================================
    // LOGGING
    // ========================================================================

    private fun log(message: String) {
        if (DEBUG) {
            Log.d(TAG, message)
        }
    }
}
