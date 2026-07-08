package uz.sevimli.tzd

import android.app.Activity
import android.app.AlertDialog
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

    // Eng yangi versiya ma'lumoti (har build'da GitHub Release'ga qo'yiladi)
    private const val VERSION_URL =
        "https://github.com/130395mq-dev/sevimli-tzd/releases/latest/download/version.json"

    private data class Info(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val url: String,
    )

    /**
     * Menyu ochilganda chaqiriladi.
     * silent=true — jimgina tekshiradi, faqat yangilik bo'lsagina oyna chiqadi.
     * silent=false — natijani har doim (Toast bilan) bildiradi ("tekshirish" tugmasi uchun).
     */
    fun check(activity: Activity, silent: Boolean = true) {
        if (!silent) toast(activity, "Yangilanish tekshirilmoqda...")
        thread {
            val info = try { fetchInfo() } catch (e: Exception) { null }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                when {
                    info == null ->
                        if (!silent) toast(activity, "Yangilanishni tekshirib bo'lmadi")
                    info.versionCode > BuildConfig.VERSION_CODE ->
                        promptUpdate(activity, info)
                    else ->
                        if (!silent) toast(activity, "Eng so'nggi versiya o'rnatilgan")
                }
            }
        }
    }

    private fun fetchInfo(): Info {
        val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
        }
        conn.inputStream.bufferedReader().use { r ->
            val j = JSONObject(r.readText())
            return Info(
                j.optInt("versionCode"),
                j.optString("versionName"),
                j.optString("notes"),
                j.optString("url"),
            )
        }
    }

    private fun promptUpdate(activity: Activity, info: Info) {
        val msg = buildString {
            append("Yangi versiya: ${info.versionName}")
            if (info.notes.isNotBlank()) append("\n\n${info.notes}")
        }
        AlertDialog.Builder(activity)
            .setTitle("Yangilanish mavjud")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Yangilash") { _, _ -> startDownload(activity, info) }
            .setNegativeButton("Keyinroq", null)
            .show()
    }

    private fun startDownload(activity: Activity, info: Info) {
        if (!ensureCanInstall(activity)) return

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val label = TextView(activity).apply { text = "Yuklanmoqda... 0%" }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(p + p, p + p, p + p, p)
            addView(label)
            addView(bar)
        }
        val dlg = AlertDialog.Builder(activity).setView(box).setCancelable(false).create()
        dlg.show()

        thread {
            val file = try {
                download(activity, info.url) { pct ->
                    activity.runOnUiThread {
                        bar.progress = pct
                        label.text = "Yuklanmoqda... $pct%"
                    }
                }
            } catch (e: Exception) { null }

            activity.runOnUiThread {
                dlg.dismiss()
                if (file == null) toast(activity, "Yuklab bo'lmadi. Internetni tekshiring.")
                else install(activity, file)
            }
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
        val total = conn.contentLength
        conn.inputStream.use { input ->
            out.outputStream.use { output ->
                val buf = ByteArray(16 * 1024)
                var read: Int
                var done = 0L
                var lastPct = -1
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    done += read
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        return out
    }

    /** Android 8+ da "noma'lum manbalar"ga ruxsatni tekshiradi. */
    private fun ensureCanInstall(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle("Ruxsat kerak")
                .setMessage(
                    "Yangilanishni o'rnatish uchun bu ilovaga ruxsat bering.\n" +
                    "Sozlamalar ochiladi — \"Ruxsat berish\"ni yoqib, qaytib keling va yana \"Yangilash\"ni bosing."
                )
                .setPositiveButton("Sozlamani ochish") { _, _ ->
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
                .setNegativeButton("Bekor", null)
                .show()
            return false
        }
        return true
    }

    private fun install(activity: Activity, file: File) {
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
            toast(activity, "O'rnatishni ochib bo'lmadi: ${e.message}")
        }
    }

    private fun toast(activity: Activity, msg: String) =
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
