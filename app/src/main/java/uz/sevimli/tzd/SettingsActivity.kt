package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
                Toast.makeText(this, getString(R.string.org_selected_fmt, name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateLangLabel() {
        b.langValue.text = Lang.label(Lang.current(this))
    }

    /**
     * Til tanlash oynasi. Ikki qator, ortiqcha tugmasiz: bosildi - tanlandi.
     * Dialog ilovaning umumiy uslubini oladi (themes.xml dagi
     * `android:alertDialogTheme`), shuning uchun bu yerda uslub berilmaydi.
     */
    private fun pickLang() {
        val codes = arrayOf(Lang.UZ, Lang.RU)
        val names = codes.map { Lang.label(it) }.toTypedArray()
        val now = codes.indexOf(Lang.current(this)).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.lang_title))
            .setSingleChoiceItems(names, now) { d, which ->
                d.dismiss()
                if (codes[which] != Lang.current(this)) {
                    Lang.set(this, codes[which])
                    // Ekran yangi tilda qayta chiziladi.
                    recreate()
                }
            }
            .show()
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

        // TIL. Menejer parolisiz ishlaydi - tilni xodimning o'zi tanlaydi.
        updateLangLabel()
        b.rowLang.setOnClickListener { pickLang() }

        // Parolni ko'rsatish/yashirish. Uzun parolni xato yozganini xodim
        // ko'ra olsin — qayta-qayta urinib vaqt ketmasin.
        b.btnEye.setOnClickListener {
            val hidden = b.inPassword.transformationMethod is PasswordTransformationMethod
            b.inPassword.transformationMethod =
                if (hidden) HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
            b.btnEye.setImageResource(
                if (hidden) R.drawable.j_ic_eye else R.drawable.j_ic_eye_off)
            b.inPassword.setSelection(b.inPassword.text?.length ?: 0)
        }

        // Bo'limlar yig'ilgan holda turadi — kerakligi bosilganda ochiladi.
        // Yangi ekran YO'Q, navigatsiya o'zgarmadi: hammasi shu ekranда.
        section(b.rowFn, b.boxFn, b.arrFn)
        section(b.rowDev, b.boxDev, b.arrDev)
        section(b.rowOrg, b.boxOrg, b.arrOrg)
        section(b.rowPrice, b.boxPrice, b.arrPrice)
        section(b.rowStore, b.boxStore, b.arrStore)
        // "Ombor" eng ko'p ishlatiladi — ochiq holda boshlanadi.
        b.arrStore.rotation = 90f
    }

    /** Bitta yig'iladigan bo'lim. Strelka ochiqда pastga qaraydi. */
    private fun section(row: View, box: View, arrow: ImageView) {
        arrow.rotation = if (box.visibility == View.VISIBLE) 90f else 0f
        row.setOnClickListener {
            val open = box.visibility == View.VISIBLE
            box.visibility = if (open) View.GONE else View.VISIBLE
            arrow.animate().rotation(if (open) 0f else 90f).setDuration(140).start()
        }
    }

    private fun doLogin() {
        val login = b.inLogin.text.toString().trim()
        val password = b.inPassword.text.toString()
        if (login.isEmpty() || password.isEmpty()) {
            showLoginError(getString(R.string.enter_login_pass))
            return
        }
        b.loginError.visibility = View.GONE
        b.loading.visibility = View.VISIBLE
        b.btnLogin.isEnabled = false

        val body = JSONObject().put("login", login).put("password", password)
        thread {
            val result = Api.post(this, "manager-login", body)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                b.btnLogin.isEnabled = true
                when (result) {
                    is ApiResult.Success -> enterStoreStage(result.json)
                    is ApiResult.Error -> showLoginError(result.message)
                }
            }
        }
    }

    private fun showLoginError(msg: String) {
        b.loginError.text = msg
        b.loginError.visibility = View.VISIBLE
    }

    /**
     * Login muvaffaqiyatli — sklad bosqichiga o'tamiz.
     * TEZLIK: sklad ro'yxati login javobida keladi — qo'shimcha so'rov shart emas.
     * Agar javobda skladlar bo'lmasa (eski server yoki token almashtirilgan holat),
     * zaxira sifatida alohida "stores" so'rovi qilinadi.
     */
    private fun enterStoreStage(loginJson: JSONObject?) {
        b.loginStage.visibility = View.GONE
        b.storeStage.visibility = View.VISIBLE
        b.inToken.setText(Config.token(this))   // joriy tokenni ko'rsatamiz
        renderFunctions()                        // bo'limlar yoqish/o'chirish
        // Narx turi (chakana / ulgurji)
        b.swUlgurji.isChecked = Config.isUlgurji(this)
        b.swUlgurji.setOnCheckedChangeListener { _, on ->
            Config.setPriceMode(this, if (on) "ulgurji" else "chakana")
        }

        val stores = loginJson?.optJSONArray("stores")
        if (stores != null && stores.length() > 0) {
            renderStores(loginJson)   // login javobidan — bir zumda, so'rovsiz
            return
        }

        // Zaxira yo'l: alohida sklad so'rovi
        b.loading.visibility = View.VISIBLE
        thread {
            val result = Api.get(this, "stores")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
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
            Toast.makeText(this, getString(R.string.enter_token), Toast.LENGTH_SHORT).show(); return
        }
        Config.setToken(this, t)
        thread { LocalDb.get(this).clearProducts() }   // boshqa sklad ma'lumoti tozalansin
        Toast.makeText(this, getString(R.string.token_saved), Toast.LENGTH_LONG).show()
        enterStoreStage(null)   // yangi token bilan sklad ro'yxati (alohida so'rov)
    }

    /**
     * Sklad ro'yxati. Har qator: ikonka + nom + tanlangan belgisi.
     * Qator balandligi 64dp dan kam emas — qo'lqop bilan ham aniq bosiladi.
     */
    private fun renderStores(json: JSONObject) {
        b.storeList.removeAllViews()
        val stores = json.optJSONArray("stores") ?: return
        val currentId = Config.storeId(this)

        for (i in 0 until stores.length()) {
            val s = stores.optJSONObject(i) ?: continue
            val id = s.optInt("id")
            val name = s.optString("name")
            val selected = id == currentId

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
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand))
                background = getDrawable(R.drawable.j_icon_box)
                val p = dp(8f).toInt()
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt())
            })
            row.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(getColor(R.color.text_dark))
                if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(12f).toInt()
                layoutParams = lp
            })
            row.addView(View(this).apply {
                background = getDrawable(
                    if (selected) R.drawable.j_radio_on else R.drawable.j_radio_off)
                layoutParams = LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt())
            })
            row.setOnClickListener { selectStore(id, name) }
            b.storeList.addView(row)
        }
    }

    /** Sklad tanlash qulfi — ikkita sklad ketma-ket bosilib qolmasin. */
    private val storeBusy = Busy()

    private fun selectStore(id: Int, name: String) {
        // ILGARI: so'rov ketayotganda boshqa sklad ham bosilishi mumkin edi.
        // Ikkala javob kelganda qurilma ko'rsatilganidan BOSHQA skladga
        // bog'lanib qolishi mumkin edi.
        if (!storeBusy.start()) return
        b.loading.visibility = View.VISIBLE
        val body = JSONObject().put("store_id", id)
        thread {
            val result = Api.post(this, "set-store", body)
            runOnUiThread {
                storeBusy.stop()
                if (isFinishing || isDestroyed) return@runOnUiThread
                b.loading.visibility = View.GONE
                when (result) {
                    is ApiResult.Success -> {
                        Config.setStore(this, id, name)
                        Toast.makeText(this, getString(R.string.store_selected_fmt, name), Toast.LENGTH_LONG).show()
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
                minimumHeight = dp(60f).toInt()
                setPadding(dp(2f).toInt(), dp(10f).toInt(), dp(2f).toInt(), dp(10f).toInt())
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = getString(fn.title); textSize = 16f; setTextColor(getColor(R.color.text_dark))
            })
            col.addView(TextView(this).apply {
                text = getString(fn.sub); textSize = 13f; setTextColor(getColor(R.color.text_gray))
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
