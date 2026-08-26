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
    // Server katalog reviziyasi. Serverda shtrixlar (masalan upakovka shtrixlari)
    // mahsulotning `updated` vaqtiga tegmasdan to'ldirilsa, delta ularni OLIB
    // KELMAYDI — natijada skan har safar serverga borib sekinlashardi.
    // Raqam o'zgarganini ko'rsak — katalogni bir marta to'liq qayta yuklaymiz.
    private const val KEY_REV = "catalog_rev"
    /** To'liq yuklash yarim qolganini belgilaydi (0 emas — alohida bayroq,
     *  chunki server catalog_rev ni yubormasa KEY_REV=0 ma'noni yo'qotardi). */
    private const val KEY_PARTIAL = "catalog_partial_at"
    /** Yarim qolgandan keyin qayta urinishgacha kutish (millisekund). */
    private const val PARTIAL_RETRY_MS = 15 * 60 * 1000L
    // Sahifa hajmi. 500 ta yozuv ~150 KB; server endi javobni siqib yuboradi
    // (gzip), shuning uchun kattaroq sahifa ham tarmoqqa og'ir emas — lekin
    // eski terminallarda xotira cheklangani uchun 500 xavfsiz chegara.
    private const val PAGE = 500

    /**
     * Bir vaqtda BITTA sinx ishlaydi.
     *
     * MUAMMO: `autoRefresh` ikki joydan chaqirilardi (onResume va 2 daqiqalik
     * taymer), qulf esa yo'q edi. Ikkita sinx bir vaqtda ishlab qolsa, ikkalasi
     * ham katalogni to'liq qayta yuklashi mumkin edi — va to'liq yuklash
     * `clearProducts()` bilan boshlanadi, ya'ni o'sha payt mahalliy katalog
     * BO'SH bo'lardi. Aynan shu vaqtda skanerlangan tovar mahalliy bazada
     * topilmay, har skan serverga borardi va sekinlashardi.
     */
    private val syncing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** To'liq yuklashni bekor qilish (foydalanuvchi "Bekor" bosgan bo'lsa). */
    @Volatile private var cancelRequested = false

    /** Oxirgi yuklash foydalanuvchi tomonidan to'xtatilganmi (xabar matni uchun). */
    @Volatile private var lastCancelled = false

    /** Hozir QO'LDA "To'liq yangilash" ketyaptimi. */
    @Volatile private var manualRunning = false

    /**
     * "Bekor" bosildi.
     *
     * DIQQAT: belgi FAQAT qo'lda yangilash davomida qo'yiladi. Aks holda
     * oyna yopilishidan oldin bosilgan "Bekor" belgisi osilib qolib,
     * keyingi BARCHA fon sinxronlarini jimgina o'ldirib qo'yardi.
     */
    fun cancel() {
        if (manualRunning) cancelRequested = true
    }

    /** Qo'lda yangilash BOSHLANISHIDA. Eski belgilarni tozalaydi. */
    fun beginManual() {
        cancelRequested = false
        lastCancelled = false
        manualRunning = true
    }

    /** Qo'lda yangilash TUGAGANDA — natijasidan qat'i nazar chaqirilishi shart. */
    fun endManual() {
        lastCancelled = cancelRequested
        cancelRequested = false
        manualRunning = false
    }

    val isCancelled: Boolean get() = lastCancelled

    /** Bekor bosilganmi (hozir ketayotgan yuklash uchun). */
    val isCancelRequested: Boolean get() = cancelRequested

    /**
     * Fon sinxroni tugashini kutadi (qo'lda "To'liq yangilash" uchun).
     *
     * MUAMMO: qo'lda yangilash bosilganda fonda 2 daqiqalik avto-sinx
     * endigina boshlangan bo'lishi mumkin. U qulfni ushlab turgani uchun
     * qo'lda yangilash darrov "muvaffaqiyatsiz" bo'lib tugardi va xodimga
     * "internetni tekshiring" degan noto'g'ri xabar chiqardi.
     *
     * @return true — qulf bo'shadi (yoki band emas edi)
     */
    fun waitIdle(maxMs: Long = 15_000L): Boolean {
        val until = System.currentTimeMillis() + maxMs
        while (syncing.get() && System.currentTimeMillis() < until) {
            try { Thread.sleep(250) } catch (e: InterruptedException) { return false }
        }
        return !syncing.get()
    }

    val isSyncing: Boolean get() = syncing.get()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Kontragentlar ----------

    fun syncCounterparties(ctx: Context): Boolean {
        // DIQQAT: bu yerda `cancelRequested` TEKSHIRILMAYDI. Aks holda
        // bekor qilingan qo'lda yangilashdan keyin fon sinxroni ham
        // jimgina ishlamay qolardi. Bekor qilishni qo'lda yangilash
        // oqimining o'zi tekshiradi (MenuActivity).
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
        // Urinish 3 tadan 2 taga tushirildi: har urinish eng yomon holatda
        // 20 soniya, ya'ni 3 urinish = 60 soniya. Bitta sahifa uchun juda ko'p.
        var attempt = 0
        while (attempt < 2) {
            if (cancelRequested) return null
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
        // Boshqa sinx ketyapti — bu yuklash boshlanmaydi.
        if (!syncing.compareAndSet(false, true)) return false
        try {
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
                if (cancelRequested) return failPartial(ctx, first.optInt("catalog_rev", 0))
                if (pageJson.isNull("next_offset")) break
                offset = pageJson.optInt("next_offset")
                // Sahifa kelmasa — katalog YARIM qolgan, bu muvaffaqiyat EMAS.
                pageJson = fetchPage(ctx, offset, null)
                    ?: return failPartial(ctx, first.optInt("catalog_rev", 0))
            }
            syncStock(ctx)
            prefs(ctx).edit()
                .putLong(KEY_PROD_AT, System.currentTimeMillis())
                .putInt(KEY_REV, first.optInt("catalog_rev", 0))
                .remove(KEY_PARTIAL)
                .apply()
            return true
        } finally {
            syncing.set(false)
        }
    }

    /**
     * To'liq yuklash yarim qolganda chaqiriladi.
     *
     * MUAMMO: ilgari yarim yuklangan katalog ham "muvaffaqiyatli" deb
     * belgilanardi (vaqt va reviziya yozilardi). Keyingi delta sinx esa
     * mahalliy bazadagi ENG YANGI o'zgarish vaqtidan boshlab so'raydi —
     * yuklanmay qolgan tovarlar esa undan ESKIROQ bo'lishi mumkin, ya'ni
     * ular hech qachon kelmasdi. Terminal doimiy yarim katalog bilan
     * qolib ketardi.
     *
     * Endi: alohida "yarim qoldi" belgisi qo'yiladi va keyingi delta sinx
     * shu belgini ko'rib katalogni TO'LIQ qayta yuklaydi — lekin darrov
     * emas, PARTIAL_RETRY_MS dan keyin.
     *
     * Reviziya ham yozib qo'yiladi: aks holda "reviziya o'zgardi" sharti
     * har 2 daqiqada qayta ishlab, orqaga surish (backoff) e'tiborsiz
     * qolardi va katalog har safar tozalanib ketaverardi.
     */
    private fun failPartial(ctx: Context, srvRev: Int): Boolean {
        // Vaqt belgisini NOLGA tushirmaymiz: aks holda avto-sinx 2 daqiqada
        // bir marta to'liq yuklashni qayta boshlab, har safar mahalliy
        // katalogni tozalab tashlardi (skan uchun eng yomon holat).
        prefs(ctx).edit()
            .putLong(KEY_PARTIAL, System.currentTimeMillis())
            .putLong(KEY_PROD_AT, System.currentTimeMillis())
            .putInt(KEY_REV, srvRev)
            .apply()
        return false
    }

    /** Delta: faqat oxirgi yuklashdan keyin o'zgargan mahsulotlar. */
    fun syncProductsDelta(ctx: Context): Boolean {
        if (!syncing.compareAndSet(false, true)) return false   // allaqachon ketyapti
        var keepLock = true
        try {
            val db = LocalDb.get(ctx)
            val since = db.maxMsUpdated()
            if (since.isBlank()) {
                // hali umuman yuklanmagan — delta emas, to'liq kerak
                return false
            }
            var offset = 0
            var pageJson = fetchPage(ctx, 0, since) ?: return false

            // Serverda shtrixlar qayta to'ldirilgan bo'lsa — delta yetarli emas,
            // to'liq yuklab olamiz (shundan keyin skan yana mahalliy bazadan, bir zumda).
            val srvRev = pageJson.optInt("catalog_rev", 0)
            val myRev = prefs(ctx).getInt(KEY_REV, 0)
            // Oldingi to'liq yuklash yarim qolganmi? Qolgan bo'lsa katalog to'liq
            // emas — uni delta bilan tuzatib bo'lmaydi (yetishmayotgan tovarlar
            // mahalliy bazadagi eng yangi vaqtdan ESKIROQ bo'lishi mumkin).
            val partialAt = prefs(ctx).getLong(KEY_PARTIAL, 0L)
            val partialDue = partialAt > 0L &&
                System.currentTimeMillis() - partialAt >= PARTIAL_RETRY_MS
            if (partialDue || (srvRev != 0 && srvRev != myRev)) {
                // To'liq yuklash o'z qulfini oladi — avval bunisini bo'shatamiz
                syncing.set(false)
                keepLock = false
                return syncProductsFull(ctx) { _, _ -> }
            }

            // Server bergan "o'chgan tovarlar" ro'yxatini mahalliy bazadan ham olib tashlaymiz
            val deleted = pageJson.optJSONArray("deleted")
            if (deleted != null && deleted.length() > 0) db.deleteProducts(deleted)
            while (true) {
                val arr = pageJson.optJSONArray("products")
                if (arr != null && arr.length() > 0) db.upsertProducts(arr)
                if (pageJson.isNull("next_offset")) break
                offset = pageJson.optInt("next_offset")
                // Delta yarim qolsa — vaqt belgisini yangilamaymiz, keyingi
                // sikl o'sha joydan qaytadan oladi.
                pageJson = fetchPage(ctx, offset, since) ?: return false
            }
            syncStock(ctx)
            prefs(ctx).edit().putLong(KEY_PROD_AT, System.currentTimeMillis()).apply()
            return true
        } finally {
            if (keepLock) syncing.set(false)
        }
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
        val db = LocalDb.get(ctx)
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        // Kontragent — kamdan-kam (10 daqiqada bir).
        // ILGARI bu tekshiruv mahsulot katalogi bo'sh bo'lsa umuman
        // bajarilmasdi, ya'ni yangi yetkazib beruvchi ko'rinmay qolardi.
        if (now - p.getLong(KEY_CP_AT, 0L) > 10 * 60 * 1000L) syncCounterparties(ctx)
        if (db.productCount() == 0) {
            // ILGARI: bu yerdan shunchaki qaytilardi va xodim menyudagi
            // "Yangilash" tugmasini bosishi kerak edi. Tugma olib tashlangach
            // yangi terminal abadiy bo'sh katalog bilan qolardi.
            // ENDI: birinchi to'liq yuklash ham FONDA, jimgina ketadi.
            val partial = p.getLong(KEY_PARTIAL, 0L)
            if (partial > 0L && now - partial < PARTIAL_RETRY_MS) return
            syncProductsFull(ctx) { _, _ -> }
            return
        }
        // Mahsulot delta — tez-tez (2 daqiqada bir). Qo'lda "Yangilash" bosish shart emas.
        if (now - p.getLong(KEY_PROD_AT, 0L) < 2 * 60 * 1000L) return
        syncProductsDelta(ctx)
    }

    // ---------- Holat ----------

    fun productsSyncedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_PROD_AT, 0L)
}
