package com.noveria.cast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.noveria.cast.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var net: NetworkManager
    private lateinit var store: DeviceStore
    private lateinit var savedAdapter: DeviceAdapter
    private lateinit var foundAdapter: FoundDeviceAdapter

    private val statuses = mutableMapOf<String, Boolean>()
    private var phoneOnline = true
    private var scanning = false
    private var pendingDevice: TvDevice? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Bildirim izni verilse de verilmese de devam et.
            requestProjection()
        }

    private var pendingFileDevice: TvDevice? = null

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val device = pendingFileDevice
            pendingFileDevice = null
            if (device != null && uri != null) sendFile(device, uri)
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val device = pendingDevice
            pendingDevice = null
            val data = result.data
            if (device != null && result.resultCode == RESULT_OK && data != null) {
                startCastService(device, result.resultCode, data)
            } else if (device != null) {
                toast("Ekran yansıtma izni verilmedi")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        net = NetworkManager(this)
        store = DeviceStore(this)

        savedAdapter = DeviceAdapter(object : DeviceAdapter.Listener {
            override fun onCastClicked(device: TvDevice) = beginCast(device, saveFirst = false)
            override fun onStopClicked(device: TvDevice) = stopCast()
            override fun onSendFileClicked(device: TvDevice) = beginSendFile(device)
            override fun onDeleteClicked(device: TvDevice) = deleteDevice(device)
        })
        foundAdapter = FoundDeviceAdapter()

        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = savedAdapter
        binding.rvFound.layoutManager = LinearLayoutManager(this)
        binding.rvFound.adapter = foundAdapter

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnAddCast.setOnClickListener {
            val selected = foundAdapter.getSelected()
            if (selected == null) toast("Önce listeden bir TV seçin") else beginCast(selected, saveFirst = true)
        }

        loadSavedDevices()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CastState.castingDeviceId.collect { savedAdapter.setCastingId(it) } }
                launch {
                    CastState.lastError.collect { msg ->
                        if (msg != null) {
                            toast(msg)
                            CastState.lastError.value = null
                            refreshStatuses()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleRefresh()
            override fun onLost(network: Network) = scheduleRefresh()
        }
        networkCallback = cb
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb
        )
    }

    override fun onStop() {
        super.onStop()
        networkCallback?.let {
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses() // Uygulama açıldığında Wi-Fi durumu kontrolü
    }

    override fun onDestroy() {
        net.stopDiscovery()
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        runOnUiThread {
            lifecycleScope.launch {
                delay(600)
                refreshStatuses()
            }
        }
    }

    // ------------------------------------------------------------------
    // Kayıtlı cihazlar
    // ------------------------------------------------------------------

    private fun loadSavedDevices() {
        val list = store.getAll()
        savedAdapter.setItems(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun deleteDevice(device: TvDevice) {
        if (CastState.castingDeviceId.value == device.id) stopCast()
        store.remove(device.id)
        statuses.remove(device.id)
        loadSavedDevices()
        renderBanner()
        toast("${device.name} silindi")
    }

    /** Telefon ve kayıtlı TV'lerin çevrimiçi durumunu kontrol eder ve uyarıyı günceller. */
    private fun refreshStatuses() {
        lifecycleScope.launch {
            val wifi = net.isPhoneWifiConnected()
            phoneOnline = wifi
            if (!wifi) {
                statuses.clear()
                savedAdapter.setStatuses(emptyMap(), false)
                renderBanner()
                return@launch
            }
            val devices = store.getAll()
            val casting = CastState.castingDeviceId.value
            val results = withContext(Dispatchers.IO) {
                devices.map { d ->
                    async { d.id to (d.id == casting || net.isDeviceReachable(d)) }
                }.awaitAll()
            }
            statuses.clear()
            results.forEach { statuses[it.first] = it.second }
            savedAdapter.setStatuses(statuses.toMap(), true)
            renderBanner()
        }
    }

    /** Hangi cihazın çevrimdışı olduğunu açıkça gösterir: "[Cihaz Adı] - Çevrimdışı". */
    private fun renderBanner(extraOffline: List<String> = emptyList()) {
        val lines = mutableListOf<String>()
        if (!phoneOnline) {
            lines.add("Telefon - Çevrimdışı")
        } else {
            store.getAll().forEach { d ->
                if (statuses[d.id] == false) lines.add("${d.name} - Çevrimdışı")
            }
        }
        extraOffline.forEach { if (!lines.contains("$it - Çevrimdışı")) lines.add("$it - Çevrimdışı") }

        binding.tvBanner.setTextColor(Color.parseColor("#B71C1C"))
        if (lines.isEmpty()) {
            binding.tvBanner.visibility = View.GONE
        } else {
            binding.tvBanner.text = lines.joinToString("\n")
            binding.tvBanner.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------
    // Cihaz tarama
    // ------------------------------------------------------------------

    private fun startScan() {
        if (scanning) return
        phoneOnline = net.isPhoneWifiConnected()
        renderBanner()
        if (!phoneOnline) {
            AlertDialog.Builder(this)
                .setTitle("Telefon - Çevrimdışı")
                .setMessage("Telefonun Wi-Fi bağlantısı kapalı. Cihaz taramak için TV ile aynı Wi-Fi ağına bağlanın.")
                .setPositiveButton("Wi-Fi Ayarları") { _, _ ->
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                .setNegativeButton("Kapat", null)
                .show()
            return
        }

        scanning = true
        foundAdapter.clear()
        binding.foundSection.visibility = View.VISIBLE
        binding.tvFoundEmpty.text = "Cihazlar aranıyor..."
        binding.tvFoundEmpty.visibility = View.VISIBLE
        binding.progressScan.visibility = View.VISIBLE
        binding.btnScan.isEnabled = false

        net.startDiscovery(object : NetworkManager.DiscoveryCallback {
            override fun onDeviceFound(device: TvDevice) {
                foundAdapter.add(device)
                binding.tvFoundEmpty.visibility = View.GONE
            }

            override fun onFinished() {
                scanning = false
                binding.progressScan.visibility = View.GONE
                binding.btnScan.isEnabled = true
                if (foundAdapter.count == 0) {
                    binding.tvFoundEmpty.text =
                        "Cihaz bulunamadı. TV'nin açık ve telefonla aynı Wi-Fi ağında olduğundan emin olun (TV'de Google Cast / Chromecast yerleşik olmalı)."
                    binding.tvFoundEmpty.visibility = View.VISIBLE
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Yansıtma
    // ------------------------------------------------------------------

    /** Hem "Yansıt" hem "Ekle ve Yansıt" bu akışı kullanır. */
    private fun beginCast(device: TvDevice, saveFirst: Boolean) {
        phoneOnline = net.isPhoneWifiConnected()
        if (!phoneOnline) {
            renderBanner()
            toast("Telefon - Çevrimdışı")
            return
        }
        if (CastState.castingDeviceId.value != null) {
            toast("Önce mevcut yansıtmadan çıkın")
            return
        }
        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) { net.isDeviceReachable(device) }
            if (!reachable) {
                statuses[device.id] = false
                savedAdapter.setStatuses(statuses.toMap(), true)
                renderBanner(extraOffline = listOf(device.name))
                toast("${device.name} - Çevrimdışı")
                return@launch
            }
            if (saveFirst) {
                store.add(device) // SharedPreferences: kalıcı
                statuses[device.id] = true
                loadSavedDevices()
                savedAdapter.setStatuses(statuses.toMap(), true)
            }
            renderBanner()
            pendingDevice = device
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestProjection()
            }
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startCastService(device: TvDevice, resultCode: Int, data: Intent) {
        val i = Intent(this, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_START
            putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCastService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCastService.EXTRA_HOST, device.host)
            putExtra(ScreenCastService.EXTRA_PORT, device.port)
            putExtra(ScreenCastService.EXTRA_DEVICE_ID, device.id)
            putExtra(ScreenCastService.EXTRA_DEVICE_NAME, device.name)
        }
        ContextCompat.startForegroundService(this, i)
    }

    /** "Video / Foto Gönder": dosya seç, TV'ye gönder (Cast butonu gibi, gecikmesiz). */
    private fun beginSendFile(device: TvDevice) {
        phoneOnline = net.isPhoneWifiConnected()
        if (!phoneOnline) {
            renderBanner()
            toast("Telefon - Çevrimdışı")
            return
        }
        if (CastState.castingDeviceId.value != null) {
            toast("Önce mevcut yansıtmadan çıkın")
            return
        }
        pendingFileDevice = device
        fileLauncher.launch(arrayOf("video/*", "image/*", "audio/*"))
    }

    private fun sendFile(device: TvDevice, uri: Uri) {
        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) { net.isDeviceReachable(device) }
            if (!reachable) {
                statuses[device.id] = false
                savedAdapter.setStatuses(statuses.toMap(), true)
                renderBanner(extraOffline = listOf(device.name))
                toast("${device.name} - Çevrimdışı")
                return@launch
            }
            val mime = contentResolver.getType(uri) ?: "video/mp4"
            val i = Intent(this@MainActivity, MediaCastService::class.java).apply {
                action = MediaCastService.ACTION_START
                putExtra(MediaCastService.EXTRA_URI, uri.toString())
                putExtra(MediaCastService.EXTRA_MIME, mime)
                putExtra(MediaCastService.EXTRA_HOST, device.host)
                putExtra(MediaCastService.EXTRA_PORT, device.port)
                putExtra(MediaCastService.EXTRA_DEVICE_ID, device.id)
                putExtra(MediaCastService.EXTRA_DEVICE_NAME, device.name)
            }
            ContextCompat.startForegroundService(this@MainActivity, i)
        }
    }

    /** "Yansıtmadan Çık": TV'deki alıcı kapatılır, TV kendi ekranına döner. */
    private fun stopCast() {
        startService(
            Intent(this, ScreenCastService::class.java).setAction(ScreenCastService.ACTION_STOP)
        )
        startService(
            Intent(this, MediaCastService::class.java).setAction(MediaCastService.ACTION_STOP)
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
