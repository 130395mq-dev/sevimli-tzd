package uz.sevimli.tzd

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Backend javobi: muvaffaqiyat yoki xato. */
sealed class ApiResult {
    data class Success(val json: JSONObject) : ApiResult()
    data class Error(val message: String, val offline: Boolean = false) : ApiResult()
}

object Api {

    /** GET so'rov (TZD API, token bilan). */
    fun get(ctx: Context, path: String, query: Map<String, String> = emptyMap()): ApiResult {
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
                connectTimeout = 8000
                readTimeout = if (body != null) 125000 else 12000
                setRequestProperty("X-Device-Token", token)
                setRequestProperty("X-Device-Id", Config.deviceId(ctx))
                setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
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
                if (json != null) ApiResult.Success(json)
                else ApiResult.Error("Server javobi noto'g'ri format ($code)")
            } else {
                val msg = json?.optString("error", "Server xatosi ($code)")
                    ?: "Server xatosi ($code)"
                ApiResult.Error(msg)
            }
        } catch (e: java.net.UnknownHostException) {
            ApiResult.Error("Internet yo'q", offline = true)
        } catch (e: java.net.SocketTimeoutException) {
            ApiResult.Error("Server javob bermadi", offline = true)
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Noma'lum xato")
        } finally {
            conn?.disconnect()
        }
    }
}
