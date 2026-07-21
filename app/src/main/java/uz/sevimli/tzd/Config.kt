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

    fun setStore(ctx: Context, id: Int, name: String) =
        prefs(ctx).edit().putInt(KEY_STORE_ID, id).putString(KEY_STORE_NAME, name).apply()

    fun hasStore(ctx: Context): Boolean = storeId(ctx) > 0

    fun orgId(ctx: Context): String = prefs(ctx).getString(KEY_ORG_ID, "") ?: ""
    fun orgName(ctx: Context): String? = prefs(ctx).getString(KEY_ORG_NAME, null)
    fun setOrg(ctx: Context, id: String, name: String) =
        prefs(ctx).edit().putString(KEY_ORG_ID, id).putString(KEY_ORG_NAME, name).apply()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
