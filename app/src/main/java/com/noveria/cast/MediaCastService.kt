package com.noveria.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Telefondaki video/foto/ses dosyasını TV'nin yerleşik Cast alıcısına oynattırır
 * (filmlerdeki "Cast" butonuyla aynı mantık: TV dosyayı kendisi çeker, gecikme/kasma olmaz).
 */
class MediaCastService : Service() {

    companion object {
        const val ACTION_START = "com.noveria.cast.MEDIA_START"
        const val ACTION_STOP = "com.noveria.cast.MEDIA_STOP"
        const val EXTRA_URI = "uri"
        const val EXTRA_MIME = "mime"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"

        private const val CHANNEL_ID = "noveriax_media"
        private const val NOTIF_ID = 1002
    }

    @Volatile private var running = false
    @Volatile private var userStop = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "TV"
        createChannel()
        val n = buildNotification(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (running) return

        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, CastConst.CAST_PORT)
        val id = intent.getStringExtra(EXTRA_DEVICE_ID)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "video/mp4"
        if (host == null || id == null || uriStr == null) {
            CastState.lastError.value = "Gönderme başlatılamadı (eksik bilgi)."
            stopForegroundAndSelf()
            return
        }
        running = true
        userStop = false
        worker = Thread({ runMedia(host, port, id, Uri.parse(uriStr), mime) }, "NoveriaX-Media").also { it.start() }
    }

    private fun handleStop() {
        userStop = true
        running = false
        if (worker == null) stopForegroundAndSelf()
    }

    private fun runMedia(host: String, port: Int, id: String, uri: Uri, mime: String) {
        val cast = CastV2Client(host, port)
        val server = FileServer(contentResolver, uri, mime)
        CastState.castingDeviceId.value = id
        try {
            val phoneIp = NetworkManager(this).phoneWifiIp()
            if (phoneIp == null) {
                CastState.lastError.value = "Telefon - Çevrimdışı"
                return
            }
            if (!cast.connect()) {
                if (running) CastState.lastError.value = "TV'ye bağlanılamadı. TV'nin açık ve aynı Wi-Fi ağında olduğunu kontrol edin."
                return
            }
            if (!running) return
            if (!cast.launch()) {
                if (running) CastState.lastError.value = "TV alıcıyı açmadı. TV'de Google Cast (Chromecast yerleşik) etkin olmalı."
                return
            }
            server.start()
            cast.load("http://$phoneIp:${server.port}/media", mime, live = false)

            while (running) {
                Thread.sleep(500)
                if (!cast.alive || cast.silentMs() > 20_000) {
                    if (!userStop) CastState.lastError.value = "Gönderme kesildi: TV bağlantısı kayboldu."
                    break
                }
                if (cast.appGone) {
                    userStop = true // TV tarafında kapatıldı
                    break
                }
            }
        } catch (e: Exception) {
            if (!userStop) CastState.lastError.value = "Gönderme kesildi: ${e.message ?: "bağlantı hatası"}"
        } finally {
            try { if (cast.alive) cast.stopApp() } catch (_: Exception) {}
            try { cast.close() } catch (_: Exception) {}
            try { server.stop() } catch (_: Exception) {}
            worker = null
            running = false
            CastState.castingDeviceId.value = null
            Handler(Looper.getMainLooper()).post { stopForegroundAndSelf() }
        }
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Video/Foto Gönderme", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(deviceName: String): Notification {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, MediaCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle("NoveriaX Cast")
            .setContentText("$deviceName cihazına gönderiliyor")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Yansıtmadan Çık", stop)
            .build()
    }
}
