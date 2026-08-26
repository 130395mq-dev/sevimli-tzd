package uz.sevimli.tzd

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import uz.sevimli.tzd.databinding.ActivityMenuBinding
import kotlin.concurrent.thread

class MenuActivity : AppCompatActivity() {

    private lateinit var b: ActivityMenuBinding

    private data class Cell(
        val title: String, val sub: String,
        @androidx.annotation.DrawableRes val icon: Int,
        val brand: Boolean, val action: () -> Unit,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.cardScannerTest.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.footerVersion.text = "v${BuildConfig.VERSION_NAME} · Jamlov"
        // Sklad yorlig'i — bosilganda Sozlamalar ochiladi (sklad o'sha yerda
        // tanlanadi). "Yangilash" tugmasi OLIB TASHLANDI: katalog fonda
        // o'zi yangilanadi (SyncEngine).
        b.storeBar.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnBell.setOnClickListener { showNotices() }

        Updater.forceCheck(this)   // MAJBURIY: yangi versiya bo'lsa so'ramasdan yangilaydi
    }

    /** Dashboard kartalarini KODDAN yasaydi: Просмотр (doim) + yoqilgan bo'limlar. */
    private fun buildGrid() {
        val grid = b.gridContainer
        grid.removeAllViews()

        val cells = ArrayList<Cell>()
        // Просмотр товара — DOIM ko'rinadi (o'chirib bo'lmaydi)
        cells.add(Cell("Просмотр товара", "Narx va qoldiq", R.drawable.ic_lookup, true) {
            startActivity(Intent(this, LookupActivity::class.java))
        })
        // Sozlamalarда yoqilgan bo'limlar
        for (fn in MenuFunctions.LIST) {
            if (!Config.isFn(this, fn.key)) continue
            cells.add(Cell(fn.title, fn.sub, fn.icon, false) { dispatch(fn) })
        }

        var i = 0
        while (i < cells.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (grid.childCount > 0) topMargin = dp(10) }
            }
            for (col in 0 until 2) {
                if (i < cells.size) {
                    row.addView(makeCard(cells[i], leftInRow = (col == 0)))
                } else {
                    val spacer = View(this)
                    spacer.layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    row.addView(spacer)
                }
                i++
            }
            grid.addView(row)
        }
    }

    private fun makeCard(cell: Cell, leftInRow: Boolean): View {
        val card = layoutInflater.inflate(R.layout.item_menu_card, b.gridContainer, false)
        val lp = LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (leftInRow) lp.marginEnd = dp(10)
        card.layoutParams = lp

        val icon = card.findViewById<ImageView>(R.id.mIcon)
        val title = card.findViewById<TextView>(R.id.mTitle)
        val sub = card.findViewById<TextView>(R.id.mSub)
        val wrap = card.findViewById<FrameLayout>(R.id.mIconWrap)

        // Ikonka endi to'g'ridan-to'g'ri havola bo'yicha (nomdan qidirish emas):
        // kompilyator tekshiradi va R8 resurs tozalashi uni olib tashlamaydi.
        icon.setImageResource(cell.icon)
        title.text = cell.title
        sub.text = cell.sub

        if (cell.brand) {
            (card as CardView).setCardBackgroundColor(getColor(R.color.brand))
            wrap.setBackgroundResource(R.drawable.bg_icon_circle_dark)
            icon.setColorFilter(getColor(R.color.white))
            title.setTextColor(getColor(R.color.white))
            sub.setTextColor(getColor(R.color.white70))
        }
        card.setOnClickListener { cell.action() }
        return card
    }

    private fun dispatch(fn: MenuFunctions.Fn) {
        if (fn.needsStore && !Config.hasStore(this)) {
            Toast.makeText(this, "Avval Sozlamalardan sklad tanlang", Toast.LENGTH_LONG).show()
            return
        }
        when (fn.key) {
            "move" -> startActivity(Intent(this, MoveInboxActivity::class.java))
            // Инвентаризация endi MoySklad'da yaratilgan ochiq sanoqlar
            // ro'yxatini ochadi. "Yangi sanoq" va "Hujjatlar" o'sha ekranda.
            "inventory" -> startActivity(Intent(this, InventoryInboxActivity::class.java))
            "etiketka" -> startActivity(Intent(this, EtiketkaActivity::class.java))
            else -> openDocs(fn.key, fn.title)
        }
    }

    private fun openDocs(type: String, title: String) {
        if (!Config.hasStore(this)) {
            Toast.makeText(this, "Avval Sozlamalardan sklad tanlang", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, DocumentsActivity::class.java).apply {
            putExtra("type", type)
            putExtra("title", title)
        })
    }

    /** Obuna holati oxirgi marta qachon tekshirilgan (millisekund). */
    private var lastPingAt = 0L

    /** Menyu ekrani hozir ko'rinib turibdimi (taymerni bekorga yoqmaslik uchun). */
    private var menuVisible = false

    // Menyu ochiq turganda har 2 daqiqada jimgina avto-yangilash (qo'lda "Yangilash" kerak emas)
    private val syncHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val syncTick = object : Runnable {
        override fun run() {
            thread { CatalogSync.autoRefresh(this@MenuActivity) }
            syncHandler.postDelayed(this, 2 * 60 * 1000L)
        }
    }

    override fun onResume() {
        super.onResume()
        menuVisible = true
        Updater.resumeIfPending(this)   // ruxsat berib qaytgan bo'lsa — majburiy yangilanish davom etadi
        buildGrid()            // sozlamalar o'zgargan bo'lsa — darrov aks etadi
        updateStatus()
        flushQueue(manual = false)
        refreshNotices()
        thread { CatalogSync.autoRefresh(this) }
        // Obuna holati: to'xtatilgan/muddati tugagan bo'lsa — bloklash ekrani.
        //
        // ILGARI: bu so'rov HAR ekran qaytishida ketardi. Hujjat yaratib
        // menyuga qaytish, sozlamadan chiqish — har safar tarmoq so'rovi.
        // Obuna holati soatlab o'zgarmaydi, shuning uchun 5 daqiqada bir marta
        // tekshirsak yetarli.
        val now = System.currentTimeMillis()
        if (now - lastPingAt > 5 * 60 * 1000L) {
            lastPingAt = now
            thread {
                Api.get(this, "ping")
                runOnUiThread { Api.blocked?.let { showBlocked(it) } }
            }
        } else {
            Api.blocked?.let { showBlocked(it) }
        }
        // davriy avto-yangilashni yoqamiz
        syncHandler.removeCallbacks(syncTick)
        syncHandler.postDelayed(syncTick, 2 * 60 * 1000L)
    }

    override fun onPause() {
        super.onPause()
        menuVisible = false
        syncHandler.removeCallbacks(syncTick)   // orqa fonда behuda ishlamasin
    }

    // ---- Obuna to'xtatilganda bloklash ekrani ----
    private var blockDialog: AlertDialog? = null
    private fun showBlocked(msg: String) {
        if (blockDialog?.isShowing == true) return
        val d = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d, d, d, d)
            addView(TextView(this@MenuActivity).apply {
                text = "⛔ Obuna to'xtatilgan"
                textSize = 21f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MenuActivity).apply {
                text = msg
                textSize = 15f
                setPadding(0, (14 * resources.displayMetrics.density).toInt(), 0, 0)
            })
        }
        blockDialog = AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Qayta tekshirish") { _, _ ->
                thread {
                    Api.get(this, "ping")
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (Api.blocked == null) { blockDialog?.dismiss(); blockDialog = null }
                        else Api.blocked?.let { showBlocked(it) }
                    }
                }
            }
            .create()
        blockDialog?.show()
    }

    private fun updateStatus() {
        val store = Config.storeName(this) ?: "Sklad tanlanmagan"
        val pending = OfflineQueue.size(this)
        b.statusChip.text =
            if (pending > 0) "$store · ⏳ $pending yuborilmagan" else store
    }

    // ==================== BILDIRISHNOMALAR ====================
    //
    // Yangi funksiya emas: mavjud ikkita ro'yxatning (kelgan ko'chirishlar,
    // ochiq sanoqlar) soni va yuborilmagan hujjatlar navbati. Xodim har
    // bo'limni ochib "bormikan" deb tekshirib yurmasin.

    private fun refreshNotices() {
        thread {
            val d = Notices.refresh(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.bellDot.visibility = if (d.isEmpty) View.GONE else View.VISIBLE
            }
        }
    }

    /** Bitta bildirishnoma turi. */
    private data class Notice(
        val icon: Int,
        val title: String,
        val sub: String,
        val count: String,
        val warn: Boolean,
        val action: () -> Unit,
    )

    private fun showNotices() {
        val d = Notices.last()
        val list = ArrayList<Notice>()

        d.updateName?.let { name ->
            list.add(Notice(
                icon = R.drawable.j_ic_download,
                title = "Yangi versiya $name",
                sub = "Bosing — darhol yangilanadi",
                count = "!", warn = true,
            ) { Updater.check(this, silent = false) })
        }
        if (d.moves > 0) list.add(Notice(
            icon = R.drawable.ic_move,
            title = "Kelgan ko'chirish",
            sub = "Qabul qilish kutilmoqda",
            count = d.moves.toString(), warn = false,
        ) { startActivity(Intent(this, MoveInboxActivity::class.java)) })

        if (d.inventories > 0) list.add(Notice(
            icon = R.drawable.ic_inventory,
            title = "Ochiq sanoq",
            sub = "Davom ettirish mumkin",
            count = d.inventories.toString(), warn = false,
        ) { startActivity(Intent(this, InventoryInboxActivity::class.java)) })

        if (d.pending > 0) list.add(Notice(
            icon = R.drawable.j_ic_send,
            title = "Yuborilmagan hujjat",
            sub = "Internet tiklanganda ketadi",
            count = d.pending.toString(), warn = true,
        ) { flushQueue(manual = true) })

        val view = layoutInflater.inflate(R.layout.dialog_notices, null)
        val holder = view.findViewById<LinearLayout>(R.id.noticeList)
        val empty = view.findViewById<View>(R.id.noticeEmpty)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("Yopish", null)
            .create()

        if (list.isEmpty()) {
            empty.visibility = View.VISIBLE
            // Ma'lumot eskirgan bo'lishi mumkin — jimgina qayta so'raymiz.
            thread {
                val fresh = Notices.refresh(this, force = true)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    b.bellDot.visibility = if (fresh.isEmpty) View.GONE else View.VISIBLE
                }
            }
        } else {
            for ((i, n) in list.withIndex()) {
                val row = layoutInflater.inflate(R.layout.item_notice, holder, false)
                row.findViewById<ImageView>(R.id.nIcon).setImageResource(n.icon)
                row.findViewById<TextView>(R.id.nTitle).text = n.title
                row.findViewById<TextView>(R.id.nSub).text = n.sub
                val badge = row.findViewById<TextView>(R.id.nCount)
                badge.text = n.count
                badge.setBackgroundResource(
                    if (n.warn) R.drawable.j_badge_warn else R.drawable.j_badge)
                row.setOnClickListener { dialog.dismiss(); n.action() }
                holder.addView(row)
                if (i < list.size - 1) {
                    holder.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).apply { marginStart = dp(14) ; marginEnd = dp(14) }
                        setBackgroundColor(getColor(R.color.card_stroke))
                    })
                }
            }
        }
        dialog.show()
    }

    private fun flushQueue(manual: Boolean) {
        if (OfflineQueue.size(this) == 0) {
            if (manual) Updater.check(this, silent = false)
            return
        }
        if (manual) Toast.makeText(this, "Yuborilmoqda...", Toast.LENGTH_SHORT).show()
        thread {
            val sent = OfflineQueue.flushBlocking(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (sent > 0) Toast.makeText(this, "$sent ta hujjat yuborildi ✓", Toast.LENGTH_SHORT).show()
                else if (manual) Toast.makeText(this, "Hozircha yuborilmadi (internet yoki server)", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }
    }

    private fun fullRefresh() {
        val label = TextView(this).apply {
            text = "Boshlanmoqda..."
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            textSize = 15f
        }
        // ILGARI bu oyna BEKOR QILINMASDI (setCancelable(false)) va ichida
        // butun katalog yuklanardi. Katta katalog va sekin internetda ilova
        // o'n daqiqalab qotib turishi mumkin edi — chiqish yo'li yo'q edi.
        val dlg = AlertDialog.Builder(this)
            .setTitle("🔄 To'liq yangilash")
            .setView(label)
            .setNegativeButton("Bekor qilish") { _, _ -> CatalogSync.cancel() }
            .setCancelable(false)
            .create()
        dlg.show()

        // 2 daqiqalik avto-sinx qulfni olib qo'ymasin — aks holda qo'lda
        // bosilgan "To'liq yangilash" jimgina hech narsa qilmasdan tugardi.
        syncHandler.removeCallbacks(syncTick)
        CatalogSync.beginManual()      // eski "bekor" belgisini tozalaymiz

        thread {
            var ok = false
            try {
                // Fonda endigina boshlangan avto-sinx bo'lsa — tugashini kutamiz.
                // Faqat `removeCallbacks` yetmaydi: u ALLAQACHON ishga tushgan
                // oqimni to'xtatmaydi.
                if (CatalogSync.isSyncing) {
                    runOnUiThread { if (!isFinishing) label.text = "Kutilmoqda..." }
                    CatalogSync.waitIdle()
                }
                if (!CatalogSync.isCancelRequested) {
                    runOnUiThread { if (!isFinishing) label.text = "Kontragentlar yuklanmoqda..." }
                    CatalogSync.syncCounterparties(this)
                }
                ok = CatalogSync.syncProductsFull(this) { done, total ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            label.text = "Mahsulotlar yuklanmoqda...\n$done / $total"
                        }
                    }
                }
            } catch (t: Throwable) {
                // Throwable — Exception ham, Error ham (masalan telefon
                // xotirasi to'lgan holat). Oyna BARIBIR yopilishi kerak,
                // aks holda "Bekor" tugmasidan boshqa chiqish yo'li qolmaydi.
                ok = false
            } finally {
                CatalogSync.endManual()   // bekor belgisi osilib qolmasin
            }
            runOnUiThread {
                // Oyna HAR DOIM yopiladi — himoyadan OLDIN. Aks holda ekran
                // yopilib ketgan holatda oyna osilib qolardi (WindowLeaked).
                try { dlg.dismiss() } catch (_: Exception) {}
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Avto-sinx taymerini FAQAT ekran ochiq bo'lsa qaytaramiz.
                // Aks holda foydalanuvchi menyudan chiqib ketgan bo'lsa ham
                // fon sinxroni ishlab turaverardi.
                if (menuVisible) {
                    syncHandler.removeCallbacks(syncTick)
                    syncHandler.postDelayed(syncTick, 2 * 60 * 1000L)
                }
                when {
                    ok -> Toast.makeText(this, "Yangilandi ✓", Toast.LENGTH_LONG).show()
                    CatalogSync.isCancelled ->
                        Toast.makeText(this, "To'xtatildi", Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(this,
                        "Yuklab bo'lmadi — internetni tekshiring. Keyingi avto-sinxda qayta urinadi.",
                        Toast.LENGTH_LONG).show()
                }
                updateStatus()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
