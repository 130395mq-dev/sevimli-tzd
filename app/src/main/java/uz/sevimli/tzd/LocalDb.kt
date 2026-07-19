package uz.sevimli.tzd

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Telefondagi mahalliy baza (offline uchun). MoySklad ma'lumotining nusxasi
 * shu yerda saqlanadi, internetsiz ham ishlaydi.
 *
 * 1-bosqich: kontragentlar.
 * (2-bosqichda mahsulot + shtrix jadvallari qo'shiladi.)
 */
class LocalDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE counterparty (id INTEGER PRIMARY KEY, name TEXT)")
        db.execSQL("CREATE INDEX idx_cp_name ON counterparty(name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // Kelajakda mahsulot jadvallari shu yerda qo'shiladi. Hozircha o'zgarish yo'q.
    }

    // ---------- Kontragentlar ----------

    /** Kontragentlarni to'liq almashtiradi (serverdan yuklab olingandan keyin). */
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

    /** Nomi bo'yicha (bo'sh bo'lsa — hammasi) mahalliy qidiruv. */
    @Synchronized
    fun searchCounterparties(q: String, limit: Int = 200): JSONArray {
        val out = JSONArray()
        val cur = if (q.isBlank())
            readableDatabase.rawQuery(
                "SELECT id,name FROM counterparty ORDER BY name LIMIT ?",
                arrayOf(limit.toString()))
        else
            readableDatabase.rawQuery(
                "SELECT id,name FROM counterparty WHERE name LIKE ? ORDER BY name LIMIT ?",
                arrayOf("%$q%", limit.toString()))
        cur.use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject().put("id", c.getInt(0)).put("name", c.getString(1)))
            }
        }
        return out
    }

    companion object {
        private const val NAME = "sevimli_local.db"
        private const val VERSION = 1

        @Volatile
        private var INSTANCE: LocalDb? = null

        fun get(ctx: Context): LocalDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalDb(ctx).also { INSTANCE = it }
            }
    }
}
