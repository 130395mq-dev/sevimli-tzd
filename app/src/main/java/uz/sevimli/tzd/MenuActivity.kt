package uz.sevimli.tzd

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        // Sozlamalar endi header'ning o'ng burchagida (ilgari ro'yxat
        // oxiridagi kartochka edi). Ochadigan ekran o'sha-o'sha.
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.footerVersion.text = "v${BuildConfig.VERSION_NAME} · Jamlov"
        // Sklad yorlig'i. Bosilganda avvalgi mantiq: navbatda hujjat
        // bo'lsa yuboradi, bo'lmasa ilova yangilanishini tekshiradi.
        b.storeBar.setOnClickListener {
            if (OfflineQueue.size(this) > 0) flushQueue(manual = true)
            else Updater.check(this, silent = false)
        }

        Updater.forceCheck(this)   // MAJBURIY: yangi versiya bo'lsa so'ramasdan yangilaydi
    }

    /**
     * Bosh sahifa funksiyalarini KODDAN yasaydi — IKKI USTUNLI grid.
     *
     * FUNKSIYALAR VA TARTIB O'ZGARMADI:
     *   Просмотр (doim) -> MenuFunctions.LIST dagi tartib.
     * Qaysi bo'lim ko'rinishi avvalgidek `Config.isFn(...)` bilan hal
     * qilinadi, bosilganda o'sha `dispatch(fn)` chaqiriladi.
     *
     * O'zgargani faqat ko'rinish: kartochka ichi ixchamlashdi
     * (ikonka 46->38, padding 16->12), shuning uchun 8 ta funksiya
     * TSD ekraniga aylantirmasdan sig'adi.
     */
    private fun buildGrid() {
        val grid = b.gridContainer
        grid.removeAllViews()

        val cells = ArrayList<Cell>()
        // Просмотр товара — DOIM ko'rinadi (o'chirib bo'lmaydi)
        cells.add(Cell("Просмотр товара", "Narx va qoldiq", R.drawable.ic_lookup, false) {
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
                ).apply { if (grid.childCount > 0) topMargin = dp(8) }
            }
            for (col in 0 until 2) {
                if (i < cells.size) {
                    row.addView(makeCell(cells[i], leftInRow = (col == 0)))
                } else {
                    // Toq sonda qolsa — o'ng tomon bo'sh qoladi, lekin
                    // kartochka kengligi buzilmaydi.
                    row.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    })
                }
                i++
            }
            grid.addView(row)
        }
    }

    /** Bitta funksiya kartochkasi. */
    private fun makeCell(cell: Cell, leftInRow: Boolean): View {
        val card = layoutInflater.inflate(R.layout.item_menu_cell, b.gridContainer, false)
        card.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { if (leftInRow) marginEnd = dp(8) }

        val icon = card.findViewById<ImageView>(R.id.mIcon)
        val title = card.findViewById<TextView>(R.id.mTitle)
        val sub = card.findViewById<TextView>(R.id.mSub)

        // Ikonka to'g'ridan-to'g'ri havola bo'yicha (nomdan qidirish emas):
        // kompilyator tekshiradi va R8 resurs tozalashi uni olib tashlamaydi.
        icon.setImageResource(cell.icon)
        title.text = cell.title
        sub.text = cell.sub
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


    // AVTO-SINX endi bu yerda EMAS.
    //
    // Ilgari taymer shu ekranda turardi va menyu ekrandan ketishi bilan
    // `onPause()` uni o'chirardi — ya'ni xodim hujjat ichida ishlayotganda
    // hech narsa yangilanmasdi. Endi buni `SyncEngine` boshqaradi:
    // ilova ochiq bo'lsa istalgan ekranда, yopiq bo'lsa JobScheduler orqali.

    override fun onResume() {
        super.onResume()
        Updater.resumeIfPending(this)   // ruxsat berib qaytgan bo'lsa — majburiy yangilanish davom etadi
        buildGrid()            // sozlamalar o'zgargan bo'lsa — darrov aks etadi
        updateStatus()
        flushQueue(manual = false)
        loadToday()
        // Sinxni bu yerda chaqirmaymiz — `SyncEngine` ilova oldinga
        // chiqqanda o'zi bir marta yurgizadi va davriy taymerni yoqadi.
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
    }

    override fun onPause() {
        super.onPause()
        // Lenta ekran yopilganda to'xtaydi — fonda batareya yeyilmasin.
        TodayStrip.stop()
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

    /**
     * "Bugungi ishlar" — mavjud hujjatlar ro'yxatidan bugungi sonlar.
     *
     * Yangi funksiya emas: `/api/tzd/documents` allaqachon bor edi va
     * Hujjatlar ekrani o'shandan foydalanadi. Biz faqat bugungi kun
     * bo'yicha sanaymiz.
     *
     * Ma'lumot kelmasa yoki bugun ish bo'lmasa — butun bo'lim
     * KO'RSATILMAYDI. Bo'sh joy yoki o'ylab topilgan son chiqmaydi.
     */
    private fun loadToday() {
        thread {
            val items = TodayStrip.load(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.todayBox.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                if (items.isNotEmpty()) {
                    TodayStrip.render(b.todayScroll, b.todayTrack, items)
                }
            }
        }
    }

    private fun updateStatus() {
        val store = Config.storeName(this) ?: "Sklad tanlanmagan"
        val pending = OfflineQueue.size(this)
        b.statusChip.text =
            if (pending > 0) "$store · $pending yuborilmagan" else store
        // Nuqta rangi holatni aytadi: yashil — hammasi joyida,
        // sariq — navbatda yuborilmagan hujjat bor. Ilgari bu ma'no
        // matn ichidagi "⏳" belgisi bilan berilardi, uzoqdan bilinmasdi.
        b.storeDot.setBackgroundResource(
            if (pending > 0) R.drawable.bg_dot_warn else R.drawable.bg_dot_ok)
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

    // `fullRefresh()` OLIB TASHLANDI.
    //
    // U ekranni bloklaydigan oyna ochib, butun katalogni (22 000+ mahsulot)
    // qaytadan yuklardi va xodim uni qo'lda bosishi kerak edi. Endi bu ish
    // `SyncEngine` orqali jimgina, fonda bajariladi — birinchi to'liq
    // yuklash ham (qarang: CatalogSync.autoRefresh).

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
