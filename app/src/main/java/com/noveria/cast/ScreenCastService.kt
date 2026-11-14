package com.noveria.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.max
import kotlin.math.min

/**
 * MediaProjection ile ekranı yakalar, H.264 olarak kodlar, telefonda canlı HLS olarak sunar ve TV'nin yerleşik Google Cast alıcısına oynattırır.
 */
class ScreenCastService : Service() {

    companion object {
        const val ACTION_START = "com.noveria.cast.START"
        const val ACTION_STOP = "com.noveria.cast.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"

        private const val CHANNEL_ID = "noveriax_cast"
        private const val NOTIF_ID = 1001
        private const val MAX_DIMENSION = 1280
        private const val BIT_RATE = 5_000_000
        private const val FPS = 30
    }

    @Volatile private var running = false
    @Volatile private var userStop = false
    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var client: CastV2Client? = null

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
        val notification = buildNotification(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (running) return

        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, CastConst.CAST_PORT)
        val id = intent.getStringExtra(EXTRA_DEVICE_ID)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (host == null || id == null || data == null) {
            CastState.lastError.value = "Yansıtma başlatılamadı (eksik bilgi)."
            stopForegroundAndSelf()
            return
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data)
            // Android 14+: createVirtualDisplay'den önce callback kaydı zorunlu.
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    userStop = true
                    running = false
                }
            }, Handler(Looper.getMainLooper()))
            projection = mp
        } catch (e: Exception) {
            CastState.lastError.value = "Ekran yakalama başlatılamadı: ${e.message}"
            stopForegroundAndSelf()
            return
        }

        running = true
        userStop = false
        worker = Thread({ runCast(host, port, id) }, "NoveriaX-Cast").also { it.start() }
    }

    private fun handleStop() {
        userStop = true
        running = false
        if (worker == null) stopForegroundAndSelf()
    }

    private fun runCast(host: String, port: Int, id: String) {
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var display: VirtualDisplay? = null
        val hls = HlsServer()
        val cast = CastV2Client(host, port)
        client = cast
        var loaded = false
        CastState.castingDeviceId.value = id // Arayüzde hemen "Yansıtılıyor / Yansıtmadan Çık" görünsün
        try {
            val net = NetworkManager(this)
            val phoneIp = net.phoneWifiIp()
            if (phoneIp == null) {
                CastState.lastError.value = "Telefon - Çevrimdışı"
                return
            }

            // 1) TV'ye (Google Cast) bağlan ve yerleşik alıcıyı aç.
            if (!cast.connect()) {
                if (running) CastState.lastError.value = "TV'ye bağlanılamadı. TV'nin açık ve aynı Wi-Fi ağında olduğunu kontrol edin."
                return
            }
            if (!running) return
            if (!cast.launch()) {
                if (running) CastState.lastError.value = "TV yansıtma alıcısını açmadı. TV'de Google Cast (Chromecast yerleşik) etkin olmalı."
                return
            }

            // 2) Ekranı yakala ve H.264 olarak kodla.
            val (w, h, dpi) = captureSize()
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                // Ekran durağan olsa da düzenli kare gönder.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()

            display = projection!!.createVirtualDisplay(
                "NoveriaXCast", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            // 3) HLS yayınını başlat.
            hls.start()
            val url = "http://$phoneIp:${hls.port}/live.m3u8"

            val info = MediaCodec.BufferInfo()
            var lastSync = SystemClock.elapsedRealtime()
            val startedAt = lastSync
            while (running) {
                val now = SystemClock.elapsedRealtime()
                // Her saniye anahtar kare iste: segmentler düzenli kesilsin.
                if (now - lastSync >= 1000) {
                    try {
                        enc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    } catch (_: Exception) {
                    }
                    lastSync = now
                }

                val idx = enc.dequeueOutputBuffer(info, 100_000)
                if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            hls.setParameterSets(bytes)
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            hls.onFrame(bytes, info.presentationTimeUs, key)
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                }

                // 4) Yeterli segment birikince TV'ye oynat komutunu gönder.
                if (!loaded && hls.segmentCount() >= 3) {
                    cast.load(url)
                    loaded = true
                }
                if (!loaded && now - startedAt > 20_000) {
                    CastState.lastError.value = "Yayın başlatılamadı (zaman aşımı)."
                    break
                }

                // TV bağlantısı koptu / TV'de yansıtma kapatıldı / TV yanıt vermiyor.
                if (!cast.alive || cast.silentMs() > 20_000) {
                    if (!userStop) CastState.lastError.value = "Yansıtma kesildi: TV bağlantısı kayboldu."
                    break
                }
                if (cast.appGone) {
                    userStop = true // TV tarafında kapatıldı; hata gösterme.
                    break
                }
            }
        } catch (e: Exception) {
            if (!userStop) {
                CastState.lastError.value = "Yansıtma kesildi: ${e.message ?: "bağlantı hatası"}"
            }
        } finally {
            // "Yansıtmadan Çık": TV'deki alıcıyı kapat, TV kendi ekranına dönsün.
            try { if (cast.alive) cast.stopApp() } catch (_: Exception) {}
            try { cast.close() } catch (_: Exception) {}
            try { hls.stop() } catch (_: Exception) {}
            try { display?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
            client = null
            worker = null
            running = false
            CastState.castingDeviceId.value = null
            Handler(Looper.getMainLooper()).post { stopForegroundAndSelf() }
        }
    }

    private fun captureSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var width: Int
        var height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            width = b.width()
            height = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            width = dm.widthPixels
            height = dm.heightPixels
        }
        val scale = min(1f, MAX_DIMENSION.toFloat() / max(width, height))
        width = ((width * scale).toInt() / 2) * 2
        height = ((height * scale).toInt() / 2) * 2
        return Triple(width, height, resources.displayMetrics.densityDpi)
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ekran Yansıtma", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(deviceName: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle("NoveriaX Cast")
            .setContentText("$deviceName cihazına yansıtılıyor")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Yansıtmadan Çık", stopIntent)
            .build()
    }
}
