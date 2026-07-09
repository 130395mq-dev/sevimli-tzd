package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import uz.sevimli.tzd.databinding.ActivityStorePickerBinding
import kotlin.concurrent.thread

/**
 * Sklad tanlash ekrani (Перемещение uchun "qayerga"). `/stores` ro'yxatidan
 * tanlanadi. `exclude_id` extra bilan berilgan skladni ko'rsatmaydi
 * (masalan, "qayerdan" skladini). Natija: store_id + store_name.
 */
class StorePickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityStorePickerBinding
    private var excludeId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStorePickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        excludeId = intent.getIntExtra("exclude_id", -1)
        intent.getStringExtra("title")?.let { b.title.text = it }

        b.btnBack.setOnClickListener { finish() }
        load()
    }

    private fun load() {
        b.loading.visibility = View.VISIBLE
        b.list.removeAllViews()
        thread {
            val result = Api.get(this, "stores")
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> render(result.json)
                    is ApiResult.Error -> Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun render(json: org.json.JSONObject) {
        b.list.removeAllViews()
        val arr = json.optJSONArray("stores") ?: return
        var shown = 0
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val id = s.optInt("id")
            val name = s.optString("name")
            if (id == excludeId) continue
            shown++
            val card = CardView(this).apply {
                radius = dp(16f); cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.white))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(10f).toInt()
                layoutParams = lp
            }
            val tv = TextView(this).apply {
                text = name; textSize = 16f
                setTextColor(getColor(R.color.text_dark))
                setPadding(dp(18f).toInt(), dp(18f).toInt(), dp(18f).toInt(), dp(18f).toInt())
            }
            card.addView(tv)
            card.setOnClickListener {
                val data = Intent().apply {
                    putExtra("store_id", id); putExtra("store_name", name)
                }
                setResult(RESULT_OK, data)
                finish()
            }
            b.list.addView(card)
        }
        if (shown == 0) {
            val tv = TextView(this).apply {
                text = "Boshqa sklad topilmadi"
                textSize = 15f
                setTextColor(getColor(R.color.text_gray))
                setPadding(dp(8f).toInt(), dp(24f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            }
            b.list.addView(tv)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
