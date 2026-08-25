package uz.sevimli.tzd

import android.app.AlertDialog
import android.content.Intent
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
import uz.sevimli.tzd.databinding.ActivityInventoryCountBinding
import kotlin.concurrent.thread

/**
 * MoySklad'da yaratilgan inventarizatsiyani sanash.
 *
 * Dokument tarkibi ko'rsatiladi (tizim qoldig'i), har tovar skanerlanib
 * HAQIQIY soni yig'iladi. Farq darhol ko'rinadi.
 *
 * MUHIM FARQ (MoveReceiveActivity'дан): hujjatда YO'Q tovar skanerlansa —
 * u ro'yxatga QO'SHILADI va "hujjatda yo'q edi" deb belgilanadi. Sabab:
 * inventarizatsiyaning maqsadi omborda HAQIQATDA nima borligini aniqlash.
 * Ortiqcha tovarni yashirish sanoqni ma'nosiz qilardi.
 *
 * Tugmasi "Sanoqni saqlash" — hujjat O'TKAZILMAYDI. Menejer kompyuterdan
 * farqlarni ko'rib chiqib, o'tkazishni o'zi hal qiladi. Ishchi bitta
 * shtrixni xato skanerlasa qoldiq buzilib ketmasin.
 */
class InventoryCountActivity : AppCompatActivity() {

    private lateinit var b: ActivityInventoryCountBinding
    private val items = mutableListOf<InvItem>()

    private val rowAdapter = RecvRowAdapter { pos ->
        items.getOrNull(pos)?.let { editItem(it) }
    }
    private var inventoryId: String = ""

    // Miqdor oynasi ochiq turganda kelgan skanni ushlash uchun
    private var qtyDialog: AlertDialog? = null
    private var qtyConfirmWith: ((Double) -> Unit)? = null
    private var qtyPrefill: Double = 0.0
    private val scanBuf = StringBuilder()
    private var lastScanKey = 0L

    /**
     * @param expected   tizim qoldig'i (MoySklad calculatedQuantity)
     * @param counted    sanalgan fakt
     * @param touched    ishchi shu tovarga TEGDIMI. `counted == 0.0` ikki xil
     *                   ma'noda bo'ladi: "hali sanalmagan" va "sanadim, yo'q
     *                   ekan". Bu bayroq ularni ajratadi — ikkinchisi
     *                   inventarizatsiyaning eng muhim natijasi.
     * @param wasInDoc   MoySklad hujjatida bor edimi
     */
    data class InvItem(
        val productMoyskladId: String,
        val productType: String,
        val name: String,
        val expected: Double,
        var counted: Double = 0.0,
        var touched: Boolean = false,
        val wasInDoc: Boolean = true,
    )

    private val pickProduct = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            matchScan(productFromIntent(res.data!!))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityInventoryCountBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = rowAdapter
        b.list.setHasFixedSize(true)

        inventoryId = intent.getStringExtra("inventory_id") ?: ""
        b.headerName.text = intent.getStringExtra("inventory_name") ?: "Инвентаризация"

        b.btnBack.setOnClickListener { confirmExit() }
        b.btnConfirm.setOnClickListener { confirm() }
        b.btnAdd.setOnClickListener {
            pickProduct.launch(Intent(this, ProductSearchActivity::class.java))
        }
        ScanInput.bind(this, b.scanInput) { code -> onScan(code) }

        loadDetail()
    }

    private fun loadDetail() {
        b.loading.visibility = View.VISIBLE
        thread {
            val result = Api.get(this, "inventory-detail", mapOf("id" to inventoryId))
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        b.btnConfirm.isEnabled = true
                        b.btnConfirm.alpha = 1f
                        val j = result.json
                        b.headerRoute.text = j.optString("store", "—")
                        items.clear()
                        val arr = j.optJSONArray("positions") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val p = arr.optJSONObject(i) ?: continue
                            items.add(InvItem(
                                productMoyskladId = p.optString("product_moysklad_id"),
                                productType = p.optString("product_type", "product"),
                                name = p.optString("name"),
                                expected = p.optDouble("expected_qty", 0.0),
                                // Hujjatда allaqachon turgan fakt (sanoq davom ettirilsa).
                                // `touched=false` — buni ishchi EMAS, hujjat keltirdi.
                                counted = p.optDouble("counted_qty", 0.0),
                                touched = false,
                                wasInDoc = true,
                            ))
                        }
                        renderList()
                    }
                    is ApiResult.Error -> {
                        // Ro'yxat kelmasa tugma o'chiriladi — aks holda xodim
                        // bo'sh hujjatni saqlab, MoySklad'dagi ro'yxatni
                        // o'chirib yuborishi mumkin edi.
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
            // Internet bo'lmasa — mahalliy (offline) bazadan qidiramiz
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
                        Toast.makeText(this, "Mahsulot topilmadi", Toast.LENGTH_SHORT).show()
                    }
                    else -> matchScan(json)
                }
            }
        }
    }

    private fun matchScan(product: JSONObject) {
        val mid = product.optString("moysklad_id")
        if (mid.isBlank()) {
            ScanFeedback.fail(this)
            Toast.makeText(this, "Bu mahsulot MoySklad'da yo'q", Toast.LENGTH_SHORT).show()
            return
        }
        var item = items.find { it.productMoyskladId == mid }
        if (item == null) {
            // Hujjatda yo'q tovar — QO'SHAMIZ. Omborda haqiqatda bor ekan,
            // uni yashirish inventarizatsiyani ma'nosiz qilardi.
            item = InvItem(
                productMoyskladId = mid,
                productType = product.optString("product_type", "product"),
                name = product.optString("name"),
                expected = 0.0,
                wasInDoc = false,
            )
            items.add(item)
            Toast.makeText(this, "Hujjatda yo'q edi — qo'shildi:\n${item.name}",
                Toast.LENGTH_SHORT).show()
        }
        ScanFeedback.ok(this)
        askQuantity(item, product)
    }

    /** Miqdor oynasi — Приёмка bilan bir xil ko'rinish. */
    private fun askQuantity(item: InvItem, product: JSONObject) {
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

        val was = item.counted
        qName.text = item.name
        qPrice.text = if (item.wasInDoc)
            "Tizim qoldig'i: ${trimNum(item.expected)}"
        else
            "⚠ Hujjatda yo'q edi"
        qWas.text = "Sanalgan: ${trimNum(was)}"

        val packQty = product.optDouble("pack_qty", 0.0)
        val isPack = product.optBoolean("is_pack", false) && packQty > 0
        val packUom = product.optString("uom", "").let { if (it.isBlank()) "" else " $it" }
        val scaleWeight = product.optDouble("scale_weight", 0.0)
        when {
            product.optBoolean("scale", false) && scaleWeight > 0 -> {
                qPackInfo.visibility = View.VISIBLE
                qPackInfo.text = "⚖ Tarozi: ${trimNum(scaleWeight)} kg"
                qInput.setText(trimNum(scaleWeight))
            }
            isPack -> {
                qPackInfo.visibility = View.VISIBLE
                qInput.setText("1")
            }
            product.optBoolean("pack_unknown", false) -> {
                qPackInfo.visibility = View.VISIBLE
                qPackInfo.text = "📦 BLOK kodi — ichidagi DONA sonini kiriting"
                qInput.setText("")
            }
            else -> {
                qPackInfo.visibility = View.GONE
                qInput.setText("1")
            }
        }

        fun typedQty(): Double = qInput.text.toString().toDoubleOrNull() ?: 0.0
        fun currentQty(): Double {
            val typed = typedQty()
            if (!isPack) return typed
            val total = round3(typed * packQty)
            qPackInfo.text =
                "📦 ${trimNum(typed)} upakovka x ${trimNum(packQty)} = ${trimNum(total)}$packUom"
            return total
        }
        fun updateWill() {
            val will = was + currentQty()
            qWill.text = "Jami: ${trimNum(will)}"
            // Tizim qoldig'idan farq qilsa — rangini o'zgartiramiz
            qWill.setTextColor(getColor(
                if (item.wasInDoc && will != item.expected) R.color.warning else R.color.brand))
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
                item.counted = round3(item.counted + addQty)
                item.touched = true
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
        var counted = 0
        val rows = ArrayList<RecvRowAdapter.Row>(items.size)
        for ((index, item) in items.withIndex()) {
            if (item.touched) counted++

            val status: String
            val statusColor: Int
            when {
                !item.wasInDoc -> {
                    status = "➕ Hujjatda yo'q edi"
                    statusColor = R.color.warning
                }
                !item.touched -> {
                    status = "Sanalmagan · qoldiq ${trimNum(item.expected)}"
                    statusColor = R.color.text_gray
                }
                item.counted == item.expected -> {
                    status = "✓ Mos keldi"
                    statusColor = R.color.brand
                }
                item.counted < item.expected -> {
                    status = "⚠ Kamomad (${trimNum(item.expected - item.counted)})"
                    statusColor = R.color.warning
                }
                else -> {
                    status = "⚠ Ortiqcha (+${trimNum(item.counted - item.expected)})"
                    statusColor = R.color.warning
                }
            }

            rows.add(RecvRowAdapter.Row(
                num = "${index + 1}",
                name = item.name,
                status = status,
                statusColor = statusColor,
                qty = "${trimNum(item.expected)} / ${trimNum(item.counted)}",
                qtyColor = if (item.touched && item.counted == item.expected)
                    R.color.brand else R.color.text_dark,
                numColor = if (item.touched) R.color.brand else R.color.text_gray,
                numBold = item.touched,
            ))
        }
        rowAdapter.submit(rows)
        b.progressText.text = "Sanalgan: $counted / ${items.size}"
    }

    /**
     * Qo'lda tuzatish. Bu yerda 0 ham QABUL QILINADI — "sanadim, yo'q ekan"
     * degani. Aynan shu holat kamomadni ko'rsatadi.
     */
    private fun editItem(item: InvItem) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(trimNum(item.counted))
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(if (item.wasInDoc)
                "Haqiqiy son (tizim qoldig'i: ${trimNum(item.expected)}).\n0 = umuman yo'q."
            else
                "Haqiqiy son. Bu tovar hujjatda yo'q edi.")
            .setView(input)
            .setPositiveButton("Saqlash") { _, _ ->
                val v = input.text.toString().toDoubleOrNull()
                if (v != null && v >= 0) {
                    item.counted = v
                    item.touched = true      // 0 kiritilsa ham "sanaldi"
                    renderList()
                }
            }
            .setNeutralButton("Sanalmagan qilish") { _, _ ->
                item.counted = 0.0
                item.touched = false
                renderList()
            }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private val busy = Busy()
    private val saveUuid = java.util.UUID.randomUUID().toString()

    private fun confirm() {
        if (busy.isRunning) return
        if (items.isEmpty()) {
            Toast.makeText(this, "Ro'yxat bo'sh", Toast.LENGTH_SHORT).show(); return
        }
        val touched = items.count { it.touched }
        if (touched == 0) {
            Toast.makeText(this, "Hech qanday tovar sanalmadi", Toast.LENGTH_SHORT).show(); return
        }
        val notTouched = items.count { !it.touched }
        val extra = items.count { !it.wasInDoc }
        val diffs = items.count { it.touched && it.wasInDoc && it.counted != it.expected }

        val sb = StringBuilder()
        sb.append("$touched ta tovar sanaldi.")
        if (diffs > 0) sb.append("\n⚠ $diffs ta tovarda farq bor.")
        if (extra > 0) sb.append("\n⚠ $extra ta tovar hujjatda yo'q edi — qo'shiladi.")
        if (notTouched > 0) {
            // Ochiq aytamiz: sanalmagan qatorlar hujjatdagi holicha qoladi.
            // MoySklad hujjatida "sanalmagan" degan holat yo'q — shuning uchun
            // ularning qiymati o'zgarmaydi, o'chirilmaydi ham.
            sb.append("\n\n$notTouched ta tovar sanalmadi — ular hujjatda o'zgarishsiz qoladi.")
        }
        sb.append("\n\nHujjat O'TKAZILMAYDI — qoldiqlar o'zgarmaydi. " +
                  "Menejer kompyuterdan ko'rib o'tkazadi.")

        AlertDialog.Builder(this)
            .setTitle("Sanoqni saqlash")
            .setMessage(sb.toString())
            .setPositiveButton("Ha, saqlayman") { _, _ -> send() }
            .setNegativeButton("Bekor", null)
            .show()
    }

    private fun send() {
        if (!busy.start(b.btnConfirm)) return
        b.loading.visibility = View.VISIBLE
        // MUHIM: BARCHA qatorlar yuboriladi, sanalmaganlari ham.
        // Sabab: server hujjat pozitsiyalarini QAYTA YOZADI. Faqat
        // sanalganlarini yuborsak, qolganlari hujjatdan O'CHIB ketardi va
        // menejer ro'yxatni boy berardi.
        val lines = JSONArray()
        for (item in items) {
            lines.put(JSONObject().apply {
                put("product_moysklad_id", item.productMoyskladId)
                put("product_type", item.productType)
                put("quantity", item.counted)
            })
        }
        val body = JSONObject().apply {
            // TAKROR-HIMOYA: tarmoq uzilib qayta yuborilsa sanoq ikkilanmasin.
            put("client_uuid", saveUuid)
            put("inventory_id", inventoryId)
            put("lines", lines)
        }
        thread {
            val result = Api.post(this, "inventory-save", body)
            runOnUiThread {
                busy.stop(b.btnConfirm)
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        val name = result.json.optString("name", "Инвентаризация")
                        AlertDialog.Builder(this)
                            .setTitle("Saqlandi ✓")
                            .setMessage("MoySklad: $name\n\n" +
                                    "Hujjat o'tkazilmadi — menejer tekshirib o'tkazadi.")
                            .setPositiveButton("OK") { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                    is ApiResult.Error -> {
                        val msg = if (result.offline)
                            "Internet yo'q. Qayta urinib ko'ring."
                        else result.message
                        AlertDialog.Builder(this)
                            .setTitle("Saqlanmadi")
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun confirmExit() {
        if (items.none { it.touched }) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Chiqasizmi?")
            .setMessage("Sanoq saqlanmagan — kiritilgan sonlar yo'qoladi.")
            .setPositiveButton("Ha, chiqaman") { _, _ -> finish() }
            .setNegativeButton("Qolaman", null)
            .show()
    }

    /** Apparat "orqaga" tugmasi ham sanoqni jimgina yo'qotib yubormasin.
     *  targetSdk 34 da bu chaqiruv hali ishlaydi (faqat eskirgan deb belgilangan). */
    @Suppress("DEPRECATION")
    override fun onBackPressed() { confirmExit() }

    private fun productFromIntent(data: Intent): JSONObject = JSONObject().apply {
        put("found", true)
        put("name", data.getStringExtra("p_name") ?: "")
        put("barcode", data.getStringExtra("p_barcode") ?: "")
        put("price", data.getLongExtra("p_price", 0))
        put("moysklad_id", data.getStringExtra("p_moysklad_id") ?: "")
        put("uom", data.getStringExtra("p_uom") ?: "")
        val pq = data.getDoubleExtra("p_pack_qty", 0.0)
        if (pq > 0) { put("pack_qty", pq); put("is_pack", true) }
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun round3(d: Double): Double = Math.round(d * 1000.0) / 1000.0

    override fun onResume() {
        super.onResume()
        b.scanInput.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) b.scanInput.requestFocus()
    }

    /** Miqdor oynasi ochiq turganda kelgan SKAN ni ushlaymiz. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (qtyDialog?.isShowing == true && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastScanKey > 150) scanBuf.setLength(0)
            lastScanKey = now
            val kc = event.keyCode
            if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                val code = scanBuf.toString().trim()
                scanBuf.setLength(0)
                if (code.length >= 6) {
                    qtyConfirmWith?.invoke(qtyPrefill)
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
