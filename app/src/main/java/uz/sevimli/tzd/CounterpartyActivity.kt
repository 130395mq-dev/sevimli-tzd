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
import uz.sevimli.tzd.databinding.ActivityCounterpartyBinding
import kotlin.concurrent.thread

class CounterpartyActivity : AppCompatActivity() {

    private lateinit var b: ActivityCounterpartyBinding
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCounterpartyBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        load("")

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
        b.loading.visibility = View.VISIBLE
        b.list.removeAllViews()
        thread {
            val result = Api.get(this, "counterparties", if (q.isEmpty()) emptyMap() else mapOf("q" to q))
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
        val arr = json.optJSONArray("counterparties") ?: return
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val id = c.optInt("id")
            val name = c.optString("name")
            val card = CardView(this).apply {
                radius = dp(12f); cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.white))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8f).toInt()
                layoutParams = lp
            }
            val tv = TextView(this).apply {
                text = name; textSize = 15f
                setTextColor(getColor(R.color.text_dark))
                setPadding(dp(16f).toInt(), dp(16f).toInt(), dp(16f).toInt(), dp(16f).toInt())
            }
            card.addView(tv)
            card.setOnClickListener {
                val data = Intent().apply {
                    putExtra("cp_id", id); putExtra("cp_name", name)
                }
                setResult(RESULT_OK, data)
                finish()
            }
            b.list.addView(card)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
