package com.oppmesh.dtn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.oppmesh.dtn.OppMeshApplication
import com.oppmesh.dtn.data.nearby.NearbyMeshManager
import com.oppmesh.dtn.data.nearby.RadioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MeshSyncForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "oppmesh_mesh_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.oppmesh.dtn.ACTION_START"
        const val ACTION_STOP = "com.oppmesh.dtn.ACTION_STOP"
        const val EXTRA_NODE_ID = "extra_node_id"

        fun startService(context: Context, nodeId: String) {
            val intent = Intent(context, MeshSyncForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NODE_ID, nodeId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MeshSyncForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var meshManager: NearbyMeshManager

    inner class LocalBinder : Binder() {
        fun getService(): MeshSyncForegroundService = this@MeshSyncForegroundService
        fun getMeshManager(): NearbyMeshManager = meshManager
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val app = application as OppMeshApplication
        meshManager = app.meshManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OppMesh::RadioWakeLock").apply {
            setReferenceCounted(false)
        }

        createNotificationChannel()
        startForegroundWithNotification("Mesh Radio Initializing...", 0, 0)
        observeLiveStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val nodeId = intent?.getStringExtra(EXTRA_NODE_ID) ?: meshManager.localNodeId

        when (action) {
            ACTION_START -> {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
                meshManager.startMesh(nodeId)
            }
            ACTION_STOP -> {
                meshManager.stopMesh()
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun observeLiveStatus() {
        val app = application as OppMeshApplication
        scope.launch {
            combine(
                meshManager.radioState,
                meshManager.activePeersCount,
                app.repository.packetsFlow
            ) { state, peerCount, packets ->
                Triple(state, peerCount, packets.size)
            }.collect { (state, peerCount, packetCount) ->
                updateNotification(state, peerCount, packetCount)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OppMesh DTN Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Nearby Connections peer discovery active in foreground"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String, peerCount: Int, packetCount: Int): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OppMesh DTN Active: ${meshManager.localNodeId}")
            .setContentText("Status: $statusText | Peers: $peerCount | Buffer: $packetCount")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithNotification(statusText: String, peerCount: Int, packetCount: Int) {
        val notification = buildNotification(statusText, peerCount, packetCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: RadioState, peerCount: Int, packetCount: Int) {
        val statusText = when (state) {
            RadioState.IDLE -> "Idle"
            RadioState.SCANNING -> "Discovering Peers"
            RadioState.CONNECTING -> "Connecting..."
            RadioState.SYNCING -> "Exchanging Bundles"
        }
        val notification = buildNotification(statusText, peerCount, packetCount)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        meshManager.stopMesh()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        scope.cancel()
    }
}
