package com.example.audio

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Headless one-shot activity that starts (or stops) the RecordingService.
 *
 * Why this exists: on Android 14+ a foreground service of type "microphone" cannot be started
 * from a background TileService click while the app process is dead. Launching the service from
 * an Activity context (even a transparent, no-UI one that finishes immediately) gives the service
 * legitimate foreground-launch privileges, which lets the mic foreground service start.
 *
 * The activity is themed transparent, no-history, excluded from recents — the user never sees it.
 */
class RecordingProxyActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_ACTION = "memwiki.recording.target_action"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetAction = intent?.getStringExtra(EXTRA_TARGET_ACTION)
            ?: RecordingService.ACTION_START
        val svc = Intent(this, RecordingService::class.java).apply { action = targetAction }
        if (targetAction == RecordingService.ACTION_START &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        finish()
    }
}
