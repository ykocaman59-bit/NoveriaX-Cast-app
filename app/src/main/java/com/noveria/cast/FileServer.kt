package com.noveria.cast

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Telefondaki tek bir video/foto/ses dosyasını HTTP (Range destekli) ile sunar.
 * TV'nin yerleşik Cast alıcısı dosyayı doğrudan buradan çekip kendisi oynatır;
 * bu yüzden ekran yansıtmadaki gibi gecikme/kasma olmaz.
 */
class FileServer(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mime: String
) {
    private var server: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 0

    fun start() {
        val s = ServerSocket(0)
        server = s
        running = true
        thread(isDaemon = true, name = "NoveriaX-File") {
            while (running) {
                try {
                    val c = s.accept()
                    thread(isDaemon = true, name = "NoveriaX-File-conn") { handle(c) }
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

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 30_000
            val input = c.getInputStream()
            val out = c.getOutputStream()
            val line = readLine(input) ?: return
            var rangeHeader: String? = null
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                if (h.startsWith("range:", ignoreCase = true)) rangeHeader = h.substringAfter(':').trim()
            }
            val parts = line.split(" ")
            val method = parts.getOrNull(0) ?: return

            if (method == "OPTIONS") {
                writeHead(out, 204, "No Content", emptyMap())
                return
            }

            val afd = resolver.openAssetFileDescriptor(uri, "r")
            if (afd == null) {
                writeHead(out, 404, "Not Found", mapOf("Content-Length" to "0"))
                return
            }
            afd.use {
                val total = it.length // bilinmiyorsa -1
                val base = it.startOffset
                var start = 0L
                var end = if (total > 0) total - 1 else -1L
                var partial = false

                if (rangeHeader != null && total > 0 && rangeHeader!!.startsWith("bytes=")) {
                    val spec = rangeHeader!!.removePrefix("bytes=").substringBefore(',').trim()
                    val a = spec.substringBefore('-')
                    val b = spec.substringAfter('-', "")
                    try {
                        if (a.isEmpty()) {
                            val n = b.toLong()
                            start = (total - n).coerceAtLeast(0)
                        } else {
                            start = a.toLong()
                            if (b.isNotEmpty()) end = b.toLong().coerceAtMost(total - 1)
                        }
                        partial = true
                    } catch (_: Exception) {
                    }
                    if (start > end || start >= total) {
                        writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
                        return
                    }
                }

                val headers = linkedMapOf("Content-Type" to mime, "Accept-Ranges" to "bytes")
                val length = if (total > 0) end - start + 1 else -1L
                if (length >= 0) headers["Content-Length"] = length.toString()
                if (partial) headers["Content-Range"] = "bytes $start-$end/$total"
                writeHead(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", headers)
                if (method == "HEAD") return

                val fis: FileInputStream = it.createInputStream()
                try {
                    try {
                        fis.channel.position(base + start)
                    } catch (_: Exception) {
                        skipFully(fis, start)
                    }
                    copy(fis, out, length)
                } finally {
                    try { fis.close() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun copy(src: InputStream, dst: OutputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (running && (left < 0 || left > 0)) {
            val want = if (left < 0) buf.size else minOf(buf.size.toLong(), left).toInt()
            val n = src.read(buf, 0, want)
            if (n < 0) break
            dst.write(buf, 0, n)
            if (left > 0) left -= n
        }
        dst.flush()
    }

    private fun skipFully(src: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = src.skip(left)
            if (s <= 0) break
            left -= s
        }
    }

    private fun writeHead(out: OutputStream, code: Int, msg: String, extra: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $msg\r\n")
        extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
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
