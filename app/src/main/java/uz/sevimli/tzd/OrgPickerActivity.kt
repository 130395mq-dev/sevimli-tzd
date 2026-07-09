package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import uz.sevimli.tzd.databinding.ActivityOrgPickerBinding
import kotlin.concurrent.thread

/**
 * Organizatsiya (Организация) tanlash — Перемещение hujjati uchun.
 * `/organizations` ro'yxatidan tanlanadi. Natija: org_id (MoySklad UUID) + org_name.
 */
class OrgPickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityOrgPickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOrgPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        load()
    }

    private fun load() {
        b.loading.visibility = View.VISIBLE
        b.list.removeAllViews()
        thread {
            val result = Api.get(this, "organizations")
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
        val arr = json.optJSONArray("organizations") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            val name = o.optString("name")
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
                    putExtra("org_id", id); putExtra("org_name", name)
                }
                setResult(RESULT_OK, data)
                finish()
            }
            b.list.addView(card)
        }
        if (arr.length() == 0) {
            val tv = TextView(this).apply {
                text = "Tashkilot topilmadi"
                textSize = 15f
                setTextColor(getColor(R.color.text_gray))
                setPadding(dp(8f).toInt(), dp(24f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            }
            b.list.addView(tv)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
