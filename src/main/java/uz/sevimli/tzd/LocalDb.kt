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

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE counterparty (id INTEGER PRIMARY KEY, name TEXT)")
        db.execSQL("CREATE INDEX idx_cp_name ON counterparty(name)")
        createProductTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            createProductTables(db)
        } else if (oldV < 3) {
            // v2 -> v3: kirim narxi ustuni qo'shiladi
            try { db.execSQL("ALTER TABLE product ADD COLUMN buy_price INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
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
    }

    // ---------------- Kontragentlar ----------------

    @Synchronized
    fun replaceCounterparties(arr: JSONArray) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM counterparty")
            val stmt = db.compileStatement("INSERT INTO counterparty(id,name) VALUES(?,?)")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
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

    @Synchronized
    fun counterpartyCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM counterparty", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    @Synchronized
    fun searchCounterparties(q: String, limit: Int = 200): JSONArray {
        val out = JSONArray()
        val cur = if (q.isBlank())
            readableDatabase.rawQuery(
                "SELECT id,name FROM counterparty ORDER BY name LIMIT ?", arrayOf(limit.toString()))
        else
            readableDatabase.rawQuery(
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
        writableDatabase.execSQL("DELETE FROM product")
        writableDatabase.execSQL("DELETE FROM barcode")
    }

    @Synchronized
    fun upsertProducts(arr: JSONArray) {
        val db = writableDatabase
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
                val p = arr.getJSONObject(i)
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
        val db = writableDatabase
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

    @Synchronized
    fun productCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM product", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    @Synchronized
    fun maxMsUpdated(): String {
        readableDatabase.rawQuery(
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

    @Synchronized
    fun productByBarcode(bc: String): JSONObject? {
        readableDatabase.rawQuery(
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

    @Synchronized
    fun productByCodeOrArticle(code: String): JSONObject? {
        readableDatabase.rawQuery(
            "SELECT moysklad_id,name,code,article,price,buy_price,uom,store_qty FROM product " +
                "WHERE code=? OR article=? LIMIT 1", arrayOf(code, code)).use { c ->
            if (!c.moveToFirst()) return null
            return rowToProduct(
                c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "",
                c.getLong(4), c.getLong(5), c.getString(6) ?: "", c.getDouble(7))
        }
    }

    @Synchronized
    fun searchProductsResult(q: String, limit: Int = 30): JSONObject {
        val arr = JSONArray()
        readableDatabase.rawQuery(
            "SELECT moysklad_id,name,code,article,price,buy_price,uom,store_qty FROM product " +
                "WHERE name LIKE ? OR code LIKE ? OR article LIKE ? ORDER BY name LIMIT ?",
            arrayOf("%$q%", "%$q%", "%$q%", limit.toString())).use { c ->
            while (c.moveToNext()) {
                arr.put(JSONObject()
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
        return JSONObject().put("ok", true).put("products", arr)
    }

    companion object {
        private const val NAME = "sevimli_local.db"
        private const val VERSION = 3

        @Volatile
        private var INSTANCE: LocalDb? = null

        fun get(ctx: Context): LocalDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalDb(ctx).also { INSTANCE = it }
            }
    }
}
