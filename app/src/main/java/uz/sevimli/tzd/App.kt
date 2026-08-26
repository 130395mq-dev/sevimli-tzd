package uz.sevimli.tzd

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Ilova darajasidagi sinf. Bitta vazifasi bor: ilova KO'RINIB turibdimi
 * yo'qmi shuni bilish va fon sinxronizatsiyasini boshqarish.
 *
 * NEGA ACTIVITY HISOBLAGICHI: ilgari sinx taymeri `MenuActivity` ichida
 * turardi va menyu ekrandan ketishi bilan o'chardi. Endi hisoblagich
 * ISTALGAN ekran ochiq turganini biladi — xodim Приёмка ichida ishlayotgan
 * bo'lsa ham sinx davom etadi.
 *
 * `registerActivityLifecycleCallbacks` — Android'ning o'zida bor (API 14+),
 * hech qanday yangi kutubxona kerak emas.
 */
class App : Application() {

    /** Hozir nechta ekran ko'rinib turibdi. 0 dan katta bo'lsa — ilova oldinda. */
    private var visible = 0

    override fun onCreate() {
        super.onCreate()
        SyncEngine.init(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                // 0 dan 1 ga o'tish = ilova endigina oldinga chiqdi.
                // Har ekran almashganda qayta ishga tushmasin deb aynan
                // shu o'tish tekshiriladi.
                if (visible == 0) SyncEngine.onForeground()
                visible++
            }

            override fun onActivityStopped(activity: Activity) {
                visible--
                if (visible <= 0) {
                    visible = 0
                    SyncEngine.onBackground()
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
