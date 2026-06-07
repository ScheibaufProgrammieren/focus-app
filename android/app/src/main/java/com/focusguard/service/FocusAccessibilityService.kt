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
        
        // Only monitor target packages
        if (packageName != "com.google.android.youtube" &&
            packageName != "com.instagram.android" &&
            packageName != "com.snapchat.android") {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        
        val shouldBlock = when (packageName) {
            "com.google.android.youtube" -> detectYouTubeShorts(rootNode)
            "com.instagram.android" -> detectInstagramReels(rootNode)
            "com.snapchat.android" -> detectSnapchatSpotlight(rootNode)
            else -> false
        }

        if (shouldBlock) {
            triggerBlockerOverlay()
        }
    }

    private fun detectYouTubeShorts(node: AccessibilityNodeInfo): Boolean {
        // Method 1: Check resource IDs containing key shorts/reel tokens
        val matches = findNodesByKeyword(node, listOf(
            "com.google.android.youtube:id/reel_player",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/shorts_player"
        ))
        if (matches) return true

        // Method 2: Fallback scan of UI layout hierarchy looking for active player context
        return scanNodeHierarchyForTerms(node, "reel")
    }

    private fun detectInstagramReels(node: AccessibilityNodeInfo): Boolean {
        // Method 1: Check resource IDs containing clips/reels identifiers
        // Instagram calls Reels "clips" internally
        val matches = findNodesByKeyword(node, listOf(
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_viewer_container",
            "com.instagram.android:id/reels_viewer_container"
        ))
        if (matches) return true

        // Method 2: Fallback scan for text-based active viewports
        return scanNodeHierarchyForTerms(node, "clips_video")
    }

    private fun detectSnapchatSpotlight(node: AccessibilityNodeInfo): Boolean {
        // Snapchat Spotlight viewer detection
        val matches = findNodesByKeyword(node, listOf(
            "com.snapchat.android:id/spotlight",
            "com.snapchat.android:id/spotlight_player"
        ))
        if (matches) return true

        return scanNodeHierarchyForTerms(node, "spotlight")
    }

    // Helper to scan tree recursively matching exact resource ID tags
    private fun findNodesByKeyword(node: AccessibilityNodeInfo?, ids: List<String>): Boolean {
        if (node == null) return false

        val viewId = node.viewIdResourceName
        if (viewId != null) {
            for (id in ids) {
                if (viewId.equals(id, ignoreCase = true) || viewId.contains(id)) {
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (findNodesByKeyword(child, ids)) {
                return true
            }
        }
        return false
    }

    // Recursive backup scan to identify text or resource descriptions containing keyword identifiers
    private fun scanNodeHierarchyForTerms(node: AccessibilityNodeInfo?, term: String): Boolean {
        if (node == null) return false

        val resourceId = node.viewIdResourceName
        if (resourceId != null && resourceId.contains(term, ignoreCase = true)) {
            // Additional check: filter out navigation items like tab icons to prevent false-positive home screen blocking
            if (!resourceId.contains("tab", ignoreCase = true) && 
                !resourceId.contains("button", ignoreCase = true) &&
                !resourceId.contains("icon", ignoreCase = true)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (scanNodeHierarchyForTerms(child, term)) {
                return true
            }
        }
        return false
    }

    private fun triggerBlockerOverlay() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockTime < BLOCK_COOLDOWN_MS) {
            return // Skip if cooldown hasn't expired to avoid screen launch flooding
        }
        lastBlockTime = currentTime

        // Open full screen focus blocker overlay activity
        val intent = Intent(this, BlockOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun executeBackNavigation() {
        // Execute BACK actions sequentially to exit the active viewport
        performGlobalAction(GLOBAL_ACTION_BACK)
        // Delay slightly and execute a second back action to guarantee exit out of full screen layout
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
