package uz.sevimli.tzd

import android.content.Context

/**
 * QORALAMA — yarim qolgan hujjatlar (turi bo'yicha bitta) telefonda saqlanadi.
 * Har hujjat ekrani o'zgarganda (skan/tahrir) o'z holatini JSON qilib saqlaydi,
 * qaytib kirganda "davom etasizmi?" deb so'raydi. Yakunlangach — tozalanadi.
 *
 * type = "supply" | "inventory" | "move" | "shipment" | "writeoff" | "preturn"
 */
object DraftStore {
    private const val PREFS = "sevimli_drafts"

    fun save(ctx: Context, type: String, json: String) =
        prefs(ctx).edit().putString(type, json).apply()

    fun load(ctx: Context, type: String): String? =
        prefs(ctx).getString(type, null)

    fun has(ctx: Context, type: String): Boolean =
        !prefs(ctx).getString(type, null).isNullOrBlank()

    fun clear(ctx: Context, type: String) =
        prefs(ctx).edit().remove(type).apply()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
