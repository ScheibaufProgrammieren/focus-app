package com.focusguard.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.CompoundButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.app.databinding.ActivityMainBinding
import com.focusguard.service.FocusAccessibilityService
import android.content.SharedPreferences
import android.os.Build

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        setupClickListeners()
        setupDashboardToggles()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionAndDashboardStatus()

        // Auto-wake the accessibility service if enabled in settings
        if (FocusAccessibilityService.isEnabled(this)) {
            val intent = Intent(this, FocusAccessibilityService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: java.lang.Exception) {
                android.util.Log.e("FocusGuard", "Failed to auto-wake service in onResume: ${e.message}")
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnAccessibility.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        binding.btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        binding.btnResetStats.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Statistics")
                .setMessage("Are you sure you want to clear your blocked loop statistics?")
                .setPositiveButton("Reset") { _, _ ->
                    val prefs = getSafePrefs()
                    prefs.edit().putInt("blocked_count", 0).apply()
                    binding.tvBlockedCount.text = "0"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnDisableService.setOnClickListener {
            val intent = Intent(this, FocusAccessibilityService::class.java).apply {
                action = FocusAccessibilityService.ACTION_DISABLE_SERVICE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("FocusGuard", "Failed to trigger service disable: ${e.message}")
            }
        }
    }

    private fun setupDashboardToggles() {
        val prefs = getSafePrefs()

        // Bind YouTube toggle
        binding.switchYoutube.isChecked = prefs.getBoolean("youtube_enabled", true)
        binding.switchYoutube.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("youtube_enabled", isChecked).apply()
        }

        // Bind Instagram toggle
        binding.switchInstagram.isChecked = prefs.getBoolean("instagram_enabled", true)
        binding.switchInstagram.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("instagram_enabled", isChecked).apply()
        }

        // Bind Snapchat toggle
        binding.switchSnapchat.isChecked = prefs.getBoolean("snapchat_enabled", true)
        binding.switchSnapchat.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("snapchat_enabled", isChecked).apply()
        }

        // Bind Browser toggle
        binding.switchBrowser.isChecked = prefs.getBoolean("browser_enabled", true)
        binding.switchBrowser.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("browser_enabled", isChecked).apply()
        }
    }

    private fun updatePermissionAndDashboardStatus() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasOverlay = Settings.canDrawOverlays(this)

        if (hasAccessibility && hasOverlay) {
            // Both permissions active -> Hide Onboarding, Show Dashboard
            binding.layoutPermissions.visibility = View.GONE
            binding.layoutDashboard.visibility = View.VISIBLE

            binding.systemStatusBadge.text = getString(R.string.status_ready)
            binding.systemStatusBadge.setTextColor(getColor(R.color.toggle_on))
            binding.tvFooterStatus.text = "FocusGuard is listening. Safe focused state is guaranteed."
            binding.tvFooterStatus.setTextColor(getColor(R.color.text_secondary))

            // Load and update statistics counter
            val prefs = getSafePrefs()
            val count = prefs.getInt("blocked_count", 0)
            binding.tvBlockedCount.text = count.toString()
        } else {
            // Permissions missing -> Show Onboarding, Hide Dashboard
            binding.layoutPermissions.visibility = View.VISIBLE
            binding.layoutDashboard.visibility = View.GONE

            binding.systemStatusBadge.text = getString(R.string.status_not_ready)
            binding.systemStatusBadge.setTextColor(getColor(R.color.accent_color))
            binding.tvFooterStatus.text = "Setup is incomplete. Both permissions are required to operate."
            binding.tvFooterStatus.setTextColor(getColor(R.color.accent_color))

            // Update individual onboarding buttons
            if (hasAccessibility) {
                binding.btnAccessibility.text = getString(R.string.perm_accessibility_status_active)
                binding.btnAccessibility.isEnabled = false
                binding.btnAccessibility.backgroundTintList = getColorStateList(R.color.toggle_on)
            } else {
                binding.btnAccessibility.text = getString(R.string.perm_accessibility_btn)
                binding.btnAccessibility.isEnabled = true
                binding.btnAccessibility.backgroundTintList = getColorStateList(R.color.white)
            }

            if (hasOverlay) {
                binding.btnOverlay.text = "Active"
                binding.btnOverlay.isEnabled = false
                binding.btnOverlay.backgroundTintList = getColorStateList(R.color.toggle_on)
            } else {
                binding.btnOverlay.text = getString(R.string.perm_overlay_btn)
                binding.btnOverlay.isEnabled = true
                binding.btnOverlay.backgroundTintList = getColorStateList(R.color.white)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return FocusAccessibilityService.isEnabled(this)
    }

    private fun getSafePrefs(): SharedPreferences {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val deviceContext = createDeviceProtectedStorageContext()
            deviceContext.moveSharedPreferencesFrom(this, "FocusGuardPrefs")
            return deviceContext.getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        }
        return getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    102
                )
            }
        }
    }
}
