package uz.sevimli.tzd

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        /** Natijasi noma'lum saqlash shu kalit + hujjat ID bilan yoziladi. */
        private const val PENDING = "inventory_pending_"
        /** Noma'lum natijani necha marta qayta tekshirish. */
        private const val SUBMIT_TRIES = 8
        /**
         * Shundan eski noma'lum saqlash AVTOMATIK qayta yuborilmaydi —
         * xodimdan so'raladi. Server takrorni qancha vaqt eslab qolishi
         * bizga kafolatlanmagan; eski so'rovni ko'r-ko'rona yuborish
         * sanoqni ikkilantirib qo'yishi mumkin.
         */
        private const val PENDING_MAX_AGE_MS = 2 * 60 * 60 * 1000L
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
     * @param counted    HUJJATDA allaqachon turgan son. Bu — oldingi
     *                   saqlashlarning natijasi (pastdagi sklad sanog'i,
     *                   yoki boshqa terminal yozgani). Ilova buni
     *                   O'ZGARTIRMAYDI, faqat ko'rsatadi.
     * @param added      SHU SAFAR sanalgan son. Saqlanganda `counted` ning
     *                   USTIGA qo'shiladi. Ekran qaytadan ochilganda 0 dan
     *                   boshlanadi — xodim tepadagi tovarni toza sanaydi.
     * @param touched    ishchi shu tovarga SHU SAFAR tegdimi. `added == 0.0`
     *                   ikki xil ma'noda bo'ladi: "hali sanalmagan" va
     *                   "sanadim, yo'q ekan". Bu bayroq ularni ajratadi.
     * @param wasInDoc   MoySklad hujjatida bor edimi
     */
    data class InvItem(
        val productMoyskladId: String,
        val productType: String,
        val name: String,
        val expected: Double,
        var counted: Double = 0.0,
        var added: Double = 0.0,
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
            // 1-QADAM: YENGIL TEKSHIRUV.
            // Faqat sarlavha so'raladi (limit=0) - qator soni va hujjatning
            // MoySklad'dagi `updated` vaqti. Bu bir zumda keladi.
            val headRes = Api.get(this, "inventory-detail", mapOf(
                "id" to inventoryId, "limit" to "0"))
            if (headRes !is ApiResult.Success) {
                val msg = (headRes as? ApiResult.Error)?.message ?: ""
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    b.loading.visibility = View.GONE
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                return@thread
            }
            val total = headRes.json.optInt("total", 0)
            val updated = headRes.json.optString("updated", "")
            val store = headRes.json.optString("store", "-")
            runOnUiThread { b.headerRoute.text = store }

            // 2-QADAM: KESH.
            // Qator soni ham, `updated` ham mos kelsa - hujjat o'zgarmagan,
            // qurilmadagi nusxa ishlatiladi. Menejer qator qo'shsa yoki
            // boshqa terminal sanoq yozsa `updated` o'zgaradi va kesh
            // avtomatik tashlanadi.
            var fromCache = false
            if (total > 0 && updated.isNotEmpty()) {
                val cached = try {
                    LocalDb.get(this).invCacheGet(inventoryId, total, updated)
                } catch (e: Throwable) { null }
                if (cached != null) {
                    addFromJson(cached)
                    fromCache = true
                }
            }

            var failed: String? = null

            // 3-QADAM: kesh yaramasa - serverdan bo'lib yuklaymiz.
            if (!fromCache) {
                var offset = 0
                var guard = 0
                while (guard++ < 200) {
                    // Qisqa uzilish butun yuklashni bekor qilmasin: har
                    // sahifa uch marta so'raladi.
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
                    addFromJson(r.json.optJSONArray("positions") ?: JSONArray())
                    val loaded = items.size
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && total > 0) {
                            b.progressText.text =
                                getString(R.string.loading_products_fmt, loaded, total)
                        }
                    }
                    val next = r.json.optInt("next_offset", 0)
                    if (next <= offset) break
                    offset = next
                }

                // Keshga FAQAT to'liq ro'yxat yoziladi. Yarim ro'yxat keshga
                // tushsa, keyingi ochilishda u to'liq deb qabul qilinardi.
                if (failed == null && items.size == total && total > 0) {
                    try {
                        LocalDb.get(this).invCachePut(
                            inventoryId, total, updated, store,
                            items.map { arrayOf<Any>(
                                it.productMoyskladId, it.productType,
                                it.name, it.expected, it.counted) })
                    } catch (_: Throwable) {}
                }
            }

            val err = failed
            val cacheUsed = fromCache
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                if (err != null) {
                    // Yarim kelgan ro'yxat bilan ishlash XAVFLI: skanerlangan
                    // tovarning qatori yuklanmagan bo'lsa, u YANGI qator deb
                    // qo'shilib, hujjatda dublikat paydo bo'lardi.
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    items.clear()
                    renderList()
                    return@runOnUiThread
                }
                b.btnConfirm.isEnabled = true
                b.btnConfirm.alpha = 1f
                // Uzilib qolgan sanoq bo'lsa - tiklaymiz.
                val back = restoreDraft()
                // NATIJASI NOMA'LUM SAQLASH bo'lsa — avval o'sha hal qilinadi.
                // Aks holda xodim qoralamani yana yuborib, sanoqni
                // ikkilantirib qo'yishi mumkin edi (11.10.2026 dagi holat).
                val pending = loadPending()
                if (pending != null) {
                    renderList()
                    resumePending(pending)
                    return@runOnUiThread
                }
                if (back > 0) {
                    Toast.makeText(this,
                        getString(R.string.draft_restored_fmt, back),
                        Toast.LENGTH_LONG).show()
                } else if (cacheUsed) {
                    Toast.makeText(this, getString(R.string.from_cache),
                        Toast.LENGTH_SHORT).show()
                }
                renderList()
            }
        }
    }

    /** Serverdan yoki keshdan kelgan qatorlarni ro'yxatga qo'shadi. */
    private fun addFromJson(arr: JSONArray) {
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
    }

    private fun onScan(code: String) {
        if (blockedByPending()) return
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
        if (blockedByPending()) return
        val mid = product.optString("moysklad_id")
        if (mid.isBlank()) {
            ScanFeedback.fail(this)
            Toast.makeText(this, getString(R.string.err_not_in_ms), Toast.LENGTH_SHORT).show()
            return
        }
        val item = items.find { it.productMoyskladId == mid }
        if (item == null) {
            // HUJJATDA YO'Q TOVAR SANALMAYDI.
            //
            // ILGARI bunday tovar ro'yxatga SO'RAMASDAN qo'shilardi va
            // saqlanganda MoySklad hujjatiga yangi qator bo'lib yozilardi.
            // Adashib skanerlangan tovar ham shu yo'l bilan hujjatga tushib
            // qolardi va uni keyin faqat menejer o'chira olardi.
            //
            // DIQQAT — bu ONGLI tanlov, kamchiligi ham bor: omborda haqiqatan
            // ortiqcha tovar topilsa u bu sanoqqa KIRMAYDI. Uni hisobga olish
            // uchun menejer MoySklad'da hujjatga o'sha tovarni qo'shishi
            // kerak; shundan keyin terminalda ko'rinadi.
            ScanFeedback.fail(this)
            val nm = product.optString("name").ifBlank { product.optString("barcode") }
            Toast.makeText(this, getString(R.string.not_in_doc_refused_fmt, nm),
                Toast.LENGTH_LONG).show()
            return
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

        // MUHIM: bu yerda HUJJATDAGI son emas, SHU SAFARGI son turadi.
        // Xodim tepaga chiqqanda javondagi tovarni toza sanashi kerak;
        // pastdagi sanoq raqami ko'z oldida tursa u chalg'iydi.
        val was = item.added
        qName.text = item.name
        // Tizim qoldig'i ATAYIN ko'rsatilmaydi — xodim javondagi tovarni
        // sanashi kerak, ekrandagi songa moslashi emas.
        when {
            !item.wasInDoc -> {
                qPrice.visibility = View.VISIBLE
                qPrice.text = getString(R.string.was_not_in_doc)
            }
            // Hujjatda allaqachon son bor — xodim bilib tursin, lekin bu
            // faqat MA'LUMOT: kiritish maydoniga tushmaydi va qo'shiladigan
            // songa aralashmaydi.
            item.counted > 0 -> {
                qPrice.visibility = View.VISIBLE
                qPrice.text = getString(R.string.in_doc_qty_fmt, trimNum(item.counted))
            }
            else -> qPrice.visibility = View.GONE
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
                item.added = round3(item.added + addQty)
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
        val big = items.count { it.added > 0 } > DRAFT_BIG
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
                put("q", it2.added)
                put("d", it2.wasInDoc)
            })
        }
        DraftStore.save(this, DRAFT, JSONObject().apply {
            // v=2 - "q" endi SHU SAFAR sanalgan son (ilgari umumiy son edi).
            // Eski qoralama tiklansa u umumiy sonni USTIGA qo'shib yuborardi.
            put("v", 2)
            put("inv", inventoryId)
            put("lines", arr)
        }.toString())
    }

    /** Yuklash tugagach chaqiriladi. Boshqa hujjatning qoralamasi tiklanmaydi. */
    private fun restoreDraft(): Int {
        val raw = DraftStore.load(this, DRAFT) ?: return 0
        val j = try { JSONObject(raw) } catch (e: Exception) { return 0 }
        if (j.optString("inv") != inventoryId) return 0
        // Eski (6.9.9 va undan oldingi) formatdagi qoralama tiklanmaydi -
        // undagi son umumiy edi, endi esa qo'shiladigan son kerak.
        if (j.optInt("v", 1) < 2) { DraftStore.clear(this, DRAFT); return 0 }
        val arr = j.optJSONArray("lines") ?: return 0
        var n = 0
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            val id = l.optString("id")
            val q = l.optDouble("q", 0.0)
            val ex = items.find { it.productMoyskladId == id }
            if (ex != null) {
                ex.added = q; ex.touched = true; n++
            }
            // ESKI QORALAMA: hujjatda yo'q tovar tiklanmaydi. Bunday tovar
            // endi umuman sanalmaydi (yuqoridagi izohga qarang), shuning
            // uchun uni qaytarib qo'yish faqat chalkashlik tug'dirardi.
        }
        return n
    }

    private fun renderList() {
        var counted = 0
        val rows = ArrayList<RecvRowAdapter.Row>(items.size)
        for ((index, item) in items.withIndex()) {
            // Hujjatda soni bor qator ham "sanalgan" hisoblanadi — u boshqa
            // qavatda yoki boshqa terminalda sanalgan bo'lishi mumkin.
            val done = item.touched || item.counted > 0
            if (done) counted++

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
                // Oldingi saqlashda sanalgan: BELGISI turadi, SONI turmaydi.
                // Foydalanuvchi talabi: "qayta ochganda o'sha tovarning
                // sonini emas, 'sanaldi' bo'lib tursin" — son ko'rinib tursa
                // xodim uni tuzatishi kerakdek tuyuladi.
                item.counted > 0 -> {
                    status = getString(R.string.counted_lbl)
                    statusColor = R.color.text_gray
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
                // FAQAT shu safar sanalgan son. Hujjatdagi eski son bu yerga
                // chiqmaydi — yuqoridagi izohga qarang.
                qty = if (item.touched) trimNum(item.added) else "",
                qtyColor = if (item.touched) R.color.brand else R.color.text_gray,
                numColor = if (done) R.color.brand else R.color.text_gray,
                numBold = item.touched,
            ))
        }
        rowAdapter.submit(rows)
        b.progressText.text = getString(R.string.counted_fmt2, counted, items.size)
    }

    /**
     * Qatorga bosilganda ochiladigan oyna.
     *
     * MAYDON BO'SH BOSHLANADI. Foydalanuvchi talabi shu edi: oldingi
     * saqlashdagi son maydonda tursa, xodim uni tuzatishi kerakdek his
     * qiladi va chalg'iydi. Bu yerga SHU SAFAR sanalgan son yoziladi.
     *
     * Sanalgan sonlar oynaning MATN qismida ko'rinadi — "necha sanalgani
     * ko'rinsin" talabi shu tarzda bajarilgan: ma'lumot bor, lekin
     * kiritish maydoniga aralashmaydi.
     */
    private fun editItem(item: InvItem) {
        if (blockedByPending()) return
        // Yalang'och EditText o'rniga chetlari joyida turgan, yirik raqamli
        // maydon — oyna ilovaning qolgan qismi bilan bir uslubda bo'lsin.
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_qty_edit, null)
        val input = view.findViewById<EditText>(R.id.qeInput)
        input.setText("")
        val info = StringBuilder(if (item.wasInDoc)
            getString(R.string.real_qty_hint)
        else
            getString(R.string.real_qty_hint_extra))
        if (item.counted > 0) {
            info.append("\n").append(getString(R.string.in_doc_qty_fmt, trimNum(item.counted)))
        }
        if (item.added > 0) {
            info.append("\n").append(getString(R.string.this_time_fmt, trimNum(item.added)))
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(info.toString())
            .setView(view)
            .setPositiveButton(getString(R.string.save_kt)) { _, _ ->
                val v = input.text.toString().toDoubleOrNull()
                if (v != null && v >= 0) {
                    item.added = v
                    item.touched = true      // 0 kiritilsa ham "sanaldi"
                    markDraftDirty()
                    renderList()
                }
            }
            .setNegativeButton(getString(R.string.cancel_short), null)
            .show()
    }

    private val busy = Busy()
    private val handler = Handler(Looper.getMainLooper())
    private var savingDialog: AlertDialog? = null

    private fun confirm() {
        if (busy.isRunning) return
        loadPending()?.let { resumePending(it); return }
        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.list_empty), Toast.LENGTH_SHORT).show(); return
        }
        val touched = items.count { it.added > 0 }
        if (touched == 0) {
            Toast.makeText(this, getString(R.string.nothing_counted), Toast.LENGTH_SHORT).show(); return
        }
        // Umuman sanalmagan: na shu safar, na oldingi saqlashda.
        val notTouched = items.count { !it.touched && it.counted <= 0 }
        val extra = items.count { !it.wasInDoc }

        // SANOQ DAVOMIDA qoldiq ko'rsatilmaydi — xodim unga moslab
        // yozmasligi uchun. Lekin SAQLASHDAN OLDIN farqlar bir marta
        // ko'rsatiladi: qo'pol xato (noto'g'ri javon, blokni dona deb
        // kiritish) shu yerda ushlanadi va xodim qaytib tekshira oladi.
        // Halollik ham saqlanadi, xato ham menejergacha yetib bormaydi.
        // Farq HUJJATDAGI son + SHU SAFARGI son bo'yicha hisoblanadi —
        // saqlangandan keyin hujjatda aynan shu yig'indi turadi.
        val diffs = items
            .filter { it.added > 0 && it.wasInDoc && round3(it.counted + it.added) != it.expected }
            .sortedByDescending { kotlin.math.abs(round3(it.counted + it.added) - it.expected) }

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
        sb.append(getString(R.string.not_posted_note))
        sb.append(getString(R.string.manager_will_post))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.count_save_kt))
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.yes_save)) { _, _ -> send() }
            .setNegativeButton(getString(R.string.go_back_check), null)
            .show()
    }

    private fun send() {
        // Oldingi saqlash natijasi noma'lum bo'lsa — YANGI so'rov tuzilmaydi,
        // o'sha so'rov o'sha kalit bilan qayta tekshiriladi.
        loadPending()?.let { resumePending(it); return }
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
        //
        // QO'SHIB BORISH. Yuboriladigan son — SHU SAFAR sanalgani. Server
        // uni hujjatdagi songa QO'SHADI (`mode = add`). Shuning uchun
        // pastdagi sklad sanog'i tepadagi sanoq bilan yo'qolmaydi.
        val lines = JSONArray()
        for (item in items.filter { it.added > 0 }) {
            lines.put(JSONObject().apply {
                put("product_moysklad_id", item.productMoyskladId)
                put("product_type", item.productType)
                put("quantity", item.added)
            })
        }
        if (lines.length() == 0) {
            busy.stop(b.btnConfirm)
            b.loading.visibility = View.GONE
            Toast.makeText(this, getString(R.string.nothing_counted), Toast.LENGTH_SHORT).show()
            return
        }
        // TAKROR-HIMOYA. Kalit va qatorlar yuborishdan OLDIN qurilmaga
        // yoziladi. Natija aniq bo'lmaguncha AYNAN SHU so'rov shu kalit
        // bilan qayta yuboriladi — server takrorni tanib, ikkinchi marta
        // qo'shmaydi. Ilgari kalit faqat xotirada edi: hujjat qayta
        // ochilsa yangi kalit olinib, sanoq 2 barobar bo'lib ketardi.
        val pending = JSONObject().apply {
            put("v", 1)
            put("inv", inventoryId)
            put("key", java.util.UUID.randomUUID().toString())
            put("at", System.currentTimeMillis())
            put("lines", lines)
        }
        savePending(pending)
        submit(pending, 1)
    }

    // ==================== NATIJASI NOMA'LUM SAQLASH ====================

    private fun pendingKey() = PENDING + inventoryId

    private fun loadPending(): JSONObject? {
        val raw = DraftStore.load(this, pendingKey()) ?: return null
        val j = try { JSONObject(raw) } catch (e: Exception) { return null }
        if (j.optString("inv") != inventoryId || j.optString("key").isBlank()) return null
        if (j.optJSONArray("lines") == null) return null
        return j
    }

    private fun savePending(p: JSONObject) =
        DraftStore.save(this, pendingKey(), p.toString())

    private fun clearPending() = DraftStore.clear(this, pendingKey())

    /** Noma'lum saqlash turganda yangi son kiritilmaydi. */
    private fun blockedByPending(): Boolean {
        if (loadPending() == null) return false
        Toast.makeText(this, getString(R.string.save_pending_wait), Toast.LENGTH_SHORT).show()
        return true
    }

    /** Hujjat ochilganda yoki "Saqlash" qayta bosilganda chaqiriladi. */
    private fun resumePending(p: JSONObject) {
        val age = System.currentTimeMillis() - p.optLong("at", 0L)
        if (age in 0..PENDING_MAX_AGE_MS) {
            if (!busy.start(b.btnConfirm)) return
            submit(p, 1)
            return
        }
        // ESKI: ko'r-ko'rona qayta yubormaymiz, xodim MoySklad'da tekshiradi.
        val whenTxt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US)
            .format(java.util.Date(p.optLong("at", 0L)))
        askPendingOutcome(p, getString(R.string.save_old_title),
            getString(R.string.save_old_msg_fmt, whenTxt))
    }

    /**
     * Natijani faqat odam aniqlay oladigan holat: xodim MoySklad'da hujjatni
     * ko'radi va tanlaydi. "Keyinroq" — hech narsa o'zgarmaydi, so'rov
     * qurilmada qoladi.
     */
    private fun askPendingOutcome(p: JSONObject, title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.save_old_done)) { _, _ ->
                // Sanoq hujjatda bor — qoralama ham, noma'lum so'rov ham keraksiz.
                clearPending()
                DraftStore.clear(this, DRAFT)
                try { LocalDb.get(this).invCacheClear(inventoryId) } catch (_: Throwable) {}
                finish()
            }
            .setNegativeButton(getString(R.string.save_old_resend)) { _, _ ->
                if (!busy.start(b.btnConfirm)) return@setNegativeButton
                submit(p, 1)
            }
            .setNeutralButton(getString(R.string.save_later), null)
            .show()
    }

    private enum class Outcome { OK, REJECTED, UNKNOWN }

    /**
     * Javobni uch turga ajratadi.
     *
     * UNKNOWN — sanoq yozilgan ham, yozilmagan ham bo'lishi mumkin:
     *   vaqt tugadi / internet uzildi (server yozishni davom ettirgan bo'lishi
     *   mumkin), 409 (server shu kalitni HALI yozyapti), 408/429, 5xx.
     * REJECTED — server aniq rad etdi (400, 403, 404…): hech narsa yozilmagan,
     *   qoralama saqlanib qoladi va xodim qayta yuborishi xavfsiz.
     */
    private fun classify(r: ApiResult): Outcome = when (r) {
        is ApiResult.Success -> Outcome.OK
        is ApiResult.Error -> when {
            r.offline -> Outcome.UNKNOWN
            r.code == 0 -> Outcome.UNKNOWN
            r.code == 408 || r.code == 409 || r.code == 429 -> Outcome.UNKNOWN
            r.code >= 500 -> Outcome.UNKNOWN
            else -> Outcome.REJECTED
        }
    }

    /** Kutish: 5, 10, 15, 20, 30, 30, 30 soniya. */
    private fun retryDelay(attempt: Int): Long =
        (if (attempt <= 4) attempt * 5 else 30) * 1000L

    private fun showSaving(attempt: Int) {
        b.loading.visibility = View.VISIBLE
        val msg = getString(R.string.save_in_progress_fmt, attempt)
        val d = savingDialog
        if (d != null && d.isShowing) { d.setMessage(msg); return }
        savingDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.count_save_kt))
            .setMessage(msg)
            .setCancelable(false)
            .show()
    }

    private fun hideSaving() {
        b.loading.visibility = View.GONE
        savingDialog?.dismiss()
        savingDialog = null
    }

    /**
     * So'rovni yuboradi. Natija noma'lum bo'lsa o'sha so'rovni o'sha kalit
     * bilan qayta yuboradi. `busy` qulfi butun jarayon davomida turadi.
     */
    private fun submit(p: JSONObject, attempt: Int) {
        showSaving(attempt)
        val body = JSONObject().apply {
            put("client_uuid", p.getString("key"))
            put("inventory_id", inventoryId)
            put("lines", p.getJSONArray("lines"))
            put("mode", "add")
        }
        thread {
            val result = Api.post(this, "inventory-save", body, Api.LONG_POST_MS)
            runOnUiThread {
                // Ekran yopilgan bo'lsa — noma'lum so'rov qurilmada qoladi va
                // hujjat keyingi ochilganda hal qilinadi.
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (classify(result)) {
                    Outcome.OK -> {
                        clearPending()
                        busy.stop(b.btnConfirm)
                        hideSaving()
                        // Serverga yozildi - endi qoralama keraksiz.
                        DraftStore.clear(this, DRAFT)
                        // Hujjat serverda o'zgardi - kesh yaroqsiz.
                        try { LocalDb.get(this).invCacheClear(inventoryId) }
                        catch (_: Throwable) {}
                        val json = (result as ApiResult.Success).json
                        val name = json.optString("name", getString(R.string.inventory))
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.saved_ok))
                            .setMessage("MoySklad: $name\n\n" +
                                    getString(R.string.not_posted_manager))
                            .setPositiveButton(getString(R.string.ok)) { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                    Outcome.REJECTED -> {
                        // Server aniq rad etdi — hech narsa yozilmagan.
                        clearPending()
                        busy.stop(b.btnConfirm)
                        hideSaving()
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.not_saved))
                            .setMessage((result as ApiResult.Error).message)
                            .setPositiveButton(getString(R.string.ok), null)
                            .show()
                    }
                    Outcome.UNKNOWN -> {
                        if (attempt < SUBMIT_TRIES) {
                            showSaving(attempt + 1)
                            handler.postDelayed({
                                if (!isFinishing && !isDestroyed) submit(p, attempt + 1)
                            }, retryDelay(attempt))
                        } else {
                            // Qurilmada qoladi. "Saqlash" qayta bosilsa yoki
                            // hujjat qayta ochilsa — xuddi shu so'rov ketadi.
                            busy.stop(b.btnConfirm)
                            hideSaving()
                            askPendingOutcome(p, getString(R.string.save_unknown_title),
                                getString(R.string.save_unknown_msg))
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Qayta urinish ekran yopilgach ketmasin. Noma'lum so'rov qurilmada
        // saqlangan — hujjat keyingi ochilganda hal qilinadi.
        handler.removeCallbacksAndMessages(null)
        savingDialog?.dismiss()
        savingDialog = null
    }

    private fun confirmExit() {
        if (busy.isRunning) {
            // Saqlash ketyapti — chiqish so'rovni yo'qotmaydi (u qurilmada),
            // lekin xodim natijani ko'rmay ketmasin.
            Toast.makeText(this, getString(R.string.save_pending_wait), Toast.LENGTH_SHORT).show()
            return
        }
        if (items.none { it.added > 0 }) { finish(); return }
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
