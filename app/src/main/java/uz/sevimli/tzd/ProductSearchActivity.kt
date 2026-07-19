package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        b.btnClear.setOnClickListener { b.search.setText("") }
        b.search.requestFocus()

        b.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                b.btnClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                searchRunnable?.let { handler.removeCallbacks(it) }
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
            showHint("Mahsulot nomini yozing", "Kamida 2 ta harf kiriting")
            b.loading.visibility = View.GONE
            return
        }
        b.hint.visibility = View.GONE
        b.loading.visibility = View.VISIBLE
        thread {
            val result = Api.get(this, "product-search", mapOf("q" to q))
            // Internet bo'lmasa — mahalliy bazadan qidiramiz
            val json: org.json.JSONObject? = when (result) {
                is ApiResult.Success -> result.json
                is ApiResult.Error -> if (result.offline) LocalDb.get(this).searchProductsResult(q) else null
            }
            val serverErr = (result as? ApiResult.Error)?.takeIf { !it.offline }?.message
            runOnUiThread {
                b.loading.visibility = View.GONE
                when {
                    serverErr != null -> Toast.makeText(this, serverErr, Toast.LENGTH_SHORT).show()
                    json != null -> render(json)
                    else -> render(org.json.JSONObject().put("products", org.json.JSONArray()))
                }
            }
        }
    }

    private fun showHint(title: String, sub: String) {
        b.hintTitle.text = title
        b.hintSub.text = sub
        b.hint.visibility = View.VISIBLE
    }

    private fun render(json: JSONObject) {
        b.list.removeAllViews()
        val arr = json.optJSONArray("products") ?: return
        if (arr.length() == 0) {
            showHint("Hech narsa topilmadi", "Boshqa nom yoki kod bilan urinib ko'ring")
            return
        }
        b.hint.visibility = View.GONE
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            b.list.addView(buildRow(p))
        }
    }

    /** Bitta mahsulot kartochkasini yasaydi. */
    private fun buildRow(p: JSONObject): View {
        val name = p.optString("name")
        val price = p.optLong("price", 0)
        val qty = p.optDouble("store_qty", 0.0)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_card_item)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10f)
            layoutParams = lp
        }

        // Bosh harf doirasi (avatar)
        val avatar = FrameLayout(this).apply {
            background = getDrawable(R.drawable.bg_avatar)
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
        }
        val initial = TextView(this).apply {
            text = name.trim().take(1).uppercase().ifBlank { "?" }
            textSize = 18f
            setTextColor(getColor(R.color.brand))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        avatar.addView(initial)
        row.addView(avatar)

        // Matn ustuni: nom + narx/qoldiq belgilari
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12f)
                marginEnd = dp(6f)
            }
        }
        val nameTv = TextView(this).apply {
            text = name
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.text_dark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        col.addView(nameTv)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7f) }
        }
        chips.addView(pill("${fmt.format(price)} so'm", R.color.brand_tint, R.color.brand_dark))
        val inStock = qty > 0
        chips.addView(pill(
            "Qoldiq: ${trimNum(qty)}",
            if (inStock) R.color.green_tint else R.color.pill_gray,
            if (inStock) R.color.green_ok else R.color.text_gray
        ).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6f) })
        col.addView(chips)
        row.addView(col)

        // O'ng tomonda strelka
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(getColor(R.color.text_gray))
            layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
        }
        row.addView(chevron)

        row.setOnClickListener {
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
        return row
    }

    /** Kichik yumaloq belgi (narx / qoldiq). */
    private fun pill(text: String, bgColor: Int, textColor: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(getColor(textColor))
            background = getDrawable(R.drawable.bg_pill)
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(bgColor))
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
