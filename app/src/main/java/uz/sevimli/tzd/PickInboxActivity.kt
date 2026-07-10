package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import uz.sevimli.tzd.databinding.ActivityPickInboxBinding
import kotlin.concurrent.thread

/**
 * Подбор заказа bosh ekrani. MoySklad'dagi mijoz buyurtmalari ro'yxati.
 * Buyurtmani ochib, tovarlarni skan qilib yig'iladi (Перемещение qabuliga o'xshash).
 */
class PickInboxActivity : AppCompatActivity() {

    private lateinit var b: ActivityPickInboxBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPickInboxBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.headerStore.text = Config.storeName(this) ?: "Sklad tanlanmagan"
        b.btnBack.setOnClickListener { finish() }
        b.btnRefresh.setOnClickListener { load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        b.loading.visibility = View.VISIBLE
        b.emptyHint.visibility = View.GONE
        b.list.removeAllViews()
        thread {
            val result = Api.get(this, "orders")
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
        val arr = json.optJSONArray("orders")
        if (arr == null || arr.length() == 0) {
            b.emptyHint.text = "Buyurtma yo'q"
            b.emptyHint.visibility = View.VISIBLE
            return
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            val name = o.optString("name")
            val customer = o.optString("customer")
            val moment = o.optString("moment")
            val count = o.optInt("positions_count")
            val state = o.optString("state")

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
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this).apply {
                text = name; textSize = 16f
                setTextColor(getColor(R.color.text_dark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            topRow.addView(title)
            if (state.isNotBlank()) {
                val badge = TextView(this).apply {
                    text = state; textSize = 12f
                    setTextColor(getColor(R.color.brand_dark))
                    setBackgroundResource(R.drawable.bg_chip)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand_tint))
                    setPadding(dp(9f).toInt(), dp(3f).toInt(), dp(9f).toInt(), dp(3f).toInt())
                }
                topRow.addView(badge)
            }
            val sub = TextView(this).apply {
                text = "$customer  ·  $count ta tovar"
                textSize = 13f
                setTextColor(getColor(R.color.brand))
                setPadding(0, dp(5f).toInt(), 0, 0)
            }
            val date = TextView(this).apply {
                text = moment; textSize = 12f
                setTextColor(getColor(R.color.text_gray))
                setPadding(0, dp(2f).toInt(), 0, 0)
            }
            col.addView(topRow); col.addView(sub); col.addView(date)
            card.addView(col)
            card.setOnClickListener {
                startActivity(Intent(this, PickReceiveActivity::class.java).apply {
                    putExtra("order_id", id)
                    putExtra("order_name", name)
                    putExtra("customer", customer)
                })
            }
            b.list.addView(card)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
