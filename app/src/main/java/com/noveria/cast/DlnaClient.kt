package com.noveria.cast

import java.net.URL

/** TV'ye UPnP AVTransport SOAP komutları gönderir: yayın adresini ver, oynat, durdur. */
class DlnaClient(private val net: NetworkManager) {

    /** Başarılıysa null, değilse kullanıcıya gösterilecek hata metni döner. */
    fun play(device: TvDevice, url: String, mime: String, live: Boolean): String? {
        soap(device, "Stop", "<InstanceID>0</InstanceID>") // eski oynatmayı temizle

        val r1 = soap(
            device, "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>${esc(url)}</CurrentURI>" +
                    "<CurrentURIMetaData>${esc(didl(url, mime, live))}</CurrentURIMetaData>"
        )
        if (r1 == null) return "TV'ye komut gönderilemedi. TV'nin açık ve aynı Wi-Fi'da olduğundan emin olun."
        if (r1.first !in 200..299) return "TV yayın adresini kabul etmedi (kod ${r1.first})."

        val r2 = soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        if (r2 == null || r2.first !in 200..299) {
            return "TV oynatmayı başlatamadı (kod ${r2?.first ?: "bağlantı yok"})."
        }
        return null
    }

    fun stop(device: TvDevice) {
        soap(device, "Stop", "<InstanceID>0</InstanceID>")
    }

    fun transportState(device: TvDevice): String? {
        val r = soap(device, "GetTransportInfo", "<InstanceID>0</InstanceID>") ?: return null
        return Regex("<CurrentTransportState>(.*?)</CurrentTransportState>", RegexOption.IGNORE_CASE)
            .find(r.second)?.groupValues?.get(1)?.trim()
    }

    private fun soap(device: TvDevice, action: String, args: String): Pair<Int, String>? {
        return try {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                    "<u:$action xmlns:u=\"${device.serviceType}\">$args</u:$action>" +
                    "</s:Body></s:Envelope>"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val c = net.openConnection(URL(device.controlUrl))
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"${device.serviceType}#$action\"")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            code to text
        } catch (e: Exception) {
            null
        }
    }

    private fun didl(url: String, mime: String, live: Boolean): String {
        val cls = when {
            mime.startsWith("image/") -> "object.item.imageItem"
            mime.startsWith("audio/") -> "object.item.audioItem"
            else -> "object.item.videoItem"
        }
        val op = if (live) "00" else "01" // dosya sunucusu Range destekler
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>NoveriaX Cast</dc:title>" +
                "<upnp:class>$cls</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mime:DLNA.ORG_OP=$op;DLNA.ORG_CI=0;" +
                "DLNA.ORG_FLAGS=01700000000000000000000000000000\">${esc(url)}</res>" +
                "</item></DIDL-Lite>"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
