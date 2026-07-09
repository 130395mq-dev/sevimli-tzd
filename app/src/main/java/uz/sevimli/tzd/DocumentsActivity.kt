package uz.sevimli.tzd

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import uz.sevimli.tzd.databinding.ActivityDocumentsBinding
import kotlin.concurrent.thread

/**
 * Bugun ushbu skladda yaratilgan hujjatlar (Приёмка, Инвентаризация, Перемещение).
 * Har kun o'ziniki — ertaga o'sha kunda yaratilganlari ko'rinadi.
 */
class DocumentsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDocumentsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDocumentsBinding.inflate(layoutInflater)
        setContentView(b.root)

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
            val result = Api.get(this, "documents")
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
        b.headerDate.text = json.optString("date")
        b.list.removeAllViews()
        val arr = json.optJSONArray("documents")
        if (arr == null || arr.length() == 0) {
            b.emptyHint.text = "Bugun hali hujjat yaratilmagan"
            b.emptyHint.visibility = View.VISIBLE
            return
        }
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            b.list.addView(buildCard(
                d.optString("type"),
                d.optString("name"),
                d.optString("status"),
                d.optString("status_code"),
                d.optString("time"),
                d.optDouble("qty", 0.0),
            ))
        }
    }

    private fun buildCard(
        type: String, name: String, status: String,
        statusCode: String, time: String, qty: Double,
    ): View {
        val card = CardView(this).apply {
            radius = dp(16f); cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.white))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10f).toInt()
            layoutParams = lp
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // yuqori qator: tur badge + vaqt
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = TextView(this).apply {
            text = type; textSize = 12f
            setTextColor(getColor(R.color.brand_dark))
            setBackgroundResource(R.drawable.bg_chip)
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand_tint))
            setPadding(dp(9f).toInt(), dp(3f).toInt(), dp(9f).toInt(), dp(3f).toInt())
        }
        val timeTv = TextView(this).apply {
            text = "  $time"; textSize = 12f
            setTextColor(getColor(R.color.text_gray))
        }
        topRow.addView(badge); topRow.addView(timeTv)

        val nameTv = TextView(this).apply {
            text = name; textSize = 16f
            setTextColor(getColor(R.color.text_dark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6f).toInt(), 0, 0)
        }
        val statusTv = TextView(this).apply {
            text = "$status · jami ${trimNum(qty)}"
            textSize = 13f
            setTextColor(getColor(when (statusCode) {
                "synced" -> R.color.green_ok
                "error" -> R.color.brand
                else -> R.color.amber_wait
            }))
            setPadding(0, dp(3f).toInt(), 0, 0)
        }
        col.addView(topRow); col.addView(nameTv); col.addView(statusTv)
        root.addView(col)
        card.addView(root)
        return card
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
