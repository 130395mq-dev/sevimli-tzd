package uz.sevimli.tzd

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import uz.sevimli.tzd.databinding.ActivityMenuBinding
import kotlin.concurrent.thread

class MenuActivity : AppCompatActivity() {

    private lateinit var b: ActivityMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.cardLookup.setOnClickListener {
            startActivity(Intent(this, LookupActivity::class.java))
        }
        b.cardScannerTest.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.cardReceiving.setOnClickListener { openDocs("supply", "Приёмка") }
        b.cardInventory.setOnClickListener { openDocs("inventory", "Инвентаризация") }
        b.cardMove.setOnClickListener {
            if (!Config.hasStore(this)) {
                Toast.makeText(this, "Avval Sozlamalardan sklad tanlang", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(this, MoveInboxActivity::class.java))
            }
        }
        b.cardPicking.setOnClickListener { openDocs("shipment", "Отгрузка") }
        b.cardWriteoff.setOnClickListener { openDocs("writeoff", "Списание") }

        b.footerVersion.text = "v${BuildConfig.VERSION_NAME} · Sevimli Market"

        // 🔄 To'liq yangilash (mahsulot + kontragent + qoldiqni qaytadan yuklaydi)
        b.btnRefresh.setOnClickListener { fullRefresh() }

        // Status chipni bosish: yuborilmagan hujjat bo'lsa yuboradi, aks holda yangilanish tekshiradi
        b.statusChip.setOnClickListener {
            if (OfflineQueue.size(this) > 0) flushQueue(manual = true)
            else Updater.check(this, silent = false)
        }

        Updater.check(this)
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
        updateStatus()
        // navbatdagi hujjatlarni jimgina yuboramiz
        flushQueue(manual = false)
        // internet bo'lsa — offline bazani jimgina yangilaymiz (chekланган)
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

    /** To'liq yangilash: mahsulot + kontragent + qoldiqni qaytadan yuklaydi (progress bilan). */
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
}
