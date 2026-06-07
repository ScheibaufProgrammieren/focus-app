package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

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
 *
 * Blocking strategy:
 * Uses WindowManager TYPE_APPLICATION_OVERLAY to draw a fullscreen blocking overlay
 * directly from the service. This bypasses Android 10+ background activity start
 * restrictions that cause Activity-based overlays to silently fail on many OEMs.
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

        // Motivational quotes shown on the block overlay
        private val MOTIVATIONAL_QUOTES = arrayOf(
            "Is this cheap 15-second escape really worth your dreams?",
            "Your focus is being monetized. Take back control of your mind.",
            "Stop consuming someone else's highlight reel. Go build your own life.",
            "You opened this app to do something else. What was it?",
            "Every short you watch is a trade: your potential in exchange for flashing lights.",
            "Break the loop. Step away.",
            "Your future self is watching you right now. Make them proud.",
            "Success is built on what you do when you are bored. Don't scroll.",
            "This feed is designed to keep you trapped. Escape now.",
            "The algorithm doesn't care about you. Your goals do.",
            "You are stronger than a dopamine loop. Prove it.",
            "15 seconds × 100 times = 25 minutes of your life. Gone."
        )
    }

    private var lastBlockTime: Long = 0
    private var lastProcessedTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDebounceRunnable: Runnable? = null

    // WindowManager overlay state
    private var overlayView: View? = null
    private var isOverlayShowing = false

    // Broadcast receiver: overlay "Go Back" button sends this to trigger back navigation
    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.focusguard.ACTION_EXIT_FEED") {
                dismissOverlay()
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
        // Don't process events while overlay is showing
        if (isOverlayShowing) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val eventType = event.eventType

        // Ignore events from our own app
        if (packageName.contains("focusguard", ignoreCase = true)) return

        // Classify the source app
        val appType = classifyApp(packageName)
        if (appType == AppType.IRRELEVANT) return

        // Check if this app category is enabled in user preferences
        if (!isBlockingEnabled(appType)) return

        // FAST PATH: Activity class name matching (zero-cost, instant detection)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (matchActivityClassName(appType, className)) {
                log("BLOCKED via Activity class match: $className (pkg=$packageName)")
                triggerBlock()
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
        if (isOverlayShowing) return

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
                triggerBlock()
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

    private fun scanBrowser(root: AccessibilityNodeInfo): Boolean {
        if (scanUrlBar(root, 0)) return true
        if (scanForBrowserContentIndicators(root, 0)) return true
        return false
    }

    private fun scanUrlBar(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_SCAN_DEPTH) return false

        val resId = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""

        val isUrlBar = URL_BAR_RESOURCE_IDS.any { resId.contains(it, ignoreCase = true) }

        if (isUrlBar && text.isNotEmpty()) {
            val lowerText = text.lowercase()
            if (BLOCKED_URL_PATTERNS.any { lowerText.contains(it) }) {
                log("URL bar match: $text")
                return true
            }
        }

        if (text.isNotEmpty()) {
            val lowerText = text.lowercase()
            if (BLOCKED_URL_PATTERNS.any { lowerText.contains(it) }) {
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

    private fun scanForBrowserContentIndicators(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_SCAN_DEPTH) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

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

        // 2. Reels player viewport detection (resource IDs)
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

        // 6. Spotlight-specific text
        if (text.contains("Send message in Spotlight", ignoreCase = true) ||
            text.contains("Spotlight & Sounds", ignoreCase = true)) {
            log("SC: Spotlight UI text=$text")
            return true
        }

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
    // BLOCK TRIGGER + OVERLAY
    // ========================================================================

    /**
     * Main entry point to block the user. Enforces cooldown, increments counter,
     * and shows the fullscreen WindowManager overlay.
     */
    private fun triggerBlock() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockTime < BLOCK_COOLDOWN_MS) {
            log("Block skipped — cooldown active (${currentTime - lastBlockTime}ms since last)")
            return
        }
        if (isOverlayShowing) {
            log("Block skipped — overlay already showing")
            return
        }
        lastBlockTime = currentTime

        // Increment persistent block counter for dashboard stats
        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        val count = prefs.getInt("blocked_count", 0)
        prefs.edit().putInt("blocked_count", count + 1).apply()

        log(">>> BLOCK TRIGGERED — total blocks: ${count + 1}")

        // Show the WindowManager overlay directly (bypasses Android 10+ background activity restrictions)
        mainHandler.post { showOverlay() }
    }

    /**
     * Shows a fullscreen blocking overlay via WindowManager.
     * This is drawn directly on top of all apps using TYPE_APPLICATION_OVERLAY.
     * Unlike Activity-based overlays, this cannot be silently blocked by Android
     * or OEM-specific background activity restrictions.
     */
    private fun showOverlay() {
        if (isOverlayShowing) return

        // Verify overlay permission is still granted
        if (!Settings.canDrawOverlays(this)) {
            log("ERROR: Overlay permission not granted — cannot show block screen")
            return
        }

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val overlay = buildOverlayView()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Focusable so we can intercept the back button
                // LAYOUT_IN_SCREEN to cover status bar
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )

            wm.addView(overlay, params)
            overlayView = overlay
            isOverlayShowing = true

            log("Overlay shown successfully")
        } catch (e: Exception) {
            log("ERROR showing overlay: ${e.message}")
            isOverlayShowing = false
        }
    }

    /**
     * Dismisses the blocking overlay and performs back navigation
     * to exit the short-form feed.
     */
    private fun dismissOverlay() {
        if (!isOverlayShowing) return

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView?.let { wm.removeView(it) }
        } catch (e: Exception) {
            log("Error removing overlay: ${e.message}")
        }

        overlayView = null
        isOverlayShowing = false
        log("Overlay dismissed")
    }

    /**
     * Builds the fullscreen block overlay view programmatically.
     * Design matches the app's dark theme:
     * - Dark near-opaque background (#F5 alpha on #0A0A0C)
     * - Centered card with rounded corners (#141419)
     * - Lock emoji icon
     * - "Focus Guarded" title
     * - Random motivational quote
     * - "Go Back to Work" button
     */
    private fun buildOverlayView(): View {
        val density = resources.displayMetrics.density

        // Root container — fullscreen dark background, consumes all touches
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // Intercept back button to prevent bypassing the block screen
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#F50A0A0C"))
            isClickable = true
            isFocusable = true
        }

        // Center card container
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val cardBg = GradientDrawable().apply {
                setColor(Color.parseColor("#FF141419"))
                cornerRadius = 20f * density
            }
            background = cardBg
            setPadding(
                (32 * density).toInt(),
                (32 * density).toInt(),
                (32 * density).toInt(),
                (32 * density).toInt()
            )
        }

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            val margin = (28 * density).toInt()
            setMargins(margin, margin, margin, margin)
        }

        // Lock icon
        val icon = TextView(this).apply {
            text = "🔒"
            textSize = 48f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (24 * density).toInt())
        }

        // Title
        val title = TextView(this).apply {
            text = "Focus Guarded"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * density).toInt())
        }

        // Random motivational quote
        val quote = TextView(this).apply {
            val randomQuote = MOTIVATIONAL_QUOTES[Random.nextInt(MOTIVATIONAL_QUOTES.size)]
            text = "\"$randomQuote\""
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(4f * density, 1f)
            setPadding(0, 0, 0, (32 * density).toInt())
        }

        // "Go Back to Work" button
        val button = Button(this).apply {
            text = "Go Back to Work"
            setTextColor(Color.parseColor("#0A0A0C"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            val btnBg = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 12f * density
            }
            background = btnBg
            setPadding(
                (24 * density).toInt(),
                (14 * density).toInt(),
                (24 * density).toInt(),
                (14 * density).toInt()
            )

            setOnClickListener {
                dismissOverlay()
                executeBackNavigation()

                // Navigate to home screen to fully break the loop
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            }
        }

        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (54 * density).toInt()
        )

        card.addView(icon)
        card.addView(title)
        card.addView(quote)
        card.addView(button, buttonParams)

        root.addView(card, cardParams)

        return root
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
        dismissOverlay()
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
