package uz.sevimli.tzd

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

/**
 * FON SINXRONI.
 *
 * Ilgari xodim menyudagi "Yangilash" tugmasini bosishi kerak edi. Bosilmasa
 * katalog eskirib qolardi va skan serverga borib sekinlashardi. Bosilsa esa
 * ekran band bo'lib turardi.
 *
 * Endi hech kim hech narsa bosmaydi:
 *   - ilova OCHIQ turganda (istalgan ekranda) har 2 daqiqada;
 *   - ilova YOPIQ bo'lganda Android'ning JobScheduler'i orqali ~15 daqiqada.
 *
 * YANGI KUTUBXONA QO'SHILMADI — `JobScheduler` (API 21+) va
 * `registerActivityLifecycleCallbacks` (API 14+) Android'ning o'zida bor.
 *
 * OCHIG'I: ilova butunlay yopiq bo'lsa Android eng ko'pi bilan 15 daqiqada
 * bir marta ishlashga ruxsat beradi va uxlash rejimida uni yana
 * kechiktirishi mumkin. Bu tizim cheklovi. Terminal odatda quvvatda va
 * ilova ochiq turadi — asosiy ishni 2 daqiqalik qatlam bajaradi.
 */
object SyncEngine {

    private const val FOREGROUND_MS = 2 * 60 * 1000L
    private const val BACKGROUND_MS = 15 * 60 * 1000L
    private const val JOB_ID = 4711

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var visible = 0
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            runOnce(appCtx ?: return)
            handler.postDelayed(this, FOREGROUND_MS)
        }
    }

    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        scheduleBackgroundJob(ctx)
    }

    /** Biror ekran ko'rinib turibdi — 2 daqiqalik qatlamni yoqamiz. */
    fun onForeground(ctx: Context) {
        appCtx = ctx.applicationContext
        visible++
        if (visible == 1) {
            handler.removeCallbacks(tick)
            handler.post(tick)          // darrov bir marta, keyin har 2 daqiqada
        }
    }

    /** Hamma ekran yopildi — taymerni to'xtatamiz, batareya yeyilmasin. */
    fun onBackground() {
        visible--
        if (visible <= 0) {
            visible = 0
            handler.removeCallbacks(tick)
        }
    }

    /**
     * Bitta sinx urinishi. Qayta kirishdan himoyalangan: sinx allaqachon
     * ketayotgan bo'lsa jim qaytadi.
     */
    fun runOnce(ctx: Context) {
        if (running || CatalogSync.isSyncing) return
        running = true
        thread {
            try {
                CatalogSync.autoRefresh(ctx.applicationContext)
            } catch (_: Throwable) {
                // Internet yo'q — odatiy hol. Eski nusxa ishlayveradi.
            } finally {
                running = false
            }
        }
    }

    /** Ilova yopiq bo'lganda ishlaydigan davriy vazifa. */
    private fun scheduleBackgroundJob(ctx: Context) {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        // Allaqachon rejalashtirilgan bo'lsa qayta yozmaymiz — aks holda
        // har ishga tushishда hisob noldan boshlanardi va vazifa hech
        // qachon bajarilmasdi.
        if (js.allPendingJobs.any { it.id == JOB_ID }) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, SyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)                 // qurilma o'chib yonsa ham qoladi
            .setPeriodic(BACKGROUND_MS)
            .build()
        try {
            js.schedule(job)
        } catch (_: Throwable) {
            // Ba'zi qurilmalarda cheklov bo'lishi mumkin — sinx baribir
            // ilova ochiq turganда ishlaydi.
        }
    }
}
