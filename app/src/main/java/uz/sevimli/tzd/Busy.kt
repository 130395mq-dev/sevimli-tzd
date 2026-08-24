package uz.sevimli.tzd

import android.view.View

/**
 * "So'rov ketyapti" qulfi.
 *
 * MUAMMO: hujjat yuborilayotganda "Yakunlash" tugmasi faol qolardi va yuklanish
 * belgisi ekranni to'smasdi. Server sekin javob berayotganini ko'rgan xodim
 * tugmani yana bosardi — natijada bir nechta bir xil so'rov ketardi.
 * MoySklad'da takror hujjat paydo bo'lish xavfi ham shundan edi.
 *
 * ISHLATILISHI:
 *     if (!busy.start(b.btnFinish)) return      // allaqachon ketyapti
 *     ...
 *     runOnUiThread { busy.stop(b.btnFinish); ... }
 */
class Busy {

    @Volatile
    private var running = false

    /** Qulfni oladi. False qaytsa — so'rov allaqachon ketyapti, hech narsa qilmang. */
    @Synchronized
    fun start(vararg views: View?): Boolean {
        if (running) return false
        running = true
        for (v in views) {
            v?.isEnabled = false
            v?.alpha = 0.5f
        }
        return true
    }

    /** Qulfni bo'shatadi va tugmalarni qaytaradi. */
    @Synchronized
    fun stop(vararg views: View?) {
        running = false
        for (v in views) {
            v?.isEnabled = true
            v?.alpha = 1f
        }
    }

    val isRunning: Boolean get() = running
}
