package ru.buswidget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Arrival(
    val route: String,
    val direction: String,
    val etaLocal: String,
    val etaSeconds: Int?,
)

class ArrivalAdapter : RecyclerView.Adapter<ArrivalAdapter.VH>() {

    private var items: List<Arrival> = emptyList()

    fun submit(list: List<Arrival>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val route: TextView     = v.findViewById(R.id.tvRoute)
        val direction: TextView = v.findViewById(R.id.tvDirection)
        val eta: TextView       = v.findViewById(R.id.tvEta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_arrival, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.route.text     = a.route
        holder.direction.text = a.direction
        holder.eta.text       = a.etaLocal
        holder.eta.setTextColor(etaColor(a.etaSeconds))
    }

    private fun etaColor(secs: Int?): Int = when {
        secs == null -> 0xFF7EA0FF.toInt()  // синий  — по расписанию
        secs <= 180  -> 0xFFFF4D4D.toInt()  // красный — < 3 мин
        secs <= 300  -> 0xFFFFAA33.toInt()  // оранжевый — < 5 мин
        secs <= 420  -> 0xFF42D883.toInt()  // зелёный — < 7 мин
        else         -> 0xFFF4F4F6.toInt()  // белый
    }
}
