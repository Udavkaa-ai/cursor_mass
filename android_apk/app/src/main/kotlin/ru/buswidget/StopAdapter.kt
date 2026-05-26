package ru.buswidget

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.buswidget.data.Stop
import java.net.HttpURLConnection
import java.net.URL

class StopAdapter(
    private val onClick:  (Stop) -> Unit,
    private val onEdit:   (Stop) -> Unit,
    private val onDelete: (Stop) -> Unit,
) : RecyclerView.Adapter<StopAdapter.VH>() {

    private var items: List<Stop> = emptyList()

    fun submit(list: List<Stop>) { items = list; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name:    TextView  = v.findViewById(R.id.tvStopName)
        val routes:  TextView  = v.findViewById(R.id.tvRoutes)
        val ivMap:   ImageView = v.findViewById(R.id.ivMap)
        val btnEdit: View      = v.findViewById(R.id.btnEdit)
        val btnDel:  View      = v.findViewById(R.id.btnDelete)

        init { ivMap.clipToOutline = true }
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

        if (s.lat != 0.0 && s.lon != 0.0) {
            holder.ivMap.visibility = View.VISIBLE
            val url = "https://static-maps.yandex.ru/1.x/?ll=${s.lon},${s.lat}&z=16&size=200,200&l=map&pt=${s.lon},${s.lat},pm2rdm"
            holder.ivMap.tag = url
            holder.ivMap.setImageBitmap(null)
            Thread {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6_000; conn.readTimeout = 6_000; conn.connect()
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    if (bmp != null) {
                        val view = holder.ivMap
                        if (view.tag == url) view.post { view.setImageBitmap(bmp) }
                    }
                } catch (_: Exception) {}
            }.start()
        } else {
            holder.ivMap.visibility = View.GONE
            holder.ivMap.setImageBitmap(null)
        }
    }
}
