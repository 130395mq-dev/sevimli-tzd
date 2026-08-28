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
        b.lnkManual.setOnClickListener {
            val show = b.manualBox.visibility != View.VISIBLE
            b.manualBox.visibility = if (show) View.VISIBLE else View.GONE
            b.lnkManual.text = if (show) "Ro'yxatdan tanlash" else "Token qo'lda kiritish"
        }
    }

    private fun step(i: Int) {
        b.step0.visibility = if (i == 0) View.VISIBLE else View.GONE
        b.step1.visibility = if (i == 1) View.VISIBLE else View.GONE
        b.step2.visibility = if (i == 2) View.VISIBLE else View.GONE
        b.step3.visibility = if (i == 3) View.VISIBLE else View.GONE
        b.setupSub.text = when (i) {
            0 -> "Hisobingizga kiring"
            1 -> "Litsenziyani tanlang"
            2 -> "Filialni tanlang"
            else -> "Tayyorlanmoqda"
        }
    }

    private fun msg(tv: TextView, t: String) { tv.text = t }

    // ---- 0: KIRISH ----
    private fun doLogin() {
        val email = b.etEmail.text.toString().trim()
        val pass = b.etPass.text.toString()
        if (email.isEmpty() || pass.isEmpty()) { msg(b.msgLogin, getString(R.string.enter_email_pass)); return }
        b.btnLogin.isEnabled = false
        msg(b.msgLogin, getString(R.string.checking))
        thread {
            val r = Api.saasPost(this, "login",
                JSONObject().put("email", email).put("password", pass))
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.btnLogin.isEnabled = true
                when (r) {
                    is ApiResult.Success -> {
                        val sess = r.json.optString("token")
                        if (sess.isNotEmpty()) Config.setSessionToken(this, sess)
                        msg(b.msgLogin, "")
                        loadLicenses()
                    }
                    is ApiResult.Error -> msg(b.msgLogin, r.message)
                }
            }
        }
    }

    // ---- 1a: LITSENZIYALAR RO'YXATI (kabinetdan) ----
    private fun loadLicenses() {
        step(1)
        b.manualBox.visibility = View.GONE
        b.lnkManual.text = getString(R.string.token_manual_kt)
        b.licLoading.visibility = View.VISIBLE
        b.llLicenses.removeAllViews()
        msg(b.msgLicense, "")
        thread {
            val r = Api.saasGet(this, "devices")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.licLoading.visibility = View.GONE
                when (r) {
                    is ApiResult.Success -> renderLicenses(r.json)
                    is ApiResult.Error -> msg(b.msgLicense, r.message)
                }
            }
        }
    }

    private fun renderLicenses(json: JSONObject) {
        b.llLicenses.removeAllViews()
        val arr = json.optJSONArray("devices")
        if (arr == null || arr.length() == 0) {
            msg(b.msgLicense, getString(R.string.no_license))
            b.manualBox.visibility = View.VISIBLE
            b.lnkManual.text = getString(R.string.pick_from_list)
            return
        }
        val myId = Config.deviceId(this)
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val name = d.optString("name")
            val token = d.optString("token")
            val store = d.optString("store_name", "")
            val bound = d.optBoolean("bound", false)
            val hw = d.optString("hardware_id", "")
            val mine = bound && hw == myId
            val taken = bound && !mine   // boshqa qurilmaga bog'langan
            val sub = when {
                mine -> "Shu qurilmaga bog'langan"
                taken -> "Band — boshqa qurilmada"
                store.isNotEmpty() -> "Filial: $store"
                else -> "Bo'sh — tayyor"
            }
            val card = CardView(this).apply {
                radius = dp(14f); cardElevation = 0f
                setCardBackgroundColor(getColor(if (taken) R.color.bg_light else R.color.white))
                alpha = if (taken) 0.6f else 1f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(10f).toInt()
                layoutParams = lp
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18f).toInt(), dp(15f).toInt(), dp(18f).toInt(), dp(15f).toInt())
            }
            val tvName = TextView(this).apply {
                text = name; textSize = 16f
                setTextColor(getColor(R.color.text_dark))
            }
            val tvSub = TextView(this).apply {
                text = sub; textSize = 12f
                setTextColor(getColor(if (taken) R.color.warning else R.color.text_gray))
                setPadding(0, dp(3f).toInt(), 0, 0)
            }
            col.addView(tvName); col.addView(tvSub)
            card.addView(col)
            if (!taken) card.setOnClickListener { pickLicense(token, name) }
            b.llLicenses.addView(card)
        }
    }

    private fun pickLicense(token: String, name: String) {
        Config.setToken(this, token)
        msg(b.msgLicense, getString(R.string.checking))
        thread {
            val r = Api.get(this, "ping")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (r) {
                    is ApiResult.Success -> { msg(b.msgLicense, ""); loadStores() }
                    is ApiResult.Error -> msg(b.msgLicense, r.message)
                }
            }
        }
    }

    // ---- 1: LITSENZIYA (TOKEN) ----
    private fun doToken() {
        val tok = b.etToken.text.toString().trim()
        if (tok.length < 8) { msg(b.msgToken, getString(R.string.token_incomplete)); return }
        Config.setToken(this, tok)
        b.btnToken.isEnabled = false
        msg(b.msgToken, getString(R.string.checking))
        thread {
            val r = Api.get(this, "ping")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.btnToken.isEnabled = true
                when (r) {
                    is ApiResult.Success -> { msg(b.msgToken, ""); loadStores() }
                    is ApiResult.Error -> msg(b.msgToken, getString(R.string.token_error_pre) + r.message)
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
                if (isFinishing || isDestroyed) return@runOnUiThread
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
        if (arr == null || arr.length() == 0) { msg(b.msgStore, getString(R.string.branch_not_found)); return }
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
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

    /** Sklad tanlash qulfi — ikkita sklad ketma-ket bosilib qolmasin. */
    private val storeBusy = Busy()

    private fun pickStore(id: Int, name: String) {
        if (!storeBusy.start()) return
        msg(b.msgStore, getString(R.string.saving))
        thread {
            val r = Api.post(this, "set-store", JSONObject().put("store_id", id))
            runOnUiThread {
                storeBusy.stop()
                if (isFinishing || isDestroyed) return@runOnUiThread
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
            runOnUiThread { b.syncMsg.text = getString(R.string.cp_loading) }
            CatalogSync.syncCounterparties(this)
            CatalogSync.syncProductsFull(this) { done, total ->
                runOnUiThread { b.syncMsg.text = getString(R.string.products_fmt, done, total) }
            }
            Config.setConfigured(this, true)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, getString(R.string.ready_started), Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MenuActivity::class.java))
                finish()
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
