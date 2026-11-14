package com.noveria.cast

import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Google Cast (CastV2) protokolü istemcisi. Chromecast yerleşik tüm Android TV / Google TV
 * cihazlarında 8009 portunda dinler. TV'ye uygulama kurmadan "Default Media Receiver" açılır
 * ve verilen HLS adresi oynatılır.
 */
class CastV2Client(private val host: String, private val port: Int) {

    @Volatile var alive = false
        private set
    @Volatile var appGone = false
        private set
    @Volatile private var lastRx = 0L

    private var sock: SSLSocket? = null
    private var out: OutputStream? = null
    private val reqId = AtomicInteger(1)
    private val launched = CountDownLatch(1)
    @Volatile private var transportId: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var appLaunched = false

    /** TV'den en son veri geldiğinden beri geçen ms. */
    fun silentMs(): Long = SystemClock.elapsedRealtime() - lastRx

    fun connect(timeoutMs: Int = 4000): Boolean {
        return try {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trustAll), SecureRandom())
            val s = ctx.socketFactory.createSocket() as SSLSocket
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 0
            s.startHandshake()
            sock = s
            out = s.outputStream
            alive = true
            lastRx = SystemClock.elapsedRealtime()
            startReader(DataInputStream(s.inputStream))
            startHeartbeat()
            send(SRC, "receiver-0", NS_CONNECTION, JSONObject().put("type", "CONNECT"))
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    /** Varsayılan medya alıcısını TV'de açar. */
    fun launch(timeoutMs: Long = 10_000): Boolean {
        send(
            SRC, "receiver-0", NS_RECEIVER,
            JSONObject().put("type", "LAUNCH").put("appId", APP_ID).put("requestId", reqId.getAndIncrement())
        )
        return launched.await(timeoutMs, TimeUnit.MILLISECONDS) && transportId != null
    }

    /** HLS canlı yayın adresini TV'de oynatır. */
    fun load(url: String, contentType: String = "application/x-mpegURL", live: Boolean = true) {
        val t = transportId ?: return
        send(SRC, t, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
        val media = JSONObject()
            .put("contentId", url)
            .put("streamType", if (live) "LIVE" else "BUFFERED")
            .put("contentType", contentType)
        send(
            SRC, t, NS_MEDIA,
            JSONObject()
                .put("type", "LOAD")
                .put("requestId", reqId.getAndIncrement())
                .put("sessionId", sessionId)
                .put("media", media)
                .put("autoplay", true)
                .put("currentTime", 0)
        )
    }

    /** TV'deki alıcı uygulamayı kapatır; TV kendi ekranına döner. */
    fun stopApp() {
        val sid = sessionId ?: return
        try {
            send(
                SRC, "receiver-0", NS_RECEIVER,
                JSONObject().put("type", "STOP").put("sessionId", sid).put("requestId", reqId.getAndIncrement())
            )
            Thread.sleep(300)
        } catch (_: Exception) {
        }
    }

    fun close() {
        alive = false
        try { sock?.close() } catch (_: Exception) {}
        sock = null
        out = null
    }

    // ------------------------------------------------------------------

    private fun startHeartbeat() {
        thread(isDaemon = true, name = "NoveriaX-Cast-HB") {
            while (alive) {
                try {
                    send(SRC, "receiver-0", NS_HEARTBEAT, JSONObject().put("type", "PING"))
                    Thread.sleep(5000)
                } catch (_: Exception) {
                    alive = false
                }
            }
        }
    }

    private fun startReader(input: DataInputStream) {
        thread(isDaemon = true, name = "NoveriaX-Cast-RX") {
            try {
                while (alive) {
                    val len = input.readInt()
                    if (len <= 0 || len > 1_000_000) throw IllegalStateException("bad frame")
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    lastRx = SystemClock.elapsedRealtime()
                    handle(buf)
                }
            } catch (_: Exception) {
            } finally {
                alive = false
            }
        }
    }

    private fun handle(raw: ByteArray) {
        val msg = decode(raw) ?: return
        val json = try { JSONObject(msg.payload) } catch (_: Exception) { return }
        when (msg.namespace) {
            NS_HEARTBEAT -> if (json.optString("type") == "PING") {
                send(SRC, msg.source, NS_HEARTBEAT, JSONObject().put("type", "PONG"))
            }
            NS_CONNECTION -> if (json.optString("type") == "CLOSE" && appLaunched && msg.source == transportId) {
                appGone = true
            }
            NS_RECEIVER -> if (json.optString("type") == "RECEIVER_STATUS") {
                val apps = json.optJSONObject("status")?.optJSONArray("applications")
                var found = false
                if (apps != null) {
                    for (i in 0 until apps.length()) {
                        val a = apps.getJSONObject(i)
                        if (a.optString("appId") == APP_ID) {
                            found = true
                            transportId = a.optString("transportId")
                            sessionId = a.optString("sessionId")
                            appLaunched = true
                            launched.countDown()
                        }
                    }
                }
                // Alıcı açıldıktan sonra kapandıysa (TV kumandasıyla çıkıldı vb.)
                if (!found && appLaunched) appGone = true
            }
        }
    }

    // ----- CastMessage protobuf -----------------------------------------

    private class Msg(val source: String, val namespace: String, val payload: String)

    private fun send(src: String, dst: String, ns: String, json: JSONObject) {
        val body = ByteArrayOutputStream()
        writeVarintField(body, 1, 0) // CASTV2_1_0
        writeStringField(body, 2, src)
        writeStringField(body, 3, dst)
        writeStringField(body, 4, ns)
        writeVarintField(body, 5, 0) // STRING
        writeStringField(body, 6, json.toString())
        val bytes = body.toByteArray()
        val o = out ?: return
        synchronized(this) {
            o.write(byteArrayOf(
                (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()
            ))
            o.write(bytes)
            o.flush()
        }
    }

    private fun writeVarint(o: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            o.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        o.write(v)
    }

    private fun writeVarintField(o: ByteArrayOutputStream, field: Int, value: Int) {
        writeVarint(o, field shl 3)
        writeVarint(o, value)
    }

    private fun writeStringField(o: ByteArrayOutputStream, field: Int, value: String) {
        val b = value.toByteArray(Charsets.UTF_8)
        writeVarint(o, (field shl 3) or 2)
        writeVarint(o, b.size)
        o.write(b)
    }

    private fun decode(b: ByteArray): Msg? {
        var p = 0
        var source = ""
        var ns = ""
        var payload = ""
        fun readVarint(): Int {
            var result = 0
            var shift = 0
            while (p < b.size) {
                val x = b[p++].toInt() and 0xFF
                result = result or ((x and 0x7F) shl shift)
                if (x and 0x80 == 0) break
                shift += 7
            }
            return result
        }
        try {
            while (p < b.size) {
                val tag = readVarint()
                val field = tag ushr 3
                when (tag and 7) {
                    0 -> readVarint()
                    2 -> {
                        val len = readVarint()
                        val s = String(b, p, len, Charsets.UTF_8)
                        p += len
                        when (field) {
                            2 -> source = s
                            4 -> ns = s
                            6 -> payload = s
                        }
                    }
                    else -> return null
                }
            }
        } catch (_: Exception) {
            return null
        }
        return Msg(source, ns, payload)
    }

    companion object {
        const val APP_ID = "CC1AD845" // Default Media Receiver
        private const val SRC = "sender-0"
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    }
}
