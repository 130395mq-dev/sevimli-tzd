package uz.sevimli.tzd

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * QO'NG'IROQCHA ostidagi ma'lumot.
 *
 * YANGI FUNKSIYA EMAS va yangi API yo'q. Bu allaqachon mavjud ikkita
 * ro'yxatning (kelgan ko'chirishlar, ochiq sanoqlar) SONI, ustiga
 * navbatda turgan yuborilmagan hujjatlar.
 *
 * Maqsad: xodim har bo'limni ochib "bormikan" deb tekshirib yurmasin.
 * Yangilik bo'lsa — qo'ng'iroqcha yonida nuqta chiqadi.
 *
 * FAQAT BUGUNGI hujjatlar sanaladi. Ilgari butun tarix qo'shilardi va
 * qo'ng'iroqchada oylik eski hujjatlar ham turaverardi — xodim uni
 * o'qishni tashlab qo'yardi. Endi u faqat bugun kelgan ishni ko'rsatadi.
 *
 * Raqamlar HAQIQIY. So'rov o'tmasa — o'sha bo'lim umuman ko'rsatilmaydi.
 */
object Notices {

    data class Data(
        val moves: Int = 0,          // kelgan ko'chirishlar
        val inventories: Int = 0,    // ochiq sanoqlar
        val pending: Int = 0,        // yuborilmagan hujjatlar (offline navbat)
        val updateName: String? = null,  // yangi versiya bo'lsa — nomi
    ) {
        val total: Int get() = moves + inventories + pending
        val isEmpty: Boolean get() = total == 0 && updateName == null
    }

    @Volatile
    private var cached = Data()
    @Volatile
    private var cachedAt = 0L
    private const val TTL_MS = 60_000L

    fun last(): Data = cached

    /**
     * Serverdan sonlarni oladi. FON oqimida chaqiring.
     * Sklad tanlanmagan bo'lsa so'rov yubormaydi.
     */
    fun refresh(ctx: Context, force: Boolean = false): Data {
        val now = System.currentTimeMillis()
        if (!force && now - cachedAt < TTL_MS) return cached

        val pending = OfflineQueue.size(ctx)
        val upd = Updater.newerVersionOrNull()
        if (Config.storeId(ctx) <= 0) {
            cached = Data(pending = pending, updateName = upd)
            cachedAt = now
            return cached
        }

        val moves = countToday(ctx, "move-incoming", "moves")
        val invs = countToday(ctx, "inventory-open", "inventories")
        cached = Data(moves = moves, inventories = invs,
                      pending = pending, updateName = upd)
        cachedAt = now
        return cached
    }

    /**
     * Faqat BUGUNGI yozuvlarni sanaydi.
     *
     * Server har hujjatni `moment` ("YYYY-MM-DD HH:MM") bilan qaytaradi —
     * shu maydonning sana qismi bugungi kunga to'g'ri kelsa hisoblanadi.
     * Yangi so'rov ham, backend o'zgarishi ham kerak emas.
     *
     * Xato bo'lsa 0 — bo'lim shunchaki ko'rsatilmaydi, xato oynasi chiqmaydi.
     */
    private fun countToday(ctx: Context, path: String, key: String): Int {
        val r = Api.get(ctx, path)
        if (r !is ApiResult.Success) return 0
        val arr = r.json.optJSONArray(key) ?: return 0
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var n = 0
        for (i in 0 until arr.length()) {
            val moment = arr.optJSONObject(i)?.optString("moment") ?: continue
            if (moment.startsWith(today)) n++
        }
        return n
    }
}
