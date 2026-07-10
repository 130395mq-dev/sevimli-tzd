package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import uz.sevimli.tzd.databinding.ActivityMenuBinding

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


        val soon = { _: android.view.View ->
            Toast.makeText(this, "Bu bo'lim tez kunda qo'shiladi", Toast.LENGTH_SHORT).show()
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
            if (!Config.hasStore(this)) {
                Toast.makeText(this, "Avval Sozlamalardan sklad tanlang", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(this, PickInboxActivity::class.java))
            }
        }
        b.cardWriteoff.setOnClickListener(soon)

        // Pastdagi versiya yozuvi — haqiqiy versiyani ko'rsatadi
        b.footerVersion.text = "v${BuildConfig.VERSION_NAME} · Sevimli Market"

        // Status chipni bosib qo'lda yangilanishni tekshirish mumkin
        b.statusChip.setOnClickListener { Updater.check(this, silent = false) }

        // Menyu ochilganda jimgina yangilanishni tekshiradi
        Updater.check(this)
    }

    /** Funksiyaning o'z hujjatlar ro'yxatini ochadi (sklad tanlanган bo'lsa). */
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
        // Status chip: tanlangan sklad
        val store = Config.storeName(this)
        b.statusChip.text = if (store != null) "TZD-01 · $store" else "Sklad tanlanmagan"
    }
}
