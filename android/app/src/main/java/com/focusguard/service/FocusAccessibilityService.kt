package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.app.BlockOverlayActivity

class FocusAccessibilityService : AccessibilityService() {

    private var lastBlockTime: Long = 0
    private val BLOCK_COOLDOWN_MS = 2500L // Prevent double trigger within 2.5s

    // Register receiver to safely exit feed when "Go Back" is clicked
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
        // Register receiver (Android 14 requires explicit exports flag)
        registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // 1. Detect category: Browsers vs Target Apps
        val isBrowser = packageName.contains("chrome", ignoreCase = true) ||
                packageName.contains("firefox", ignoreCase = true) ||
                packageName.contains("brave", ignoreCase = true) ||     // Brave Browser
                packageName.contains("browser", ignoreCase = true) ||
                packageName.contains("sbrowser", ignoreCase = true) || // Samsung Internet
                packageName.contains("emmx", ignoreCase = true) ||     // Edge
                packageName.contains("opera", ignoreCase = true) ||
                packageName.contains("duckduckgo", ignoreCase = true)

        val isYouTube = packageName.contains("youtube", ignoreCase = true)
        val isInstagram = packageName.contains("instagram", ignoreCase = true)
        val isSnapchat = packageName.contains("snapchat", ignoreCase = true)

        if (!isBrowser && !isYouTube && !isInstagram && !isSnapchat) {
            return
        }

        // 2. Check SharedPreferences if blocking is enabled for this app in dashboard
        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        if (isYouTube && !prefs.getBoolean("youtube_enabled", true)) return
        if (isInstagram && !prefs.getBoolean("instagram_enabled", true)) return
        if (isSnapchat && !prefs.getBoolean("snapchat_enabled", true)) return

        // 3. FAIL-SAFE: Instant block based on known Activity Class Names
        if (isYouTube && (
            className.contains("ReelWatchActivity", ignoreCase = true) ||
            className.contains("ShortsWatchActivity", ignoreCase = true) ||
            className.contains("ReelPlayer", ignoreCase = true)
        )) {
            triggerBlockerOverlay()
            return
        }

        if (isInstagram && (
            className.contains("ClipsViewerActivity", ignoreCase = true) ||
            className.contains("ReelsViewerActivity", ignoreCase = true)
        )) {
            triggerBlockerOverlay()
            return
        }

        // 4. Resolve Active Layout Tree Root
        var rootNode = rootInActiveWindow
        if (rootNode == null) {
            var source = event.source
            while (source?.parent != null) {
                source = source.parent
            }
            rootNode = source
        }
        
        if (rootNode == null) return

        // 5. Run layout content heuristics scanning
        val shouldBlock = if (isBrowser) {
            detectBrowserShortsOrReels(rootNode) || scanNodeForIndicators(rootNode, packageName, isBrowser = true)
        } else {
            scanNodeForIndicators(rootNode, packageName, isBrowser = false)
        }

        if (shouldBlock) {
            triggerBlockerOverlay()
        }
    }

    // Heuristics scanning for native apps & browser web views using text, description, class, and selection states
    private fun scanNodeForIndicators(node: AccessibilityNodeInfo?, packageName: String, isBrowser: Boolean): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""

        val isYouTube = packageName.contains("youtube", ignoreCase = true) || isBrowser
        val isInstagram = packageName.contains("instagram", ignoreCase = true) || isBrowser
        val isSnapchat = packageName.contains("snapchat", ignoreCase = true) // Native only

        // 1. DYNAMIC FEED TAB BLOCKING (Blocks if the bottom/sidebar navigation tab is active/selected)
        val isTabSelected = node.isSelected || node.isChecked
        if (isTabSelected) {
            val hasTargetTab = resId.contains("shorts", ignoreCase = true) || 
                               resId.contains("reels", ignoreCase = true) || 
                               resId.contains("spotlight", ignoreCase = true) ||
                               desc.contains("shorts", ignoreCase = true) ||
                               desc.contains("reels", ignoreCase = true) ||
                               desc.contains("spotlight", ignoreCase = true) ||
                               text.contains("shorts", ignoreCase = true) ||
                               text.contains("reels", ignoreCase = true) ||
                               text.contains("spotlight", ignoreCase = true)
            if (hasTargetTab) {
                return true
            }
        }

        // 2. VIEWPORT INDICATOR SCANS (Bypasses resource ID obfuscation)
        if (isYouTube) {
            // YouTube Shorts signatures
            val hasShortsId = resId.contains("reel", ignoreCase = true) || resId.contains("short", ignoreCase = true)
            val hasShortsText = text.equals("Shorts", ignoreCase = true) || text.contains("Remix", ignoreCase = true)
            val hasShortsDesc = desc.contains("shorts player", ignoreCase = true) || 
                                 desc.contains("shorts video", ignoreCase = true) || 
                                 desc.contains("Remix", ignoreCase = true) ||
                                 desc.contains("Dislike this video", ignoreCase = true)

            if (hasShortsId || hasShortsText || hasShortsDesc) {
                // Ignore navigation components to allow standard home feed browsing (only if not active/selected)
                if (!resId.contains("tab", ignoreCase = true) && 
                    !resId.contains("button", ignoreCase = true) &&
                    !desc.contains("tab", ignoreCase = true)) {
                    return true
                }
            }
        }

        if (isInstagram) {
            // Instagram Reels signatures
            val hasReelsId = resId.contains("clips", ignoreCase = true) || resId.contains("reels", ignoreCase = true)
            val hasReelsText = text.contains("Reel by", ignoreCase = true) || 
                                text.contains("Write a comment...", ignoreCase = true) || 
                                text.contains("Add a comment...", ignoreCase = true) || 
                                text.equals("Reels", ignoreCase = true)
            val hasReelsDesc = desc.contains("Reel by", ignoreCase = true) || 
                                 desc.contains("Share Reel", ignoreCase = true) || 
                                 desc.contains("Reels", ignoreCase = true) ||
                                 desc.contains("Double tap to Like", ignoreCase = true)

            if (hasReelsId || hasReelsText || hasReelsDesc) {
                if (!resId.contains("tab", ignoreCase = true) && 
                    !resId.contains("button", ignoreCase = true) &&
                    !desc.contains("button", ignoreCase = true) &&
                    !desc.contains("profile", ignoreCase = true)) {
                    return true
                }
            }
        }

        if (isSnapchat) {
            // Snapchat Spotlight signatures
            val hasSpotlightId = resId.contains("spotlight", ignoreCase = true)
            val hasSpotlightText = text.contains("Spotlight", ignoreCase = true) || 
                                    text.contains("Send message in Spotlight", ignoreCase = true)
            val hasSpotlightDesc = desc.contains("Spotlight", ignoreCase = true)

            if (hasSpotlightId || hasSpotlightText || hasSpotlightDesc) {
                if (!resId.contains("tab", ignoreCase = true) && 
                    !resId.contains("button", ignoreCase = true)) {
                    return true
                }
            }
        }

        // Recursively check all children layout nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (scanNodeForIndicators(child, packageName, isBrowser)) {
                return true
            }
        }
        return false
    }

    // Heuristics scanning for browsers by reading the address bar URL
    private fun detectBrowserShortsOrReels(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val resId = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""

        // Match browser address bar nodes
        if (resId.contains("url_bar", ignoreCase = true) || 
            resId.contains("url_edit", ignoreCase = true) ||
            resId.contains("address_bar", ignoreCase = true) ||
            resId.contains("search_src_text", ignoreCase = true)) {
            
            if (text.isNotEmpty()) {
                if (text.contains("youtube.com/shorts", ignoreCase = true) || 
                    text.contains("instagram.com/reels", ignoreCase = true) ||
                    (text.contains("instagram.com/p/") && text.contains("/reels", ignoreCase = true))) {
                    return true
                }
            }
        }

        // Secondary check: match raw URL text nodes
        if (text.contains("youtube.com/shorts", ignoreCase = true) || 
            text.contains("instagram.com/reels", ignoreCase = true)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (detectBrowserShortsOrReels(child)) {
                return true
            }
        }
        return false
    }

    private fun triggerBlockerOverlay() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockTime < BLOCK_COOLDOWN_MS) {
            return // Prevent overlay flood
        }
        lastBlockTime = currentTime

        // Increment block counter in SharedPreferences
        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        val count = prefs.getInt("blocked_count", 0)
        prefs.edit().putInt("blocked_count", count + 1).apply()

        val intent = Intent(this, BlockOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun executeBackNavigation() {
        // Sequentially execute global back actions to exit layout viewports
        performGlobalAction(GLOBAL_ACTION_BACK)
        android.os.Handler(mainLooper).postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
        }, 150)
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(exitReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
