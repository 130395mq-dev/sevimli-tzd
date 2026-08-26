package uz.sevimli.tzd

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Ilova YOPIQ bo'lganda ishlaydigan sinx.
 *
 * Android tizimi bizni ~15 daqiqada bir marta uyg'otadi (aniq vaqtni tizim
 * o'zi tanlaydi). Ishimiz bitta: katalogni jimgina yangilash.
 *
 * `jobFinished(params, false)` — ish tugadi, qayta urinish kerak emas.
 * Sinx muvaffaqiyatsiz bo'lsa ham `false` beramiz: davriy ish baribir
 * keyingi safar o'zi keladi, qayta urinishni tizimga yuklab, batareyani
 * behuda sarflashning hojati yo'q.
 */
class SyncJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        SyncEngine.runOnce(applicationContext) {
            jobFinished(params, false)
        }
        return true      // ish fon oqimida davom etyapti
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // Tizim to'xtatdi (masalan tarmoq yo'qoldi). Keyingi davriy
        // chaqiruvni kutamiz — darhol qayta urinmaymiz.
        return false
    }
}
