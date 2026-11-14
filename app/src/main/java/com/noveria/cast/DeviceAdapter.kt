package com.noveria.cast

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.noveria.cast.databinding.ItemDeviceBinding
import com.noveria.cast.databinding.ItemFoundDeviceBinding

/** Ana ekrandaki "Cihazlar" bölümü: kayıtlı cihazlar + Yansıt / Cihazı Sil butonları. */
class DeviceAdapter(private val listener: Listener) :
    RecyclerView.Adapter<DeviceAdapter.VH>() {

    interface Listener {
        fun onCastClicked(device: TvDevice)
        fun onStopClicked(device: TvDevice)
        fun onDeleteClicked(device: TvDevice)
    }

    private val items = mutableListOf<TvDevice>()
    private var statuses: Map<String, Boolean> = emptyMap()
    private var phoneOnline = true
    private var castingId: String? = null

    fun setItems(list: List<TvDevice>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setStatuses(map: Map<String, Boolean>, phoneOnline: Boolean) {
        statuses = map
        this.phoneOnline = phoneOnline
        notifyDataSetChanged()
    }

    fun setCastingId(id: String?) {
        castingId = id
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        val b = holder.b
        val casting = castingId == d.id

        b.tvName.text = d.name
        b.tvAddress.text = "${d.host}:${d.port}"

        when {
            casting -> {
                b.tvStatus.text = "Yansıtılıyor"
                b.tvStatus.setTextColor(Color.parseColor("#2E7D32"))
            }
            !phoneOnline -> {
                b.tvStatus.text = "Telefon - Çevrimdışı"
                b.tvStatus.setTextColor(Color.parseColor("#C62828"))
            }
            statuses[d.id] == true -> {
                b.tvStatus.text = "Çevrimiçi"
                b.tvStatus.setTextColor(Color.parseColor("#2E7D32"))
            }
            statuses[d.id] == false -> {
                b.tvStatus.text = "${d.name} - Çevrimdışı"
                b.tvStatus.setTextColor(Color.parseColor("#C62828"))
            }
            else -> {
                b.tvStatus.text = "Kontrol ediliyor..."
                b.tvStatus.setTextColor(Color.parseColor("#757575"))
            }
        }

        b.btnCast.text = if (casting) "Yansıtmadan Çık" else "Yansıt"
        b.btnCast.setOnClickListener {
            if (casting) listener.onStopClicked(d) else listener.onCastClicked(d)
        }
        b.btnDelete.setOnClickListener { listener.onDeleteClicked(d) }
    }
}

/** "Cihaz Tara" sonucunda bulunan cihazların tek seçimli listesi. */
class FoundDeviceAdapter : RecyclerView.Adapter<FoundDeviceAdapter.VH>() {

    private val items = mutableListOf<TvDevice>()
    private var selectedId: String? = null

    val count: Int get() = items.size

    fun add(device: TvDevice) {
        if (items.none { it.id == device.id }) {
            items.add(device)
            notifyItemInserted(items.size - 1)
        }
    }

    fun clear() {
        items.clear()
        selectedId = null
        notifyDataSetChanged()
    }

    fun getSelected(): TvDevice? = items.firstOrNull { it.id == selectedId }

    inner class VH(val b: ItemFoundDeviceBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFoundDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.b.tvName.text = d.name
        holder.b.tvAddress.text = "${d.host}:${d.port}"
        holder.b.rbSelect.isChecked = d.id == selectedId
        holder.b.root.setOnClickListener {
            selectedId = d.id
            notifyDataSetChanged()
        }
    }
}
