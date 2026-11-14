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
                TvDevice(o.getString("name"), o.getString("host"), o.getInt("port"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(device: TvDevice) {
        val list = getAll().filter { it.id != device.id } + device
        save(list)
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
            )
        }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    private companion object {
        const val KEY_DEVICES = "devices"
    }
}
