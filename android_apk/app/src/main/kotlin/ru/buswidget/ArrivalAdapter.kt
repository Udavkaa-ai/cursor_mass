package ru.buswidget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Arrival(
    val route:      String,
    val direction:  String,
    val etaLocal:   String,
    val etaSeconds: Int?,
)

class ArrivalAdapter : RecyclerView.Adapter<ArrivalAdapter.VH>() {

    private var items: List<Arrival> = emptyList()

    fun submit(list: List<Arrival>) { items = list; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val route:     TextView = v.findViewById(R.id.tvRoute)
        val direction: TextView = v.findViewById(R.id.tvDirection)
        val eta:       TextView = v.findViewById(R.id.tvEta)
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
        secs == null -> 0xFF8A8A9A.toInt()  // grey  — scheduled/unknown
        secs <= 0    -> 0xFFD32F2F.toInt()  // red   — arriving now
        secs <= 180  -> 0xFFE53935.toInt()  // red   — < 3 min
        secs <= 300  -> 0xFFF57C00.toInt()  // dark orange — < 5 min
        else         -> 0xFFFF7722.toInt()  // orange — normal
    }
}
