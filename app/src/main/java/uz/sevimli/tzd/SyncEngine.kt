package uz.sevimli.tzd

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * FON SINXRONIZATSIYASI — xodim hech narsa bosmaydi.
 *
 * ILGARIGI MUAMMO: sinx faqat MENYU ekrani ochiq turganda ishlardi.
 * `MenuActivity.onPause()` taymerni o'chirardi. Ya'ni xodim Приёмка yoki
 * Инвентаризация ichida ishlayotganda, boshqa ilovaga o'tganda yoki ekran
 * o'chganda hech narsa yangilanmasdi. Shuning uchun xodim har safar
 * "Yangilash" tugmasini bosishga majbur edi.
 *
 * ENDI ikki qatlam bor:
 *
 *   1. ILOVA OCHIQ (istalgan ekran) — har 2 daqiqada jimgina sinx.
 *      `App` sinfidagi activity hisoblagichi ilova ko'rinib turibdimi yo'qmi
 *      shuni biladi; ekran qaysi ekan — farqi yo'q.
 *
 *   2. ILOVA YOPIQ — Android JobScheduler har ~15 daqiqada uyg'otadi.
 *      DIQQAT: 15 daqiqa — Android'ning O'ZI qo'ygan eng kichik oraliq
 *      (API 24+). Uxlash rejimida (Doze) tizim uni yana kechiktirishi
 *      mumkin. Ya'ni bu KAFOLAT emas, imkoni boricha degani. Terminal
 *      odatda quvvatda va ilova ochiq turadi — 1-qatlam asosiy ishni qiladi.
 *
 * Yangi kutubxona QO'SHILMADI: `registerActivityLifecycleCallbacks` va
 * `JobScheduler` — ikkalasi ham Android'ning o'zida bor (API 21+).
 */
object SyncEngine {

    /** Ilova ochiq turganda sinx oralig'i. */
    private const val FOREGROUND_MS = 2 * 60 * 1000L

    /** JobScheduler ishi identifikatori. */
    private const val JOB_ID = 4711

    /** Ilova yopiqdagi oraliq. Android buni 15 daqiqadan pastga tushirmaydi. */
    private const val BACKGROUND_MS = 15 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())

    /** Bir vaqtda bitta fon oqimi — takror ishga tushirmaymiz. */
    @Volatile private var running = false

    private val tick = object : Runnable {
        override fun run() {
            runOnce(appCtx)
            handler.postDelayed(this, FOREGROUND_MS)
        }
    }

    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        scheduleBackgroundJob(appCtx)
    }

    /** Ilova ko'rinadigan holatga o'tdi — darhol bir marta, keyin davriy. */
    fun onForeground() {
        if (!::appCtx.isInitialized) return
        handler.removeCallbacks(tick)
        runOnce(appCtx)
        handler.postDelayed(tick, FOREGROUND_MS)
    }

    /** Ilova ko'rinmay qoldi — taymerni to'xtatamiz (JobScheduler davom etadi). */
    fun onBackground() {
        handler.removeCallbacks(tick)
    }

    /**
     * Bitta sinx urinishi. Fon oqimida, jimgina.
     *
     * Xato bo'lsa hech narsa ko'rsatilmaydi — internet yo'qligi odatiy hol,
     * xodimni bezovta qilmaymiz. Eski nusxa mahalliy bazada qolaveradi.
     */
    fun runOnce(ctx: Context, onDone: (() -> Unit)? = null) {
        if (running) { onDone?.invoke(); return }
        running = true
        val app = ctx.applicationContext
        thread {
            try {
                CatalogSync.autoRefresh(app)
            } catch (_: Throwable) {
                // Jim. Keyingi urinishda qayta uriniladi.
            } finally {
                running = false
                onDone?.invoke()
            }
        }
    }

    /** Ilova yopiq bo'lganda ham ishlashi uchun davriy ish rejalashtiramiz. */
    private fun scheduleBackgroundJob(ctx: Context) {
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            // Allaqachon rejalashtirilgan bo'lsa qayta yaratmaymiz — aks holda
            // har ishga tushishda hisoblagich noldan boshlanardi.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (js.getPendingJob(JOB_ID) != null) return
            } else if (js.allPendingJobs.any { it.id == JOB_ID }) {
                return
            }
            val b = JobInfo.Builder(JOB_ID, ComponentName(ctx, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)              // qurilma o'chib yonsa ham qoladi
                .setPeriodic(BACKGROUND_MS)
            js.schedule(b.build())
        } catch (_: Throwable) {
            // JobScheduler bo'lmasa yoki tizim rad etsa — ilova ochiqdagi
            // qatlam baribir ishlaydi. Bu sabab bilan ilova yiqilmasin.
        }
    }
}
