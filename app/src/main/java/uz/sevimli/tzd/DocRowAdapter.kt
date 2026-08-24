package uz.sevimli.tzd

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Hujjat qatorlari ro'yxati: nom (chapda) + son (o'ngda).
 *
 * NEGA KERAK EDI:
 * Ilgari har skandan keyin `renderList()` butun ro'yxatni O'CHIRIB, boshidan
 * qayta chizardi. 200 qatorli приёмкада bu — 200 ta qator uchun 600 ta View
 * yaratish, va bu ASOSIY (UI) oqimda. Ya'ni har skandan keyin ekran
 * sezilarli muddat qotardi va bu ro'yxat uzaygani sari yomonlashardi.
 *
 * RecyclerView faqat EKRANDA KO'RINIB TURGAN qatorlarni chizadi (~10 ta) va
 * pastga surilganda o'sha View'larni QAYTA ISHLATADI. Ya'ni endi ro'yxatda
 * 10 ta qator bormi yoki 2000 tami — skandan keyingi ish hajmi bir xil.
 *
 * Bitta adapter 8 ta ekranda ishlatiladi (Приёмка, Отгрузка, Инвентаризация,
 * Перемещение, Списание, ikkala Возврат, Этикетка) — chunki ularning
 * qatori aynan bir xil edi.
 */
class DocRowAdapter(
    /** Qator bosilganda chaqiriladi (ro'yxatdagi o'rni bilan). */
    private val onClick: (position: Int) -> Unit,
) : RecyclerView.Adapter<DocRowAdapter.VH>() {

    /** Ekranda ko'rinadigan bitta qator — faqat MATN, model emas. */
    data class Row(val name: String, val qty: String)

    private var rows: List<Row> = emptyList()

    /** Ro'yxatni yangilaydi. */
    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    class VH(item: View) : RecyclerView.ViewHolder(item) {
        val body: View = item.findViewById(R.id.rowBody)
        val name: TextView = item.findViewById(R.id.rowName)
        val qty: TextView = item.findViewById(R.id.rowQty)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_doc_row, parent, false)
        val vh = VH(v)
        // Tinglovchi BIR MARTA — har bog'lanishda emas (qayta ishlatish uchun).
        vh.body.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.name.text = row.name
        holder.qty.text = row.qty
    }

    override fun getItemCount(): Int = rows.size
}
