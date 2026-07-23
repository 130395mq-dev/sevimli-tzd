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
import uz.sevimli.tzd.databinding.ActivitySettingsBinding
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    private val pickOrg = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val id = res.data!!.getStringExtra("org_id") ?: ""
            val name = res.data!!.getStringExtra("org_name") ?: ""
            if (id.isNotEmpty()) {
                Config.setOrg(this, id, name)
                updateOrgLabel()
                Toast.makeText(this, "Organizatsiya: $name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateOrgLabel() {
        val name = Config.orgName(this)
        b.btnOrg.text = if (name.isNullOrBlank()) "Organizatsiyani tanlash" else "Organizatsiya: $name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnLogin.setOnClickListener { doLogin() }
        b.btnSaveToken.setOnClickListener { saveToken() }
        b.btnOrg.setOnClickListener { pickOrg.launch(Intent(this, OrgPickerActivity::class.java)) }
        updateOrgLabel()
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
        renderFunctions()                        // bo'limlar yoqish/o'chirish
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

    /** Bo'limlarni yoqish/o'chirish ro'yxati (MenuFunctions.LIST dan avtomatik). */
    private fun renderFunctions() {
        b.fnList.removeAllViews()
        val list = MenuFunctions.LIST
        for ((idx, fn) in list.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt())
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = fn.title; textSize = 16f; setTextColor(getColor(R.color.text_dark))
            })
            col.addView(TextView(this).apply {
                text = fn.sub; textSize = 12f; setTextColor(getColor(R.color.text_gray))
            })
            val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
                isChecked = Config.isFn(this@SettingsActivity, fn.key)
                setOnCheckedChangeListener { _, on ->
                    Config.setFn(this@SettingsActivity, fn.key, on)
                }
            }
            row.addView(col)
            row.addView(sw)
            b.fnList.addView(row)
            if (idx < list.size - 1) {
                b.fnList.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(getColor(R.color.card_stroke))
                })
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
