package uz.sevimli.tzd

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import uz.sevimli.tzd.databinding.ActivityShipmentBinding
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Отгрузка (jo'natma). Приёмкаning teskarisi — mijoz tanlanadi, tovarlar skan
 * qilinadi va MoySklad'ga demand (jo'natma) yoziladi: tovar ombordan chiqadi.
 */
class ShipmentActivity : AppCompatActivity() {

    private lateinit var b: ActivityShipmentBinding
    private val fmt = NumberFormat.getInstance(Locale("uz"))
    private val items = mutableListOf<SupplyItem>()

    private var cpId: Int = -1
    private var cpName: String = ""
    private val clientUuid = UUID.randomUUID().toString()

    private val pickCp = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            cpId = res.data!!.getIntExtra("cp_id", -1)
            cpName = res.data!!.getStringExtra("cp_name") ?: ""
            b.headerCp.text = cpName
        } else if (cpId < 0) {
            finish() // mijoz tanlanmasa, chiqamiz
        }
    }

    private val pickProduct = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val json = productFromIntent(res.data!!)
            askQuantity(json, json.optString("barcode"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityShipmentBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { confirmExit() }
        b.btnFinish.setOnClickListener { finishDocument() }
        b.btnManualAdd.setOnClickListener {
            pickProduct.launch(Intent(this, ProductSearchActivity::class.java))
        }
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

        // Avval mijoz tanlash
        pickCp.launch(Intent(this, CounterpartyActivity::class.java))
    }

    private fun onScan(code: String) {
        b.loading.visibility = View.VISIBLE
        thread {
            val result = Api.get(this, "product", mapOf("barcode" to code))
            // Internet bo'lmasa — mahalliy (offline) bazadan qidiramiz
            val json: org.json.JSONObject? = when (result) {
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
                        askQuantity(json, code)
                    }
                }
            }
        }
    }

    private fun askQuantity(product: JSONObject, code: String) {
        val name = product.optString("name")
        val price = product.optLong("price", 0)
        val pmid = product.optString("moysklad_id", product.optString("code"))
        // Qatorlarni ISHONCHLI kalit — mahsulot ID si bo'yicha birlashtiramiz (barcode zaxira).
        // Ilgari NOM bo'yicha ham birlashtirilardi — bir xil nomli turli mahsulotlar
        // aralashib, miqdor buzilardi.
        val existing = items.find {
            (pmid.isNotBlank() && it.productMoyskladId == pmid) ||
                    (code.isNotBlank() && it.barcode == code)
        }
        val was = existing?.quantity ?: 0.0

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_quantity, null)
        val qName = view.findViewById<TextView>(R.id.qName)
        val qPrice = view.findViewById<TextView>(R.id.qPrice)
        val qPackInfo = view.findViewById<TextView>(R.id.qPackInfo)
        val qWas = view.findViewById<TextView>(R.id.qWas)
        val qWill = view.findViewById<TextView>(R.id.qWill)
        val qInput = view.findViewById<EditText>(R.id.qInput)
        val btnMinus = view.findViewById<TextView>(R.id.btnMinus)
        val btnPlus = view.findViewById<TextView>(R.id.btnPlus)
        val btnOk = view.findViewById<View>(R.id.btnOk)

        qName.text = name
        val storeQty = product.optDouble("store_qty", -1.0)
        qPrice.text = if (storeQty >= 0)
            "${fmt.format(price)} so'm · Qoldiq: ${trimNum(storeQty)}"
        else
            "${fmt.format(price)} so'm"
        qWas.text = "Было: ${trimNum(was)}"

        // Tarozi og'irligi yoki upakovka (blok) — avto to'ldiramiz
        val packQty = product.optDouble("pack_qty", 0.0)
        val scaleWeight = product.optDouble("scale_weight", 0.0)
        when {
            product.optBoolean("scale", false) && scaleWeight > 0 -> {
                qPackInfo.visibility = View.VISIBLE
                qPackInfo.text = "⚖ Tarozi: ${trimNum(scaleWeight)} kg"
                qInput.setText(trimNum(scaleWeight))
            }
            product.optBoolean("is_pack", false) && packQty > 0 -> {
                qPackInfo.visibility = View.VISIBLE
                qPackInfo.text = "📦 Upakovka (blok): ${trimNum(packQty)} dona"
                qInput.setText(trimNum(packQty))
            }
            else -> {
                qPackInfo.visibility = View.GONE
                qInput.setText("1")
            }
        }

        fun currentQty(): Double = qInput.text.toString().toDoubleOrNull() ?: 0.0
        fun updateWill() {
            val will = was + currentQty()
            qWill.text = "Будет: ${trimNum(will)}"
            if (storeQty >= 0 && will > storeQty) qWill.append("  ⚠ qoldiqdan ko'p")
        }
        updateWill()
        qInput.setOnFocusChangeListener { _, _ -> updateWill() }
        qInput.setSelection(qInput.text.length)

        btnMinus.setOnClickListener {
            val v = (currentQty() - 1).coerceAtLeast(0.0); qInput.setText(trimNum(v)); updateWill()
        }
        btnPlus.setOnClickListener {
            qInput.setText(trimNum(currentQty() + 1)); updateWill()
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()
        qInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateWill() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        btnOk.setOnClickListener {
            val add = currentQty()
            if (add <= 0) { dialog.dismiss(); return@setOnClickListener }
            if (existing != null) {
                existing.quantity = round3(existing.quantity + add)
            } else {
                items.add(SupplyItem(pmid, code, name, price, add))
            }
            dialog.dismiss()
            renderList()
        }
        dialog.show()
    }

    private fun renderList() {
        b.list.removeAllViews()
        b.emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        b.totalCount.text = "Товар, всего: ${items.size}"
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
            }
            val nameTv = TextView(this).apply {
                text = item.name; textSize = 15f
                setTextColor(getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val qtyTv = TextView(this).apply {
                text = trimNum(item.quantity); textSize = 18f
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

    private fun editItem(item: SupplyItem) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(trimNum(item.quantity))
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage("Yangi miqdor (0 = o'chirish)")
            .setView(input)
            .setPositiveButton("Saqlash") { _, _ ->
                val v = input.text.toString().toDoubleOrNull() ?: item.quantity
                if (v <= 0) items.remove(item) else item.quantity = v
                renderList()
            }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun finishDocument() {
        if (items.isEmpty()) {
            Toast.makeText(this, "Hujjat bo'sh", Toast.LENGTH_SHORT).show(); return
        }
        if (cpId < 0) {
            Toast.makeText(this, "Mijoz tanlanmagan", Toast.LENGTH_SHORT).show(); return
        }
        AlertDialog.Builder(this)
            .setTitle("Отгрузкаni yakunlash")
            .setMessage("Mijoz: $cpName\n${items.size} ta mahsulot · jami ${trimNum(items.sumOf { it.quantity })}\nTovar ombordan chiqadi. MoySklad'ga yozilsinmi?")
            .setPositiveButton("Завершить") { _, _ -> sendDocument() }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun sendDocument() {
        b.loading.visibility = View.VISIBLE
        val lines = JSONArray()
        for (item in items) {
            lines.put(JSONObject().apply {
                put("product_moysklad_id", item.productMoyskladId)
                put("barcode", item.barcode)
                put("name", item.name)
                put("quantity", item.quantity)
                put("price", item.price)
            })
        }
        val body = JSONObject().apply {
            put("client_uuid", clientUuid)
            put("counterparty_id", cpId)
            put("lines", lines)
        }
        thread {
            val result = Api.post(this, "shipment", body)
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        val name = result.json.optString("moysklad_name", "Отгрузка")
                        AlertDialog.Builder(this)
                            .setTitle("Yuborildi ✓")
                            .setMessage("MoySklad: $name")
                            .setPositiveButton("OK") { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                    is ApiResult.Error -> {
                        if (result.offline) {
                            // Internet yo'q — hujjatni navbatga saqlaymiz, keyin o'zi yuboriladi.
                            OfflineQueue.enqueue(this, "shipment", "Отгрузка", body)
                            AlertDialog.Builder(this)
                                .setTitle("Saqlandi ⏳")
                                .setMessage("Internet yo'q. Hujjat telefonda saqlandi — internet qaytganda o'zi yuboriladi.")
                                .setPositiveButton("OK") { _, _ -> finish() }
                                .setCancelable(false)
                                .show()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Yuborilmadi")
                                .setMessage(result.message)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
        }
    }

    private fun confirmExit() {
        if (items.isEmpty()) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Chiqish")
            .setMessage("Hujjat yakunlanmagan. Chiqilsinmi? (ma'lumot saqlanmaydi)")
            .setPositiveButton("Chiqish") { _, _ -> finish() }
            .setNegativeButton("Qolish", null)
            .show()
    }

    /** Miqdorni 3 xonagacha yaxlitlaydi (0.1+0.2 kabi "shovqin"ni yo'qotadi). */
    private fun round3(d: Double): Double = Math.round(d * 1000.0) / 1000.0

    private fun trimNum(d: Double): String {
        val r = round3(d)
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** ProductSearchActivity'dan qaytgan tanlovni product_lookup javobi kabi JSON'ga aylantiradi. */
    private fun productFromIntent(data: Intent): JSONObject = JSONObject().apply {
        put("found", true)
        put("name", data.getStringExtra("p_name") ?: "")
        put("barcode", data.getStringExtra("p_barcode") ?: "")
        put("price", data.getLongExtra("p_price", 0))
        put("moysklad_id", data.getStringExtra("p_moysklad_id") ?: "")
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
