package io.mo.glassmic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.MainActivity
import io.mo.glassmic.R
import io.mo.glassmic.audio.LivePlaybackCaptureSource
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the MediaProjection session. Android 14+ requires the projection foreground
 * service to be promoted before getMediaProjection() is called.
 */
@AndroidEntryPoint
class LivePlaybackCaptureService : LifecycleService() {
    @Inject lateinit var playback: PlaybackController
    private var source: LivePlaybackCaptureSource? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        createNotificationChannel()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtraCompat(EXTRA_RESULT_DATA)
        if (resultData == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            GlassLog.b("LiveCapture") { "Could not start capture foreground service: ${t.message}" }
            stopSelf()
            return START_NOT_STICKY
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = manager.getMediaProjection(resultCode, resultData)
                val capture = LivePlaybackCaptureSource(projection)
                if (!capture.start()) {
                    capture.release()
                    GlassLog.b("LiveCapture") { "AudioRecord could not start" }
                    stopSelf()
                    return@launch
                }
                source = capture
                playback.setLivePlaybackCapture(capture)
                GlassLog.b("LiveCapture") { "Live playback capture started" }
            } catch (t: Throwable) {
                GlassLog.b("LiveCapture") { "Capture startup failed: ${t.message}" }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        source = null // SharedPcmPublisher releases the previous source when setRealMic runs.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { playback.setRealMic() } }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Live audio capture", NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false); description = "Shows when live app audio is being captured" })
            }
        }
    }

    private fun buildNotification(): Notification {
        val openApp = android.app.PendingIntent.getActivity(
            this, 908, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GlassMic live audio")
            .setContentText("Capturing supported app playback")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java) else getParcelableExtra(key)

    companion object {
        private const val CHANNEL_ID = "glassmic_live_capture"
        private const val NOTIFICATION_ID = 908
        const val EXTRA_RESULT_CODE = "live_projection_result_code"
        const val EXTRA_RESULT_DATA = "live_projection_result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, LivePlaybackCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, LivePlaybackCaptureService::class.java))
        }
    }
}
