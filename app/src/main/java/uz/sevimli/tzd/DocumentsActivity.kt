package uz.sevimli.tzd

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import org.json.JSONObject
import uz.sevimli.tzd.databinding.ActivityDocumentsBinding
import kotlin.concurrent.thread

/**
 * Bitta funksiyaning hujjatlar ro'yxati. Xato bilan qolgan hujjat qizil ko'rinadi
 * va bosilganda MoySklad'ga qaytadan yuborishga uriniladi.
 */
class DocumentsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDocumentsBinding
    private var type: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDocumentsBinding.inflate(layoutInflater)
        setContentView(b.root)

        type = intent.getStringExtra("type") ?: ""
        val title = intent.getStringExtra("title") ?: "Hujjatlar"
        b.headerTitle.text = title
        b.headerDate.text = Config.storeName(this) ?: ""

        b.btnBack.setOnClickListener { finish() }
        // Ikki marta bosilsa ikkita so'rov ketmasin
        b.btnRefresh.setOnClickListener { if (!loading) load() }

        val createTarget = when (type) {
            "supply" -> SupplyActivity::class.java
            "inventory" -> InventoryActivity::class.java
            "shipment" -> ShipmentActivity::class.java
            "writeoff" -> WriteoffActivity::class.java
            "preturn" -> PurchaseReturnActivity::class.java
            "sreturn" -> SalesReturnActivity::class.java
            else -> null
        }
        if (createTarget != null) {
            b.btnNew.visibility = View.VISIBLE
            b.btnNewText.text = "＋  Yangi hujjat yaratish"
            // ILGARI: tez ikki marta bosilsa IKKITA hujjat ekrani ochilardi,
            // har biri o'z raqami bilan — natijada ikkita haqiqiy hujjat.
            b.btnNew.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastNewAt < 1000L) return@setOnClickListener
                lastNewAt = now
                loadedAt = 0L    // qaytganda ro'yxat albatta yangilanadi
                startActivity(Intent(this, createTarget))
            }
        }
    }

    /** Oxirgi "Yangi hujjat" bosilgan vaqt — takror ochilmasligi uchun. */
    private var lastNewAt = 0L

    /** Ro'yxat yuklanyaptimi — takror so'rov ketmasligi uchun. */
    @Volatile private var loading = false

    /** Ro'yxat oxirgi marta qachon olingan. */
    private var loadedAt = 0L

    override fun onResume() {
        super.onResume()
        // ILGARI: ekranga har qaytishda (hujjat oynasini yopganda ham)
        // ro'yxat qaytadan serverdan olinardi. Endi 20 soniya ichida
        // qayta so'ralmaydi — "Yangilash" tugmasi doim ishlaydi.
        if (System.currentTimeMillis() - loadedAt > 20_000L) load()
    }

    private fun load() {
        if (loading) return
        loading = true
        b.loading.visibility = View.VISIBLE
        b.emptyHint.visibility = View.GONE
        b.list.removeAllViews()
        val query = if (type.isEmpty()) emptyMap() else mapOf("type" to type)
        thread {
            val result = Api.get(this, "documents", query)
            runOnUiThread {
                loading = false
                // Vaqt belgisi FAQAT muvaffaqiyatda yangilanadi — aks holda
                // xato bo'lgan ro'yxat 20 soniya qayta so'ralmasdi.
                if (result is ApiResult.Success) loadedAt = System.currentTimeMillis()
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> render(result.json)
                    is ApiResult.Error -> {
                        b.emptyHint.text = result.message
                        b.emptyHint.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun render(json: JSONObject) {
        b.list.removeAllViews()
        val arr = json.optJSONArray("documents")
        if (arr == null || arr.length() == 0) {
            b.emptyHint.text = "Hali hujjat yo'q"
            b.emptyHint.visibility = View.VISIBLE
            return
        }
        for (i in 0 until arr.length()) {
            val doc = arr.optJSONObject(i) ?: continue
            b.list.addView(buildCard(doc))
        }
    }

    private fun buildCard(d: JSONObject): View {
        val type = d.optString("type")
        val name = d.optString("name")
        val status = d.optString("status")
        val statusCode = d.optString("status_code")
        val date = d.optString("date")
        val time = d.optString("time")
        val qty = d.optDouble("qty", 0.0)
        val id = d.optInt("id")
        val tcode = d.optString("tcode")
        val errorText = d.optString("error")
        val isError = statusCode == "error"

        val card = CardView(this).apply {
            radius = dp(16f); cardElevation = 0f
            setCardBackgroundColor(getColor(if (isError) R.color.brand_tint else R.color.white))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10f).toInt()
            layoutParams = lp
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = TextView(this).apply {
            text = type; textSize = 12f
            setTextColor(getColor(R.color.brand_dark))
            setBackgroundResource(R.drawable.bg_chip)
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand_tint))
            setPadding(dp(9f).toInt(), dp(3f).toInt(), dp(9f).toInt(), dp(3f).toInt())
        }
        val dateTv = TextView(this).apply {
            text = "  $date · $time"; textSize = 12f
            setTextColor(getColor(R.color.text_gray))
        }
        topRow.addView(badge); topRow.addView(dateTv)

        val nameTv = TextView(this).apply {
            text = name; textSize = 16f
            setTextColor(getColor(R.color.text_dark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6f).toInt(), 0, 0)
        }
        val statusTv = TextView(this).apply {
            text = if (isError) "⚠ $status · qayta yuborish uchun bosing"
                   else "$status · jami ${trimNum(qty)}"
            textSize = 13f
            setTextColor(getColor(when (statusCode) {
                "synced" -> R.color.green_ok
                "error" -> R.color.brand_soft
                else -> R.color.amber_wait
            }))
            setPadding(0, dp(3f).toInt(), 0, 0)
        }
        col.addView(topRow); col.addView(nameTv); col.addView(statusTv)
        root.addView(col)
        card.addView(root)

        // Har qanday hujjatni bosib — ichini (tovarlar ro'yxatini) ochib ko'rish mumkin
        if (tcode.isNotEmpty()) {
            card.isClickable = true
            card.foreground = getDrawable(android.R.drawable.list_selector_background)
            card.setOnClickListener { openDetail(tcode, id, name, statusCode, errorText) }
        }
        return card
    }

    /** Hujjatni ochib ichidagi tovarlarni ko'rsatadi (faqat ko'rish). */
    private fun openDetail(tcode: String, id: Int, name: String, statusCode: String, errorText: String) {
        b.loading.visibility = View.VISIBLE
        thread {
            val r = Api.get(this, "document-detail", mapOf("type" to tcode, "id" to id.toString()))
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (r) {
                    is ApiResult.Success -> showDetailDialog(tcode, id, statusCode, name, r.json)
                    is ApiResult.Error -> Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showDetailDialog(tcode: String, id: Int, statusCode: String, name: String, json: JSONObject) {
        val lines = json.optJSONArray("lines")
        val total = json.optDouble("total", 0.0)
        val errorText = json.optString("error")
        val isError = statusCode == "error"
        val cnt = lines?.length() ?: 0

        val pad = dp(16f).toInt()
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(12f).toInt(), pad, 0)
        }
        outer.addView(TextView(this).apply {
            text = "Jami: $cnt tovar · ${trimNum(total)} dona"
            textSize = 13f
            setTextColor(getColor(R.color.text_gray))
            setPadding(0, 0, 0, dp(8f).toInt())
        })
        if (isError && errorText.isNotBlank()) {
            outer.addView(TextView(this).apply {
                text = "⚠ Bu hujjat MoySklad'ga o'tmagan. Qizil bilan belgilangan tovar muammo bergan."
                textSize = 12.5f
                setTextColor(android.graphics.Color.parseColor("#B91C1C"))
                setBackgroundColor(android.graphics.Color.parseColor("#FEF2F2"))
                setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            })
        }

        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10f).toInt(), 0, dp(8f).toInt())
        }
        val editable = json.optBoolean("editable", false)
        var dlg: AlertDialog? = null
        if (lines != null) {
            for (i in 0 until lines.length()) {
                val lnObj = lines.optJSONObject(i) ?: continue
                listCol.addView(detailRow(lnObj, i + 1, editable) { lineId, lineName ->
                    removeLine(tcode, id, lineId, lineName) {
                        dlg?.dismiss()
                        openDetail(tcode, id, name, statusCode, "")
                    }
                })
            }
        }
        val scroll = ScrollView(this).apply { addView(listCol) }
        outer.addView(scroll)
        if (editable) {
            outer.addView(TextView(this).apply {
                text = "Keraksiz yoki xato tovarni 🗑 bilan olib tashlab, keyin qayta yuboring."
                textSize = 12f
                setTextColor(getColor(R.color.text_gray))
                setPadding(0, dp(4f).toInt(), 0, dp(8f).toInt())
            })
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (name.isNotBlank()) name else "Hujjat tarkibi")
            .setView(outer)
            .setPositiveButton("Yopish", null)
        if (isError && tcode.isNotEmpty()) {
            builder.setNeutralButton("Qayta yuborish") { _, _ -> doRetry(tcode, id) }
        }
        val d = builder.create()
        dlg = d
        d.show()
    }

    private fun detailRow(ln: JSONObject, num: Int, editable: Boolean, onRemove: (Int, String) -> Unit): View {
        val problem = ln.optBoolean("problem", false)
        val name = ln.optString("name")
        val barcode = ln.optString("barcode")
        val qty = ln.optDouble("qty", 0.0)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6f).toInt()
            layoutParams = lp
            setBackgroundColor(
                if (problem) android.graphics.Color.parseColor("#FEE2E2")
                else android.graphics.Color.parseColor("#F8F8FB"))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = "$num. $name"
            textSize = 15f
            setTextColor(getColor(R.color.text_dark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (barcode.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = barcode
                textSize = 12f
                setTextColor(getColor(R.color.text_gray))
            })
        }
        if (problem) {
            col.addView(TextView(this).apply {
                text = "⚠ Shu tovar muammo bergan (MoySklad'da topilmadi)"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#B91C1C"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        val qtyTv = TextView(this).apply {
            text = trimNum(qty)
            textSize = 16f
            setTextColor(
                if (problem) android.graphics.Color.parseColor("#B91C1C")
                else getColor(R.color.text_dark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(col)
        row.addView(qtyTv)
        if (editable) {
            val rm = TextView(this).apply {
                text = "🗑"
                textSize = 19f
                setPadding(dp(14f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
                isClickable = true
                setOnClickListener { onRemove(ln.optInt("line_id"), ln.optString("name")) }
            }
            row.addView(rm)
        }
        return row
    }

    /** Yuborilmagan hujjatdan bitta tovarni o'chiradi (tasdiqlab). */
    private fun removeLine(tcode: String, docId: Int, lineId: Int, name: String, after: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Tovarni o'chirish")
            .setMessage("«$name» ni hujjatdan olib tashlaymizmi?")
            .setPositiveButton("O'chirish") { _, _ ->
                b.loading.visibility = View.VISIBLE
                val body = JSONObject().put("type", tcode).put("id", docId).put("line_id", lineId)
                thread {
                    val r = Api.post(this, "document-remove-line", body)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        b.loading.visibility = View.GONE
                        when (r) {
                            is ApiResult.Success -> {
                                Toast.makeText(this, "O'chirildi", Toast.LENGTH_SHORT).show()
                                after()
                            }
                            is ApiResult.Error ->
                                Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun confirmRetry(tcode: String, id: Int, name: String, error: String) {
        val msg = (if (error.isNotBlank()) "Xato: $error\n\n" else "") +
                "«$name» hujjatini MoySklad'ga qaytadan yuboraylikmi?"
        AlertDialog.Builder(this)
            .setTitle("Qayta yuborish")
            .setMessage(msg)
            .setPositiveButton("Qayta yuborish") { _, _ -> doRetry(tcode, id) }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun doRetry(tcode: String, id: Int) {
        if (loading) return          // allaqachon so'rov ketyapti
        loading = true
        b.loading.visibility = View.VISIBLE
        val body = JSONObject().put("type", tcode).put("id", id)
        thread {
            val r = Api.post(this, "retry-document", body)
            runOnUiThread {
                loading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (r) {
                    is ApiResult.Success -> {
                        Toast.makeText(this, "Yuborildi ✓", Toast.LENGTH_SHORT).show()
                        load()
                    }
                    is ApiResult.Error ->
                        Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
