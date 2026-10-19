package sh.kizer.golive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import sh.kizer.golive.ui.MainActivity

/** The foreground service's notification, showing the latest reading. */
object Notifications {
    const val ID = 1
    private const val CHANNEL = "collecting"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Collecting readings", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Go Live listens for the Go in the background."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, repo: Repository): android.app.Notification {
        val f = repo.units.value
        val r = repo.latest.value
        val (title, text) = when (repo.status.value) {
            ScanStatus.BluetoothOff -> "Bluetooth is off" to "Readings resume when Bluetooth is back on."
            ScanStatus.NoPermission -> "Bluetooth permission needed" to "Open Go Live to allow scanning."
            is ScanStatus.Failed -> "Scanning stopped" to "Retrying shortly."
            else -> if (r == null) {
                "Listening for the Go" to "Waiting for its first broadcast."
            } else {
                summary(r, f, listOf(Metric.PM25, Metric.PM10, Metric.CO2)) to
                    summary(r, f, listOf(Metric.TEMPERATURE, Metric.HUMIDITY, Metric.PM1))
            }
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, ScanService::class.java).setAction(ScanService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(r != null)
            .setWhen(r?.time ?: System.currentTimeMillis())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(context: Context, repo: Repository) {
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context, repo))
    }

    private fun summary(r: Reading, units: Units, metrics: List<Metric>) =
        metrics.mapNotNull { m ->
            r.value(m)?.let { v ->
                val label = when (m) {
                    Metric.PM1, Metric.PM25, Metric.PM10, Metric.CO2 -> m.label + " "
                    else -> ""
                }
                label + m.formatWithUnit(v, units)
            }
        }.joinToString(" · ")
}
