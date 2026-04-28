package ru.buswidget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.buswidget.data.Stop

class StopAdapter(
    private val onClick:  (Stop) -> Unit,
    private val onEdit:   (Stop) -> Unit,
    private val onDelete: (Stop) -> Unit,
) : RecyclerView.Adapter<StopAdapter.VH>() {

    private var items: List<Stop> = emptyList()

    fun submit(list: List<Stop>) { items = list; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name:    TextView = v.findViewById(R.id.tvStopName)
        val routes:  TextView = v.findViewById(R.id.tvRoutes)
        val btnEdit: View     = v.findViewById(R.id.btnEdit)
        val btnDel:  View     = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_stop, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.name.text   = s.name.ifBlank { s.id }
        holder.routes.text = if (s.routes.isBlank()) "все маршруты" else s.routes
        holder.itemView.setOnClickListener { onClick(s) }
        holder.btnEdit.setOnClickListener  { onEdit(s) }
        holder.btnDel.setOnClickListener   { onDelete(s) }
    }
}
