package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.view.View
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
            b.lastCode.text = getString(R.string.picked_manually)
            showResult(json, json.optString("barcode"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLookupBinding.inflate(layoutInflater)
        setContentView(b.root)

        val storeName = Config.storeName(this) ?: getString(R.string.store_not_set)
        b.headerStore.text = storeName

        b.btnBack.setOnClickListener { finish() }
        b.btnManualSearch.setOnClickListener {
            pickProduct.launch(Intent(this, ProductSearchActivity::class.java))
        }
        // Skan uch kanaldan qabul qilinadi: Enter, Enter'siz (jimlik) va
        // qurilma skaner signali. Tafsilot — ScanInput.kt
        ScanInput.bind(this, b.scanInput) { code -> handleScan(code) }
    }

    private fun handleScan(code: String) {
        b.lastCode.text = getString(R.string.last_scan_fmt, code)
        b.emptyState.visibility = View.GONE
        b.card.visibility = View.GONE
        b.notFound.visibility = View.GONE
        b.loading.visibility = View.VISIBLE

        thread {
            val result = Api.get(this, "product", mapOf("barcode" to code))
            val json: org.json.JSONObject? = when (result) {
                is ApiResult.Success -> result.json
                is ApiResult.Error -> if (result.offline) OfflineLookup.lookup(this, code) else null
            }
            val serverErr = (result as? ApiResult.Error)?.takeIf { !it.offline }?.message
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when {
                    serverErr != null -> showError(serverErr)
                    json != null -> showResult(json, code)
                    else -> showError(getString(R.string.product_not_found_2))
                }
            }
        }
    }

    private fun showResult(json: org.json.JSONObject, code: String) {
        val found = json.optBoolean("found", false)
        if (!found) {
            ScanFeedback.fail(this)
            b.notFound.text = getString(R.string.not_found_fmt, code)
            b.notFound.visibility = View.VISIBLE
            return
        }
        ScanFeedback.ok(this)
        b.card.visibility = View.VISIBLE
        b.pName.text = json.optString("name")

        val barcode = json.optString("barcode")
        b.pBarcode.text = barcode
        val article = json.optString("article")
        if (article.isNotBlank()) {
            b.pArticle.visibility = View.VISIBLE
            b.pArticle.text = getString(R.string.article_fmt, article)
        } else {
            b.pArticle.visibility = View.GONE
        }

        val price = json.optLong("price", 0)
        b.pPrice.text = getString(R.string.sum_fmt, fmt.format(price))

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
                val u = json.optString("uom", "").let { if (it.isBlank()) "dona" else it }
                b.pPackInfo.text = getString(R.string.pack_info_fmt, trimNum(packQty), u)
            }
            json.optBoolean("pack_unknown", false) -> {
                b.pPackInfo.visibility = View.VISIBLE
                b.pPackInfo.text = getString(R.string.blok_no_qty)
            }
            else -> b.pPackInfo.visibility = View.GONE
        }

        val storeQty = json.optDouble("store_qty", 0.0)
        b.pStock.text = trimNum(storeQty)
        b.storeLabel.text = getString(R.string.stock_kt) + (json.optString("store_name").let {
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
                val o = others.optJSONObject(i) ?: continue
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
        ScanFeedback.fail(this)
        b.notFound.text = message
        b.notFound.visibility = View.VISIBLE
    }

    private fun trimNum(d: Double): String {
        // Yaxlitlash: `Double` da qo'shish ikkilik kasr xatosini to'playdi
        // (63.789 -> 63.788999999999994). Boshqa ekranlarda bor edi,
        // shu uchtasida tushib qolgan.
        val r = Math.round(d * 1000.0) / 1000.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

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
        val pq = data.getDoubleExtra("p_pack_qty", 0.0)
        if (pq > 0) { put("pack_qty", pq); put("is_pack", true) }
    }

    override fun onResume() {
        super.onResume()
        b.headerStore.text = Config.storeName(this) ?: getString(R.string.store_not_set)
        b.scanInput.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) b.scanInput.requestFocus()
    }
}
