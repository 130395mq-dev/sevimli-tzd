package uz.sevimli.tzd

import android.content.Context
import org.json.JSONObject

/**
 * Serverdan ma'lumotni telefonga yuklab, mahalliy bazaga (LocalDb) saqlaydi.
 * FON oqimida chaqiring. Internet yo'q bo'lsa jim o'tadi (eski nusxa qoladi).
 *
 * - Kontragentlar: to'liq.
 * - Mahsulotlar: birinchi marta to'liq (bo'laklab), keyin delta (faqat o'zgargan).
 * - Qoldiq: to'liq (yengil), tez-tez yangilanadi.
 */
object CatalogSync {

    private const val PREFS = "sevimli_tzd_sync"
    private const val KEY_CP_AT = "counterparties_at"
    private const val KEY_PROD_AT = "products_at"
    private const val PAGE = 500

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Kontragentlar ----------

    fun syncCounterparties(ctx: Context): Boolean {
        val r = Api.get(ctx, "counterparties", mapOf("all" to "1"))
        if (r is ApiResult.Success) {
            val arr = r.json.optJSONArray("counterparties") ?: return false
            LocalDb.get(ctx).replaceCounterparties(arr)
            prefs(ctx).edit().putLong(KEY_CP_AT, System.currentTimeMillis()).apply()
            return true
        }
        return false
    }

    // ---------- Mahsulotlar ----------

    /**
     * Bir sahifa katalogni oladi (retry bilan). offset/limit + ixtiyoriy delta.
     * null = xato (internet yo'q va h.k.).
     */
    private fun fetchPage(ctx: Context, offset: Int, updatedSince: String?): JSONObject? {
        val params = HashMap<String, String>()
        params["offset"] = offset.toString()
        params["limit"] = PAGE.toString()
        if (!updatedSince.isNullOrBlank()) params["updated_since"] = updatedSince
        var attempt = 0
        while (attempt < 3) {
            val r = Api.get(ctx, "catalog", params)
            if (r is ApiResult.Success) return r.json
            attempt++
        }
        return null
    }

    /**
     * To'liq yuklash: butun katalogni qayta oladi. progress(done,total) chaqiriladi.
     * Muvaffaqiyatli bo'lsa true.
     */
    fun syncProductsFull(ctx: Context, progress: (Int, Int) -> Unit): Boolean {
        val db = LocalDb.get(ctx)
        val first = fetchPage(ctx, 0, null) ?: return false
        db.clearProducts()
        var total = first.optInt("total", 0)
        var done = 0
        var pageJson: JSONObject? = first
        var offset = 0
        while (pageJson != null) {
            val arr = pageJson.optJSONArray("products")
            if (arr != null && arr.length() > 0) {
                db.upsertProducts(arr)
                done += arr.length()
                progress(done, total)
            }
            if (pageJson.isNull("next_offset")) break
            offset = pageJson.optInt("next_offset")
            pageJson = fetchPage(ctx, offset, null) ?: break
        }
        syncStock(ctx)
        prefs(ctx).edit().putLong(KEY_PROD_AT, System.currentTimeMillis()).apply()
        return true
    }

    /** Delta: faqat oxirgi yuklashdan keyin o'zgargan mahsulotlar. */
    fun syncProductsDelta(ctx: Context): Boolean {
        val db = LocalDb.get(ctx)
        val since = db.maxMsUpdated()
        if (since.isBlank()) {
            // hali umuman yuklanmagan — delta emas, to'liq kerak
            return false
        }
        var offset = 0
        var pageJson = fetchPage(ctx, 0, since) ?: return false
        while (true) {
            val arr = pageJson.optJSONArray("products")
            if (arr != null && arr.length() > 0) db.upsertProducts(arr)
            if (pageJson.isNull("next_offset")) break
            offset = pageJson.optInt("next_offset")
            pageJson = fetchPage(ctx, offset, since) ?: break
        }
        syncStock(ctx)
        prefs(ctx).edit().putLong(KEY_PROD_AT, System.currentTimeMillis()).apply()
        return true
    }

    /** Qoldiqni to'liq yangilaydi (qurilma skladi bo'yicha). */
    fun syncStock(ctx: Context): Boolean {
        val r = Api.get(ctx, "stock")
        if (r is ApiResult.Success) {
            val obj = r.json.optJSONObject("stock") ?: return false
            LocalDb.get(ctx).updateStock(obj)
            return true
        }
        return false
    }

    /**
     * Ilova ochilganda jimgina yangilash (chekланган — tez-tez qotirmaslik uchun).
     * Baza bo'sh bo'lsa hech narsa qilmaydi (foydalanuvchi "To'liq yangilash" bilan boshlaydi).
     */
    fun autoRefresh(ctx: Context) {
        // kontragent — kamdan-kam
        syncCounterparties(ctx)
        val db = LocalDb.get(ctx)
        if (db.productCount() == 0) return  // hali to'liq yuklanmagan
        val last = prefs(ctx).getLong(KEY_PROD_AT, 0L)
        val age = System.currentTimeMillis() - last
        if (age < 15 * 60 * 1000L) return   // 15 daqiqadan yosh bo'lsa — tegmaymiz
        syncProductsDelta(ctx)
    }

    // ---------- Holat ----------

    fun productsSyncedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_PROD_AT, 0L)
}
