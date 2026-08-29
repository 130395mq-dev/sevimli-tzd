package uz.sevimli.tzd

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Telefondagi mahalliy baza (offline uchun): kontragent + mahsulot + shtrix.
 * Mahsulotda sotuv narxi (price) va KIRIM narxi (buy_price) ham saqlanadi.
 */
class LocalDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, NAME, null, VERSION) {

    /**
     * Baza tutqichi BIR MARTA olinadi (`warm()` da, obyekt boshqa oqimlarga
     * ko'rinishidan OLDIN).
     *
     * MUAMMO 1: `readableDatabase` / `writableDatabase` xossalari
     * SQLiteOpenHelper ning O'ZIDA `synchronized(this)` — ya'ni har o'qishda
     * ham o'sha umumiy qulf olinardi. WAL yoqilgan bo'lsa ham, skan uchun
     * o'qish so'rovi fondagi yozuv tranzaksiyasini kutib turardi.
     *
     * MUAMMO 2 (agar `by lazy` ishlatilsa): yozish metodlari `@Synchronized`
     * bo'lgani uchun avval `this` qulfini, so'ng lazy qulfini olardi; o'qish
     * metodlari esa teskari tartibda. Ikki oqim bir vaqtda birinchi marta
     * bazaga tegsa — o'zaro kutib qotib qolish (deadlock) ehtimoli bor edi.
     *
     * YECHIM: tutqich `get()` ichida, obyekt e'lon qilinishidan oldin
     * ochiladi. Shu payt boshqa oqim bu obyektni ko'rmaydi, ya'ni hech
     * qanday qulf to'qnashuvi bo'lmaydi.
     */
    private lateinit var db: SQLiteDatabase

    /** Bazani ochadi. FAQAT get() dan, obyekt e'lon qilinishidan oldin chaqiriladi. */
    private fun warm() {
        db = writableDatabase
    }

    init {
        // WAL (Write-Ahead Logging) — O'QISH va YOZISH bir vaqtda ishlaydi.
        //
        // MUAMMO: fonda katalog sinxroni 500 tadan mahsulot yozayotganda
        // skan uchun kerak bo'lgan o'qish so'rovi navbatda kutib turardi.
        // Sinx har 2 daqiqada ishlagani uchun xodim vaqti-vaqti bilan
        // "skan kechikdi" holatiga tushardi. WAL bilan o'qish yozishni
        // kutmaydi.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE counterparty (id INTEGER PRIMARY KEY, name TEXT)")
        db.execSQL("CREATE INDEX idx_cp_name ON counterparty(name)")
        createProductTables(db)
        createInventoryTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            createProductTables(db)
        } else if (oldV < 3) {
            // v2 -> v3: kirim narxi ustuni qo'shiladi
            try { db.execSQL("ALTER TABLE product ADD COLUMN buy_price INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        // v3 -> v4: inventarizatsiya hujjati keshi
        if (oldV < 4) createInventoryTables(db)
    }

    /**
     * INVENTARIZATSIYA KESHI.
     *
     * 7000-10 000 qatorli hujjat serverdan bir necha o'n soniyada keladi.
     * Xodim ekrandan chiqib qaytsa yoki ilova yopilsa - hammasi qaytadan
     * yuklanardi. Endi hujjat qurilmada saqlanadi va qayta ochilganda
     * BIR ZUMDA chiqadi.
     *
     * ESKIRISH XAVFI hisobga olingan: `inv_head` da hujjatning MoySklad'dagi
     * `updated` vaqti va qator soni saqlanadi. Ochilishda bitta yengil
     * so'rov yuboriladi; ular mos kelmasa kesh tashlanadi va hujjat
     * qaytadan yuklanadi. Ya'ni menejer hujjatga qator qo'shsa yoki
     * boshqa terminal sanoq yozsa - terminal buni sezadi.
     */
    private fun createInventoryTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS inv_head (" +
                "inv_id TEXT PRIMARY KEY, total INTEGER, updated TEXT, " +
                "store TEXT, saved_at INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS inv_pos (" +
                "inv_id TEXT, ord INTEGER, mid TEXT, ptype TEXT, name TEXT, " +
                "expected REAL, counted REAL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_invpos ON inv_pos(inv_id, ord)")
    }

    private fun createProductTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS product (" +
                "moysklad_id TEXT PRIMARY KEY, name TEXT, code TEXT, article TEXT, " +
                "price INTEGER, buy_price INTEGER, uom TEXT, store_qty REAL, ms_updated TEXT)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS barcode (" +
                "barcode TEXT PRIMARY KEY, product_id TEXT, pack_qty REAL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prod_name ON product(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prod_code ON product(code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prod_art ON product(article)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bc_prod ON barcode(product_id)")
        // Delta sinx har safar MAX(ms_updated) so'raydi. Indekssiz bu butun
        // jadvalni o'qish edi — 20 000 tovarda sezilarli, va u har 2 daqiqada
        // bazani band qilib turardi.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prod_upd ON product(ms_updated)")
    }

    // ---------------- Inventarizatsiya keshi ----------------

    /** Kesh mos kelsa qatorlarni qaytaradi, aks holda null. */
    @Synchronized
    fun invCacheGet(invId: String, total: Int, updated: String): JSONArray? {
        val c = db.rawQuery(
            "SELECT total, updated FROM inv_head WHERE inv_id=?", arrayOf(invId))
        var ok = false
        c.use { if (it.moveToFirst()) ok = it.getInt(0) == total && it.getString(1) == updated }
        if (!ok) return null

        val out = JSONArray()
        db.rawQuery("SELECT mid, ptype, name, expected, counted FROM inv_pos " +
                    "WHERE inv_id=? ORDER BY ord", arrayOf(invId)).use { r ->
            while (r.moveToNext()) {
                out.put(JSONObject().apply {
                    put("product_moysklad_id", r.getString(0))
                    put("product_type", r.getString(1))
                    put("name", r.getString(2))
                    put("expected_qty", r.getDouble(3))
                    put("counted_qty", r.getDouble(4))
                })
            }
        }
        return if (out.length() == total) out else null
    }

    /**
     * Hujjatni keshga yozadi. FAQAT to'liq yuklab bo'lingach chaqiriladi -
     * yarim ro'yxat keshga tushsa, keyingi ochilishda u to'liq deb
     * qabul qilinardi.
     */
    @Synchronized
    fun invCachePut(invId: String, total: Int, updated: String, store: String,
                    rows: List<Array<Any>>) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM inv_pos WHERE inv_id=?", arrayOf(invId))
            db.execSQL("DELETE FROM inv_head WHERE inv_id=?", arrayOf(invId))
            val st = db.compileStatement(
                "INSERT INTO inv_pos(inv_id, ord, mid, ptype, name, expected, counted) " +
                "VALUES(?,?,?,?,?,?,?)")
            for ((i, r) in rows.withIndex()) {
                st.clearBindings()
                st.bindString(1, invId); st.bindLong(2, i.toLong())
                st.bindString(3, r[0] as String); st.bindString(4, r[1] as String)
                st.bindString(5, r[2] as String)
                st.bindDouble(6, r[3] as Double); st.bindDouble(7, r[4] as Double)
                st.executeInsert()
            }
            db.execSQL("INSERT INTO inv_head(inv_id, total, updated, store, saved_at) " +
                       "VALUES(?,?,?,?,?)",
                arrayOf(invId, total, updated, store, System.currentTimeMillis()))
            // Faqat oxirgi 3 ta hujjat saqlanadi - xotira cheksiz o'smasin.
            db.execSQL("DELETE FROM inv_pos WHERE inv_id NOT IN " +
                       "(SELECT inv_id FROM inv_head ORDER BY saved_at DESC LIMIT 3)")
            db.execSQL("DELETE FROM inv_head WHERE inv_id NOT IN " +
                       "(SELECT inv_id FROM (SELECT inv_id FROM inv_head " +
                       "ORDER BY saved_at DESC LIMIT 3))")
            db.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            try { db.endTransaction() } catch (_: Exception) {}
        }
    }

    /** Saqlangach chaqiriladi: hujjat serverda o'zgardi, kesh yaroqsiz. */
    @Synchronized
    fun invCacheClear(invId: String) {
        try {
            db.execSQL("DELETE FROM inv_pos WHERE inv_id=?", arrayOf(invId))
            db.execSQL("DELETE FROM inv_head WHERE inv_id=?", arrayOf(invId))
        } catch (_: Exception) {}
    }

    // ---------------- Kontragentlar ----------------

    @Synchronized
    fun replaceCounterparties(arr: JSONArray) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM counterparty")
            val stmt = db.compileStatement("INSERT INTO counterparty(id,name) VALUES(?,?)")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                stmt.clearBindings()
                stmt.bindLong(1, o.optInt("id").toLong())
                stmt.bindString(2, o.optString("name"))
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // O'qish — qulfsiz (WAL tufayli yozish bilan bir vaqtda ishlaydi)
    fun counterpartyCount(): Int {
        db.rawQuery("SELECT COUNT(*) FROM counterparty", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // O'qish — qulfsiz (WAL tufayli yozish bilan bir vaqtda ishlaydi)
    fun searchCounterparties(q: String, limit: Int = 200): JSONArray {
        val out = JSONArray()
        val cur = if (q.isBlank())
            db.rawQuery(
                "SELECT id,name FROM counterparty ORDER BY name LIMIT ?", arrayOf(limit.toString()))
        else
            db.rawQuery(
                "SELECT id,name FROM counterparty WHERE name LIKE ? ORDER BY name LIMIT ?",
                arrayOf("%$q%", limit.toString()))
        cur.use { c ->
            while (c.moveToNext())
                out.put(JSONObject().put("id", c.getInt(0)).put("name", c.getString(1)))
        }
        return out
    }

    // ---------------- Mahsulotlar ----------------

    @Synchronized
    fun clearProducts() {
        db.execSQL("DELETE FROM product")
        db.execSQL("DELETE FROM barcode")
    }

    /** MoySklad'da o'chgan tovarlarni (server bergan id ro'yxati) bazadan olib tashlaydi. */
    @Synchronized
    fun deleteProducts(ids: JSONArray) {
        db.beginTransaction()
        try {
            val delP = db.compileStatement("DELETE FROM product WHERE moysklad_id=?")
            val delB = db.compileStatement("DELETE FROM barcode WHERE product_id=?")
            for (i in 0 until ids.length()) {
                val mid = ids.optString(i)
                if (mid.isBlank()) continue
                delP.clearBindings(); delP.bindString(1, mid); delP.executeUpdateDelete()
                delB.clearBindings(); delB.bindString(1, mid); delB.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun upsertProducts(arr: JSONArray) {
        db.beginTransaction()
        try {
            val pStmt = db.compileStatement(
                "INSERT OR REPLACE INTO product" +
                    "(moysklad_id,name,code,article,price,buy_price,uom,store_qty,ms_updated) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)")
            val delBc = db.compileStatement("DELETE FROM barcode WHERE product_id=?")
            val insBc = db.compileStatement(
                "INSERT OR REPLACE INTO barcode(barcode,product_id,pack_qty) VALUES(?,?,?)")
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val mid = p.optString("moysklad_id")
                if (mid.isBlank()) continue
                pStmt.clearBindings()
                pStmt.bindString(1, mid)
                pStmt.bindString(2, p.optString("name"))
                pStmt.bindString(3, p.optString("code"))
                pStmt.bindString(4, p.optString("article"))
                pStmt.bindLong(5, p.optLong("price", 0))
                pStmt.bindLong(6, p.optLong("buy_price", 0))
                pStmt.bindString(7, p.optString("uom"))
                pStmt.bindDouble(8, p.optDouble("store_qty", 0.0))
                pStmt.bindString(9, p.optString("ms_updated"))
                pStmt.executeInsert()

                delBc.clearBindings(); delBc.bindString(1, mid); delBc.executeUpdateDelete()
                val bcs = p.optJSONArray("barcodes")
                if (bcs != null) {
                    for (j in 0 until bcs.length()) {
                        val bc = bcs.getJSONObject(j)
                        val code = bc.optString("barcode")
                        if (code.isBlank()) continue
                        insBc.clearBindings()
                        insBc.bindString(1, code)
                        insBc.bindString(2, mid)
                        if (bc.isNull("pack_qty")) insBc.bindNull(3)
                        else insBc.bindDouble(3, bc.optDouble("pack_qty", 0.0))
                        insBc.executeInsert()
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateStock(stock: JSONObject) {
        db.beginTransaction()
        try {
            db.execSQL("UPDATE product SET store_qty=0")
            val st = db.compileStatement("UPDATE product SET store_qty=? WHERE moysklad_id=?")
            val keys = stock.keys()
            while (keys.hasNext()) {
                val mid = keys.next()
                st.clearBindings()
                st.bindDouble(1, stock.optDouble(mid, 0.0))
                st.bindString(2, mid)
                st.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // O'qish — qulfsiz (WAL tufayli yozish bilan bir vaqtda ishlaydi)
    fun productCount(): Int {
        db.rawQuery("SELECT COUNT(*) FROM product", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // O'qish — qulfsiz (WAL tufayli yozish bilan bir vaqtda ishlaydi)
    fun maxMsUpdated(): String {
        db.rawQuery(
            "SELECT MAX(ms_updated) FROM product WHERE ms_updated IS NOT NULL AND ms_updated<>''",
            null).use { c ->
            return if (c.moveToFirst() && c.getString(0) != null) c.getString(0) else ""
        }
    }

    private fun rowToProduct(
        mid: String, name: String, code: String, article: String,
        price: Long, buyPrice: Long, uom: String, qty: Double
    ): JSONObject = JSONObject()
        .put("found", true)
        .put("moysklad_id", mid)
        .put("name", name)
        .put("code", code)
        .put("article", article)
        .put("price", price)
        .put("buy_price", buyPrice)
        .put("uom", uom)
        .put("store_qty", qty)

    // O'qish — qulfsiz (WAL tufayli yozish bilan bir vaqtda ishlaydi)
    fun productByBarcode(bc: String): JSONObject? {
        db.rawQuery(
            "SELECT p.moysklad_id,p.name,p.code,p.article,p.price,p.buy_price,p.uom,p.store_qty,b.pack_qty " +
                "FROM barcode b JOIN product p ON p.moysklad_id=b.product_id WHERE b.barcode=? LIMIT 1",
            arrayOf(bc)).use { c ->
            if (!c.moveToFirst()) return null
            val obj = rowToProduct(
                c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "",
                c.getLong(4), c.getLong(5), c.getString(6) ?: "", c.getDouble(7))
            if (!c.isNull(8)) obj.put("pack_qty", c.getDouble(8))
            return obj
        }
    }

    // O'qish — qulfsiz (WAL tufayli yozish bilan bir vaqtda ishlaydi)
    fun productByCodeOrArticle(code: String): JSONObject? {
        db.rawQuery(
            "SELECT moysklad_id,name,code,article,price,buy_price,uom,store_qty FROM product " +
                "WHERE code=? OR article=? LIMIT 1", arrayOf(code, code)).use { c ->
            if (!c.moveToFirst()) return null
            return rowToProduct(
                c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "",
                c.getLong(4), c.getLong(5), c.getString(6) ?: "", c.getDouble(7))
        }
    }

    // O'qish — qulfsiz (WAL tufayli yozish bilan bir vaqtda ishlaydi)
    fun searchProductsResult(q: String, limit: Int = 30): JSONObject {
        // Kod/artikul — PREFIKS (boshlanishi) bo'yicha, nom/shtrix — "ichiga oladi".
        // Tarozi PLU kodlari nol'siz saqlansa ham topilsin (masalan "00123" -> "123").
        val qz = q.trimStart('0')
        val hasZ = qz.isNotEmpty() && qz != q
        // Shtrix — faqat BOSHLANISHI bo'yicha ("%...%" emas: qisqa raqam ko'p
        // shtrix o'rtasida uchrab, aloqasiz tovarlar chiqadi).
        val where = StringBuilder(
            "name LIKE ? OR code LIKE ? OR article LIKE ? " +
                "OR moysklad_id IN (SELECT product_id FROM barcode WHERE barcode LIKE ?)")
        val args = arrayListOf("%$q%", "$q%", "$q%", "$q%")
        if (hasZ) {
            // Nol'siz variant FAQAT kod/artikul bo'yicha — shtrix ichidan emas
            // (aks holda "352" kabi qisqa raqam ko'p shtrix o'rtasida uchrab,
            // aloqasiz tovarlar chiqadi).
            where.append(" OR code LIKE ? OR article LIKE ? OR code = ?")
            args.add("$qz%"); args.add("$qz%"); args.add(qz)
        }
        args.add("80")  // muvofiqlik saralashdan oldin biroz ko'proq nomzod olamiz

        val rows = ArrayList<JSONObject>()
        db.rawQuery(
            "SELECT moysklad_id,name,code,article,price,buy_price,uom,store_qty FROM product " +
                "WHERE $where LIMIT ?",
            args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                rows.add(JSONObject()
                    .put("moysklad_id", c.getString(0))
                    .put("name", c.getString(1))
                    .put("code", c.getString(2) ?: "")
                    .put("article", c.getString(3) ?: "")
                    .put("price", c.getLong(4))
                    .put("buy_price", c.getLong(5))
                    .put("uom", c.getString(6) ?: "")
                    .put("store_qty", c.getDouble(7))
                    .put("barcode", ""))
            }
        }

        // AYNAN shtrix terilgan bo'lsa — o'sha shtrixni (upakovka bo'lsa,
        // ichidagi miqdori bilan) qatorga biriktiramiz.
        var exactBcOwner: String? = null
        db.rawQuery(
            "SELECT product_id,pack_qty FROM barcode WHERE barcode=? LIMIT 1",
            arrayOf(q)).use { c ->
            if (c.moveToFirst()) {
                exactBcOwner = c.getString(0)
                val pq = if (c.isNull(1)) 0.0 else c.getDouble(1)
                rows.forEach { p ->
                    if (p.optString("moysklad_id") == exactBcOwner) {
                        p.put("barcode", q)
                        if (pq > 0) { p.put("pack_qty", pq); p.put("is_pack", true) }
                    }
                }
            }
        }

        // ANIQ moslik: kiritilgan matn kod/artikulga (yoki shtrixga) AYNAN teng
        // bo'lsa — faqat o'sha tovar(lar) qoladi, kod davomlari chiqmaydi.
        val ql = q.lowercase(); val qzl = qz.lowercase()
        var exact = rows.filter { p ->
            val code = p.optString("code").lowercase()
            val art = p.optString("article").lowercase()
            (code.isNotEmpty() && (code == ql || code == qzl)) || (art.isNotEmpty() && art == ql)
        }
        if (exact.isEmpty() && exactBcOwner != null) {
            exact = rows.filter { it.optString("moysklad_id") == exactBcOwner }
        }
        val pool = if (exact.isNotEmpty()) exact else rows

        // Muvofiqlik: aniq kod -> prefiks kod -> artikul boshi -> nom boshi -> qolgani.
        fun score(p: JSONObject): Int {
            val code = p.optString("code").lowercase()
            val art = p.optString("article").lowercase()
            val nm = p.optString("name").lowercase()
            if (code == ql || (qzl.isNotEmpty() && code == qzl)) return 0
            if (code.startsWith(ql) || (qzl.isNotEmpty() && code.startsWith(qzl))) return 1
            if (art.startsWith(ql)) return 2
            if (nm.startsWith(ql)) return 3
            return 4
        }
        val arr = JSONArray()
        pool.sortedWith(compareBy({ score(it) }, { it.optString("name").lowercase() }))
            .take(limit).forEach { arr.put(it) }
        return JSONObject().put("ok", true).put("products", arr)
    }

    companion object {
        private const val NAME = "sevimli_local.db"
        private const val VERSION = 4

        @Volatile
        private var INSTANCE: LocalDb? = null

        fun get(ctx: Context): LocalDb =
            INSTANCE ?: synchronized(this) {
                // warm() E'LON QILISHDAN OLDIN — shu payt obyektni boshqa
                // oqim ko'rmaydi, ya'ni ochilish paytida qulf to'qnashuvi
                // bo'lishi mumkin emas.
                INSTANCE ?: LocalDb(ctx).also { it.warm(); INSTANCE = it }
            }
    }
}
