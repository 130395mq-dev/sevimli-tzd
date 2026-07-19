package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
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

        // "Skaner diagnostika" kartasi endi Sozlamalarni ochadi
        b.cardScannerTest.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        b.cardReceiving.setOnClickListener {
            openDocs("supply", "Приёмка")
        }
        b.cardInventory.setOnClickListener {
            openDocs("inventory", "Инвентаризация")
        }
        b.cardMove.setOnClickListener {
            if (!Config.hasStore(this)) {
                Toast.makeText(this, "Avval Sozlamalardan sklad tanlang", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(this, MoveInboxActivity::class.java))
            }
        }
        b.cardPicking.setOnClickListener {
            openDocs("shipment", "Отгрузка")
        }
        b.cardWriteoff.setOnClickListener {
            openDocs("writeoff", "Списание")
        }

        // Pastdagi versiya yozuvi — haqiqiy versiyani ko'rsatadi
        b.footerVersion.text = "v${BuildConfig.VERSION_NAME} · Sevimli Market"

        // Status chipni bosish:
        //  - yuborilmagan hujjat bo'lsa — hozir yuborishga urinadi
        //  - bo'lmasa — yangilanishni tekshiradi
        b.statusChip.setOnClickListener {
            if (OfflineQueue.size(this) > 0) flushQueue(manual = true)
            else Updater.check(this, silent = false)
        }

        // Menyu ochilganda jimgina yangilanishni tekshiradi
        Updater.check(this)
    }

    /** Funksiyaning o'z hujjatlar ro'yxatini ochadi (sklad tanlangan bo'lsa). */
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
        // Menyuga har qaytganda navbatdagi hujjatlarni jimgina yuborishga urinamiz
        flushQueue(manual = false)
    }

    /** Status chip: tanlangan sklad + yuborilmagan hujjatlar soni. */
    private fun updateStatus() {
        val store = Config.storeName(this) ?: "Sklad tanlanmagan"
        val pending = OfflineQueue.size(this)
        b.statusChip.text =
            if (pending > 0) "$store · ⏳ $pending yuborilmagan" else store
    }

    /** Navbatdagi hujjatlarni fon oqimida yuborishga urinadi. */
    private fun flushQueue(manual: Boolean) {
        if (OfflineQueue.size(this) == 0) {
            if (manual) Updater.check(this, silent = false)
            return
        }
        if (manual) Toast.makeText(this, "Yuborilmoqda...", Toast.LENGTH_SHORT).show()
        thread {
            val sent = OfflineQueue.flushBlocking(this)
            runOnUiThread {
                if (sent > 0) {
                    Toast.makeText(this, "$sent ta hujjat yuborildi ✓", Toast.LENGTH_SHORT).show()
                } else if (manual) {
                    Toast.makeText(this, "Hozircha yuborilmadi (internet yoki server)", Toast.LENGTH_SHORT).show()
                }
                updateStatus()
            }
        }
    }
}
