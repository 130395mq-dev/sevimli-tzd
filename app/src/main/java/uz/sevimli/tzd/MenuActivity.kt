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
            if (!Config.hasStore(this)) {
                Toast.makeText(this, "Avval Sozlamalardan sklad tanlang", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(this, SupplyActivity::class.java))
            }
        }
        b.cardInventory.setOnClickListener(soon)
        b.cardMove.setOnClickListener(soon)
        b.cardPicking.setOnClickListener(soon)
        b.cardWriteoff.setOnClickListener(soon)

        // Pastdagi versiya yozuvi — haqiqiy versiyani ko'rsatadi
        b.footerVersion.text = "v${BuildConfig.VERSION_NAME} · Sevimli Market"

        // Status chipni bosib qo'lda yangilanishni tekshirish mumkin
        b.statusChip.setOnClickListener { Updater.check(this, silent = false) }

        // Menyu ochilganda jimgina yangilanishni tekshiradi
        Updater.check(this)
    }

    override fun onResume() {
        super.onResume()
        // Status chip: tanlangan sklad
        val store = Config.storeName(this)
        b.statusChip.text = if (store != null) "TZD-01 · $store" else "Sklad tanlanmagan"
    }
}
