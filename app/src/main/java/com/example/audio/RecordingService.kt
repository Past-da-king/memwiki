package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.WikiDatabase
import com.example.data.WikiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that owns a single [MediaRecorder] instance. Started by the QS tile to begin
 * recording, stopped by either the tile or the "Stop" action in its persistent notification.
 *
 * On stop the audio is auto-ingested into the wiki via [WikiRepository.ingestNote].
 *
 * Gemini's inline audio support is roughly 15 minutes max (about 20MB inline). We hard-cap the
 * recording at that duration and auto-stop so the resulting file is always within range.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "memwiki_recording_channel"
        private const val NOTIFICATION_ID = 4711
        private const val MAX_DURATION_MS = 15 * 60 * 1000L // 15 minutes — Gemini inline cap

        const val ACTION_START = "com.memwiki.audio.START"
        const val ACTION_STOP = "com.memwiki.audio.STOP"

        // Process-wide flag so the tile knows whether we're currently recording.
        val isRecording = MutableStateFlow(false)
    }

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var startedAtElapsed: Long = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoStopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> {
                stopRecordingAndIngest()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (isRecording.value) return
        ensureNotificationChannel()
        try {
            val file = File(filesDir, "qs_audio_${System.currentTimeMillis()}.m4a")
            recordingFile = file
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            recorder = rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            startedAtElapsed = SystemClock.elapsedRealtime()
            isRecording.value = true
            startForeground(NOTIFICATION_ID, buildRecordingNotification())
            // Hard cap: auto-stop after 15 minutes so the audio fits inline in Gemini.
            autoStopJob = scope.launch {
                delay(MAX_DURATION_MS)
                Log.i(TAG, "Auto-stopping at 15-minute cap.")
                stopRecordingAndIngest()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRecording.value = false
            stopSelf()
        }
        // Refresh tile UI.
        RecordingTileService.requestUpdate(this)
    }

    private fun stopRecordingAndIngest() {
        if (!isRecording.value) {
            stopSelf()
            return
        }
        autoStopJob?.cancel()
        autoStopJob = null
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Recorder.stop() threw — likely zero-length recording", e)
        }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecording.value = false
        RecordingTileService.requestUpdate(this)

        val file = recordingFile
        recordingFile = null

        if (file == null || !file.exists() || file.length() == 0L) {
            stopForegroundCompat()
            stopSelf()
            return
        }

        // Switch notification to "Compiling…" while ingest runs in the background.
        updateNotification("Compiling to wiki", "Sending audio to MemWiki…", showStop = false)

        scope.launch {
            try {
                val prefs = applicationContext.getSharedPreferences("wiki_settings", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("gemini_api_key", "").orEmpty()
                val model = prefs.getString("gemini_model", "").orEmpty()
                if (apiKey.isBlank() || model.isBlank()) {
                    updateNotification(
                        "Recording saved",
                        "Open MemWiki and set your API key + model to compile it.",
                        showStop = false
                    )
                    delay(4000)
                    stopForegroundCompat()
                    stopSelf()
                    return@launch
                }
                val dao = WikiDatabase.getDatabase(applicationContext).wikiDao
                val repo = WikiRepository(applicationContext, dao)
                val result = repo.ingestNote(
                    apiKey = apiKey,
                    modelId = model,
                    content = "",
                    audioPath = file.absolutePath,
                    imagePaths = emptyList()
                )
                val msg = result.fold(
                    onSuccess = { plan -> "Updated ${plan.pagesToCreateOrUpdate.size} page(s)." },
                    onFailure = { it.localizedMessage ?: "Compile failed" }
                )
                updateNotification("MemWiki: voice memo", msg, showStop = false)
                delay(4500)
            } catch (e: Exception) {
                Log.e(TAG, "Background ingest failed", e)
                updateNotification("MemWiki: voice memo", e.localizedMessage ?: "Failed.", showStop = false)
                delay(4500)
            } finally {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecording.value = false
        RecordingTileService.requestUpdate(this)
        scope.cancel()
    }

    // --- notification plumbing ---

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live notifications while MemWiki is capturing audio."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildRecordingNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_launcher_mono)
            .setContentTitle("MemWiki is recording")
            .setContentText("Tap stop when done. Auto-ingests on stop.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAtElapsed))
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, body: String, showStop: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_launcher_mono)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(showStop)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (showStop) {
            val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
            val stopPi = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
        }
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION") stopForeground(false)
        }
    }
}
