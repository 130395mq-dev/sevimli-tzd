package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import org.json.JSONObject
import uz.sevimli.tzd.databinding.ActivitySetupBinding
import kotlin.concurrent.thread

/**
 * Birinchi ishga tushish sehrgari:
 *  0) Kirish (kabinet email/parol)
 *  1) Litsenziya (qurilma tokeni)
 *  2) Filial (sklad) tanlash
 *  3) Sinxron -> Menyu
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var b: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        step(0)
        b.btnLogin.setOnClickListener { doLogin() }
        b.btnToken.setOnClickListener { doToken() }
    }

    private fun step(i: Int) {
        b.step0.visibility = if (i == 0) View.VISIBLE else View.GONE
        b.step1.visibility = if (i == 1) View.VISIBLE else View.GONE
        b.step2.visibility = if (i == 2) View.VISIBLE else View.GONE
        b.step3.visibility = if (i == 3) View.VISIBLE else View.GONE
        b.setupSub.text = when (i) {
            0 -> "Hisobingizga kiring"
            1 -> "Qurilma litsenziyasi"
            2 -> "Filialni tanlang"
            else -> "Tayyorlanmoqda"
        }
    }

    private fun msg(tv: TextView, t: String) { tv.text = t }

    // ---- 0: KIRISH ----
    private fun doLogin() {
        val email = b.etEmail.text.toString().trim()
        val pass = b.etPass.text.toString()
        if (email.isEmpty() || pass.isEmpty()) { msg(b.msgLogin, "Email va parolni kiriting"); return }
        b.btnLogin.isEnabled = false
        msg(b.msgLogin, "Tekshirilmoqda...")
        thread {
            val r = Api.saasPost(this, "login",
                JSONObject().put("email", email).put("password", pass))
            runOnUiThread {
                b.btnLogin.isEnabled = true
                when (r) {
                    is ApiResult.Success -> { msg(b.msgLogin, ""); step(1) }
                    is ApiResult.Error -> msg(b.msgLogin, r.message)
                }
            }
        }
    }

    // ---- 1: LITSENZIYA (TOKEN) ----
    private fun doToken() {
        val tok = b.etToken.text.toString().trim()
        if (tok.length < 8) { msg(b.msgToken, "Tokenni to'liq kiriting"); return }
        Config.setToken(this, tok)
        b.btnToken.isEnabled = false
        msg(b.msgToken, "Tekshirilmoqda...")
        thread {
            val r = Api.get(this, "ping")
            runOnUiThread {
                b.btnToken.isEnabled = true
                when (r) {
                    is ApiResult.Success -> { msg(b.msgToken, ""); loadStores() }
                    is ApiResult.Error -> msg(b.msgToken, "Token xato: " + r.message)
                }
            }
        }
    }

    // ---- 2: FILIAL (SKLAD) ----
    private fun loadStores() {
        step(2)
        b.storeLoading.visibility = View.VISIBLE
        b.llStores.removeAllViews()
        msg(b.msgStore, "")
        thread {
            val r = Api.get(this, "stores")
            runOnUiThread {
                b.storeLoading.visibility = View.GONE
                when (r) {
                    is ApiResult.Success -> renderStores(r.json)
                    is ApiResult.Error -> msg(b.msgStore, r.message)
                }
            }
        }
    }

    private fun renderStores(json: JSONObject) {
        b.llStores.removeAllViews()
        val arr = json.optJSONArray("stores")
        if (arr == null || arr.length() == 0) { msg(b.msgStore, "Filial topilmadi"); return }
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val id = s.optInt("id")
            val name = s.optString("name")
            val card = CardView(this).apply {
                radius = dp(14f); cardElevation = 0f
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
            card.setOnClickListener { pickStore(id, name) }
            b.llStores.addView(card)
        }
    }

    private fun pickStore(id: Int, name: String) {
        msg(b.msgStore, "Saqlanmoqda...")
        thread {
            val r = Api.post(this, "set-store", JSONObject().put("store_id", id))
            runOnUiThread {
                when (r) {
                    is ApiResult.Success -> { Config.setStore(this, id, name); doSync() }
                    is ApiResult.Error -> msg(b.msgStore, r.message)
                }
            }
        }
    }

    // ---- 3: SINXRON ----
    private fun doSync() {
        step(3)
        thread {
            runOnUiThread { b.syncMsg.text = "Kontragentlar yuklanmoqda..." }
            CatalogSync.syncCounterparties(this)
            CatalogSync.syncProductsFull(this) { done, total ->
                runOnUiThread { b.syncMsg.text = "Mahsulotlar: $done / $total" }
            }
            Config.setConfigured(this, true)
            runOnUiThread {
                Toast.makeText(this, "Tayyor! Ilova ishga tushdi ✓", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MenuActivity::class.java))
                finish()
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
