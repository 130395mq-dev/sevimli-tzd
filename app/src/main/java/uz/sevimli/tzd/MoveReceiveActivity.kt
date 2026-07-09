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
import uz.sevimli.tzd.databinding.ActivityMoveReceiveBinding
import kotlin.concurrent.thread

/**
 * Kelgan перемещениени qabul qilish. Dokument tarkibi ko'rsatiladi (kutilgan
 * miqdor), har tovar skan qilinib haqiqiy kelgan soni yig'iladi. Farq ko'rinadi.
 * «Далее» — MoySklad'da haqiqiy miqdorlar bilan o'tkazadi (проведён).
 */
class MoveReceiveActivity : AppCompatActivity() {

    private lateinit var b: ActivityMoveReceiveBinding
    private val items = mutableListOf<RecvItem>()
    private var moveId: String = ""

    data class RecvItem(
        val productMoyskladId: String,
        val productType: String,
        val name: String,
        val expected: Double,
        var scanned: Double = 0.0,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMoveReceiveBinding.inflate(layoutInflater)
        setContentView(b.root)

        moveId = intent.getStringExtra("move_id") ?: ""
        b.headerName.text = intent.getStringExtra("move_name") ?: "Перемещение"

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
            val result = Api.get(this, "move-detail", mapOf("id" to moveId))
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        val j = result.json
                        b.headerRoute.text = "${j.optString("source_store")} → ${j.optString("target_store")}"
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
            Toast.makeText(this, "Bu tovar dokumentda yo'q:\n${product.optString("name")}",
                Toast.LENGTH_LONG).show()
            return
        }
        // Blok (upakovka) skanlansa — ichidagi dona soni qo'shiladi
        val packQty = product.optDouble("pack_qty", 0.0)
        val add = if (product.optBoolean("is_pack", false) && packQty > 0) packQty else 1.0
        item.scanned += add
        Toast.makeText(this, "${item.name}  +${trimNum(add)}", Toast.LENGTH_SHORT).show()
        renderList()
    }

    private fun renderList() {
        b.list.removeAllViews()
        var checked = 0
        for (item in items) {
            if (item.scanned > 0) checked++
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
                        text = "Kutilyapti · ${trimNum(item.expected)} dona"
                        setTextColor(getColor(R.color.text_gray))
                    }
                    item.scanned == item.expected -> {
                        text = "✓ To'liq keldi"
                        setTextColor(getColor(R.color.brand))
                    }
                    item.scanned < item.expected -> {
                        text = "⚠ Kam keldi (${trimNum(item.expected - item.scanned)} yetmadi)"
                        setTextColor(getColor(R.color.warning))
                    }
                    else -> {
                        text = "⚠ Ortiq keldi (+${trimNum(item.scanned - item.expected)})"
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
        b.progressText.text = "Tekshirilgan: $checked / ${items.size}"
    }

    private fun editItem(item: RecvItem) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(trimNum(item.scanned))
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage("Haqiqiy kelgan miqdor (kutilgan: ${trimNum(item.expected)})")
            .setView(input)
            .setPositiveButton("Saqlash") { _, _ ->
                item.scanned = input.text.toString().toDoubleOrNull() ?: item.scanned
                renderList()
            }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun confirm() {
        val scannedItems = items.filter { it.scanned > 0 }
        if (scannedItems.isEmpty()) {
            Toast.makeText(this, "Hech qanday tovar skan qilinmadi", Toast.LENGTH_SHORT).show(); return
        }
        val notScanned = items.count { it.scanned == 0.0 }
        val diffs = scannedItems.count { it.scanned != it.expected }
        val sb = StringBuilder()
        sb.append("${scannedItems.size} ta tovar qabul qilinadi.")
        if (notScanned > 0) sb.append("\n⚠ $notScanned ta tovar skan qilinmadi — ular qabul qilinmaydi.")
        if (diffs > 0) sb.append("\n⚠ $diffs ta tovarda miqdor farqi bor.")
        sb.append("\n\nMoySklad'ga o'tkazilsinmi?")

        AlertDialog.Builder(this)
            .setTitle("Qabul qilish")
            .setMessage(sb.toString())
            .setPositiveButton("Ha, qabul qilaman") { _, _ -> send(scannedItems) }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun send(scannedItems: List<RecvItem>) {
        b.loading.visibility = View.VISIBLE
        val lines = JSONArray()
        for (item in scannedItems) {
            lines.put(JSONObject().apply {
                put("product_moysklad_id", item.productMoyskladId)
                put("product_type", item.productType)
                put("quantity", item.scanned)
            })
        }
        val body = JSONObject().apply {
            put("move_id", moveId)
            put("lines", lines)
        }
        thread {
            val result = Api.post(this, "move-confirm", body)
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        val name = result.json.optString("moysklad_name", "Перемещение")
                        AlertDialog.Builder(this)
                            .setTitle("Qabul qilindi ✓")
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
                            .setTitle("Qabul qilinmadi")
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
