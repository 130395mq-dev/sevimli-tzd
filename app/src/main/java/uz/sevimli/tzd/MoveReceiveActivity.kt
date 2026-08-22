package uz.sevimli.tzd

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
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

    // Miqdor oynasi ochiq turganda kelgan skanni ushlash uchun (Приёмка kabi)
    private var qtyDialog: AlertDialog? = null
    private var qtyConfirmWith: ((Double) -> Unit)? = null
    private var qtyPrefill: Double = 0.0
    private val scanBuf = StringBuilder()
    private var lastScanKey = 0L

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
        // Skan uch kanaldan qabul qilinadi: Enter, Enter'siz (jimlik) va
        // qurilma skaner signali. Tafsilot — ScanInput.kt
        ScanInput.bind(this, b.scanInput) { code -> onScan(code) }

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
            // Internet bo'lmasa — mahalliy (offline) bazadan qidiramiz (Приёмка kabi)
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
                    else -> matchScan(json)
                }
            }
        }
    }

    private fun matchScan(product: JSONObject) {
        val mid = product.optString("moysklad_id")
        val item = items.find { it.productMoyskladId == mid }
        if (item == null) {
            ScanFeedback.fail(this)
            Toast.makeText(this, "Bu tovar dokumentda yo'q:\n${product.optString("name")}",
                Toast.LENGTH_LONG).show()
            return
        }
        ScanFeedback.ok(this)
        askQuantity(item, product)
    }

    /**
     * Miqdor oynasi — Приёмка (SupplyActivity) bilan bir xil ko'rinish.
     * Skanerdan kelgan blok/tarozi qiymati avtomatik to'ldiriladi,
     * xodim −/+ bilan yoki qo'lda o'zgartira oladi.
     */
    private fun askQuantity(item: RecvItem, product: JSONObject) {
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

        val was = item.scanned
        qName.text = item.name
        // Приёмка'да narx turadi; bu yerda muhimi — KUTILGAN miqdor
        qPrice.text = "Kutilgan: ${trimNum(item.expected)} dona"
        qWas.text = "Было: ${trimNum(was)}"

        // Blok (upakovka) yoki tarozi shtrixi — avtomatik to'ldiramiz
        val packQty = product.optDouble("pack_qty", 0.0)
        val isPack = product.optBoolean("is_pack", false) && packQty > 0
        val packUom = product.optString("uom", "").let { if (it.isBlank()) "" else " $it" }
        val scaleWeight = product.optDouble("scale_weight", 0.0)
        when {
            product.optBoolean("scale", false) && scaleWeight > 0 -> {
                qPackInfo.visibility = View.VISIBLE
                qPackInfo.text = "\u2696 Tarozi: ${trimNum(scaleWeight)} kg"
                qInput.setText(trimNum(scaleWeight))
            }
            isPack -> {
                qPackInfo.visibility = View.VISIBLE
                qInput.setText("1")
            }
            else -> {
                qPackInfo.visibility = View.GONE
                qInput.setText("1")
            }
        }

        fun typedQty(): Double = qInput.text.toString().toDoubleOrNull() ?: 0.0
        // UPAKOVKA: kiritilgan raqam — upakovka SONI. Hujjatga esa ichidagi
        // jami miqdor yoziladi: 1 upakovka x 3 kg = 3 kg.
        fun currentQty(): Double {
            val typed = typedQty()
            if (!isPack) return typed
            val total = round3(typed * packQty)
            qPackInfo.text =
                "\uD83D\uDCE6 ${trimNum(typed)} upakovka x ${trimNum(packQty)} = ${trimNum(total)}$packUom"
            return total
        }
        fun updateWill() {
            val will = was + currentQty()
            qWill.text = "Будет: ${trimNum(will)}"
            // Kutilgandan oshsa — ogohlantirib rangini o'zgartiramiz
            qWill.setTextColor(getColor(
                if (will > item.expected) R.color.warning else R.color.brand))
        }
        updateWill()
        qInput.setSelection(qInput.text.length)

        btnMinus.setOnClickListener {
            val v = (typedQty() - 1).coerceAtLeast(0.0)
            qInput.setText(trimNum(v)); updateWill()
        }
        btnPlus.setOnClickListener {
            qInput.setText(trimNum(typedQty() + 1)); updateWill()
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()
        qInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateWill() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val confirmWith: (Double) -> Unit = { addQty ->
            if (addQty > 0) {
                item.scanned = round3(item.scanned + addQty)
                renderList()
            }
            dialog.dismiss()
        }
        btnOk.setOnClickListener { confirmWith(currentQty()) }

        qtyPrefill = currentQty()
        qtyConfirmWith = confirmWith
        qtyDialog = dialog
        dialog.setOnDismissListener { qtyDialog = null; qtyConfirmWith = null }
        dialog.show()
    }

    private fun renderList() {
        b.list.removeAllViews()
        var checked = 0
        for ((index, item) in items.withIndex()) {
            if (item.scanned > 0) checked++
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12f).toInt(), dp(13f).toInt(), dp(16f).toInt(), dp(13f).toInt())
            }

            // ---- TARTIB RAQAMI (1, 2, 3 ...) ----
            // Skan qilingan qator raqami brend rangida — qaysilari bo'lganini
            // bir qarashda ko'rish uchun.
            val numTv = TextView(this).apply {
                text = "${index + 1}"
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                minWidth = dp(26f).toInt()
                setPadding(dp(4f).toInt(), 0, dp(6f).toInt(), 0)
                setTextColor(getColor(
                    if (item.scanned > 0) R.color.brand else R.color.text_gray))
                if (item.scanned > 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            row.addView(numTv)

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

    /** Kasr xatolarini oldini olish (0.1 + 0.2 muammosi) — 3 xonaga yaxlitlaymiz. */
    private fun round3(d: Double): Double = Math.round(d * 1000.0) / 1000.0

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onResume() {
        super.onResume()
        b.scanInput.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) b.scanInput.requestFocus()
    }

    /**
     * Miqdor oynasi ochiq turganda kelgan SKAN (tez ketma-ketlik + Enter) ni ushlaymiz.
     * Aks holda skaner raqamlari miqdor maydoniga tushib ketardi.
     * Приёмка'дagi mantiqning aynan o'zi.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (qtyDialog?.isShowing == true && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastScanKey > 150) scanBuf.setLength(0)
            lastScanKey = now
            val kc = event.keyCode
            if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                val code = scanBuf.toString().trim()
                scanBuf.setLength(0)
                if (code.length >= 6) {                    // barcode — yangi mahsulot
                    qtyConfirmWith?.invoke(qtyPrefill)     // joriyni standart miqdor bilan tasdiqlaymiz
                    onScan(code)
                    return true
                }
            } else {
                val ch = event.unicodeChar
                if (ch != 0) scanBuf.append(ch.toChar())
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
