# FocusGuard: Implementation Phases

This document outlines the step-by-step phases required to build FocusGuard (Browser Extension + Android Blocker App).

---

## Phase 1: Browser Extension - Core Redirection & Hiding
**Goal:** Build the foundational blocking engine for desktop and mobile browsers (Kiwi/Firefox on Android).

1. **Setup Workspace:** Create `manifest.json` using Manifest V3 standards.
2. **Network/URL Interception:**
   - Write content script logic to watch URL transitions.
   - Detect matching patterns:
     - YouTube Shorts: `*://*.youtube.com/shorts/*`
     - Instagram Reels: `*://*.instagram.com/reels/*` and `*://*.instagram.com/p/*/reels/*`
3. **Element Hiding (CSS Injection):**
   - Inject static and dynamic CSS to hide:
     - YouTube sidebar Shorts link, home page Shorts shelf, search result Shorts.
     - Instagram Reels tab in the bottom bar/sidebar.
4. **Late-Load Handling:** Use `MutationObserver` to ensure elements that load dynamically (SPA navigation) are instantly stripped from the DOM.

---

## Phase 2: Browser Extension - Focus Overlay & "Go Back" Action
**Goal:** Replace the blocked pages with a premium motivational interface.

1. **Injectable Overlay Construction:**
   - Design an overlay HTML element (`div` based) that attaches to the document body when a blocked path is hit.
   - Design a premium glassmorphism styling utilizing HSL colors, dark modes, and soft typography (Outfit/Inter).
2. **Motivational Engine:**
   - Implement a list of high-impact motivational quotes to urge the user back to focus (e.g., *"Is this cheap dopamine loop worth your dreams? Get back to work."*).
   - Randomize the quotes on overlay insertion.
3. **"Go Back" Logic:**
   - Bind a click handler to the prominent "Go Back" button.
   - For YouTube: Navigate browser to `youtube.com` (clearing the short path).
   - For Instagram: Navigate browser to `instagram.com` (redirecting to home).

---

## Phase 3: Android Blocker App - Onboarding & Overlay Service
**Goal:** Establish the Android app codebase and request necessary system overlay permissions.

1. **Initialize Android Project:** Setup Kotlin-based Android Gradle project.
2. **Permission Gatekeeping UI (MainActivity):**
   - Build a sleek, minimalist setup screen.
   - Guide the user to enable:
     - **Overlay Permission** (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`).
     - **Accessibility Service** (`Settings.ACTION_ACCESSIBILITY_SETTINGS`).
   - Implement status checks to display when permissions are successfully active.
3. **BlockOverlayActivity Implementation:**
   - Create a clean full-screen Activity styled with high contrast and focus prompts.
   - Implement the "Go Back" button which calls `finish()` and triggers simulated keypresses/navigation actions.

---

## Phase 4: Android Blocker App - Accessibility Service Core
**Goal:** Implement the logic to inspect UI structures of native apps in real-time.

1. **Accessibility Configuration:** Declare monitored package names:
   - `com.google.android.youtube` (YouTube App)
   - `com.instagram.android` (Instagram App)
   - `com.snapchat.android` (Snapchat App)
2. **Event Listening:**
   - Listen to `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` (app opened/switched).
   - Listen to `AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED` (UI scrolled/updated).
3. **UI Node Target Detection:**
   - Scan node hierarchy for Reels/Shorts tags.
   - **YouTube Targets:** Check for window title changes indicating `/shorts` or view containers with resource IDs representing the Shorts player.
   - **Instagram Targets:** Detect active view container IDs for Reels, or bottom navigation menu items pointing to Reels.
   - **Snapchat Targets:** Check node classes or content descriptions associated with the "Spotlight" tab.

---

## Phase 5: Android Blocker App - Block Execution & Home Redirection
**Goal:** Bind the layout detection to the blocker overlay.

1. **Trigger Action:**
   - Once a Shorts/Reels/Spotlight node matches, launch the `BlockOverlayActivity` as a new task.
   - Temporarily disable node tracking for 1 second to prevent double-triggering.
2. **Exit Action ("Go Back"):**
   - When the user taps "Go Back" on the overlay:
     - Option A: Execute `performGlobalAction(GLOBAL_ACTION_BACK)` twice to exit the Shorts viewport.
     - Option B: Launch a intent to open the target app's root activity, or minimize/go to home screen (`Intent.ACTION_MAIN`, `Intent.CATEGORY_HOME`).
     - We will implement Option A with a fallback to Option B to ensure the user is safely extracted from the addictive feed.

---

## Phase 6: Testing & Verification
**Goal:** End-to-end verification of browser extension and Android app under load.

1. **Browser Blocker Verification:** Run manual navigation tests on desktop Chrome and Android Kiwi browser. Assert zero layout leaks.
2. **Android Blocker Verification:** Open Instagram, YouTube, and Snapchat apps. Navigate to Reels/Shorts/Spotlight. Verify the blocker displays immediately. Click "Go Back" and verify returning to a safe screen.
3. **Optimization Check:** Profile Android background battery usage and memory footprint of the Accessibility Service to ensure lightweight performance.
