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

        b.cardScannerTest.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        val soon = { _: android.view.View ->
            Toast.makeText(this, "Bu bo'lim tez kunda qo'shiladi", Toast.LENGTH_SHORT).show()
        }
        b.cardReceiving.setOnClickListener(soon)
        b.cardInventory.setOnClickListener(soon)
        b.cardMove.setOnClickListener(soon)
        b.cardPicking.setOnClickListener(soon)
        b.cardWriteoff.setOnClickListener(soon)
    }
}
