package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import org.json.JSONObject
import uz.sevimli.tzd.databinding.ActivityProductSearchBinding
import java.text.NumberFormat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Shtrix kodi bo'lmagan (yoki topilmagan) mahsulotlarni nomi/kodi bo'yicha
 * qidirib, ro'yxatdan qo'lda tanlash uchun ekran.
 * Natija Intent orqali qaytariladi (Просмотр va Приёмка shu natijadan foydalanadi).
 */
class ProductSearchActivity : AppCompatActivity() {

    private lateinit var b: ActivityProductSearchBinding
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private val fmt = NumberFormat.getInstance(Locale("uz"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProductSearchBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.search.requestFocus()

        b.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { handler.removeCallbacks(it) }
                val q = s?.toString()?.trim() ?: ""
                searchRunnable = Runnable { load(q) }
                handler.postDelayed(searchRunnable!!, 350)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun load(q: String) {
        b.list.removeAllViews()
        if (q.length < 2) {
            b.hint.text = "Kamida 2 ta harf kiriting"
            b.hint.visibility = View.VISIBLE
            return
        }
        b.hint.visibility = View.GONE
        b.loading.visibility = View.VISIBLE
        thread {
            val result = Api.get(this, "product-search", mapOf("q" to q))
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> render(result.json)
                    is ApiResult.Error -> Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun render(json: JSONObject) {
        b.list.removeAllViews()
        val arr = json.optJSONArray("products") ?: return
        if (arr.length() == 0) {
            b.hint.text = "Hech narsa topilmadi"
            b.hint.visibility = View.VISIBLE
            return
        }
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val name = p.optString("name")
            val price = p.optLong("price", 0)
            val qty = p.optDouble("store_qty", 0.0)

            val card = CardView(this).apply {
                radius = dp(12f); cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.white))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8f).toInt()
                layoutParams = lp
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
            }
            val nameTv = TextView(this).apply {
                text = name; textSize = 15f
                setTextColor(getColor(R.color.text_dark))
            }
            val subTv = TextView(this).apply {
                text = "${fmt.format(price)} so'm  ·  qoldiq: ${trimNum(qty)}"
                textSize = 13f
                setTextColor(getColor(R.color.text_gray))
                setPadding(0, dp(4f).toInt(), 0, 0)
            }
            col.addView(nameTv)
            col.addView(subTv)
            card.addView(col)
            card.setOnClickListener {
                val data = Intent().apply {
                    putExtra("p_moysklad_id", p.optString("moysklad_id"))
                    putExtra("p_name", name)
                    putExtra("p_price", price)
                    putExtra("p_uom", p.optString("uom"))
                    putExtra("p_article", p.optString("article"))
                    putExtra("p_code", p.optString("code"))
                    putExtra("p_barcode", p.optString("barcode"))
                    putExtra("p_store_qty", qty)
                }
                setResult(RESULT_OK, data)
                finish()
            }
            b.list.addView(card)
        }
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
