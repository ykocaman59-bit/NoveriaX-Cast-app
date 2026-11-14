package com.noveria.cast

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** TV'nin çekeceği canlı MPEG-TS akışını HTTP ile sunar. */
class TsStreamServer {

    private class Client(val sock: Socket) {
        val queue = LinkedBlockingQueue<ByteArray>(240)
        @Volatile var started = false
        @Volatile var alive = true
        fun close() {
            alive = false
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private val server = ServerSocket(0)
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var running = false
    @Volatile var everConnected = false
        private set

    val port: Int get() = server.localPort

    fun start() {
        running = true
        thread(isDaemon = true, name = "NoveriaX-HTTP") {
            while (running) {
                try {
                    val s = server.accept()
                    thread(isDaemon = true, name = "NoveriaX-HTTP-Client") { handle(s) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: Exception) {}
        clients.forEach { it.close() }
        clients.clear()
    }

    /** Yeni bağlanan istemci ilk anahtar kareyi bekler (PAT/PMT anahtar karenin başındadır). */
    fun broadcast(data: ByteArray, key: Boolean) {
        for (c in clients) {
            if (!c.alive) continue
            if (!c.started) {
                if (key) c.started = true else continue
            }
            if (!c.queue.offer(data)) {
                c.queue.clear()
                c.started = false
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), "ISO-8859-1"))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val l = reader.readLine()
                if (l == null || l.isEmpty()) break
            }
            val out = sock.getOutputStream()
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mpeg\r\n" +
                    "transferMode.dlna.org: Streaming\r\n" +
                    "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.flush()
            if (requestLine.startsWith("HEAD")) return

            everConnected = true
            val c = Client(sock)
            clients.add(c)
            try {
                while (c.alive && running) {
                    val d = c.queue.poll(1, TimeUnit.SECONDS) ?: continue
                    out.write(d)
                    out.flush()
                }
            } finally {
                clients.remove(c)
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }
}
