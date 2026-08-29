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

    companion object {
        /** Bitta so'rovda olinadigan qator soni. Serverdagi chegara 1000. */
        private const val PAGE = 1000
        /** Bitta sahifani necha marta qayta so'rash. */
        private const val PAGE_TRIES = 3
        /** Qurilmada saqlanadigan qoralama kaliti. */
        private const val DRAFT = "inventory"
        /** Shu sondan ko'p qator sanalgach qoralama tez-tez emas, oralab yoziladi. */
        private const val DRAFT_BIG = 300
        private const val DRAFT_MIN_MS = 2000L
    }

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
        b.headerName.text = intent.getStringExtra("inventory_name") ?: getString(R.string.inventory)

        b.btnBack.setOnClickListener { confirmExit() }
        b.btnConfirm.setOnClickListener { confirm() }
        b.btnAdd.setOnClickListener {
            pickProduct.launch(Intent(this, ProductSearchActivity::class.java))
        }
        ScanInput.bind(this, b.scanInput) { code -> onScan(code) }

        loadDetail()
    }

    /**
     * Hujjat tarkibini BO'LIB yuklaydi.
     *
     * NEGA BO'LIB. 7000+ qatorli hujjat bitta so'rovda kelmaydi: MoySklad
     * sekin javob beradi va GET uchun berilgan 12 soniya yetmaydi -
     * "Server javob bermadi" chiqardi. Ilgari server bir marta 1000 ta
     * qator qaytarardi va qolgani JIMGINA tushib qolardi.
     *
     * Endi sahifama-sahifa olinadi va ekranda necha qator kelgani
     * ko'rinib turadi. `next_offset = 0` - hammasi keldi.
     */
    private fun loadDetail() {
        b.loading.visibility = View.VISIBLE
        b.btnConfirm.isEnabled = false
        b.btnConfirm.alpha = 0.5f
        items.clear()
        thread {
            var offset = 0
            var total = 0
            var guard = 0            // cheksiz siklga qarshi
            var failed: String? = null

            while (guard++ < 200) {
                // Qisqa uzilish butun yuklashni bekor qilmasin: har sahifa
                // uch marta so'raladi. 10 000 qatorli hujjatda bitta
                // sahifaning tasodifiy xatosi tufayli qaytadan boshlash
                // xodim uchun juda qimmat.
                var r: ApiResult? = null
                for (attempt in 1..PAGE_TRIES) {
                    r = Api.get(this, "inventory-detail", mapOf(
                        "id" to inventoryId,
                        "offset" to offset.toString(),
                        "limit" to PAGE.toString()))
                    if (r is ApiResult.Success) break
                    if (attempt < PAGE_TRIES) Thread.sleep(1200L * attempt)
                }
                if (r !is ApiResult.Success) {
                    failed = (r as? ApiResult.Error)?.message ?: ""
                    break
                }
                val j = r.json
                if (offset == 0) {
                    total = j.optInt("total", 0)
                    val store = j.optString("store", "-")
                    runOnUiThread { b.headerRoute.text = store }
                }
                val arr = j.optJSONArray("positions") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    items.add(InvItem(
                        productMoyskladId = p.optString("product_moysklad_id"),
                        productType = p.optString("product_type", "product"),
                        name = p.optString("name"),
                        expected = p.optDouble("expected_qty", 0.0),
                        // Hujjatda allaqachon turgan fakt (sanoq davom ettirilsa).
                        // `touched=false` - buni ishchi EMAS, hujjat keltirdi.
                        counted = p.optDouble("counted_qty", 0.0),
                        touched = false,
                        wasInDoc = true,
                    ))
                }
                val loaded = items.size
                val t = total
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && t > 0) {
                        b.progressText.text =
                            getString(R.string.loading_products_fmt, loaded, t)
                    }
                }
                val next = j.optInt("next_offset", 0)
                if (next <= offset) break        // tugadi yoki oldinga siljimadi
                offset = next
            }

            val err = failed
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                if (err != null && items.isEmpty()) {
                    // Ro'yxat kelmasa tugma o'chiq qoladi - aks holda xodim
                    // bo'sh hujjatni saqlab, MoySklad'dagi ro'yxatni
                    // o'chirib yuborishi mumkin edi.
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (err != null) {
                    // Yarim kelgan ro'yxat bilan ishlash XAVFLI: saqlashda
                    // kelmagan qatorlar hujjatdan o'chib ketardi.
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    items.clear()
                    renderList()
                    return@runOnUiThread
                }
                b.btnConfirm.isEnabled = true
                b.btnConfirm.alpha = 1f
                // Uzilib qolgan sanoq bo'lsa - tiklaymiz.
                val back = restoreDraft()
                if (back > 0) {
                    Toast.makeText(this,
                        getString(R.string.draft_restored_fmt, back),
                        Toast.LENGTH_LONG).show()
                }
                renderList()
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
                        Toast.makeText(this, getString(R.string.product_not_found), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.err_not_in_ms), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.added_not_in_doc_fmt, item.name),
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
        // Tizim qoldig'i ATAYIN ko'rsatilmaydi — xodim javondagi tovarni
        // sanashi kerak, ekrandagi songa moslashi emas.
        if (item.wasInDoc) {
            qPrice.visibility = View.GONE
        } else {
            qPrice.visibility = View.VISIBLE
            qPrice.text = getString(R.string.was_not_in_doc)
        }
        qWas.text = getString(R.string.counted_fmt1, trimNum(was))

        val packQty = product.optDouble("pack_qty", 0.0)
        val isPack = product.optBoolean("is_pack", false) && packQty > 0
        val packUom = product.optString("uom", "").let { if (it.isBlank()) "" else " $it" }
        val scaleWeight = product.optDouble("scale_weight", 0.0)
        when {
            product.optBoolean("scale", false) && scaleWeight > 0 -> {
                qPackInfo.visibility = View.VISIBLE
                qPackInfo.text = getString(R.string.scale_fmt, trimNum(scaleWeight))
                qInput.setText(trimNum(scaleWeight))
            }
            isPack -> {
                qPackInfo.visibility = View.VISIBLE
                qInput.setText("1")
            }
            product.optBoolean("pack_unknown", false) -> {
                qPackInfo.visibility = View.VISIBLE
                qPackInfo.text = getString(R.string.blok_enter_qty)
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
                getString(R.string.pack_calc_fmt, trimNum(typed), trimNum(packQty), trimNum(total), packUom)
            return total
        }
        fun updateWill() {
            val will = was + currentQty()
            qWill.text = getString(R.string.total_fmt, trimNum(will))
            qWill.setTextColor(getColor(R.color.brand))
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
                markDraftDirty()
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

    /**
     * Ro'yxat. XODIM TIZIM QOLDIG'INI KO'RMAYDI.
     *
     * Sabab (Sklad 15 dagi kabi): qoldiq ko'rinib tursa, sanoq "ko'chirib
     * yozish" ga aylanadi — xodim javondagi tovarni sanash o'rniga ekrandagi
     * songa moslaydi. Inventarizatsiyaning butun ma'nosi shunda yo'qoladi.
     *
     * Shuning uchun qatorда faqat FAKT turadi. Farqni menejer kompyuterda,
     * MoySklad hujjatida ko'radi — u yerda ikkala son ham bor.
     */
    // ------------------------------------------------------------------
    //  QORALAMA (uzilishga qarshi)
    // ------------------------------------------------------------------
    //  Sanoq faqat XOTIRADA turardi: ilova yopilsa, batareya tugasa yoki
    //  xodim boshqa ekranga o'tib qaytsa - kiritilgan sonlar yo'qolardi.
    //  10 000 qatorli hujjatda bu bir kunlik ishni yo'qotish demakdir.
    //
    //  Endi har o'zgarishda qurilmaga yoziladi. Faqat SANALGAN qatorlar
    //  saqlanadi - hujjat qaytadan yuklanganda qolgani baribir serverdan
    //  keladi.
    // ------------------------------------------------------------------

    private var draftDirty = false
    private var lastDraftMs = 0L

    /**
     * O'zgarish bo'ldi - qoralamani yozish kerak.
     *
     * NEGA ORALAB. Har skanda BARCHA sanalgan qatorlar qaytadan yoziladi.
     * 300 tagacha bu sezilmaydi. 5000 qator sanalganda esa har skanda
     * yuzlab kilobayt yozish ekranni sekinlashtirardi. Shuning uchun
     * ro'yxat kattalashgach yozish 2 soniyada bir marta bajariladi.
     *
     * Kichik ro'yxatda esa DARHOL yoziladi - eng ko'p uchraydigan holat
     * to'liq himoyalangan bo'lib qoladi.
     */
    private fun markDraftDirty() {
        draftDirty = true
        val big = items.count { it.touched } > DRAFT_BIG
        val now = System.currentTimeMillis()
        if (!big || now - lastDraftMs >= DRAFT_MIN_MS) flushDraft()
    }

    private fun flushDraft() {
        if (!draftDirty) return
        draftDirty = false
        lastDraftMs = System.currentTimeMillis()
        saveDraft()
    }

    /** Ekran fondan chiqsa - kutib turgan qoralama albatta yoziladi. */
    override fun onPause() {
        super.onPause()
        flushDraft()
    }

    private fun saveDraft() {
        val counted = items.filter { it.touched }
        if (counted.isEmpty()) { DraftStore.clear(this, DRAFT); return }
        val arr = JSONArray()
        for (it2 in counted) {
            arr.put(JSONObject().apply {
                put("id", it2.productMoyskladId)
                put("t", it2.productType)
                put("n", it2.name)
                put("q", it2.counted)
                put("d", it2.wasInDoc)
            })
        }
        DraftStore.save(this, DRAFT, JSONObject().apply {
            put("inv", inventoryId)
            put("lines", arr)
        }.toString())
    }

    /** Yuklash tugagach chaqiriladi. Boshqa hujjatning qoralamasi tiklanmaydi. */
    private fun restoreDraft(): Int {
        val raw = DraftStore.load(this, DRAFT) ?: return 0
        val j = try { JSONObject(raw) } catch (e: Exception) { return 0 }
        if (j.optString("inv") != inventoryId) return 0
        val arr = j.optJSONArray("lines") ?: return 0
        var n = 0
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            val id = l.optString("id")
            val q = l.optDouble("q", 0.0)
            val ex = items.find { it.productMoyskladId == id }
            if (ex != null) {
                ex.counted = q; ex.touched = true; n++
            } else {
                // Hujjatda yo'q, lekin xodim skanerlagan tovar.
                items.add(InvItem(
                    productMoyskladId = id,
                    productType = l.optString("t", "product"),
                    name = l.optString("n"),
                    expected = 0.0,
                    counted = q,
                    touched = true,
                    wasInDoc = l.optBoolean("d", false),
                ))
                n++
            }
        }
        return n
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
                    status = getString(R.string.not_in_doc_plus)
                    statusColor = R.color.warning
                }
                item.touched -> {
                    status = getString(R.string.counted_lbl)
                    statusColor = R.color.brand
                }
                else -> {
                    status = ""
                    statusColor = R.color.text_gray
                }
            }

            rows.add(RecvRowAdapter.Row(
                num = "${index + 1}",
                name = item.name,
                status = status,
                statusColor = statusColor,
                // FAQAT fakt. Ilgari "qoldiq / fakt" turardi.
                qty = trimNum(item.counted),
                qtyColor = if (item.touched) R.color.brand else R.color.text_gray,
                numColor = if (item.touched) R.color.brand else R.color.text_gray,
                numBold = item.touched,
            ))
        }
        rowAdapter.submit(rows)
        b.progressText.text = getString(R.string.counted_fmt2, counted, items.size)
    }

    /**
     * Qo'lda tuzatish. Bu yerda 0 ham QABUL QILINADI — "sanadim, yo'q ekan"
     * degani. Aynan shu holat kamomadni ko'rsatadi.
     */
    private fun editItem(item: InvItem) {
        // Yalang'och EditText o'rniga chetlari joyida turgan, yirik raqamli
        // maydon — oyna ilovaning qolgan qismi bilan bir uslubda bo'lsin.
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_qty_edit, null)
        val input = view.findViewById<EditText>(R.id.qeInput)
        input.setText(trimNum(item.counted))
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(if (item.wasInDoc)
                getString(R.string.real_qty_hint)
            else
                getString(R.string.real_qty_hint_extra))
            .setView(view)
            .setPositiveButton(getString(R.string.save_kt)) { _, _ ->
                val v = input.text.toString().toDoubleOrNull()
                if (v != null && v >= 0) {
                    item.counted = v
                    item.touched = true      // 0 kiritilsa ham "sanaldi"
                    markDraftDirty()
                    renderList()
                }
            }
            .setNegativeButton(getString(R.string.cancel_short), null)
            .show()
    }

    private val busy = Busy()
    private val saveUuid = java.util.UUID.randomUUID().toString()

    private fun confirm() {
        if (busy.isRunning) return
        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.list_empty), Toast.LENGTH_SHORT).show(); return
        }
        val touched = items.count { it.touched }
        if (touched == 0) {
            Toast.makeText(this, getString(R.string.nothing_counted), Toast.LENGTH_SHORT).show(); return
        }
        val notTouched = items.count { !it.touched }
        val extra = items.count { !it.wasInDoc }

        // SANOQ DAVOMIDA qoldiq ko'rsatilmaydi — xodim unga moslab
        // yozmasligi uchun. Lekin SAQLASHDAN OLDIN farqlar bir marta
        // ko'rsatiladi: qo'pol xato (noto'g'ri javon, blokni dona deb
        // kiritish) shu yerda ushlanadi va xodim qaytib tekshira oladi.
        // Halollik ham saqlanadi, xato ham menejergacha yetib bormaydi.
        val diffs = items
            .filter { it.touched && it.wasInDoc && it.counted != it.expected }
            .sortedByDescending { kotlin.math.abs(it.counted - it.expected) }

        val sb = StringBuilder()
        sb.append(getString(R.string.counted_n_fmt, touched))
        if (extra > 0) sb.append(getString(R.string.extra_items_fmt, extra))
        if (notTouched > 0) {
            // Ochiq aytamiz: sanalmagan qatorlar hujjatdagi holicha qoladi.
            // MoySklad hujjatida "sanalmagan" degan holat yo'q — shuning uchun
            // ularning qiymati o'zgarmaydi, o'chirilmaydi ham.
            sb.append(getString(R.string.untouched_fmt, notTouched))
        }
        if (diffs.isNotEmpty()) {
            // DIQQAT: bu yerda FARQ SONI ATAYIN yozilmaydi, faqat NOMLAR.
            // Agar "Guruch: -5" deb yozsak, xodim tizim qoldig'ini bilib
            // oladi va sanoq yana "raqamga moslash" ga aylanadi — ya'ni
            // qoldiqni yashirganimizning ma'nosi qolmaydi.
            // Nom yetarli: xodim o'sha tovarni qaytib sanaydi, xolos.
            sb.append(getString(R.string.diffs_header_fmt, diffs.size))
            for (it2 in diffs.take(8)) sb.append(getString(R.string.diff_line_fmt, it2.name))
            if (diffs.size > 8) sb.append(getString(R.string.diff_more_fmt, diffs.size - 8))
            sb.append(getString(R.string.recount_hint))
        }
        sb.append(getString(R.string.not_posted_note) +
                  "Menejer kompyuterdan ko'rib o'tkazadi.")

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.count_save_kt))
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.yes_save)) { _, _ -> send() }
            .setNegativeButton(getString(R.string.go_back_check), null)
            .show()
    }

    private fun send() {
        if (!busy.start(b.btnConfirm)) return
        flushDraft()          // kutib turgan o'zgarish yo'qolmasin
        b.loading.visibility = View.VISIBLE
        // FAQAT SANALGAN qatorlar yuboriladi.
        //
        // ILGARI hammasi yuborilardi, chunki server pozitsiyalarni QAYTA
        // YOZARDI va yuborilmagan qator hujjatdan o'chib ketardi.
        // Server `POST /positions` ga o'tkazilgach bu holat o'zgardi:
        // endi faqat yuborilgan qator YANGILANADI, qolganlariga TEGILMAYDI.
        //
        // Buning uchligi bor:
        //   1. Tarmoq uzilsa ham hujjatdan hech narsa o'chmaydi;
        //   2. 10 ta terminal bitta hujjatda ishlay oladi - har biri
        //      faqat o'zi sanaganini yozadi, boshqasinikini bosmaydi;
        //   3. 10 000 qatorli hujjatda ham so'rov kichik bo'ladi.
        val lines = JSONArray()
        for (item in items.filter { it.touched }) {
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
                        // Serverga yozildi - endi qoralama keraksiz.
                        DraftStore.clear(this, DRAFT)
                        val name = result.json.optString("name", getString(R.string.inventory))
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.saved_ok))
                            .setMessage("MoySklad: $name\n\n" +
                                    getString(R.string.not_posted_manager))
                            .setPositiveButton(getString(R.string.ok)) { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                    is ApiResult.Error -> {
                        val msg = if (result.offline)
                            getString(R.string.no_internet_retry2)
                        else result.message
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.not_saved))
                            .setMessage(msg)
                            .setPositiveButton(getString(R.string.ok), null)
                            .show()
                    }
                }
            }
        }
    }

    private fun confirmExit() {
        if (items.none { it.touched }) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.exit_q))
            .setMessage(getString(R.string.count_unsaved_note))
            .setPositiveButton(getString(R.string.yes_exit)) { _, _ -> finish() }
            .setNegativeButton(getString(R.string.stay), null)
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
