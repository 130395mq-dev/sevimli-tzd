package uz.sevimli.tzd

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Ilovani "havodan" (OTA) yangilaydi: GitHub Releases dagi version.json ni tekshiradi,
 * yangi versiya bo'lsa APK ni yuklab, o'rnatishni taklif qiladi.
 * Telegram / qo'lda ko'chirish kerak emas.
 */
object Updater {

    // YANGILANISH ENDI O'Z SERVERIMIZDAN.
    //
    // SABAB: GitHub 2025-yilda release fayllarini beradigan domenni
    // almashtirdi (`release-assets.githubusercontent.com`). Yangi domen
    // O'zbekiston tarmoqlaridan ochilmayapti - terminal ham, brauzer ham
    // `TIMED_OUT` oladi. Butun park yangilanishdan uzilib qolgan edi.
    //
    // Endi terminal GitHub'ga UMUMAN bormaydi: u o'z serveriga murojaat
    // qiladi, server esa GitHub'dan o'zi olib beradi. Server manzili
    // qurilma sozlamasidan olinadi - ya'ni test/ishchi server almashsa
    // yangilanish ham o'sha bilan ketadi.
    private const val VERSION_PATH = "/api/tzd/app-version"

    // APK FAQAT shu manzildan yuklanadi.
    //
    // XAVFSIZLIK: ilgari yuklab olish manzili version.json ICHIDAN olinardi
    // va hech tekshirilmasdi. Ya'ni o'sha faylni o'zgartira olgan har kim
    // (relizga yozish huquqi, GitHub Actions'dagi uchinchi tomon amali)
    // butun parkka ISTALGAN manzildan APK yuklatib, o'rnatish oynasini
    // ochtira olardi — majburiy yangilanish oynasi esa xodimni "Ha" bosishga
    // o'rgatib qo'ygan.
    // Prefiks qat'iy YOZILMAYDI: u qurilmaning o'z server manzili bilan
    // solishtiriladi (`allowedPrefix`). Mantiq o'zgarmadi - APK faqat
    // ishonchli manzildan yuklanadi.

    private data class Info(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val url: String,
    )

    // Ruxsat kutayotgan MAJBURIY yangilanish (unknown-sources sozlamasidan qaytilganda davom etadi)
    private var pending: Info? = null

    /**
     * FON oqimida chaqiriladi (bildirishnomalar uchun): yangi versiya bormi?
     * Bo'lsa — versiya nomi, bo'lmasa yoki tarmoq yo'q bo'lsa — null.
     * Hech narsa yuklamaydi, faqat tekshiradi.
     */
    fun newerVersionOrNull(ctx: Context): String? {
        val info = try { fetchInfo(ctx) } catch (e: Exception) { return null }
        return if (info.versionCode > BuildConfig.VERSION_CODE) info.versionName else null
    }
    private var busy = false

    /**
     * MAJBURIY yangilanish. Menyu ochilganda chaqiriladi.
     * Yangi versiya bo'lsa — hodimdan so'ramaydi: darhol yuklab, o'rnatishga o'tadi.
     * Yangilamaguncha ilovadan foydalanib bo'lmaydi (oyna yopilmaydi, "Keyinroq" yo'q).
     */
    fun forceCheck(activity: Activity) {
        thread {
            val info = try { fetchInfo(activity) } catch (e: Exception) { null }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (info != null && info.versionCode > BuildConfig.VERSION_CODE) {
                    pending = info
                    forceUpdate(activity, info)
                }
            }
        }
    }

    /** Sozlamalardan (yoki o'rnatuvchidan) qaytganda majburiy yangilanishni davom ettiradi. */
    fun resumeIfPending(activity: Activity) {
        val info = pending ?: return
        if (activity.isFinishing || busy) return
        forceUpdate(activity, info)
    }

    /**
     * Qo'lda tekshirish ("holat" tugmasi uchun).
     * silent=false — natijani Toast bilan bildiradi; yangilik bo'lsa MAJBURIY yangilaydi.
     */
    fun check(activity: Activity, silent: Boolean = true) {
        if (!silent) toast(activity, activity.getString(R.string.checking_update))
        thread {
            val info = try { fetchInfo(activity) } catch (e: Exception) { null }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                when {
                    info == null ->
                        if (!silent) toast(activity, activity.getString(R.string.update_check_failed))
                    info.versionCode > BuildConfig.VERSION_CODE -> {
                        pending = info
                        forceUpdate(activity, info)
                    }
                    else ->
                        if (!silent) toast(activity, activity.getString(R.string.latest_installed))
                }
            }
        }
    }

    /**
     * Majburiy oqim: ruxsat bo'lsa — darhol yuklaydi; bo'lmasa — ruxsatni majburan so'raydi.
     * Hech qanday "Bekor"/"Keyinroq" yo'q.
     */
    private fun forceUpdate(activity: Activity, info: Info) {
        if (busy) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_required))
                .setMessage(
                    "Yangi versiya (${info.versionName}) o'rnatilishi shart — " +
                    "ilovadan foydalanishni davom ettirish uchun.\n\n" +
                    "Sozlamalar ochiladi: \"Ruxsat berish\"ni yoqing va ortga qayting. " +
                    "Yangilanish avtomatik davom etadi."
                )
                .setCancelable(false)
                .setPositiveButton(activity.getString(R.string.grant)) { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                    }
                }
                .show()
            return
        }
        // Ruxsat bor — so'ramasdan darhol yuklab, o'rnatishga o'tamiz
        startForcedDownload(activity, info)
    }

    private fun startForcedDownload(activity: Activity, info: Info) {
        busy = true
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
        }
        val label = TextView(activity).apply {
            text = activity.getString(R.string.force_update_zero_fmt, info.versionName)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(p + p, p + p, p + p, p)
            addView(label); addView(bar)
        }
        val dlg = AlertDialog.Builder(activity).setView(box).setCancelable(false).create()
        dlg.show()

        // Manzil o'zimizning reliz sahifamizdan bo'lmasa — umuman yuklamaymiz.
        if (!info.url.startsWith(allowedPrefix(activity))) {
            dlg.dismiss()
            busy = false
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_blocked))
                .setMessage(activity.getString(R.string.update_untrusted))
                .setCancelable(false)
                .setPositiveButton(activity.getString(R.string.close), null)
                .show()
            return
        }
        thread {
            val file = try {
                download(activity, info.url) { pct ->
                    activity.runOnUiThread {
                        if (activity.isFinishing) return@runOnUiThread
                        if (pct >= 0) {
                            bar.isIndeterminate = false
                            bar.progress = pct
                            label.text = activity.getString(R.string.force_update_pct_fmt, info.versionName, pct)
                        } else {
                            // Hajm noma'lum — aylanuvchi indikator
                            bar.isIndeterminate = true
                            label.text = activity.getString(R.string.force_update_fmt, info.versionName)
                        }
                    }
                }
            } catch (e: Exception) { null }

            activity.runOnUiThread {
                if (activity.isFinishing) { busy = false; return@runOnUiThread }
                dlg.dismiss()
                busy = false
                if (file == null) retryForced(activity, info)
                else install(activity, file)   // o'rnatuvchi ochiladi; pending saqlanadi
            }
        }
    }

    /** Yuklab bo'lmasa — chiqib ketishga yo'l qo'ymay, qayta urinishni majburlaydi. */
    private fun retryForced(activity: Activity, info: Info) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_failed))
            .setMessage(activity.getString(R.string.update_required_note))
            .setCancelable(false)
            .setPositiveButton(activity.getString(R.string.retry)) { _, _ -> startForcedDownload(activity, info) }
            .show()
    }

    private fun versionUrl(ctx: Context) = Config.baseUrl(ctx) + VERSION_PATH

    /** APK faqat shu manzil bilan boshlanishi shart. */
    private fun allowedPrefix(ctx: Context) = Config.baseUrl(ctx)

    private fun fetchInfo(ctx: Context): Info {
        val conn = (URL(versionUrl(ctx)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
        }
        // Ulanish har doim yopiladi — ilgari `disconnect()` chaqirilmasdi va
        // har tekshiruvda ochiq soket qolib ketardi.
        try {
            conn.inputStream.bufferedReader().use { r ->
                val j = JSONObject(r.readText())
                return Info(
                    j.optInt("versionCode"),
                    j.optString("versionName"),
                    j.optString("notes"),
                    j.optString("url"),
                )
            }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun download(activity: Activity, url: String, onProgress: (Int) -> Unit): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "sevimli-tzd.apk")
        if (out.exists()) out.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        // GitHub APK'ni "chunked" holda yuborsa contentLength = -1 bo'ladi.
        // ILGARI shunda foiz umuman ko'rsatilmasdi va MAJBURIY yangilanish
        // oynasida "Yuklanmoqda... 0%" butun yuklash davomida turib qolardi.
        // DIQQAT: contentLengthLong faqat Android 7.0 (API 24) dan bor,
        // ilovaning eng past versiyasi esa API 21. Eski terminalda u
        // NoSuchMethodError bilan yiqilardi — va bu MAJBURIY yangilanish
        // yo'li bo'lgani uchun ilova umuman ishlamay qolardi.
        val total: Long =
            if (android.os.Build.VERSION.SDK_INT >= 24) conn.contentLengthLong
            else conn.contentLength.toLong()
        try {
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    var lastMb = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        } else {
                            // Hajm noma'lum — har megabaytda belgi beramiz,
                            // shunda oyna "qotib qolgan"dek ko'rinmaydi.
                            val mb = (done / (1024 * 1024)).toInt()
                            if (mb != lastMb) {
                                lastMb = mb
                                onProgress(-1)
                            }
                        }
                    }
                }
            }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
        return out
    }

    /**
     * Yuklab olingan APK HAQIQATAN ham shu ilovaning yangi versiyasimi?
     *
     * Ikkita narsa tekshiriladi:
     *   1) paket nomi bir xilmi (aks holda u ALOHIDA ilova bo'lib o'rnatiladi —
     *      Android'ning "bir xil imzo" qoidasi bunga to'sqinlik qilmaydi);
     *   2) imzolovchi sertifikat aynan bizniki-mi.
     *
     * Ikkalasi ham mos kelmasa — o'rnatish OCHILMAYDI.
     */
    private fun verifyApk(activity: Activity, file: File): Boolean {
        return try {
            val pm = activity.packageManager
            @Suppress("DEPRECATION")
            val flag = if (Build.VERSION.SDK_INT >= 28)
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else
                android.content.pm.PackageManager.GET_SIGNATURES
            val info = pm.getPackageArchiveInfo(file.absolutePath, flag) ?: return false
            if (info.packageName != activity.packageName) return false

            @Suppress("DEPRECATION")
            val newSigs: Array<android.content.pm.Signature>? =
                if (Build.VERSION.SDK_INT >= 28)
                    (info.signingInfo?.apkContentsSigners ?: info.signatures)
                else info.signatures
            @Suppress("DEPRECATION")
            val cur = pm.getPackageInfo(activity.packageName, flag)
            @Suppress("DEPRECATION")
            val curSigs: Array<android.content.pm.Signature>? =
                if (Build.VERSION.SDK_INT >= 28)
                    (cur.signingInfo?.apkContentsSigners ?: cur.signatures)
                else cur.signatures
            if (newSigs.isNullOrEmpty() || curSigs.isNullOrEmpty()) return false
            val a = newSigs.map { it.toCharsString() }.toHashSet()
            val b = curSigs.map { it.toCharsString() }.toHashSet()
            a == b
        } catch (e: Throwable) {
            false
        }
    }

    private fun install(activity: Activity, file: File) {
        if (!verifyApk(activity, file)) {
            try { file.delete() } catch (_: Exception) {}
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_blocked))
                .setMessage(
                    "Yuklab olingan fayl imzosi mos kelmadi — o'rnatish to'xtatildi.\n" +
                    "Administratorga xabar bering."
                )
                .setCancelable(false)
                .setPositiveButton(activity.getString(R.string.close), null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            toast(activity, activity.getString(R.string.install_open_failed_fmt, e.message))
        }
    }

    private fun toast(activity: Activity, msg: String) =
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
