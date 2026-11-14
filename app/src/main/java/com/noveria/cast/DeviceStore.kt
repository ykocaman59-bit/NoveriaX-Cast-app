package com.noveria.cast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Kayıtlı cihazları SharedPreferences içinde kalıcı tutar. */
class DeviceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("noveriax_cast_prefs", Context.MODE_PRIVATE)

    fun getAll(): List<TvDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TvDevice(
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.optInt("port", CastConst.CAST_PORT),
                    type = o.optString("type", TvDevice.TYPE_CAST),
                    location = o.optString("location", ""),
                    controlUrl = o.optString("controlUrl", ""),
                    serviceType = o.optString("serviceType", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(device: TvDevice) {
        save(getAll().filter { it.id != device.id } + device)
    }

    fun remove(id: String) {
        save(getAll().filter { it.id != id })
    }

    private fun save(list: List<TvDevice>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("host", it.host)
                    .put("port", it.port)
                    .put("type", it.type)
                    .put("location", it.location)
                    .put("controlUrl", it.controlUrl)
                    .put("serviceType", it.serviceType)
            )
        }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    private companion object {
        const val KEY_DEVICES = "devices"
    }
}
