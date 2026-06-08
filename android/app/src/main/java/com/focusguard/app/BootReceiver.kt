package com.focusguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("FocusGuard", "BootReceiver: received action $action")
        // Just receiving the broadcast wakes up the application process, 
        // allowing the system to bind to the Accessibility Service.
    }
}
