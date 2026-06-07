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

        // 1. Detect category: Browsers vs Target Apps
        val isBrowser = packageName.contains("chrome", ignoreCase = true) ||
                packageName.contains("firefox", ignoreCase = true) ||
                packageName.contains("browser", ignoreCase = true) ||
                packageName.contains("sbrowser", ignoreCase = true) || // Samsung
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

        // 3. Resolve Active Layout Tree Root
        var rootNode = rootInActiveWindow
        if (rootNode == null) {
            var source = event.source
            while (source?.parent != null) {
                source = source.parent
            }
            rootNode = source
        }
        
        if (rootNode == null) return

        // 4. Run detection based on app category
        val shouldBlock = if (isBrowser) {
            detectBrowserShortsOrReels(rootNode)
        } else {
            scanNodeForIndicators(rootNode, packageName)
        }

        if (shouldBlock) {
            triggerBlockerOverlay()
        }
    }

    // Heuristics scanning for native apps using text, description, and ID names
    private fun scanNodeForIndicators(node: AccessibilityNodeInfo?, packageName: String): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""

        val isYouTube = packageName.contains("youtube", ignoreCase = true)
        val isInstagram = packageName.contains("instagram", ignoreCase = true)
        val isSnapchat = packageName.contains("snapchat", ignoreCase = true)

        if (isYouTube) {
            // YouTube Shorts indicators (matches obfuscated layouts via text/description)
            if (resId.contains("reel", ignoreCase = true) || 
                resId.contains("short", ignoreCase = true) ||
                desc.contains("shorts player", ignoreCase = true) ||
                desc.contains("shorts video", ignoreCase = true) ||
                desc.contains("Remix", ignoreCase = true) ||
                text.equals("Shorts", ignoreCase = true)) {
                
                // Exclude the bottom navigation tab button itself
                if (!resId.contains("tab", ignoreCase = true) && 
                    !resId.contains("button", ignoreCase = true)) {
                    return true
                }
            }
        }

        if (isInstagram) {
            // Instagram Reels indicators (matches obfuscated layouts via clips labels)
            if (resId.contains("clips", ignoreCase = true) || 
                resId.contains("reels", ignoreCase = true) ||
                desc.contains("Reel by", ignoreCase = true) || 
                desc.contains("Share Reel", ignoreCase = true) ||
                desc.contains("Reels", ignoreCase = true) ||
                text.contains("Reel by", ignoreCase = true) ||
                text.contains("Write a comment...", ignoreCase = true) ||
                text.contains("Add a comment...", ignoreCase = true) ||
                text.equals("Reels", ignoreCase = true)) {
                
                // Exclude profiles, home tabs, and buttons
                if (!resId.contains("tab", ignoreCase = true) && 
                    !resId.contains("button", ignoreCase = true) &&
                    !desc.contains("button", ignoreCase = true)) {
                    return true
                }
            }
        }

        if (isSnapchat) {
            // Snapchat Spotlight indicators
            if (resId.contains("spotlight", ignoreCase = true) ||
                desc.contains("Spotlight", ignoreCase = true) ||
                text.contains("Spotlight", ignoreCase = true) ||
                text.contains("Send message in Spotlight", ignoreCase = true)) {
                
                if (!resId.contains("tab", ignoreCase = true) && 
                    !resId.contains("button", ignoreCase = true)) {
                    return true
                }
            }
        }

        // Recursively check all children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (scanNodeForIndicators(child, packageName)) {
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
