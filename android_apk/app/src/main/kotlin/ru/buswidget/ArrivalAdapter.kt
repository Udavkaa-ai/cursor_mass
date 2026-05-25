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
        val route: TextView = v.findViewById(R.id.tvRoute)
        val dir:   TextView = v.findViewById(R.id.tvDirection)
        val eta:   TextView = v.findViewById(R.id.tvEta)
        val unit:  TextView = v.findViewById(R.id.tvEtaUnit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_arrival, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.route.text = a.route
        holder.dir.text   = a.direction

        val secs = a.etaSeconds
        when {
            secs == null -> { holder.eta.text = a.etaLocal; holder.unit.text = "" }
            secs <= 0    -> { holder.eta.text = "→";        holder.unit.text = "сейчас" }
            secs < 60    -> { holder.eta.text = "< 1";      holder.unit.text = "мин" }
            else         -> { holder.eta.text = (secs / 60).toString(); holder.unit.text = "мин" }
        }
        holder.eta.setTextColor(etaColor(secs))
    }

    private fun etaColor(secs: Int?): Int = when {
        secs == null -> 0xFF7878A0.toInt()  // grey   — scheduled/unknown
        secs <= 0    -> 0xFFD32F2F.toInt()  // red    — arriving now
        secs <= 300  -> 0xFFE53935.toInt()  // red    — < 5 min
        secs <= 420  -> 0xFFFF7722.toInt()  // orange — 5-7 min
        secs <= 600  -> 0xFF2ECC71.toInt()  // green  — 7-10 min
        else         -> 0xFF7878A0.toInt()  // grey   — > 10 min
    }
}
