package app.painmeter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.painmeter.core.Fix
import java.io.File

/**
 * Keeps the ride recording while the screen is off or another app is in front: a foreground
 * service with a location type, a 1 Hz GPS subscription and a 1 s ticker.
 */
class RideService : Service() {

    private val feed by lazy { LocationFeed(this) { RideController.onLocation(it) } }
    private val steps by lazy { StepFeed(this) { RideController.onStep(it) } }
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            RideController.tick(System.currentTimeMillis())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RideController.init(this)
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                RideController.startNewRide(System.currentTimeMillis(), intent.getBooleanExtra(EXTRA_CALIBRATION, false))
                startUpdates()
            }
            ACTION_RESUME -> {
                val dir = intent.getStringExtra(EXTRA_DIR)?.let(::File)
                goForeground()
                if (dir != null) RideController.resumeUnfinished(dir, System.currentTimeMillis())
                startUpdates()
            }
            ACTION_STOP -> {
                stopUpdates()
                RideController.stop(System.currentTimeMillis()) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            null -> {
                // Restarted by the system after the process was killed mid-ride: carry on.
                val dir = RideStore(this).unfinished().firstOrNull()
                if (dir == null) { stopSelf(); return START_NOT_STICKY }
                goForeground()
                RideController.resumeUnfinished(dir, System.currentTimeMillis())
                startUpdates()
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ride recording", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if ((RideController.currentSport ?: Settings.current.value.sport) == app.painmeter.core.run.Sport.RUN) "Recording run" else "Recording ride")
            .setContentText("Tap to open Painmeter")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    private fun startUpdates() {
        if (running) return
        running = true
        HrMonitor.acquire("ride")
        Compass.start(this)
        feed.start(Settings.current.value.rawGps)
        if (RideController.currentSport == app.painmeter.core.run.Sport.RUN) steps.start()
        handler.post(ticker)
    }

    private fun stopUpdates() {
        if (!running) return
        running = false
        HrMonitor.release("ride")
        Compass.stop()
        feed.stop()
        steps.stop()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        stopUpdates()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ride"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "app.painmeter.START"
        const val ACTION_RESUME = "app.painmeter.RESUME"
        const val ACTION_STOP = "app.painmeter.STOP"
        const val EXTRA_DIR = "dir"
        const val EXTRA_CALIBRATION = "calibration"

        fun start(context: Context, calibration: Boolean = false) = send(
            context, Intent(context, RideService::class.java).setAction(ACTION_START).putExtra(EXTRA_CALIBRATION, calibration)
        )

        fun resume(context: Context, dir: File) = send(
            context, Intent(context, RideService::class.java).setAction(ACTION_RESUME).putExtra(EXTRA_DIR, dir.path)
        )

        fun stop(context: Context) = context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))

        private fun send(context: Context, intent: Intent) = ContextCompat.startForegroundService(context, intent)
    }
}

fun Location.toFix() = Fix(
    timeMs = time,
    lat = latitude,
    lon = longitude,
    altitudeM = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
)
