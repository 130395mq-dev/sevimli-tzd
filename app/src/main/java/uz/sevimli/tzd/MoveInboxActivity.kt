package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import uz.sevimli.tzd.databinding.ActivityMoveInboxBinding
import kotlin.concurrent.thread

/**
 * Перемещение bosh ekrani. Ushbu skladga jo'natilgan, hali qabul qilinmagan
 * ko'chirish dokumentlari ro'yxati. Dokumentni ochib skan qilib qabul qilinadi.
 * Pastda «Yangi jo'natma» — o'z skladidan boshqa skladga ko'chirish yaratish.
 */
class MoveInboxActivity : AppCompatActivity() {

    private lateinit var b: ActivityMoveInboxBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMoveInboxBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.headerStore.text = Config.storeName(this) ?: "Sklad tanlanmagan"
        b.btnBack.setOnClickListener { finish() }
        b.btnRefresh.setOnClickListener { load() }
        b.btnNew.setOnClickListener {
            startActivity(Intent(this, MoveActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        load() // qabul qilingandan keyin ro'yxat yangilanadi
    }

    private fun load() {
        b.loading.visibility = View.VISIBLE
        b.emptyHint.visibility = View.GONE
        b.list.removeAllViews()
        thread {
            val result = Api.get(this, "move-incoming")
            runOnUiThread {
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
        val arr = json.optJSONArray("moves")
        if (arr == null || arr.length() == 0) {
            b.emptyHint.text = "Kelgan dokument yo'q"
            b.emptyHint.visibility = View.VISIBLE
            return
        }
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val id = m.optString("id")
            val name = m.optString("name")
            val source = m.optString("source_store")
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
                text = "$source dan  ·  $count ta tovar"
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
                startActivity(Intent(this, MoveReceiveActivity::class.java).apply {
                    putExtra("move_id", id)
                    putExtra("move_name", name)
                })
            }
            b.list.addView(card)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
