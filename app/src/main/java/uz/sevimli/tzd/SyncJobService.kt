package uz.sevimli.tzd

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Ilova YOPIQ bo'lganda ishlaydigan fon vazifasi (SyncEngine rejalashtiradi).
 * Ish o'zi boshqa oqimда ketadi, shuning uchun darhol `jobFinished` deymiz —
 * tizim vazifani "osilib qolgan" deb hisoblamasin.
 */
class SyncJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        SyncEngine.runOnce(applicationContext)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
