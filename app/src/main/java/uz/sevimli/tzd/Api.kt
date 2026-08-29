package uz.sevimli.tzd

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Backend javobi: muvaffaqiyat yoki xato. */
sealed class ApiResult {
    data class Success(val json: JSONObject) : ApiResult()
    data class Error(val message: String, val offline: Boolean = false, val blocked: Boolean = false) : ApiResult()
}

object Api {

    /** Qurilma va skaner ma'lumoti — bir marta hisoblanadi (paketlar ro'yxati og'ir). */
    @Volatile private var devInfo: String? = null

    private fun deviceInfo(ctx: Context): String {
        devInfo?.let { return it }
        var v = try { ScannerBridge.deviceInfo(ctx) } catch (e: Exception) { "?" }
        // Shifrlangan saqlash ishlamay qolgan bo'lsa — buni serverga bildiramiz.
        // Aks holda bu holat jimgina davom etardi va hech kim bilmasdi.
        if (Config.plaintextFallback) v = "$v; KEYSTORE-YO'Q"
        devInfo = v
        return v
    }

    /** Obuna to'xtatilgan/muddati tugagan bo'lsa server 403 "blocked" qaytaradi.
     *  Shu global bayroq o'rnatiladi; muvaffaqiyatli javobda tozalanadi. */
    @Volatile var blocked: String? = null

    // --- KUTISH VAQTLARI ---
    //
    // ILGARI POST uchun 125 SONIYA turardi. Ya'ni "Yakunlash" bosilganda ilova
    // eng yomon holatda 8 + 125 = 133 soniya qotib turishi mumkin edi. Xodimlar
    // "ilova qotib qoldi" deganda ko'pincha aynan shu bo'lgan.
    //
    // Server tomonda MoySklad'ga yozish 45 soniyada uziladi (DOC_TIMEOUT), ya'ni
    // server har holda 45-50 soniyada javob beradi. 60 soniya — server javobini
    // kutish uchun yetarli, lekin cheksiz kutish emas.
    private const val CONNECT_MS = 8000
    private const val POST_READ_MS = 60000
    private const val GET_READ_MS = 12000

    /** GET so'rov (TZD API, token bilan). */
    fun get(ctx: Context, path: String, query: Map<String, String> = emptyMap()): ApiResult {
        // TEZLIK: shtrix skan lookup — avval mahalliy baza (bir zumda), topilmasa serverga.
        // Barcha ekranlar (Приёмка, Просмотр, Инвентаризация, Отгрузка...) shu yerdan o'tadi.
        val bc = query["barcode"]
        if (path == "product" && !bc.isNullOrBlank()) {
            try {
                val local = OfflineLookup.lookup(ctx, bc)
                if (local.optBoolean("found")) return ApiResult.Success(local)
            } catch (e: Exception) { /* mahalliy topilmadi — serverga o'tamiz */ }
        }
        val qs = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return request(ctx, "GET", "$path$qs", null, "api/tzd")
    }

    /** POST so'rov (TZD API, JSON tanasi bilan). */
    fun post(ctx: Context, path: String, body: JSONObject): ApiResult {
        return request(ctx, "POST", path, body, "api/tzd")
    }

    /** SaaS API (/api/saas/...) — kabinet login/onboarding uchun (device token shart emas). */
    fun saasPost(ctx: Context, path: String, body: JSONObject): ApiResult {
        return request(ctx, "POST", path, body, "api/saas")
    }

    /** SaaS API GET (/api/saas/...) — sessiya tokeni (login natijasi) bilan. */
    fun saasGet(ctx: Context, path: String): ApiResult {
        return request(ctx, "GET", path, null, "api/saas")
    }

    private fun request(ctx: Context, method: String, path: String,
                        body: JSONObject?, apiSeg: String): ApiResult {
        val base = Config.baseUrl(ctx)
        val token = Config.token(ctx)
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$base/$apiSeg/$path")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_MS
                readTimeout = if (body != null) POST_READ_MS else GET_READ_MS
                setRequestProperty("X-Device-Token", token)
                setRequestProperty("X-Device-Id", Config.deviceId(ctx))
                setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
                // Terminal modeli va undagi skaner dasturlari — serverda log qilinadi,
                // shunda qaysi qurilma ekanini so'ramasdan bilamiz
                setRequestProperty("X-Device-Info", deviceInfo(ctx))
                setRequestProperty("Accept", "application/json")
                // SaaS (kabinet) endpointlari menejer sessiya tokeni bilan ishlaydi
                if (apiSeg == "api/saas") {
                    val sess = Config.sessionToken(ctx)
                    if (sess.isNotEmpty()) setRequestProperty("Authorization", "Bearer $sess")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = try { JSONObject(text) } catch (e: Exception) { null }
            if (code in 200..299) {
                blocked = null   // muvaffaqiyat — obuna faol, blok yo'q
                if (json != null) ApiResult.Success(json)
                else ApiResult.Error("Server javobi noto'g'ri format ($code)")
            } else {
                val msg = json?.optString("error", "Server xatosi ($code)")
                    ?: "Server xatosi ($code)"
                if (json?.optBoolean("blocked", false) == true) {
                    blocked = msg
                    ApiResult.Error(msg, blocked = true)
                } else {
                    ApiResult.Error(msg)
                }
            }
        } catch (e: java.net.UnknownHostException) {
            ApiResult.Error(ctx.getString(R.string.no_internet), offline = true)
        } catch (e: java.net.SocketTimeoutException) {
            ApiResult.Error(ctx.getString(R.string.server_no_answer), offline = true)
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: ctx.getString(R.string.unknown_error))
        } finally {
            conn?.disconnect()
        }
    }
}
