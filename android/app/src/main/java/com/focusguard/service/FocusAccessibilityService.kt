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
import android.view.ContextThemeWrapper
import com.focusguard.app.R
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * FocusAccessibilityService — Content blocking engine for FocusGuard.
 *
 * Detects and blocks short-form video feeds across:
 * - YouTube Shorts (app + browser)
 * - Instagram Reels (app + browser)
 * - Snapchat Spotlight (app only — Discover and normal Snapchat are NOT blocked)
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
 * directly from the service. Bypasses Android 10+ background activity start restrictions.
 *
 * Navigation strategy:
 * - Browser blocks: navigates BACK within the browser (not home screen) to avoid loops
 * - App blocks: navigates to home screen to fully exit the feed
 */
class FocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FocusGuard"
        private const val DEBUG = true

        // Cooldown between overlay triggers to prevent double-fire
        private const val BLOCK_COOLDOWN_MS = 2000L

        // Post-dismiss cooldown — prevents re-triggering when the underlying app is still showing blocked content
        private const val POST_DISMISS_COOLDOWN_MS = 3500L

        // Debounce window for scroll/content events to prevent CPU storms
        private const val DEBOUNCE_MS = 300L

        // Maximum depth for recursive tree scanning
        private const val MAX_SCAN_DEPTH = 12

        // Browser package identifiers (partial match against packageName)
        private val BROWSER_IDENTIFIERS = listOf(
            "chrome", "firefox", "fenix", "brave", "browser", "sbrowser",
            "emmx", "opera", "duckduckgo", "vivaldi", "kiwibrowser",
            "ucmobile", "via.gp"
        )

        // Known YouTube Shorts Activity class names
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

        // Browser URL bar resource ID patterns
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
            "instagram.com/reel/"
        )

        // Motivational quotes
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

        // Human-readable labels for each app type (shown on overlay)
        private val APP_TYPE_LABELS = mapOf(
            AppType.YOUTUBE to "YouTube Shorts",
            AppType.INSTAGRAM to "Instagram Reels",
            AppType.SNAPCHAT to "Snapchat Spotlight",
            AppType.BROWSER to "Short-form content in browser"
        )
    }

    private var lastBlockTime: Long = 0
    private var lastProcessedTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDebounceRunnable: Runnable? = null

    // WindowManager overlay state
    private var overlayView: View? = null
    private var isOverlayShowing = false
    private var lastBlockedAppType: AppType = AppType.IRRELEVANT

    // Broadcast receiver (kept for backwards compat with BlockOverlayActivity)
    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.focusguard.ACTION_EXIT_FEED") {
                handleGoBack()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("com.focusguard.ACTION_EXIT_FEED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exitReceiver, filter)
        }
        log("Service created")
    }

    // ========================================================================
    // EVENT DISPATCH
    // ========================================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (isOverlayShowing) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val eventType = event.eventType

        if (packageName.contains("focusguard", ignoreCase = true)) return

        val appType = classifyApp(packageName)
        if (appType == AppType.IRRELEVANT) return

        if (!isBlockingEnabled(appType)) return

        // FAST PATH: Activity class name matching
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (matchActivityClassName(appType, className)) {
                log("BLOCKED via Activity class: $className (pkg=$packageName)")
                triggerBlock(appType)
                return
            }
        }

        // DEBOUNCED PATH: Tree scanning
        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < DEBOUNCE_MS) {
            scheduleDebouncedScan(packageName, appType)
            return
        }

        lastProcessedTime = now
        performContentScan(packageName, appType)
    }

    // ========================================================================
    // APP CLASSIFICATION
    // ========================================================================

    enum class AppType {
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
                triggerBlock(appType)
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun acquireRootNode(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }

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

        // Also check raw text nodes that look like URLs
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

        if ((node.isSelected || node.isChecked) && isShortsFeedIndicator(text, desc, resId)) {
            log("YT: Shorts tab selected — text=$text desc=$desc resId=$resId")
            return true
        }

        val hasPlayerResId = resId.contains("reel_recycler", ignoreCase = true) ||
                resId.contains("shorts_video_player", ignoreCase = true) ||
                resId.contains("reel_multi_format_post_container", ignoreCase = true) ||
                resId.contains("reel_player_page_container", ignoreCase = true) ||
                resId.contains("shorts_surface_container", ignoreCase = true)
        if (hasPlayerResId) {
            log("YT: Shorts player resId=$resId")
            return true
        }

        val hasPlayerDesc = desc.contains("shorts player", ignoreCase = true) ||
                desc.contains("shorts video", ignoreCase = true) ||
                (desc.contains("Dislike this video", ignoreCase = true) &&
                        !resId.contains("regular", ignoreCase = true))
        if (hasPlayerDesc) {
            log("YT: Shorts player desc=$desc")
            return true
        }

        if (text.equals("Remix", ignoreCase = true) && className.contains("Button", ignoreCase = true)) {
            log("YT: Remix button — Shorts confirmed")
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
        return text.equals("Shorts", ignoreCase = true) ||
                desc.contains("shorts", ignoreCase = true) ||
                resId.contains("shorts", ignoreCase = true)
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

        if ((node.isSelected || node.isChecked) && isReelsFeedIndicator(text, desc, resId)) {
            log("IG: Reels tab selected — text=$text desc=$desc resId=$resId")
            return true
        }

        val hasReelsResId = resId.contains("clips_viewer", ignoreCase = true) ||
                resId.contains("reels_viewer", ignoreCase = true) ||
                resId.contains("clips_tab", ignoreCase = true) ||
                resId.contains("clips_surface", ignoreCase = true) ||
                resId.contains("reel_viewer", ignoreCase = true)
        if (hasReelsResId) {
            log("IG: Reels viewport resId=$resId")
            return true
        }

        if (desc.contains("Reel by", ignoreCase = true) ||
            desc.contains("Share Reel", ignoreCase = true) ||
            desc.contains("Double tap to Like", ignoreCase = true)) {
            log("IG: Reels content desc=$desc")
            return true
        }

        if (text.contains("Reel by", ignoreCase = true) ||
            (text.equals("Reels", ignoreCase = true) && !resId.contains("tab", ignoreCase = true) &&
                    (className.contains("TextView", ignoreCase = true) || className.contains("Header", ignoreCase = true)))) {
            log("IG: Reels title text=$text")
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
    // SNAPCHAT DETECTION — SPOTLIGHT ONLY
    // Only blocks the Spotlight feed. Discover, Stories, and normal Snapchat are NOT blocked.
    // ========================================================================

    private fun scanSnapchat(root: AccessibilityNodeInfo): Boolean {
        return scanSnapchatNode(root, 0)
    }

    private fun scanSnapchatNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_SCAN_DEPTH) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""

        // 1. Spotlight tab is selected in the bottom navigation
        if ((node.isSelected || node.isChecked)) {
            val isSpotlightTab = text.equals("Spotlight", ignoreCase = true) ||
                    desc.equals("Spotlight", ignoreCase = true) ||
                    (resId.contains("spotlight", ignoreCase = true) &&
                            (resId.contains("tab", ignoreCase = true) || resId.contains("nav", ignoreCase = true)))
            if (isSpotlightTab) {
                log("SC: Spotlight tab selected — text=$text desc=$desc resId=$resId")
                return true
            }
        }

        // 2. Spotlight player/feed viewport (resource IDs that are NOT nav elements)
        if (resId.contains("spotlight_feed", ignoreCase = true) ||
            resId.contains("spotlight_player", ignoreCase = true) ||
            resId.contains("spotlight_video", ignoreCase = true) ||
            resId.contains("spotlight_recycler", ignoreCase = true) ||
            resId.contains("spotlight_container", ignoreCase = true)) {
            log("SC: Spotlight feed viewport resId=$resId")
            return true
        }

        // 3. Spotlight-specific UI text (only in the Spotlight player)
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

    // ========================================================================
    // BLOCK TRIGGER + OVERLAY
    // ========================================================================

    private fun triggerBlock(appType: AppType) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockTime < BLOCK_COOLDOWN_MS) {
            log("Block skipped — cooldown (${currentTime - lastBlockTime}ms)")
            return
        }
        if (isOverlayShowing) {
            log("Block skipped — overlay already showing")
            return
        }
        lastBlockTime = currentTime
        lastBlockedAppType = appType

        // Increment persistent block counter
        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        val count = prefs.getInt("blocked_count", 0)
        prefs.edit().putInt("blocked_count", count + 1).apply()

        log(">>> BLOCK TRIGGERED ($appType) — total: ${count + 1}")

        mainHandler.post { showOverlay(appType) }
    }

    private fun showOverlay(appType: AppType) {
        if (isOverlayShowing) return

        if (!Settings.canDrawOverlays(this)) {
            log("ERROR: Overlay permission not granted")
            return
        }

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val overlay = buildOverlayView(appType)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )

            wm.addView(overlay, params)
            overlayView = overlay
            isOverlayShowing = true

            log("Overlay shown for $appType")
        } catch (e: Exception) {
            log("ERROR showing overlay: ${e.message}")
            isOverlayShowing = false
        }
    }

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

        // Reset cooldown from the moment of dismissal, not trigger.
        // This prevents the service from immediately re-detecting the same content
        // before the back navigation has time to move the app away.
        lastBlockTime = System.currentTimeMillis() + (POST_DISMISS_COOLDOWN_MS - BLOCK_COOLDOWN_MS)

        log("Overlay dismissed")
    }

    /**
     * Handle the "Go Back to Work" action with app-type-aware navigation.
     *
     * - Browser: navigate BACK within the browser (so it goes from youtube.com/shorts
     *   to the previous page). Does NOT go to home screen to avoid the reopen loop.
     * - Native apps: navigate to home screen to fully exit the feed.
     */
    private fun handleGoBack() {
        val appType = lastBlockedAppType
        log("Go Back pressed — appType=$appType")

        dismissOverlay()

        // Small delay to let the underlying app regain focus before performing back actions
        mainHandler.postDelayed({
            when (appType) {
                AppType.BROWSER -> {
                    // Navigate back WITHIN the browser — goes back in browser history
                    // away from the shorts/reels URL to whatever page was before
                    log("Browser: performing in-app back navigation (no home)")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    mainHandler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, 200)
                    // Do NOT go to home screen — that causes the reopen loop
                }
                else -> {
                    // Native apps: perform back + go to home screen to fully exit the feed
                    log("App: performing back + home navigation")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    mainHandler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        mainHandler.postDelayed({
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(homeIntent)
                        }, 150)
                    }, 150)
                }
            }
        }, 200)
    }

    // ========================================================================
    // OVERLAY UI
    // ========================================================================

    private fun buildOverlayView(appType: AppType): View {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_FocusGuard)
        val d = themedContext.resources.displayMetrics.density
        val blockedLabel = APP_TYPE_LABELS[appType] ?: "Short-form content"

        // Root — fullscreen dark overlay, intercepts back button
        val root = object : FrameLayout(themedContext) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#F20A0A0C"))
            isClickable = true
            isFocusable = true
        }

        // ── Card container ──
        val card = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF141419"))
                cornerRadius = 24f * d
                setStroke((1 * d).toInt(), Color.parseColor("#27272A"))
            }
            setPadding((32 * d).toInt(), (40 * d).toInt(), (32 * d).toInt(), (36 * d).toInt())
        }

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            val m = (24 * d).toInt()
            setMargins(m, m, m, m)
        }

        // ── Shield icon ──
        val icon = TextView(themedContext).apply {
            text = "🛡️"
            textSize = 52f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (20 * d).toInt())
        }

        // ── Title ──
        val title = TextView(themedContext).apply {
            text = "Focus Guarded"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
            setPadding(0, 0, 0, (8 * d).toInt())
        }

        // ── What was blocked ──
        val blockedBadge = TextView(themedContext).apply {
            text = "⊘  $blockedLabel blocked"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            letterSpacing = 0.02f

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1AEF4444"))
                cornerRadius = 8f * d
            }
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
        }

        val badgeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            bottomMargin = (20 * d).toInt()
        }

        // ── Divider line ──
        val divider = View(themedContext).apply {
            setBackgroundColor(Color.parseColor("#27272A"))
        }
        val dividerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (1 * d).toInt()
        ).apply {
            bottomMargin = (20 * d).toInt()
        }

        // ── Motivational quote ──
        val quote = TextView(themedContext).apply {
            val randomQuote = MOTIVATIONAL_QUOTES[Random.nextInt(MOTIVATIONAL_QUOTES.size)]
            text = "\"$randomQuote\""
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            gravity = Gravity.CENTER
            setLineSpacing(5f * d, 1f)
            setPadding(0, 0, 0, (28 * d).toInt())
        }

        // ── Go Back button ──
        val button = Button(themedContext).apply {
            text = "Go Back to Work"
            setTextColor(Color.parseColor("#0A0A0C"))
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false
            stateListAnimator = null // remove default elevation animation
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 14f * d
            }
            setPadding(
                (24 * d).toInt(), (14 * d).toInt(),
                (24 * d).toInt(), (14 * d).toInt()
            )
            setOnClickListener { handleGoBack() }
        }

        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (56 * d).toInt()
        )

        // ── Assemble card ──
        card.addView(icon)
        card.addView(title)
        card.addView(blockedBadge, badgeParams)
        card.addView(divider, dividerParams)
        card.addView(quote)
        card.addView(button, buttonParams)

        root.addView(card, cardParams)

        return root
    }

    // ========================================================================
    // BACK NAVIGATION (legacy — used by broadcast receiver)
    // ========================================================================

    private fun executeBackNavigation() {
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
