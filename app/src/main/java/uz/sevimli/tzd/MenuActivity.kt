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
        val title: String, val sub: String, val icon: String,
        val brand: Boolean, val action: () -> Unit,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.cardScannerTest.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.footerVersion.text = "v${BuildConfig.VERSION_NAME} · Sevimli Market"
        b.btnRefresh.setOnClickListener { fullRefresh() }
        b.statusChip.setOnClickListener {
            if (OfflineQueue.size(this) > 0) flushQueue(manual = true)
            else Updater.check(this, silent = false)
        }

        Updater.check(this)
    }

    /** Dashboard kartalarini KODDAN yasaydi: Просмотр (doim) + yoqilgan bo'limlar. */
    private fun buildGrid() {
        val grid = b.gridContainer
        grid.removeAllViews()

        val cells = ArrayList<Cell>()
        // Просмотр товара — DOIM ko'rinadi (o'chirib bo'lmaydi)
        cells.add(Cell("Просмотр товара", "Narx va qoldiq", "ic_lookup", true) {
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

        val resId = resources.getIdentifier(cell.icon, "drawable", packageName)
        if (resId != 0) icon.setImageResource(resId)
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

    override fun onResume() {
        super.onResume()
        buildGrid()            // sozlamalar o'zgargan bo'lsa — darrov aks etadi
        updateStatus()
        flushQueue(manual = false)
        thread { CatalogSync.autoRefresh(this) }
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
        val dlg = AlertDialog.Builder(this)
            .setTitle("🔄 To'liq yangilash")
            .setView(label)
            .setCancelable(false)
            .create()
        dlg.show()

        thread {
            runOnUiThread { label.text = "Kontragentlar yuklanmoqda..." }
            CatalogSync.syncCounterparties(this)
            val ok = CatalogSync.syncProductsFull(this) { done, total ->
                runOnUiThread { label.text = "Mahsulotlar yuklanmoqda...\n$done / $total" }
            }
            runOnUiThread {
                dlg.dismiss()
                if (ok) Toast.makeText(this, "Yangilandi ✓", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "Yuklab bo'lmadi — internetni tekshiring", Toast.LENGTH_LONG).show()
                updateStatus()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
