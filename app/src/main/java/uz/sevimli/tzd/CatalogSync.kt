package uz.sevimli.tzd

import android.content.Context

/**
 * Serverdan ma'lumotni telefonga yuklab, mahalliy bazaga (LocalDb) saqlaydi.
 * Internet BOR paytda chaqiriladi (fon oqimida). Internet yo'q bo'lsa jim
 * o'tadi — ilova eski nusxadan ishlayveradi.
 *
 * 1-bosqich: kontragentlar. (2-bosqichda mahsulotlar qo'shiladi.)
 */
object CatalogSync {

    private const val PREFS = "sevimli_tzd_sync"
    private const val KEY_CP_AT = "counterparties_synced_at"

    /**
     * Barcha kontragentlarni yuklab, mahalliy bazani yangilaydi.
     * Muvaffaqiyatli bo'lsa true. FON oqimida chaqiring.
     */
    fun syncCounterparties(ctx: Context): Boolean {
        val r = Api.get(ctx, "counterparties", mapOf("all" to "1"))
        if (r is ApiResult.Success) {
            val arr = r.json.optJSONArray("counterparties") ?: return false
            LocalDb.get(ctx).replaceCounterparties(arr)
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_CP_AT, System.currentTimeMillis()).apply()
            return true
        }
        return false
    }

    /** Oxirgi kontragent sinxroni vaqti (millis), 0 = hech qachon. */
    fun counterpartiesSyncedAt(ctx: Context): Long =
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_CP_AT, 0L)
}
