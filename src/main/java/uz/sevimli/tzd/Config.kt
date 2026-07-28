package uz.sevimli.tzd

import android.content.Context
import android.content.SharedPreferences

/**
 * Qurilma sozlamalari: backend domeni, qurilma tokeni, tanlangan sklad.
 *
 * Token va boshqa sozlamalar EncryptedSharedPreferences (androidx.security)
 * orqali shifrlangan holda saqlanadi. Eski (shifrlanmagan) "sevimli_tzd_prefs"
 * faylidagi qiymatlar birinchi ishga tushishda avtomatik ko'chiriladi, shu
 * sababli allaqachon sozlangan terminallar sozlamasini yo'qotmaydi.
 * Agar shifrlash kutubxonasi biror sababga ko'ra ishlamasa — ilova qulab
 * tushmasligi uchun eski oddiy prefs'ga qaytiladi (fallback).
 */
object Config {
    private const val PREFS = "sevimli_tzd_prefs"
    private const val ENC_PREFS = "sevimli_tzd_prefs_enc"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_STORE_ID = "store_id"
    private const val KEY_STORE_NAME = "store_name"
    private const val KEY_ORG_ID = "org_id"
    private const val KEY_ORG_NAME = "org_name"

    // Standart qiymatlar (birinchi o'rnatishda)
    private const val DEFAULT_BASE_URL = "https://web-production-e3caa.up.railway.app"
    // Xavfsizlik: qat'iy kodlangan haqiqiy token OLIB TASHLANDI. Sozlanmagan
    // o'rnatishlar avtomatik autentifikatsiya qilinmasin — Setup ekraniga tushsin.
    private const val DEFAULT_TOKEN = ""

    // Shifrlangan (yoki fallback) prefs bitta marta yaratilib keshlanadi.
    @Volatile
    private var cached: SharedPreferences? = null

    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun token(ctx: Context): String =
        prefs(ctx).getString(KEY_TOKEN, DEFAULT_TOKEN) ?: DEFAULT_TOKEN

    fun storeId(ctx: Context): Int =
        prefs(ctx).getInt(KEY_STORE_ID, -1)

    fun storeName(ctx: Context): String? =
        prefs(ctx).getString(KEY_STORE_NAME, null)

    // Xavfsizlik: faqat https qabul qilinadi. http (yoki noto'g'ri) URL berilsa —
    // e'tiborsiz qoldirib, standart https base url'ga qaytamiz.
    fun setBaseUrl(ctx: Context, url: String) {
        val cleaned = url.trim().trimEnd('/')
        val safe = if (cleaned.startsWith("https://")) cleaned else DEFAULT_BASE_URL
        prefs(ctx).edit().putString(KEY_BASE_URL, safe).apply()
    }

    fun setToken(ctx: Context, token: String) =
        prefs(ctx).edit().putString(KEY_TOKEN, token.trim()).apply()

    // --- Kabinet sessiya tokeni (login natijasi) — SaaS API uchun ---
    fun sessionToken(ctx: Context): String =
        prefs(ctx).getString("session_token", "") ?: ""

    fun setSessionToken(ctx: Context, token: String) =
        prefs(ctx).edit().putString("session_token", token.trim()).apply()

    fun setStore(ctx: Context, id: Int, name: String) =
        prefs(ctx).edit().putInt(KEY_STORE_ID, id).putString(KEY_STORE_NAME, name).apply()

    fun hasStore(ctx: Context): Boolean = storeId(ctx) > 0

    // --- Narx turi: "chakana" (standart) yoki "ulgurji" (optom filial) ---
    fun priceMode(ctx: Context): String =
        prefs(ctx).getString("price_mode", "chakana") ?: "chakana"

    fun setPriceMode(ctx: Context, mode: String) =
        prefs(ctx).edit().putString("price_mode", mode).apply()

    fun isUlgurji(ctx: Context): Boolean = priceMode(ctx) == "ulgurji"

    // --- Menyu bo'limlari yoqilgan/o'chirilgan (kalit bo'yicha, standart: yoqilgan) ---
    fun isFn(ctx: Context, key: String): Boolean =
        prefs(ctx).getBoolean("fn_$key", true)

    fun setFn(ctx: Context, key: String, on: Boolean) =
        prefs(ctx).edit().putBoolean("fn_$key", on).apply()

    fun orgId(ctx: Context): String = prefs(ctx).getString(KEY_ORG_ID, "") ?: ""
    fun orgName(ctx: Context): String? = prefs(ctx).getString(KEY_ORG_NAME, null)
    fun setOrg(ctx: Context, id: String, name: String) =
        prefs(ctx).edit().putString(KEY_ORG_ID, id).putString(KEY_ORG_NAME, name).apply()

    // --- Qurilma ID: token bitta terminalga bog'lanishi uchun (1 token = 1 TZD) ---
    // Android ID (qayta o'rnatishda ham barqaror) yoki UUID; saqlanadi.
    // --- Birinchi ishga tushish tugaganmi (login+token+sklad) ---
    fun isConfigured(ctx: Context): Boolean =
        prefs(ctx).getBoolean("configured", false) || hasStore(ctx)

    fun setConfigured(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("configured", v).apply()

    fun deviceId(ctx: Context): String {
        val saved = prefs(ctx).getString("device_hw_id", null)
        if (!saved.isNullOrBlank()) return saved
        val androidId = try {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (e: Exception) { null }
        val id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c")
                    androidId else java.util.UUID.randomUUID().toString()
        prefs(ctx).edit().putString("device_hw_id", id).apply()
        return id
    }

    // Oddiy (shifrlanmagan) eski prefs — migratsiya manbai va fallback.
    private fun plainPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val store = buildPrefs(ctx.applicationContext)
            cached = store
            return store
        }
    }

    private fun buildPrefs(ctx: Context): SharedPreferences {
        val enc = try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                ctx,
                ENC_PREFS,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            // Shifrlash ishlamadi — ilova qulamasin, eski oddiy prefs bilan davom etamiz.
            return plainPrefs(ctx)
        }

        // Birinchi ishga tushishda: eski oddiy prefs'da qiymat bor, lekin shifrlangan
        // do'kon hali bo'sh bo'lsa — barcha sozlamalarni ko'chiramiz (live fleet
        // o'z sozlamasini yo'qotmasin).
        try {
            val old = plainPrefs(ctx)
            if (enc.all.isEmpty() && old.all.isNotEmpty()) {
                migratePlainToEncrypted(old, enc)
            }
        } catch (e: Throwable) {
            // Migratsiya muvaffaqiyatsiz bo'lsa ham shifrlangan do'kondan foydalanamiz;
            // yomon holatda foydalanuvchi qayta sozlaydi, lekin ilova qulamaydi.
        }
        return enc
    }

    // Eski oddiy prefs'dagi BARCHA kalitlarni shifrlangan do'konga ko'chiradi
    // (device_token, session_token, store_id, store_name, org_id/org_name,
    // base_url, fn_*, price_mode, configured, device_hw_id va h.k.).
    private fun migratePlainToEncrypted(old: SharedPreferences, enc: SharedPreferences) {
        val editor = enc.edit()
        for ((key, value) in old.all) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> { /* noma'lum tur — o'tkazib yuboriladi */ }
            }
        }
        editor.apply()
    }
}
