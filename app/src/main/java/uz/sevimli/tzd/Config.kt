package uz.sevimli.tzd

import android.content.Context

/**
 * Qurilma sozlamalari: backend domeni, qurilma tokeni, tanlangan sklad.
 * SharedPreferences'da saqlanadi.
 */
object Config {
    private const val PREFS = "sevimli_tzd_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_STORE_ID = "store_id"
    private const val KEY_STORE_NAME = "store_name"
    private const val KEY_ORG_ID = "org_id"
    private const val KEY_ORG_NAME = "org_name"

    // Standart qiymatlar (birinchi o'rnatishda)
    private const val DEFAULT_BASE_URL = "https://web-production-e3caa.up.railway.app"
    private const val DEFAULT_TOKEN = "238494fc604b1a37258c7467726fe9aa9826c7abeab7ba9c"

    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun token(ctx: Context): String =
        prefs(ctx).getString(KEY_TOKEN, DEFAULT_TOKEN) ?: DEFAULT_TOKEN

    fun storeId(ctx: Context): Int =
        prefs(ctx).getInt(KEY_STORE_ID, -1)

    fun storeName(ctx: Context): String? =
        prefs(ctx).getString(KEY_STORE_NAME, null)

    fun setBaseUrl(ctx: Context, url: String) =
        prefs(ctx).edit().putString(KEY_BASE_URL, url.trim().trimEnd('/')).apply()

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

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
