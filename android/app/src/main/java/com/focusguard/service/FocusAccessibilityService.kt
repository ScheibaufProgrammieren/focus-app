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

        // 1. Package Filter using flexible substring checks (supports Vanced/Lite/mods)
        val isYouTube = packageName.contains("youtube", ignoreCase = true)
        val isInstagram = packageName.contains("instagram", ignoreCase = true)
        val isSnapchat = packageName.contains("snapchat", ignoreCase = true)

        if (!isYouTube && !isInstagram && !isSnapchat) {
            return
        }

        // 2. Check SharedPreferences if blocking is enabled for this app in dashboard
        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        if (isYouTube && !prefs.getBoolean("youtube_enabled", true)) return
        if (isInstagram && !prefs.getBoolean("instagram_enabled", true)) return
        if (isSnapchat && !prefs.getBoolean("snapchat_enabled", true)) return

        // 3. Resolve Active Layout Tree Root
        // If rootInActiveWindow is null, climb up from event.source to the tree root
        var rootNode = rootInActiveWindow
        if (rootNode == null) {
            var source = event.source
            while (source?.parent != null) {
                source = source.parent
            }
            rootNode = source
        }
        
        if (rootNode == null) return

        // 4. Scan hierarchy for block targets
        val shouldBlock = scanNodeRecursive(rootNode, packageName)

        if (shouldBlock) {
            triggerBlockerOverlay()
        }
    }

    // Unified, case-insensitive layout scanner mapping app signatures
    private fun scanNodeRecursive(node: AccessibilityNodeInfo?, packageName: String): Boolean {
        if (node == null) return false

        val resourceId = node.viewIdResourceName
        if (resourceId != null) {
            val isMatch = when {
                packageName.contains("youtube", ignoreCase = true) -> {
                    resourceId.contains("reel_player", ignoreCase = true) ||
                    resourceId.contains("reel_watch", ignoreCase = true) ||
                    resourceId.contains("shorts_player", ignoreCase = true) ||
                    resourceId.contains("shorts_video", ignoreCase = true) ||
                    resourceId.contains("reel_container", ignoreCase = true) ||
                    resourceId.contains("reel_recycler", ignoreCase = true)
                }
                packageName.contains("instagram", ignoreCase = true) -> {
                    resourceId.contains("clips_video", ignoreCase = true) ||
                    resourceId.contains("clips_viewer", ignoreCase = true) ||
                    resourceId.contains("reels_viewer", ignoreCase = true) ||
                    resourceId.contains("clips_pager", ignoreCase = true) ||
                    resourceId.contains("clips_viewer_pager", ignoreCase = true)
                }
                packageName.contains("snapchat", ignoreCase = true) -> {
                    resourceId.contains("spotlight_player", ignoreCase = true) ||
                    resourceId.contains("spotlight_card", ignoreCase = true) ||
                    resourceId.contains("spotlight_viewer", ignoreCase = true)
                }
                else -> false
            }

            if (isMatch) {
                // Ignore navigation tabs, launch shortcuts, and entry buttons to prevent main feed blocks
                if (!resourceId.contains("tab", ignoreCase = true) &&
                    !resourceId.contains("button", ignoreCase = true) &&
                    !resourceId.contains("icon", ignoreCase = true) &&
                    !resourceId.contains("shortcut", ignoreCase = true)) {
                    return true
                }
            }
        }

        // Check child components
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (scanNodeRecursive(child, packageName)) {
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
