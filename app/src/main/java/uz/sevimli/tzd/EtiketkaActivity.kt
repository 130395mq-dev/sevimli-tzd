package uz.sevimli.tzd

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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
    /** Ro'yxat adapteri — qatorlarni qayta ishlatadi (RecyclerView). */
    private val rowAdapter = DocRowAdapter { pos ->
        items.getOrNull(pos)?.let { editItem(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEtiketkaBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = rowAdapter
        b.list.setHasFixedSize(true)

        b.btnBack.setOnClickListener { finish() }
        b.pGodex.setOnClickListener { setPrinter("godex") }
        b.pXprinter.setOnClickListener { setPrinter("xprinter") }
        setPrinter("godex")
        // Narx turi Sozlamalardan olinadi (chakana / ulgurji) — sarlavhada ko'rsatamiz
        b.priceMode.text = if (Config.isUlgurji(this)) "Narx: Ulgurji (оптом)" else "Narx: Chakana"

        b.btnPrint.setOnClickListener { sendPrint() }

        // Skan uch kanaldan qabul qilinadi: Enter, Enter'siz (jimlik) va
        // qurilma skaner signali. Tafsilot — ScanInput.kt
        ScanInput.bind(this, b.scanInput) { code -> onScan(code) }
        renderList()
    }

    private fun setPrinter(p: String) {
        printer = p
        val sel = getColor(R.color.brand)
        val off = android.graphics.Color.parseColor("#9AA0A6")
        b.pGodex.setTextColor(if (p == "godex") sel else off)
        b.pXprinter.setTextColor(if (p == "xprinter") sel else off)
        b.pGodex.setBackgroundResource(if (p == "godex") R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        b.pXprinter.setBackgroundResource(if (p == "xprinter") R.drawable.bg_chip_on else R.drawable.bg_chip_off)
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
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when {
                    serverErr != null -> {
                        ScanFeedback.fail(this)
                        Toast.makeText(this, serverErr, Toast.LENGTH_SHORT).show()
                    }
                    json == null || !json.optBoolean("found", false) -> {
                        ScanFeedback.fail(this)
                        Toast.makeText(this, getString(R.string.product_not_found), Toast.LENGTH_SHORT).show()
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
            .setMessage(getString(R.string.how_many_labels))
            .setView(input)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: 1
                if (n > 0) {
                    if (existing != null) existing.count += n
                    else items.add(Lab(code, mid, name, n))
                    renderList()
                }
            }
            .setNegativeButton(getString(R.string.cancel_short), null)
            .create()
        dialog.show()
        input.requestFocus()
    }

    private fun renderList() {
        b.emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val total = items.sumOf { it.count }
        b.totalCount.text = getString(R.string.prod_label_fmt, items.size, total)
        b.btnPrint.isEnabled = items.isNotEmpty()
        b.btnPrint.alpha = if (items.isEmpty()) 0.5f else 1f
        // Ro'yxat adapterga beriladi — RecyclerView faqat ko'rinib
        // turgan qatorlarni chizadi (ilgari hammasi qayta yasalardi).
        rowAdapter.submit(items.map { item ->
            DocRowAdapter.Row(item.name, "\u00D7${item.count}")
        })
    }

    private fun editItem(item: Lab) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(item.count.toString())
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(getString(R.string.copies_hint))
            .setView(input)
            .setPositiveButton(getString(R.string.save_kt)) { _, _ ->
                val v = input.text.toString().toIntOrNull() ?: item.count
                if (v <= 0) items.remove(item) else item.count = v
                renderList()
            }
            .setNegativeButton(getString(R.string.cancel_short), null)
            .show()
    }

    /** Yuborish qulfi — ikki marta bosilsa ikkinchi so'rov ketmaydi. */
    private val busy = Busy()

    /** Shu chop buyrug'ining yagona kaliti (takror-himoya uchun).
     *  Muvaffaqiyatdan keyin yangilanadi — keyingi chop alohida buyruq. */
    private var printUuid = java.util.UUID.randomUUID().toString()

    private fun sendPrint() {
        if (items.isEmpty()) return
        if (!busy.start(b.btnPrint)) return    // allaqachon yuborilyapti
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("barcode", it.barcode)
                put("moysklad_id", it.moyskladId)
                put("count", it.count)
            })
        }
        val body = JSONObject().apply {
            // TAKROR-HIMOYA: shu chop buyrug'ining yagona kaliti. Tarmoq uzilib
            // ilova qayta yuborsa ham, server yorliqlarni IKKI MARTA bosmaydi.
            put("client_uuid", printUuid)
            put("printer", printer)
            put("price_mode", Config.priceMode(this@EtiketkaActivity))  // chakana / ulgurji
            put("items", arr)
        }
        b.loading.visibility = View.VISIBLE
        thread {
            val result = Api.post(this, "print-label", body)
            runOnUiThread {
                busy.stop(b.btnPrint)
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        Toast.makeText(this, getString(R.string.sent_to_print), Toast.LENGTH_SHORT).show()
                        items.clear(); renderList()
                        // Keyingi chop — ALOHIDA buyruq, yangi kalit bilan
                        printUuid = java.util.UUID.randomUUID().toString()
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


    override fun onResume() {
        super.onResume()
        b.scanInput.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) b.scanInput.requestFocus()
    }
}
