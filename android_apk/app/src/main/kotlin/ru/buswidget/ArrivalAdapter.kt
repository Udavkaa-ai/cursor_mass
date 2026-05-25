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
        val accent: View     = v.findViewById(R.id.vAccent)
        val route:  TextView = v.findViewById(R.id.tvRoute)
        val dir:    TextView = v.findViewById(R.id.tvDirection)
        val eta:    TextView = v.findViewById(R.id.tvEta)
        val unit:   TextView = v.findViewById(R.id.tvEtaUnit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_arrival, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.route.text = a.route
        holder.dir.text   = a.direction

        val secs  = a.etaSeconds
        val color = etaColor(secs)

        holder.accent.setBackgroundColor(color)
        holder.eta.setTextColor(color)
        holder.unit.setTextColor(color)

        when {
            secs == null -> { holder.eta.text = a.etaLocal; holder.unit.text = "" }
            secs <= 0    -> { holder.eta.text = "→";        holder.unit.text = "СЕЙЧАС" }
            secs < 60    -> { holder.eta.text = "< 1";      holder.unit.text = "МИН" }
            else         -> { holder.eta.text = (secs / 60).toString(); holder.unit.text = "МИН" }
        }
    }

    fun etaColor(secs: Int?): Int = when {
        secs == null -> 0xFF7878A0.toInt()  // grey   — unknown
        secs <= 0    -> 0xFFE53040.toInt()  // red    — arriving now
        secs < 300   -> 0xFFE53040.toInt()  // red    — < 5 min
        secs < 480   -> 0xFFFF8C00.toInt()  // orange — 5-8 min
        secs < 660   -> 0xFF2ED87A.toInt()  // green  — 8-11 min
        else         -> 0xFF7878A0.toInt()  // grey   — > 11 min
    }
}
