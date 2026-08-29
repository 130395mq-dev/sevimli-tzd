package uz.sevimli.tzd

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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

    /** Ro'yxat adapteri — qatorlarni qayta ishlatadi (RecyclerView). */
    private val rowAdapter = RecvRowAdapter { pos ->
        items.getOrNull(pos)?.let { editItem(it) }
    }
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
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = rowAdapter
        b.list.setHasFixedSize(true)

        moveId = intent.getStringExtra("move_id") ?: ""
        b.headerName.text = intent.getStringExtra("move_name") ?: getString(R.string.move)

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
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        b.btnConfirm.isEnabled = true
                        b.btnConfirm.alpha = 1f
                        val j = result.json
                        b.headerRoute.text = "${j.optString("source_store")} → ${j.optString("target_store")}"
                        items.clear()
                        val arr = j.optJSONArray("positions") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val p = arr.optJSONObject(i) ?: continue
                            items.add(RecvItem(
                                p.optString("product_moysklad_id"),
                                p.optString("product_type", "product"),
                                p.optString("name"),
                                p.optDouble("expected_qty", 0.0),
                            ))
                        }
                        renderList()
                    }
                    is ApiResult.Error -> {
                        // ILGARI: ro'yxat bo'sh qolib, "Qabul qilish" tugmasi
                        // faol turardi — xodim bo'sh hujjatni tasdiqlashi mumkin edi.
                        b.btnConfirm.isEnabled = false
                        b.btnConfirm.alpha = 0.5f
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
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
            Toast.makeText(this, getString(R.string.not_in_doc_fmt, product.optString("name")),
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
        qPrice.text = getString(R.string.expected_fmt, trimNum(item.expected))
        qWas.text = getString(R.string.was_fmt, trimNum(was))

        // Blok (upakovka) yoki tarozi shtrixi — avtomatik to'ldiramiz
        val packQty = product.optDouble("pack_qty", 0.0)
        val isPack = product.optBoolean("is_pack", false) && packQty > 0
        val packUom = product.optString("uom", "").let { if (it.isBlank()) "" else " $it" }
        val scaleWeight = product.optDouble("scale_weight", 0.0)
        when {
            product.optBoolean("scale", false) && scaleWeight > 0 -> {
                qPackInfo.visibility = View.VISIBLE
                qPackInfo.text = getString(R.string.scale_fmt_u, trimNum(scaleWeight))
                qInput.setText(trimNum(scaleWeight))
            }
            isPack -> {
                qPackInfo.visibility = View.VISIBLE
                qInput.setText("1")
            }
            // QR dagi GTIN-14 BLOK ekanini ko'rsatyapti, lekin ichida nechta
            // dona ekani MoySklad'da ro'yxatdan o'tmagan — xodim o'zi kiritadi.
            product.optBoolean("pack_unknown", false) -> {
                qPackInfo.visibility = View.VISIBLE
                qPackInfo.text = getString(R.string.blok_enter_qty_u)
                qInput.setText("")
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
                getString(R.string.pack_calc_fmt_u, trimNum(typed), trimNum(packQty), trimNum(total), packUom)
            return total
        }
        fun updateWill() {
            val will = was + currentQty()
            qWill.text = getString(R.string.will_fmt, trimNum(will))
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
        // Ro'yxat adapterga beriladi — RecyclerView faqat ko'rinib turgan
        // qatorlarni chizadi. Ilgari har skandan keyin butun ro'yxat
        // (kelgan ko'chirishda yuzlab qator bo'lishi mumkin) qayta yasalardi.
        var checked = 0
        val rows = ArrayList<RecvRowAdapter.Row>(items.size)
        for ((index, item) in items.withIndex()) {
            val scanned = item.scanned > 0
            if (scanned) checked++

            val status: String
            val statusColor: Int
            when {
                item.scanned == 0.0 -> {
                    status = getString(R.string.expected_pcs_fmt, trimNum(item.expected))
                    statusColor = R.color.text_gray
                }
                item.scanned == item.expected -> {
                    status = getString(R.string.arrived_full)
                    statusColor = R.color.brand
                }
                item.scanned < item.expected -> {
                    status = getString(R.string.arrived_less_fmt, trimNum(item.expected - item.scanned))
                    statusColor = R.color.warning
                }
                else -> {
                    status = getString(R.string.arrived_more_fmt, trimNum(item.scanned - item.expected))
                    statusColor = R.color.warning
                }
            }

            rows.add(RecvRowAdapter.Row(
                num = "${index + 1}",
                name = item.name,
                status = status,
                statusColor = statusColor,
                qty = "${trimNum(item.expected)} / ${trimNum(item.scanned)}",
                qtyColor = if (item.scanned == item.expected && item.scanned > 0)
                    R.color.brand else R.color.text_dark,
                numColor = if (scanned) R.color.brand else R.color.text_gray,
                numBold = scanned,
            ))
        }
        rowAdapter.submit(rows)
        b.progressText.text = getString(R.string.checked_fmt, checked, items.size)
    }

    private fun editItem(item: RecvItem) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(trimNum(item.scanned))
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(getString(R.string.actual_qty_fmt, trimNum(item.expected)))
            .setView(input)
            .setPositiveButton(getString(R.string.save_kt)) { _, _ ->
                item.scanned = input.text.toString().toDoubleOrNull() ?: item.scanned
                renderList()
            }
            .setNegativeButton(getString(R.string.cancel_short), null)
            .show()
    }

    /** Yuborish qulfi — ikki marta bosilsa ikkinchi so'rov ketmaydi. */
    private val busy = Busy()

    /** Shu qabul qilishning yagona kaliti (takror-himoya uchun). */
    private val confirmUuid = java.util.UUID.randomUUID().toString()

    private fun confirm() {
        if (busy.isRunning) return               // allaqachon yuborilyapti
        val scannedItems = items.filter { it.scanned > 0 }
        if (scannedItems.isEmpty()) {
            Toast.makeText(this, getString(R.string.nothing_scanned), Toast.LENGTH_SHORT).show(); return
        }
        val notScanned = items.count { it.scanned == 0.0 }
        val diffs = scannedItems.count { it.scanned != it.expected }
        val sb = StringBuilder()
        sb.append(getString(R.string.will_accept_fmt, scannedItems.size))
        if (notScanned > 0) sb.append(getString(R.string.not_scanned_fmt, notScanned))
        if (diffs > 0) sb.append(getString(R.string.qty_diff_fmt, diffs))
        sb.append(getString(R.string.post_to_ms_q))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.accept))
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.yes_accept)) { _, _ -> send(scannedItems) }
            .setNegativeButton(getString(R.string.cancel_short), null)
            .show()
    }

    private fun send(scannedItems: List<RecvItem>) {
        if (!busy.start(b.btnConfirm)) return    // allaqachon yuborilyapti
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
            // TAKROR-HIMOYA: bitta ko'chirish ikki marta o'tkazilmasin.
            // Kalit ekran ochilganda yaratiladi va qayta urinishda o'zgarmaydi —
            // ya'ni tarmoq uzilib qayta yuborilsa ham hujjat bitta bo'ladi.
            put("client_uuid", confirmUuid)
            put("move_id", moveId)
            put("lines", lines)
        }
        thread {
            val result = Api.post(this, "move-confirm", body)
            runOnUiThread {
                busy.stop(b.btnConfirm)
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        val name = result.json.optString("moysklad_name", getString(R.string.move))
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.accepted_ok))
                            .setMessage("MoySklad: $name")
                            .setPositiveButton(getString(R.string.ok)) { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                    is ApiResult.Error -> {
                        val msg = if (result.offline)
                            getString(R.string.no_internet_retry2)
                        else result.message
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.not_accepted))
                            .setMessage(msg)
                            .setPositiveButton(getString(R.string.ok), null)
                            .show()
                    }
                }
            }
        }
    }

    private fun trimNum(d: Double): String {
        // Yaxlitlash: `Double` da qo'shish ikkilik kasr xatosini to'playdi
        // (63.789 -> 63.788999999999994). Boshqa ekranlarda bor edi,
        // shu uchtasida tushib qolgan.
        val r = Math.round(d * 1000.0) / 1000.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    /** Kasr xatolarini oldini olish (0.1 + 0.2 muammosi) — 3 xonaga yaxlitlaymiz. */
    private fun round3(d: Double): Double = Math.round(d * 1000.0) / 1000.0


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
