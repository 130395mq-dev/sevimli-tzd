package uz.sevimli.tzd

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Ko'chirishni qabul qilish ro'yxati:
 *   tartib raqami · nom / holat · "kutilgan / kelgan"
 *
 * DocRowAdapter bilan bir xil sababdan RecyclerView'ga o'tkazildi (o'sha
 * faylning izohiga qarang) — bu ekranda ham har skandan keyin butun ro'yxat
 * qayta chizilardi, kelgan ko'chirishda esa yuzlab qator bo'lishi mumkin.
 */
class RecvRowAdapter(
    private val onClick: (position: Int) -> Unit,
) : RecyclerView.Adapter<RecvRowAdapter.VH>() {

    /**
     * Bitta qatorning ko'rinishi. Rang RESURS ID sifatida beriladi —
     * adapter Activity'ga bog'lanmasin.
     */
    data class Row(
        val num: String,
        val name: String,
        val status: String,
        val statusColor: Int,
        val qty: String,
        val qtyColor: Int,
        val numColor: Int,
        val numBold: Boolean,
    )

    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    class VH(item: View) : RecyclerView.ViewHolder(item) {
        val body: View = item.findViewById(R.id.rowBody)
        val num: TextView = item.findViewById(R.id.rowNum)
        val name: TextView = item.findViewById(R.id.rowName)
        val status: TextView = item.findViewById(R.id.rowStatus)
        val qty: TextView = item.findViewById(R.id.rowQty)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recv_row, parent, false)
        val vh = VH(v)
        vh.body.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        val ctx = holder.itemView.context
        holder.num.text = r.num
        holder.num.setTextColor(ContextCompat.getColor(ctx, r.numColor))
        // DIQQAT: qayta ishlatilgan View eski qalinligini saqlab qolmasin —
        // shuning uchun har ikki holat ham ANIQ o'rnatiladi.
        holder.num.setTypeface(null, if (r.numBold) Typeface.BOLD else Typeface.NORMAL)
        holder.name.text = r.name
        holder.status.text = r.status
        holder.status.setTextColor(ContextCompat.getColor(ctx, r.statusColor))
        holder.qty.text = r.qty
        holder.qty.setTextColor(ContextCompat.getColor(ctx, r.qtyColor))
        holder.qty.setTypeface(null, Typeface.BOLD)
    }

    override fun getItemCount(): Int = rows.size
}
