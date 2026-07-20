package uz.sevimli.tzd

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import org.json.JSONObject
import uz.sevimli.tzd.databinding.ActivitySettingsBinding
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnLogin.setOnClickListener { doLogin() }
        b.btnSaveToken.setOnClickListener { saveToken() }
    }

    private fun doLogin() {
        val login = b.inLogin.text.toString().trim()
        val password = b.inPassword.text.toString()
        if (login.isEmpty() || password.isEmpty()) {
            showLoginError("Login va parolni kiriting")
            return
        }
        b.loginError.visibility = View.GONE
        b.loading.visibility = View.VISIBLE
        b.btnLogin.isEnabled = false

        val body = JSONObject().put("login", login).put("password", password)
        thread {
            val result = Api.post(this, "manager-login", body)
            runOnUiThread {
                b.loading.visibility = View.GONE
                b.btnLogin.isEnabled = true
                when (result) {
                    is ApiResult.Success -> loadStores()
                    is ApiResult.Error -> showLoginError(result.message)
                }
            }
        }
    }

    private fun showLoginError(msg: String) {
        b.loginError.text = msg
        b.loginError.visibility = View.VISIBLE
    }

    private fun loadStores() {
        b.loginStage.visibility = View.GONE
        b.storeStage.visibility = View.VISIBLE
        b.inToken.setText(Config.token(this))   // joriy tokenni ko'rsatamiz
        b.loading.visibility = View.VISIBLE

        thread {
            val result = Api.get(this, "stores")
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> renderStores(result.json)
                    is ApiResult.Error ->
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Qurilma tokenini saqlaydi va eski sklad keshini tozalaydi. */
    private fun saveToken() {
        val t = b.inToken.text.toString().trim()
        if (t.isEmpty()) {
            Toast.makeText(this, "Token kiriting", Toast.LENGTH_SHORT).show(); return
        }
        Config.setToken(this, t)
        thread { LocalDb.get(this).clearProducts() }   // boshqa sklad ma'lumoti tozalansin
        Toast.makeText(this, "Token saqlandi. Menyuda 'Yangilash' ni bosing.", Toast.LENGTH_LONG).show()
        loadStores()   // yangi token bilan sklad ro'yxati
    }

    private fun renderStores(json: JSONObject) {
        b.storeList.removeAllViews()
        val stores = json.optJSONArray("stores") ?: return
        val currentId = Config.storeId(this)

        for (i in 0 until stores.length()) {
            val s = stores.getJSONObject(i)
            val id = s.optInt("id")
            val name = s.optString("name")
            val selected = id == currentId

            val card = CardView(this).apply {
                radius = dp(16f)
                cardElevation = 0f
                setCardBackgroundColor(getColor(if (selected) R.color.brand else R.color.white))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(10f).toInt()
                layoutParams = lp
            }
            val tv = TextView(this).apply {
                text = name + (if (selected) "   ✓" else "")
                textSize = 16f
                setTextColor(getColor(if (selected) R.color.white else R.color.text_dark))
                setPadding(dp(18f).toInt(), dp(18f).toInt(), dp(18f).toInt(), dp(18f).toInt())
            }
            card.addView(tv)
            card.setOnClickListener { selectStore(id, name) }
            b.storeList.addView(card)
        }
    }

    private fun selectStore(id: Int, name: String) {
        b.loading.visibility = View.VISIBLE
        val body = JSONObject().put("store_id", id)
        thread {
            val result = Api.post(this, "set-store", body)
            runOnUiThread {
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        Config.setStore(this, id, name)
                        Toast.makeText(this, "Sklad tanlandi: $name", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    is ApiResult.Error ->
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
