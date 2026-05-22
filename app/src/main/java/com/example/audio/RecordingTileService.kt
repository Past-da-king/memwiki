package com.example.audio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.MainActivity

/**
 * Quick Settings tile. Tap = toggle recording on/off.
 *
 * Caveats:
 *   - Android does not let the app insert its tile into Quick Settings programmatically. The user
 *     must drag it in via Edit Tiles the first time.
 *   - If RECORD_AUDIO has never been granted, we can't show a permission prompt from a tile —
 *     so we launch the app and surface a Toast saying to grant the mic permission.
 */
class RecordingTileService : TileService() {

    companion object {
        fun requestUpdate(context: Context) {
            // Static helper so the recording service can ask Android to redraw the tile state
            // (active vs inactive label).
            try {
                requestListeningState(context, ComponentName(context, RecordingTileService::class.java))
            } catch (_: Exception) { }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            // Push the user into the app to grant the runtime permission.
            Toast.makeText(this, "Open MemWiki to grant microphone permission first.", Toast.LENGTH_LONG).show()
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                ))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(intent)
            }
            return
        }

        // Route through the proxy activity. Android 14+ blocks starting a mic-typed foreground
        // service directly from a background TileService click when the app process is dead.
        val starting = !RecordingService.isRecording.value
        val proxyIntent = Intent(this, RecordingProxyActivity::class.java).apply {
            putExtra(
                RecordingProxyActivity.EXTRA_TARGET_ACTION,
                if (starting) RecordingService.ACTION_START else RecordingService.ACTION_STOP
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(
                this, 2, proxyIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            ))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(proxyIntent)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val recording = RecordingService.isRecording.value
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (recording) "MemWiki · Stop" else "MemWiki Record"
        // Subtitle requires API 29+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (recording) "Tap to stop & ingest" else "Tap to record"
        }
        tile.updateTile()
    }
}
