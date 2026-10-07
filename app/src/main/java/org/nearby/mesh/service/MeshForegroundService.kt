package org.nearby.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.nearby.mesh.MainActivity
import org.nearby.mesh.ble.BleMeshEngine
import org.nearby.mesh.crypto.IdentityManager

/**
 * Foreground service ensuring continuous BLE presence advertising and scanning
 * when the app is backgrounded (PRD §5.6, Architecture §3.7).
 */
class MeshForegroundService : Service() {

    private var bleEngine: BleMeshEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val identityManager = IdentityManager(this)
        val identity = identityManager.getOrCreateIdentity()
        bleEngine = BleMeshEngine(this, identity)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MESHING) {
            stopMeshService()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        bleEngine?.start()

        return START_STICKY
    }

    override fun onDestroy() {
        bleEngine?.stop()
        super.onDestroy()
    }

    private fun stopMeshService() {
        bleEngine?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nearby Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps emergency peer discovery active while in background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingLaunchIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MeshForegroundService::class.java).apply {
            action = ACTION_STOP_MESHING
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nearby Meshing Active")
            .setContentText("Emergency discovery active. Relaying for nearby peers.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingLaunchIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Pause Meshing",
                pendingStopIntent
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "nearby_mesh_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_MESHING = "org.nearby.mesh.ACTION_STOP_MESHING"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_STOP_MESHING
            }
            context.startService(intent)
        }
    }
}
