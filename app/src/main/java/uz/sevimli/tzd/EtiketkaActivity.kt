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
import uz.sevimli.tzd.databinding.ActivityEtiketkaBinding
import kotlin.concurrent.thread

/**
 * Этикетка / Ценник chop etish.
 * Mahsulot skanerlanadi -> nechta nusxa so'raladi -> ro'yxatga qo'shiladi.
 * Yuqorida printer tanlanadi (Godex 40*58 / Xprinter 30*20 / Ulgurji).
 * "Chop et" bosilganda backendga navbatga qo'yiladi; kompyuterdagi agent
 * MoySklad'ning HAQIQIY ценник PDF'ini printerga bosadi.
 */
class EtiketkaActivity : AppCompatActivity() {

    private lateinit var b: ActivityEtiketkaBinding

    // printer kodi: "godex" | "xprinter" | "opt"
    private var printer = "godex"

    data class Lab(val barcode: String, val moyskladId: String, val name: String, var count: Int)
    private val items = mutableListOf<Lab>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEtiketkaBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.pGodex.setOnClickListener { setPrinter("godex") }
        b.pXprinter.setOnClickListener { setPrinter("xprinter") }
        b.pOpt.setOnClickListener { setPrinter("opt") }
        setPrinter("godex")

        b.btnPrint.setOnClickListener { sendPrint() }

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
        renderList()
    }

    private fun setPrinter(p: String) {
        printer = p
        val sel = getColor(R.color.brand)
        val off = android.graphics.Color.parseColor("#9AA0A6")
        b.pGodex.setTextColor(if (p == "godex") sel else off)
        b.pXprinter.setTextColor(if (p == "xprinter") sel else off)
        b.pOpt.setTextColor(if (p == "opt") sel else off)
        b.pGodex.setBackgroundResource(if (p == "godex") R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        b.pXprinter.setBackgroundResource(if (p == "xprinter") R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        b.pOpt.setBackgroundResource(if (p == "opt") R.drawable.bg_chip_on else R.drawable.bg_chip_off)
    }

    private fun onScan(code: String) {
        b.loading.visibility = View.VISIBLE
        thread {
            val result = Api.get(this, "product", mapOf("barcode" to code))
            val json: JSONObject? = when (result) {
                is ApiResult.Success -> result.json
                is ApiResult.Error -> if (result.offline) OfflineLookup.lookup(this, code) else null
            }
            val serverErr = (result as? ApiResult.Error)?.takeIf { !it.offline }?.message
            runOnUiThread {
                b.loading.visibility = View.GONE
                when {
                    serverErr != null -> {
                        ScanFeedback.fail(this)
                        Toast.makeText(this, serverErr, Toast.LENGTH_SHORT).show()
                    }
                    json == null || !json.optBoolean("found", false) -> {
                        ScanFeedback.fail(this)
                        Toast.makeText(this, "Mahsulot topilmadi", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        ScanFeedback.ok(this)
                        askCount(json, code)
                    }
                }
            }
        }
    }

    private fun askCount(product: JSONObject, code: String) {
        val name = product.optString("name")
        val mid = product.optString("moysklad_id", "")
        val existing = items.find {
            (mid.isNotBlank() && it.moyskladId == mid) || (code.isNotBlank() && it.barcode == code)
        }

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
            setSelection(text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)

        val dialog = AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Nechta ценник chop etilsin?")
            .setView(input)
            .setPositiveButton("Qo'shish") { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: 1
                if (n > 0) {
                    if (existing != null) existing.count += n
                    else items.add(Lab(code, mid, name, n))
                    renderList()
                }
            }
            .setNegativeButton("Bekor", null)
            .create()
        dialog.show()
        input.requestFocus()
    }

    private fun renderList() {
        b.list.removeAllViews()
        b.emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val total = items.sumOf { it.count }
        b.totalCount.text = "Mahsulot: ${items.size} · Yorliq: $total"
        b.btnPrint.isEnabled = items.isNotEmpty()
        b.btnPrint.alpha = if (items.isEmpty()) 0.5f else 1f
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }
            val nameTv = TextView(this).apply {
                text = item.name; textSize = 15f
                setTextColor(getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val qtyTv = TextView(this).apply {
                text = "×${item.count}"; textSize = 18f
                setTextColor(getColor(R.color.brand))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            row.addView(nameTv); row.addView(qtyTv)
            row.setOnClickListener { editItem(item) }
            b.list.addView(row)
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(getColor(R.color.card_stroke))
            }
            b.list.addView(div)
        }
    }

    private fun editItem(item: Lab) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(item.count.toString())
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage("Nusxa soni (0 = o'chirish)")
            .setView(input)
            .setPositiveButton("Saqlash") { _, _ ->
                val v = input.text.toString().toIntOrNull() ?: item.count
                if (v <= 0) items.remove(item) else item.count = v
                renderList()
            }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun sendPrint() {
        if (items.isEmpty()) return
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("barcode", it.barcode)
                put("moysklad_id", it.moyskladId)
                put("count", it.count)
            })
        }
        val body = JSONObject().apply {
            put("printer", printer)
            put("items", arr)
        }
        b.loading.visibility = View.VISIBLE
        thread {
            val result = Api.post(this, "print-label", body)
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        Toast.makeText(this, "Chopga yuborildi ✓", Toast.LENGTH_SHORT).show()
                        items.clear(); renderList()
                    }
                    is ApiResult.Error -> {
                        Toast.makeText(this,
                            if (result.offline) "Internet yo'q — qayta urinib ko'ring"
                            else result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        b.scanInput.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) b.scanInput.requestFocus()
    }
}
