package uz.sevimli.tzd

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import uz.sevimli.tzd.databinding.ActivityStorePickerBinding
import kotlin.concurrent.thread

/**
 * Sklad tanlash ekrani (Перемещение uchun "qayerga"). `/stores` ro'yxatidan
 * tanlanadi. `exclude_id` extra bilan berilgan skladni ko'rsatmaydi
 * (masalan, "qayerdan" skladini). Natija: store_id + store_name.
 *
 * 6.6: ilgari sklad bosilgan zahoti ekran yopilardi — xato bosilsa xodim
 * qaytadan kirishga majbur edi. Endi TANLASH va TASDIQLASH ajratildi:
 * bosilganda belgilanadi, pastdagi "Saqlash" natijani qaytaradi.
 * API, natija formati va chaqiruvchi ekranlar O'ZGARMADI.
 */
class StorePickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityStorePickerBinding
    private var excludeId: Int = -1

    private var pickedId: Int = -1
    private var pickedName: String = ""
    /** Har bir qator uchun tanlov belgisi — qayta chizmasdan yangilash uchun. */
    private val marks = ArrayList<Pair<Int, View>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStorePickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        excludeId = intent.getIntExtra("exclude_id", -1)
        intent.getStringExtra("title")?.let { b.title.text = it }

        b.btnBack.setOnClickListener { finish() }
        b.btnSave.setOnClickListener { confirm() }
        load()
    }

    private fun confirm() {
        if (pickedId <= 0) return
        val data = Intent().apply {
            putExtra("store_id", pickedId)
            putExtra("store_name", pickedName)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun load() {
        b.loading.visibility = View.VISIBLE
        b.list.removeAllViews()
        marks.clear()
        thread {
            val result = Api.get(this, "stores")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> render(result.json)
                    // ILGARI faqat qisqa Toast chiqardi va ekran bo'sh qolardi —
                    // xodim nima bo'lganini bilmasdi va qayta urinish yo'li yo'q edi.
                    is ApiResult.Error -> showError(result.message)
                }
            }
        }
    }

    /** Xato holati: sabab ko'rinadi va "Qayta urinish" tugmasi bo'ladi. */
    private fun showError(msg: String) {
        b.list.removeAllViews()
        val tv = TextView(this).apply {
            text = getString(R.string.stores_failed_fmt, msg)
            textSize = 15f
            setTextColor(getColor(R.color.text_dark))
            setPadding(dp(8f).toInt(), dp(24f).toInt(), dp(8f).toInt(), dp(14f).toInt())
        }
        val btn = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.retry_2)
            isAllCaps = false
            textSize = 16f
            setOnClickListener { load() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52f).toInt())
        }
        b.list.addView(tv)
        b.list.addView(btn)
    }

    private fun render(json: org.json.JSONObject) {
        b.list.removeAllViews()
        marks.clear()
        val arr = json.optJSONArray("stores") ?: return
        var shown = 0
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optInt("id")
            val name = s.optString("name")
            if (id == excludeId) continue
            shown++

            // Qator balandligi 64dp dan kam emas — qo'lqop bilan aniq bosiladi.
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(64f).toInt()
                setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
                background = getDrawable(R.drawable.j_card)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8f).toInt()
                layoutParams = lp
            }
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.j_ic_store)
                imageTintList = ColorStateList.valueOf(getColor(R.color.brand))
                background = getDrawable(R.drawable.j_icon_box)
                val p = dp(8f).toInt()
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt())
            })
            val label = TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(getColor(R.color.text_dark))
                val lp = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(12f).toInt()
                layoutParams = lp
            }
            row.addView(label)
            val mark = View(this).apply {
                background = getDrawable(R.drawable.j_radio_off)
                layoutParams = LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt())
            }
            row.addView(mark)
            marks.add(id to mark)

            row.setOnClickListener {
                pickedId = id
                pickedName = name
                for ((mid, mv) in marks) {
                    mv.background = getDrawable(
                        if (mid == id) R.drawable.j_radio_on else R.drawable.j_radio_off)
                }
                b.btnSave.isEnabled = true
            }
            b.list.addView(row)
        }
        if (shown == 0) {
            val tv = TextView(this).apply {
                text = getString(R.string.no_other_store)
                textSize = 15f
                setTextColor(getColor(R.color.text_gray))
                setPadding(dp(8f).toInt(), dp(24f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            }
            b.list.addView(tv)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
