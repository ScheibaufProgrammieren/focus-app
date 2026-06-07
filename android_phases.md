# FocusGuard: Android Application Phased Roadmap

We have written the complete core codebase for the native Android application under `d:/focus/android/`. Below is the detailed roadmap to build, install, authorize, test, and optimize the application on your device.

---

## Phase 1: Local compilation & Build setup
**Goal:** Import, resolve dependencies, and compile the APK.

1. **Gradle Synchronization:**
   - Open Android Studio.
   - Choose **File -> Open** and select `d:\focus\android`.
   - Let Gradle sync the build configuration (`settings.gradle.kts`, `build.gradle.kts`, and `app/build.gradle.kts`).
2. **Build Debug APK:**
   - In Android Studio, go to **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
   - Alternatively, open a terminal in `d:\focus\android\` and run:
     ```cmd
     gradlew assembleDebug
     ```
   - Verify that the output APK is generated at:
     `d:\focus\android\app\build\outputs\apk\debug\app-debug.apk`

---

## Phase 2: Device Sideloading & Installation
**Goal:** Get the compiled APK installed on your target Android device.

1. **Developer Settings Activation:**
   - On your phone, go to **Settings -> About Phone**.
   - Scroll to the bottom and tap **Build Number** 7 times until you see the toast: *"You are now a developer!"*
   - Return to Settings, find **Developer Options**, and enable **USB Debugging**.
2. **Installation:**
   - **Method A (Android Studio)**: Connect your phone via USB. Select your device from the toolbar dropdown and click the green **Run (Play)** button.
   - **Method B (ADB Tool)**: Open your terminal and deploy directly via:
     ```cmd
     adb install d:\focus\android\app\build\outputs\apk\debug\app-debug.apk
     ```

---

## Phase 3: Android Permission Onboarding (Critical)
**Goal:** Authorize FocusGuard to draw overlays and scan UI layout hierarchies.

1. **Overlay permission (Draw Over Other Apps):**
   - Launch **FocusGuard** on your phone.
   - Tap **Enable Overlay**.
   - You will be redirected to the system setting *"Display over other apps"*.
   - Locate **FocusGuard** in the list, tap it, and enable **Allow display over other apps**.
2. **Accessibility Service Activation (Including Android 13+ Restricted Settings Bypass):**
   - In the FocusGuard app, tap **Enable Accessibility**.
   - If a dialog says *"Restricted Setting - For your security, this setting is currently unavailable"*, execute these steps:
     1. Open your phone's system **Settings**.
     2. Navigate to **Apps** (or *Apps & Notifications*).
     3. Select **FocusGuard** from the list of installed apps.
     4. Tap the **three vertical dots** in the top-right corner.
     5. Select **Allow restricted settings** and authenticate with your PIN/fingerprint.
     6. Return to the FocusGuard app, tap **Enable Accessibility** again.
     7. Go to *Downloaded Apps* / *Installed Services*, tap **FocusGuard**, and turn the toggle **ON**.

---

## Phase 4: Target Feeds Live Verification
**Goal:** Verify layout scanner intercepts YouTube Shorts, Instagram Reels, and Snapchat Spotlight.

1. **Test YouTube Shorts:**
   - Open the YouTube app.
   - Tap the *Shorts* tab or open a short video.
   - **Expected Result:** Within milliseconds, the screen is covered by FocusGuard's fullscreen glassmorphic overlay displaying a motivational quote.
   - Tap **Go Back to Work** -> verify the screen closes and you are returned to your launcher homepage.
2. **Test Instagram Reels:**
   - Open Instagram and tap the *Reels* button on the bottom bar.
   - **Expected Result:** The overlay triggers immediately.
   - Tap **Go Back to Work** -> verify the overlay closes and triggers simulated back gestures to exit the Reel viewport.
3. **Test Snapchat Spotlight:**
   - Open Snapchat and swipe to the *Spotlight* section.
   - **Expected Result:** FocusGuard locks the feed instantly.

---

## Phase 5: Battery & Background Performance Optimization
**Goal:** Guarantee FocusGuard runs reliably in the background without getting killed by OS memory managers.

1. **Disable Battery Optimization:**
   - Go to your phone's **Settings -> Apps -> FocusGuard -> Battery**.
   - Set battery usage to **Unrestricted** (prevents Android's Doze mode from shutting down the Accessibility Service).
2. **Accessibility Keep-Alive:**
   - Android's system occasionally suspends idle services. Having the Accessibility Service active ensures persistent monitoring. If the service ever stops, simply open the FocusGuard app to check status indicators.
