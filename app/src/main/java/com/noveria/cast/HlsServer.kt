package com.noveria.cast

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Telefonda çalışan küçük HTTP sunucusu. Canlı HLS (MPEG-TS segmentleri) yayınlar;
 * TV'nin yerleşik Google Cast alıcısı bu adresi açar. TV'ye uygulama kurmak gerekmez.
 */
class HlsServer {

    private class Segment(val seq: Int, val data: ByteArray, val durationSec: Double)

    private val lock = Object()
    private val segments = ArrayList<Segment>()
    private var nextSeq = 0

    private val muxer = TsMuxer()
    private var current: ByteArrayOutputStream? = null
    private var currentStartUs = -1L
    private var lastPtsUs = 0L
    private var paramSets: ByteArray = ByteArray(0)

    private var server: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 0

    /** Hazır (tamamlanmış) segment sayısı. */
    fun segmentCount(): Int = synchronized(lock) { segments.size }

    fun start() {
        val s = ServerSocket(0)
        server = s
        running = true
        thread(isDaemon = true, name = "NoveriaX-HLS") {
            while (running) {
                try {
                    val c = s.accept()
                    thread(isDaemon = true, name = "NoveriaX-HLS-conn") { handle(c) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    /** Kodlayıcının CODEC_CONFIG çıktısı (SPS/PPS, Annex-B). */
    fun setParameterSets(bytes: ByteArray) {
        paramSets = bytes
    }

    /** Kodlanmış her kare için çağrılır. Anahtar karede yeni segment başlar. */
    fun onFrame(annexB: ByteArray, ptsUs: Long, keyframe: Boolean) {
        if (keyframe) {
            finishSegment(ptsUs)
            val out = ByteArrayOutputStream(512 * 1024)
            out.write(muxer.patPmt())
            current = out
            currentStartUs = ptsUs
        }
        val out = current ?: return // ilk anahtar kareyi bekle
        val payload = if (keyframe) paramSets + annexB else annexB
        out.write(muxer.video(payload, ptsUs, keyframe))
        lastPtsUs = ptsUs
    }

    private fun finishSegment(endUs: Long) {
        val out = current ?: return
        val dur = ((endUs - currentStartUs) / 1_000_000.0).coerceAtLeast(0.2)
        synchronized(lock) {
            segments.add(Segment(nextSeq++, out.toByteArray(), dur))
            while (segments.size > 6) segments.removeAt(0)
        }
        current = null
    }

    private fun playlist(): ByteArray {
        val sb = StringBuilder()
        synchronized(lock) {
            val list = segments.takeLast(5)
            val first = list.firstOrNull()?.seq ?: 0
            val target = Math.ceil(list.maxOfOrNull { it.durationSec } ?: 2.0).toInt().coerceAtLeast(2)
            sb.append("#EXTM3U\n#EXT-X-VERSION:3\n")
            sb.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n')
            for (s in list) {
                sb.append(String.format(Locale.US, "#EXTINF:%.3f,\n", s.durationSec))
                sb.append("seg").append(s.seq).append(".ts\n")
            }
        }
        return sb.toString().toByteArray()
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 10_000
            val input = c.getInputStream()
            val line = readLine(input) ?: return
            // Başlıkların geri kalanını oku.
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
            }
            val parts = line.split(" ")
            val method = parts.getOrNull(0) ?: return
            val path = (parts.getOrNull(1) ?: "/").substringBefore('?')
            val out = c.getOutputStream()

            if (method == "OPTIONS") {
                respond(out, 204, "No Content", "text/plain", ByteArray(0))
                return
            }
            when {
                path == "/live.m3u8" -> respond(out, 200, "OK", "application/x-mpegURL", playlist())
                path.startsWith("/seg") && path.endsWith(".ts") -> {
                    val seq = path.removePrefix("/seg").removeSuffix(".ts").toIntOrNull()
                    val seg = synchronized(lock) { segments.firstOrNull { it.seq == seq } }
                    if (seg != null) respond(out, 200, "OK", "video/mp2t", seg.data)
                    else respond(out, 404, "Not Found", "text/plain", ByteArray(0))
                }
                else -> respond(out, 404, "Not Found", "text/plain", ByteArray(0))
            }
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, msg: String, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code $msg\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }
}
