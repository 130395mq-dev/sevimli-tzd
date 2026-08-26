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
        b.statusChip.setOnClickListener {
            if (OfflineQueue.size(this) > 0) flushQueue(manual = true)
            else Updater.check(this, silent = false)
        }

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
