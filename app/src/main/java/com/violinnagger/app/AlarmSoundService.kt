package com.violinnagger.app

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Plays the alarm sound on the alarm volume (like a clock app) so it rings even when the phone is on silent.
 * Stops when the reminder is answered, when the screen is touched, or after two minutes.
 */
class AlarmSoundService : Service() {
    companion object {
        @Volatile
        var instance: AlarmSoundService? = null
        private const val RING_MS = 120_000L
    }

    private var player: MediaPlayer? = null
    private val h = Handler(Looper.getMainLooper())
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopped = false
        val test = intent?.getBooleanExtra("test", false) ?: false
        try {
            startForeground(Nag.ID, Nag.build(this, test, Nag.CHANNEL_LOUD))
        } catch (e: Exception) {
            // Could not become a foreground service: show a normal notification so the reminder is not lost.
            try {
                getSystemService(NotificationManager::class.java).notify(Nag.ID, Nag.build(this, test, Nag.CHANNEL))
            } catch (x: Exception) {
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startSound()
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ stopAll(false) }, RING_MS)
        return START_NOT_STICKY
    }

    private fun startSound() {
        stopSound()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, _, _ ->
                stopSound()
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            player = null
        }
    }

    private fun stopSound() {
        val p = player ?: return
        player = null
        try {
            p.stop()
        } catch (e: Exception) {
        }
        try {
            p.release()
        } catch (e: Exception) {
        }
    }

    /** Stops the sound and the service. With remove = true the reminder is cleared from the shade too. */
    fun stopAll(remove: Boolean) {
        if (stopped) return
        stopped = true
        h.removeCallbacksAndMessages(null)
        stopSound()
        try {
            stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        stopSound()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
