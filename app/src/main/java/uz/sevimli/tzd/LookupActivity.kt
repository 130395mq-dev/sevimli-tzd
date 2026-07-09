package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import uz.sevimli.tzd.databinding.ActivityLookupBinding
import java.text.NumberFormat
import java.util.Locale
import kotlin.concurrent.thread

class LookupActivity : AppCompatActivity() {

    private lateinit var b: ActivityLookupBinding
    private val fmt = NumberFormat.getInstance(Locale("uz"))

    private val pickProduct = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val json = productFromIntent(res.data!!)
            b.emptyState.visibility = View.GONE
            b.notFound.visibility = View.GONE
            b.lastCode.text = "Qo'lda tanlandi"
            showResult(json, json.optString("barcode"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLookupBinding.inflate(layoutInflater)
        setContentView(b.root)

        val storeName = Config.storeName(this) ?: "Sklad tanlanmagan"
        b.headerStore.text = storeName

        b.btnBack.setOnClickListener { finish() }
        b.btnManualSearch.setOnClickListener {
            pickProduct.launch(Intent(this, ProductSearchActivity::class.java))
        }
        b.scanInput.showSoftInputOnFocus = false

        b.scanInput.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_NEXT ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.action == KeyEvent.ACTION_DOWN)
            if (enter) {
                val code = b.scanInput.text.toString().trim()
                if (code.isNotEmpty()) handleScan(code)
                b.scanInput.setText("")
                true
            } else false
        }
    }

    private fun handleScan(code: String) {
        b.lastCode.text = "Oxirgi skan: $code"
        b.emptyState.visibility = View.GONE
        b.card.visibility = View.GONE
        b.notFound.visibility = View.GONE
        b.loading.visibility = View.VISIBLE

        thread {
            val result = Api.get(this, "product", mapOf("barcode" to code))
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> showResult(result.json, code)
                    is ApiResult.Error -> showError(result.message)
                }
            }
        }
    }

    private fun showResult(json: org.json.JSONObject, code: String) {
        val found = json.optBoolean("found", false)
        if (!found) {
            b.notFound.text = "Mahsulot topilmadi\n$code"
            b.notFound.visibility = View.VISIBLE
            return
        }
        b.card.visibility = View.VISIBLE
        b.pName.text = json.optString("name")

        val barcode = json.optString("barcode")
        b.pBarcode.text = barcode
        val article = json.optString("article")
        if (article.isNotBlank()) {
            b.pArticle.visibility = View.VISIBLE
            b.pArticle.text = "арт: $article"
        } else {
            b.pArticle.visibility = View.GONE
        }

        val price = json.optLong("price", 0)
        b.pPrice.text = "${fmt.format(price)} so'm"

        // Tarozi shtrixi yoki Upakovka (blok) shtrixi
        val packQty = json.optDouble("pack_qty", 0.0)
        val scaleWeight = json.optDouble("scale_weight", 0.0)
        when {
            json.optBoolean("scale", false) -> {
                b.pPackInfo.visibility = View.VISIBLE
                val p = json.optLong("scale_price", 0)
                b.pPackInfo.text = if (scaleWeight > 0)
                    "⚖ Tarozi: ${trimNum(scaleWeight)} kg · ${fmt.format(p)} so'm"
                else "⚖ Tarozi: ${fmt.format(p)} so'm"
            }
            json.optBoolean("is_pack", false) && packQty > 0 -> {
                b.pPackInfo.visibility = View.VISIBLE
                b.pPackInfo.text = "📦 Upakovka (blok): ${trimNum(packQty)} dona"
            }
            else -> b.pPackInfo.visibility = View.GONE
        }

        val storeQty = json.optDouble("store_qty", 0.0)
        b.pStock.text = trimNum(storeQty)
        b.storeLabel.text = "Qoldiq" + (json.optString("store_name").let {
            if (it.isNotBlank()) " · $it" else ""
        })

        val uom = json.optString("uom")
        b.pUom.text = if (uom.isNotBlank()) uom else "—"

        // boshqa skladlar
        b.otherStores.removeAllViews()
        val others = json.optJSONArray("other_stores")
        if (others != null && others.length() > 0) {
            b.otherLabel.visibility = View.VISIBLE
            b.divider.visibility = View.VISIBLE
            for (i in 0 until others.length()) {
                val o = others.getJSONObject(i)
                val row = TextView(this).apply {
                    text = "• ${o.optString("store")}: ${trimNum(o.optDouble("qty", 0.0))}"
                    textSize = 14f
                    setTextColor(getColor(R.color.text_dark))
                    setPadding(0, 4, 0, 4)
                }
                b.otherStores.addView(row)
            }
        } else {
            b.otherLabel.visibility = View.GONE
            b.divider.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        b.notFound.text = message
        b.notFound.visibility = View.VISIBLE
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** ProductSearchActivity'dan qaytgan tanlovni product_lookup javobi kabi JSON'ga aylantiradi. */
    private fun productFromIntent(data: Intent): JSONObject = JSONObject().apply {
        put("found", true)
        put("name", data.getStringExtra("p_name") ?: "")
        put("barcode", data.getStringExtra("p_barcode") ?: "")
        put("article", data.getStringExtra("p_article") ?: "")
        put("price", data.getLongExtra("p_price", 0))
        put("uom", data.getStringExtra("p_uom") ?: "")
        put("store_name", Config.storeName(this@LookupActivity) ?: "")
        put("store_qty", data.getDoubleExtra("p_store_qty", 0.0))
        put("moysklad_id", data.getStringExtra("p_moysklad_id") ?: "")
    }

    override fun onResume() {
        super.onResume()
        b.headerStore.text = Config.storeName(this) ?: "Sklad tanlanmagan"
        b.scanInput.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) b.scanInput.requestFocus()
    }
}
