package com.focusguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.focusguard.service.FocusAccessibilityService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("FocusGuard", "BootReceiver: received action $action")
        
        if (FocusAccessibilityService.isEnabled(context)) {
            Log.d("FocusGuard", "BootReceiver: FocusAccessibilityService is enabled, waking it up")
            val serviceIntent = Intent(context, FocusAccessibilityService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("FocusGuard", "BootReceiver: failed to start service: ${e.message}")
            }
        }
    }
}
