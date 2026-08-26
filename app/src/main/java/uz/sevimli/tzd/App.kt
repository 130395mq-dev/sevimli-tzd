package uz.sevimli.tzd

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Ilova darajasidagi kirish nuqtasi.
 *
 * Vazifasi bitta: nechta ekran KO'RINIB turganini sanash va shuni
 * `SyncEngine` ga aytish. Shu bilan fon sinxroni ilova ochiq turganда
 * ishlaydi, yopilganda esa to'xtaydi.
 *
 * Ilgari avto-sinx faqat MENYU ekranida ishlardi — xodim Приёмка ichида
 * uzoq turса katalog eskirib qolardi.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        SyncEngine.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                SyncEngine.onForeground(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                SyncEngine.onBackground()
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
