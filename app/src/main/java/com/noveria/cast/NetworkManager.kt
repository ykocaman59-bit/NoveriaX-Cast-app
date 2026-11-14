package com.noveria.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory

/** Google Cast sabitleri. Chromecast yerleşik TV'ler 8009 portunda dinler. */
object CastConst {
    const val SERVICE_TYPE = "_googlecast._tcp."
    const val CAST_PORT = 8009
}

/**
 * Wi-Fi durumu + cihaz keşfi.
 * Keşif iki yöntemle YAN YANA yapılır:
 *  1) mDNS  : Google Cast (Chromecast yerleşik) TV'ler
 *  2) SSDP  : DLNA / UPnP medya oynatıcı TV'ler (çoğu markanın "Ekran yansıtma / Medya" özelliği)
 */
class NetworkManager(context: Context) {

    interface DiscoveryCallback {
        fun onDeviceFound(device: TvDevice)
        fun onFinished()
    }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()

    @Volatile private var scanId = 0
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = LinkedList<NsdServiceInfo>()
    private var resolving = false
    private var multicastLock: WifiManager.MulticastLock? = null

    // Tarama raporu (cihaz bulunamazsa nedenini görmek için)
    @Volatile private var castFound = 0
    @Volatile private var ssdpResponses = 0
    @Volatile private var ssdpError: String? = null
    private val upnpOther = CopyOnWriteArrayList<String>()

    // ------------------------------------------------------------------
    // Wi-Fi durumu
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun wifiNetworks(): List<Network> = cm.allNetworks.filter {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun wifiNetwork(): Network? = wifiNetworks().firstOrNull()

    /** Telefon bir Wi-Fi ağına bağlı mı? */
    fun isPhoneWifiConnected(): Boolean = wifiNetworks().isNotEmpty()

    /** Telefonun Wi-Fi IPv4 adresi (TV'nin yayını çekeceği adres). */
    fun phoneWifiIp(): String? {
        try {
            for (net in wifiNetworks()) {
                val lp = cm.getLinkProperties(net) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Mobil veri açık olsa bile trafiği Wi-Fi üzerinden gönderir. */
    fun openConnection(url: URL): HttpURLConnection {
        val n = wifiNetwork()
        return (if (n != null) n.openConnection(url) else url.openConnection()) as HttpURLConnection
    }

    /**
     * TV ağda cevap veriyor mu? Cast: 8009 portuna TCP; DLNA: cihaz tanım adresine HTTP GET.
     * Arka plan iş parçacığında çağırın.
     */
    fun isDeviceReachable(device: TvDevice, timeoutMs: Int = 1500): Boolean {
        return try {
            if (device.type == TvDevice.TYPE_DLNA) {
                val c = openConnection(URL(device.location))
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                val code = c.responseCode
                c.disconnect()
                code in 200..299
            } else {
                Socket().use { s -> s.connect(InetSocketAddress(device.host, device.port), timeoutMs) }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Cihaz keşfi
    // ------------------------------------------------------------------

    fun startDiscovery(callback: DiscoveryCallback) {
        stopDiscovery()
        val myId = ++scanId
        val seen = HashSet<String>()
        val castHosts = HashSet<String>()
        val pendingDlna = LinkedHashMap<String, TvDevice>()
        castFound = 0
        ssdpResponses = 0
        ssdpError = null
        upnpOther.clear()

        val deliver: (TvDevice) -> Unit = { d ->
            mainHandler.post {
                if (myId == scanId) {
                    if (d.type == TvDevice.TYPE_DLNA) {
                        // Aynı TV hem Cast hem DLNA sunuyorsa Cast tercih edilir; DLNA tarama sonunda eklenir.
                        pendingDlna[d.id] = d
                    } else if (seen.add(d.id)) {
                        castFound++
                        castHosts.add(d.host)
                        callback.onDeviceFound(d)
                    }
                }
            }
        }

        try {
            multicastLock = wifi.createMulticastLock("noveriax_cast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        startNsd(deliver)
        Thread({ ssdpSearch(myId, deliver) }, "NoveriaX-SSDP").apply { isDaemon = true }.start()

        mainHandler.postDelayed({
            if (myId == scanId) {
                val extra = pendingDlna.values.filter { it.host !in castHosts && seen.add(it.id) }
                stopDiscovery()
                extra.forEach { callback.onDeviceFound(it) }
                callback.onFinished()
            }
        }, SCAN_MS)
    }

    /** "Cihaz bulunamadı" durumunda gösterilecek kısa teknik özet. */
    fun scanReport(): String {
        val sb = StringBuilder()
        sb.append("Google Cast (mDNS): $castFound cihaz\n")
        sb.append("UPnP/DLNA (SSDP): $ssdpResponses yanıt")
        if (upnpOther.isNotEmpty()) sb.append(" → ").append(upnpOther.take(6).joinToString(", "))
        ssdpError?.let { sb.append("\nSSDP hatası: $it") }
        return sb.toString()
    }

    fun stopDiscovery() {
        scanId++
        mainHandler.removeCallbacksAndMessages(null)
        discoveryListener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    // ---------------- mDNS: Google Cast ----------------

    private fun startNsd(deliver: (TvDevice) -> Unit) {
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("googlecast")) {
                    synchronized(resolveQueue) { resolveQueue.add(info) }
                    resolveNext(deliver)
                }
            }
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(CastConst.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            discoveryListener = null
        }
    }

    /** NSD aynı anda tek çözümlemeye izin verir; sıraya alıyoruz. */
    @Suppress("DEPRECATION")
    private fun resolveNext(deliver: (TvDevice) -> Unit) {
        val info = synchronized(resolveQueue) {
            if (resolving) null else resolveQueue.poll()?.also { resolving = true }
        } ?: return
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(resolveQueue) { resolving = false }
                    resolveNext(deliver)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    if (host is Inet4Address && host.hostAddress != null) {
                        // "fn" TXT kaydı TV'nin kullanıcı dostu adıdır (ör. "Salon TV").
                        val friendly = serviceInfo.attributes?.get("fn")
                            ?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                        val name = friendly ?: serviceInfo.serviceName
                        val port = if (serviceInfo.port > 0) serviceInfo.port else CastConst.CAST_PORT
                        deliver(TvDevice(name, host.hostAddress!!, port))
                    }
                    synchronized(resolveQueue) { resolving = false }
                    resolveNext(deliver)
                }
            })
        } catch (_: Exception) {
            synchronized(resolveQueue) { resolving = false }
        }
    }

    // ---------------- SSDP: DLNA / UPnP ----------------

    private fun ssdpSearch(id: Int, deliver: (TvDevice) -> Unit) {
        var sock: MulticastSocket? = null
        try {
            sock = MulticastSocket().apply {
                soTimeout = 400
                timeToLive = 4
            }
            try {
                wifiNetwork()?.bindSocket(sock) // mobil veri açıkken de Wi-Fi'dan gönder
            } catch (_: Exception) {
            }
            val group = InetAddress.getByName("239.255.255.250")
            val seen = ConcurrentHashMap.newKeySet<String>()
            val end = SystemClock.elapsedRealtime() + SCAN_MS - 500
            var sends = 0
            var lastSend = 0L
            val buf = ByteArray(2048)
            val locationRegex = Regex("(?im)^location:\\s*(\\S+)")

            while (id == scanId && SystemClock.elapsedRealtime() < end) {
                val now = SystemClock.elapsedRealtime()
                if (sends < 3 && now - lastSend >= 1000) {
                    for (st in SEARCH_TARGETS) {
                        val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                                "HOST: 239.255.255.250:1900\r\n" +
                                "MAN: \"ssdp:discover\"\r\n" +
                                "MX: 2\r\n" +
                                "ST: $st\r\n\r\n").toByteArray()
                        try {
                            sock.send(DatagramPacket(msg, msg.size, group, 1900))
                        } catch (e: Exception) {
                            ssdpError = e.message ?: e.javaClass.simpleName
                        }
                    }
                    sends++
                    lastSend = now
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val text = String(p.data, 0, p.length)
                    val loc = locationRegex.find(text)?.groupValues?.get(1)
                    if (loc != null && seen.add(loc)) {
                        ssdpResponses++
                        ioExecutor.execute { fetchDevice(loc)?.let(deliver) }
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (e: Exception) {
            ssdpError = e.message ?: e.javaClass.simpleName
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    /** Cihaz tanım XML'ini okur; AVTransport (medya oynatıcı) servisi yoksa null döner. */
    private fun fetchDevice(location: String): TvDevice? {
        return try {
            val c = openConnection(URL(location))
            c.connectTimeout = 2500
            c.readTimeout = 2500
            if (c.responseCode !in 200..299) return null
            val xml = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()

            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
            fun Element.text(tag: String): String? =
                getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

            val root = doc.documentElement
            val name = root.text("friendlyName") ?: "Bilinmeyen TV"
            val base = URL(root.text("URLBase") ?: location)

            val services = doc.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val s = services.item(i) as? Element ?: continue
                val type = s.text("serviceType") ?: continue
                if (type.contains("AVTransport")) {
                    val control = s.text("controlURL") ?: continue
                    val u = URL(location)
                    return TvDevice(
                        name = name,
                        host = u.host,
                        port = if (u.port > 0) u.port else 80,
                        type = TvDevice.TYPE_DLNA,
                        location = location,
                        controlUrl = URL(base, control).toString(),
                        serviceType = type
                    )
                }
            }
            upnpOther.add(name)
            null
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val SCAN_MS = 6000L
        val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "upnp:rootdevice"
        )
    }
}
