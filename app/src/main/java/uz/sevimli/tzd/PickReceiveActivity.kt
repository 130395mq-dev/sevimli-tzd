package uz.sevimli.tzd

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import uz.sevimli.tzd.databinding.ActivityPickReceiveBinding
import kotlin.concurrent.thread

/**
 * Buyurtmani yig'ish (Подбор). Buyurtma tovarlari kutilgan miqdor bilan ko'rsatiladi;
 * har tovar skan qilinib yig'iladi. «Далее» — MoySklad'da Отгрузка yaratadi (tovar chiqadi).
 */
class PickReceiveActivity : AppCompatActivity() {

    private lateinit var b: ActivityPickReceiveBinding
    private val items = mutableListOf<RecvItem>()
    private var orderId: String = ""

    data class RecvItem(
        val productMoyskladId: String,
        val productType: String,
        val name: String,
        val expected: Double,
        var scanned: Double = 0.0,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPickReceiveBinding.inflate(layoutInflater)
        setContentView(b.root)

        orderId = intent.getStringExtra("order_id") ?: ""
        b.headerName.text = intent.getStringExtra("order_name") ?: "Подбор"
        b.headerCustomer.text = intent.getStringExtra("customer") ?: "—"

        b.btnBack.setOnClickListener { finish() }
        b.btnConfirm.setOnClickListener { confirm() }
        b.scanInput.showSoftInputOnFocus = false

        b.scanInput.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.action == KeyEvent.ACTION_DOWN)
            if (enter) {
                val code = b.scanInput.text.toString().trim()
                if (code.isNotEmpty()) onScan(code)
                b.scanInput.setText("")
                true
            } else false
        }

        loadDetail()
    }

    private fun loadDetail() {
        b.loading.visibility = View.VISIBLE
        thread {
            val result = Api.get(this, "order-detail", mapOf("id" to orderId))
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        val j = result.json
                        b.headerCustomer.text = j.optString("customer")
                        items.clear()
                        val arr = j.optJSONArray("positions") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val p = arr.getJSONObject(i)
                            items.add(RecvItem(
                                p.optString("product_moysklad_id"),
                                p.optString("product_type", "product"),
                                p.optString("name"),
                                p.optDouble("expected_qty", 0.0),
                            ))
                        }
                        renderList()
                    }
                    is ApiResult.Error ->
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onScan(code: String) {
        b.loading.visibility = View.VISIBLE
        thread {
            val result = Api.get(this, "product", mapOf("barcode" to code))
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        val j = result.json
                        if (!j.optBoolean("found", false)) {
                            Toast.makeText(this, "Mahsulot topilmadi", Toast.LENGTH_SHORT).show()
                        } else {
                            matchScan(j)
                        }
                    }
                    is ApiResult.Error ->
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun matchScan(product: JSONObject) {
        val mid = product.optString("moysklad_id")
        val item = items.find { it.productMoyskladId == mid }
        if (item == null) {
            Toast.makeText(this, "Bu tovar buyurtmada yo'q:\n${product.optString("name")}",
                Toast.LENGTH_LONG).show()
            return
        }
        // Blok (upakovka) yoki tarozi og'irligi bo'lsa — o'shancha qo'shiladi
        val packQty = product.optDouble("pack_qty", 0.0)
        val scaleWeight = product.optDouble("scale_weight", 0.0)
        val add = when {
            product.optBoolean("scale", false) && scaleWeight > 0 -> scaleWeight
            product.optBoolean("is_pack", false) && packQty > 0 -> packQty
            else -> 1.0
        }
        item.scanned += add
        Toast.makeText(this, "${item.name}  +${trimNum(add)}", Toast.LENGTH_SHORT).show()
        renderList()
    }

    private fun renderList() {
        b.list.removeAllViews()
        var done = 0
        for (item in items) {
            if (item.scanned > 0) done++
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16f).toInt(), dp(13f).toInt(), dp(16f).toInt(), dp(13f).toInt())
            }
            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameTv = TextView(this).apply {
                text = item.name; textSize = 15f
                setTextColor(getColor(R.color.text_dark))
            }
            val statusTv = TextView(this).apply {
                textSize = 12f
                setPadding(0, dp(2f).toInt(), 0, 0)
                when {
                    item.scanned == 0.0 -> {
                        text = "Kerak: ${trimNum(item.expected)}"
                        setTextColor(getColor(R.color.text_gray))
                    }
                    item.scanned == item.expected -> {
                        text = "✓ To'liq yig'ildi"
                        setTextColor(getColor(R.color.brand))
                    }
                    item.scanned < item.expected -> {
                        text = "⚠ Kam yig'ildi (${trimNum(item.expected - item.scanned)} qoldi)"
                        setTextColor(getColor(R.color.warning))
                    }
                    else -> {
                        text = "⚠ Ortiq (+${trimNum(item.scanned - item.expected)})"
                        setTextColor(getColor(R.color.warning))
                    }
                }
            }
            nameCol.addView(nameTv); nameCol.addView(statusTv)

            val qtyTv = TextView(this).apply {
                text = "${trimNum(item.expected)} / ${trimNum(item.scanned)}"
                textSize = 17f
                setTextColor(getColor(
                    if (item.scanned == item.expected && item.scanned > 0) R.color.brand
                    else R.color.text_dark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            row.addView(nameCol); row.addView(qtyTv)
            row.setOnClickListener { editItem(item) }
            b.list.addView(row)
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(getColor(R.color.card_stroke))
            }
            b.list.addView(div)
        }
        b.progressText.text = "Yig'ilgan: $done / ${items.size}"
    }

    private fun editItem(item: RecvItem) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(trimNum(item.scanned))
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage("Yig'ilgan miqdor (kerak: ${trimNum(item.expected)})")
            .setView(input)
            .setPositiveButton("Saqlash") { _, _ ->
                item.scanned = input.text.toString().toDoubleOrNull() ?: item.scanned
                renderList()
            }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun confirm() {
        val picked = items.filter { it.scanned > 0 }
        if (picked.isEmpty()) {
            Toast.makeText(this, "Hech qanday tovar yig'ilmadi", Toast.LENGTH_SHORT).show(); return
        }
        val notPicked = items.count { it.scanned == 0.0 }
        val diffs = picked.count { it.scanned != it.expected }
        val sb = StringBuilder()
        sb.append("${picked.size} ta tovar chiqariladi (Отгрузка).")
        if (notPicked > 0) sb.append("\n⚠ $notPicked ta tovar yig'ilmadi — ular chiqmaydi.")
        if (diffs > 0) sb.append("\n⚠ $diffs ta tovarda miqdor farqi bor.")
        sb.append("\n\nTovar ombordan chiqadi. Davom etilsinmi?")

        AlertDialog.Builder(this)
            .setTitle("Yig'ishni yakunlash")
            .setMessage(sb.toString())
            .setPositiveButton("Ha, chiqarilsin") { _, _ -> send(picked) }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun send(picked: List<RecvItem>) {
        b.loading.visibility = View.VISIBLE
        val lines = JSONArray()
        for (item in picked) {
            lines.put(JSONObject().apply {
                put("product_moysklad_id", item.productMoyskladId)
                put("product_type", item.productType)
                put("quantity", item.scanned)
            })
        }
        val body = JSONObject().apply {
            put("order_id", orderId)
            put("lines", lines)
        }
        thread {
            val result = Api.post(this, "order-pick", body)
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        val name = result.json.optString("moysklad_name", "Отгрузка")
                        AlertDialog.Builder(this)
                            .setTitle("Yig'ildi ✓")
                            .setMessage("MoySklad: $name")
                            .setPositiveButton("OK") { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                    is ApiResult.Error -> {
                        val msg = if (result.offline)
                            "Internet yo'q. Qayta urinib ko'ring."
                        else result.message
                        AlertDialog.Builder(this)
                            .setTitle("Yuborilmadi")
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onResume() {
        super.onResume()
        b.scanInput.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) b.scanInput.requestFocus()
    }
}
