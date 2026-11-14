package com.noveria.cast

import android.content.Intent

/**
 * Ağda bulunan / kaydedilen TV.
 * type = "cast": Google Cast (Chromecast yerleşik) | type = "dlna": DLNA/UPnP MediaRenderer
 */
data class TvDevice(
    val name: String,
    val host: String,
    val port: Int,
    val type: String = TYPE_CAST,
    val location: String = "",     // DLNA: cihaz tanım dosyası adresi
    val controlUrl: String = "",   // DLNA: AVTransport SOAP adresi
    val serviceType: String = ""   // DLNA: ör. urn:schemas-upnp-org:service:AVTransport:1
) {
    val id: String get() = if (type == TYPE_DLNA) location else "$host:$port"

    val addressLabel: String
        get() = if (type == TYPE_DLNA) "$host  (DLNA)" else "$host:$port  (Google Cast)"

    companion object {
        const val TYPE_CAST = "cast"
        const val TYPE_DLNA = "dlna"
    }
}

/** Cihazı servislere taşımak için ortak Intent anahtarları. */
fun Intent.putDevice(d: TvDevice): Intent {
    putExtra("host", d.host)
    putExtra("port", d.port)
    putExtra("device_id", d.id)
    putExtra("device_name", d.name)
    putExtra("device_type", d.type)
    putExtra("device_location", d.location)
    putExtra("device_control", d.controlUrl)
    putExtra("device_service", d.serviceType)
    return this
}

fun Intent.getDevice(): TvDevice? {
    val host = getStringExtra("host") ?: return null
    return TvDevice(
        name = getStringExtra("device_name") ?: "TV",
        host = host,
        port = getIntExtra("port", CastConst.CAST_PORT),
        type = getStringExtra("device_type") ?: TvDevice.TYPE_CAST,
        location = getStringExtra("device_location") ?: "",
        controlUrl = getStringExtra("device_control") ?: "",
        serviceType = getStringExtra("device_service") ?: ""
    )
}
