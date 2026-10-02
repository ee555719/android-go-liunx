package com.ee555719.golinux.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ee555719.golinux.App
import com.ee555719.golinux.R
import com.ee555719.golinux.data.VmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service keeping the QEMU process (and the app process hosting
 * it) alive while the virtual machine runs in the background.
 */
class VmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        acquireWakeLock()
        watchJob?.cancel()
        watchJob = scope.launch {
            val stateFlow = (application as App).qemuManager.state
            stateFlow.collect { state ->
                if (state == VmState.STOPPED) {
                    releaseWakeLock()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Linux 虚拟机运行中")
            .setContentText("QEMU 正在后台运行，关闭通知将结束虚拟机")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(0xFF7CFF6B.toInt())
            .build()
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (t: Throwable) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "虚拟机状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Linux 虚拟机前台服务通知" }
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoLinux:vm").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "vm_service"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, VmService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VmService::class.java))
        }
    }
}
