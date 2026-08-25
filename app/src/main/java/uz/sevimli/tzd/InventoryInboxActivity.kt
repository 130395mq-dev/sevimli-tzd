package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import uz.sevimli.tzd.databinding.ActivityInventoryInboxBinding
import kotlin.concurrent.thread

/**
 * Инвентаризация bosh ekrani.
 *
 * MoySklad'da yaratilgan, hali o'tkazilmagan sanoqlar ro'yxati. Dokumentni
 * ochib skanerlab sanaladi. Pastda «Yangi sanoq» — noldan boshlash (eski oqim,
 * InventoryActivity).
 *
 * MoveInboxActivity bilan ataylab bir xil tuzilgan: o'sha oqim ishlab turibdi
 * va sinalgan, uni takrorlash yangi mantiq o'ylab topishdan xavfsizroq.
 */
class InventoryInboxActivity : AppCompatActivity() {

    private lateinit var b: ActivityInventoryInboxBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityInventoryInboxBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.headerStore.text = Config.storeName(this) ?: "Sklad tanlanmagan"
        b.btnBack.setOnClickListener { finish() }
        b.btnRefresh.setOnClickListener { load() }
        b.btnNew.setOnClickListener {
            startActivity(Intent(this, InventoryActivity::class.java))
        }
        // Avval yaratilgan hujjatlar — ilgari menyu aynan shu ro'yxatni
        // ochardi. Yangi ekran uni yo'qotmasin.
        b.btnDocs.setOnClickListener {
            startActivity(Intent(this, DocumentsActivity::class.java).apply {
                putExtra("type", "inventory")
                putExtra("title", "Инвентаризация")
            })
        }
    }

    override fun onResume() {
        super.onResume()
        load()   // sanoq saqlangandan keyin ro'yxat yangilanadi
    }

    private fun load() {
        b.loading.visibility = View.VISIBLE
        b.emptyHint.visibility = View.GONE
        b.list.removeAllViews()
        thread {
            val result = Api.get(this, "inventory-open")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> render(result.json)
                    is ApiResult.Error -> {
                        b.emptyHint.text = result.message
                        b.emptyHint.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun render(json: org.json.JSONObject) {
        b.list.removeAllViews()
        val arr = json.optJSONArray("inventories")
        if (arr == null || arr.length() == 0) {
            b.emptyHint.text = "Ochiq sanoq yo'q.\nMoySklad'da inventarizatsiya yarating."
            b.emptyHint.visibility = View.VISIBLE
            return
        }
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id")
            val name = m.optString("name")
            val moment = m.optString("moment")
            val count = m.optInt("positions_count")

            val card = CardView(this).apply {
                radius = dp(16f); cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.white))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(10f).toInt()
                layoutParams = lp
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
            }
            val title = TextView(this).apply {
                text = name; textSize = 16f
                setTextColor(getColor(R.color.text_dark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val sub = TextView(this).apply {
                text = if (count > 0) "$count ta tovar sanaladi" else "Ro'yxat bo'sh"
                textSize = 13f
                setTextColor(getColor(R.color.brand))
                setPadding(0, dp(4f).toInt(), 0, 0)
            }
            val date = TextView(this).apply {
                text = moment; textSize = 12f
                setTextColor(getColor(R.color.text_gray))
                setPadding(0, dp(2f).toInt(), 0, 0)
            }
            col.addView(title); col.addView(sub); col.addView(date)
            card.addView(col)
            card.setOnClickListener {
                startActivity(Intent(this, InventoryCountActivity::class.java).apply {
                    putExtra("inventory_id", id)
                    putExtra("inventory_name", name)
                })
            }
            b.list.addView(card)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
