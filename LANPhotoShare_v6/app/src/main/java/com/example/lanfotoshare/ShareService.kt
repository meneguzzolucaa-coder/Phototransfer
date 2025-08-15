package com.example.lanfotoshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ShareService : Service() {

    private var server: PhotoServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdReg: NsdManager.RegistrationListener? = null
    private var port: Int = 8080
    private var token: String? = null
    private var items: ArrayList<SelectedItem> = arrayListOf()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                port = intent.getIntExtra(EXTRA_PORT, 8080)
                token = intent.getStringExtra(EXTRA_TOKEN)
                @Suppress("UNCHECKED_CAST")
                items = intent.getParcelableArrayListExtra(EXTRA_ITEMS) ?: arrayListOf()
                startForegroundWithNotification()
                startHttp()
                registerNsd()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHttp()
        unregisterNsd()
    }

    private fun startHttp() {
        if (server != null) return
        server = PhotoServer(this, port, items, token).also { it.start() }
    }

    private fun stopHttp() {
        server?.stop()
        server = null
    }

    private fun registerNsd() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = "LAN Photo Share"
            serviceType = "_http._tcp."
            port = this@ShareService.port
        }
        nsdReg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(p0: NsdServiceInfo?) { }
            override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) { }
            override fun onServiceUnregistered(p0: NsdServiceInfo?) { }
            override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) { }
        }
        nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdReg)
    }

    private fun unregisterNsd() {
        try { nsdManager?.unregisterService(nsdReg) } catch (_: Exception) {}
        nsdReg = null
    }

    private fun startForegroundWithNotification() {
        val channelId = "share_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(channelId, "LAN Photo Share", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val stopIntent = Intent(this, ShareService::class.java).apply { action = ACTION_STOP }
        val pStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val openIntent = Intent(this, MainActivity::class.java)
        val pOpen = PendingIntent.getActivity(this, 2, openIntent, PendingIntent.FLAG_IMMUTABLE)
        val text = "Attivo" + if (token != null) " (PIN)" else ""

        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("LAN Photo Share")
            .setContentText(text)
            .setContentIntent(pOpen)
            .addAction(0, "Stop", pStop)
            .setOngoing(true)
            .build()

        startForeground(42, notif)
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"

        fun start(ctx: Context, port: Int, items: ArrayList<SelectedItem>, token: String?) {
            val i = Intent(ctx, ShareService::class.java).apply {
                action = ACTION_START
                putParcelableArrayListExtra(EXTRA_ITEMS, items)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_TOKEN, token)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            val i = Intent(ctx, ShareService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }
}