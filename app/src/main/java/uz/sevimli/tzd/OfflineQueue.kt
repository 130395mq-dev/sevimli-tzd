package uz.sevimli.tzd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Yuborilmagan hujjatlar navbati (telefonda SharedPreferences'da saqlanadi).
 *
 * Internet uzilsa yoki telefon o'chsa — hujjat YO'QOLMAYDI. Menyu ochilganda
 * (yoki internet qaytganda) navbatdagilar avtomatik yuboriladi.
 *
 * client_uuid idempotentlik kaliti bo'lgani uchun qayta yuborish XAVFSIZ —
 * server bir hujjatni ikki marta yozmaydi.
 */
object OfflineQueue {
    private const val PREFS = "sevimli_tzd_queue"
    private const val KEY = "pending"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Yuborilmagan hujjatni navbatga qo'shadi. */
    @Synchronized
    fun enqueue(ctx: Context, path: String, typeLabel: String, body: JSONObject) {
        val arr = load(ctx)
        arr.put(JSONObject().apply {
            put("uuid", body.optString("client_uuid"))
            put("path", path)
            put("type", typeLabel)
            put("body", body.toString())
            put("error", "")
        })
        save(ctx, arr)
    }

    /** Navbatda nechta hujjat bor. */
    @Synchronized
    fun size(ctx: Context): Int = load(ctx).length()

    @Synchronized
    private fun removeByUuid(ctx: Context, uuid: String) {
        val arr = load(ctx)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("uuid") != uuid) out.put(o)
        }
        save(ctx, out)
    }

    @Synchronized
    private fun setError(ctx: Context, uuid: String, msg: String) {
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("uuid") == uuid) o.put("error", msg)
        }
        save(ctx, arr)
    }

    @Synchronized
    private fun snapshot(ctx: Context): List<JSONObject> {
        val arr = load(ctx)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /**
     * Navbatdagilarni ketma-ket yuborishga urinadi. FON oqimida chaqiring
     * (tarmoq bilan ishlaydi). Muvaffaqiyatli yuborilganlar soni qaytadi.
     */
    fun flushBlocking(ctx: Context): Int {
        var sent = 0
        for (item in snapshot(ctx)) {
            val uuid = item.optString("uuid")
            val path = item.optString("path")
            val body = try { JSONObject(item.optString("body")) } catch (e: Exception) {
                // buzilgan yozuv — o'chirib tashlaymiz
                removeByUuid(ctx, uuid); continue
            }
            when (val r = Api.post(ctx, path, body)) {
                is ApiResult.Success -> { removeByUuid(ctx, uuid); sent++ }
                is ApiResult.Error -> {
                    if (r.offline) break          // hali internet yo'q — keyinroq davom etamiz
                    else setError(ctx, uuid, r.message)  // server rad etdi — qoldiramiz (ko'rinib tursin)
                }
            }
        }
        return sent
    }

    private fun load(ctx: Context): JSONArray =
        try { JSONArray(prefs(ctx).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }

    private fun save(ctx: Context, arr: JSONArray) {
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
