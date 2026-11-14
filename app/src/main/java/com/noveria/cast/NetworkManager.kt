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
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.LinkedList

/** Google Cast sabitleri. Chromecast yerleşik TV'ler 8009 portunda dinler. */
object CastConst {
    const val SERVICE_TYPE = "_googlecast._tcp."
    const val CAST_PORT = 8009
}

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

    @Volatile private var scanId = 0
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = LinkedList<NsdServiceInfo>()
    private var resolving = false
    private var multicastLock: WifiManager.MulticastLock? = null

    // ------------------------------------------------------------------
    // Wi-Fi durumu
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun wifiNetworks(): List<Network> = cm.allNetworks.filter {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

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

    /**
     * TV ağda cevap veriyor mu? Cast portuna (8009) TCP bağlantısı denenir.
     * Cevap yoksa TV kapalı ya da Wi-Fi'a bağlı değil demektir. Arka plan iş parçacığında çağırın.
     */
    fun isDeviceReachable(device: TvDevice, timeoutMs: Int = 1500): Boolean {
        return try {
            Socket().use { s -> s.connect(InetSocketAddress(device.host, device.port), timeoutMs) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Cihaz keşfi (mDNS / NSD: _googlecast._tcp)
    // ------------------------------------------------------------------

    fun startDiscovery(callback: DiscoveryCallback) {
        stopDiscovery()
        val myId = ++scanId
        val seen = HashSet<String>()

        val deliver: (TvDevice) -> Unit = { d ->
            mainHandler.post {
                if (myId == scanId && seen.add(d.id)) callback.onDeviceFound(d)
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

        mainHandler.postDelayed({
            if (myId == scanId) {
                stopDiscovery()
                callback.onFinished()
            }
        }, SCAN_MS)
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

    private companion object {
        const val SCAN_MS = 6000L
    }
}
