package uz.sevimli.tzd

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
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
        b.btnRefresh.setOnClickListener { load() }

        val createTarget = when (type) {
            "supply" -> SupplyActivity::class.java
            "inventory" -> InventoryActivity::class.java
            "shipment" -> ShipmentActivity::class.java
            "writeoff" -> WriteoffActivity::class.java
            else -> null
        }
        if (createTarget != null) {
            b.btnNew.visibility = View.VISIBLE
            b.btnNewText.text = "＋  Yangi hujjat yaratish"
            b.btnNew.setOnClickListener { startActivity(Intent(this, createTarget)) }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        b.loading.visibility = View.VISIBLE
        b.emptyHint.visibility = View.GONE
        b.list.removeAllViews()
        val query = if (type.isEmpty()) emptyMap() else mapOf("type" to type)
        thread {
            val result = Api.get(this, "documents", query)
            runOnUiThread {
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
            b.list.addView(buildCard(arr.getJSONObject(i)))
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

        if (isError && tcode.isNotEmpty()) {
            card.isClickable = true
            card.foreground = getDrawable(android.R.drawable.list_selector_background)
            card.setOnClickListener { confirmRetry(tcode, id, name, errorText) }
        }
        return card
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
        b.loading.visibility = View.VISIBLE
        val body = JSONObject().put("type", tcode).put("id", id)
        thread {
            val r = Api.post(this, "retry-document", body)
            runOnUiThread {
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
