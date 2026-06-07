package com.focusguard.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.app.databinding.ActivityMainBinding
import com.focusguard.service.FocusAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
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
    }

    private fun updatePermissionStatus() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasOverlay = Settings.canDrawOverlays(this)

        // Update Accessibility Button UI
        if (hasAccessibility) {
            binding.btnAccessibility.text = getString(R.string.perm_accessibility_status_active)
            binding.btnAccessibility.isEnabled = false
            binding.btnAccessibility.backgroundTintList = getColorStateList(R.color.toggle_on)
        } else {
            binding.btnAccessibility.text = getString(R.string.perm_accessibility_btn)
            binding.btnAccessibility.isEnabled = true
            binding.btnAccessibility.backgroundTintList = getColorStateList(R.color.white)
        }

        // Update Overlay Button UI
        if (hasOverlay) {
            binding.btnOverlay.text = "Active"
            binding.btnOverlay.isEnabled = false
            binding.btnOverlay.backgroundTintList = getColorStateList(R.color.toggle_on)
        } else {
            binding.btnOverlay.text = getString(R.string.perm_overlay_btn)
            binding.btnOverlay.isEnabled = true
            binding.btnOverlay.backgroundTintList = getColorStateList(R.color.white)
        }

        // Update Status Banner
        if (hasAccessibility && hasOverlay) {
            binding.systemStatusBadge.text = getString(R.string.status_ready)
            binding.systemStatusBadge.setTextColor(getColor(R.color.toggle_on))
            binding.tvFooterStatus.text = "FocusGuard is listening. Safe focused state is guaranteed."
            binding.tvFooterStatus.setTextColor(getColor(R.color.text_secondary))
        } else {
            binding.systemStatusBadge.text = getString(R.string.status_not_ready)
            binding.systemStatusBadge.setTextColor(getColor(R.color.accent_color))
            binding.tvFooterStatus.text = "Setup is incomplete. Both permissions are required to operate."
            binding.tvFooterStatus.setTextColor(getColor(R.color.accent_color))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "${packageName}/${FocusAccessibilityService::class.java.canonicalName}"
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            if (componentNameString.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        
        // Secondary verification step check via active AccessibilityManager
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val runningServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (serviceInfo in runningServices) {
            val id = serviceInfo.id
            if (id.contains(packageName) && id.contains(FocusAccessibilityService::class.java.simpleName)) {
                return true
            }
        }

        return false
    }
}
