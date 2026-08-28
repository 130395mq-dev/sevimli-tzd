package uz.sevimli.tzd

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * ILOVA TILI.
 *
 * Ikki til: o'zbekcha (asosiy) va ruscha. Tanlov qurilmada saqlanadi va
 * ilova har ishga tushganda qayta qo'llanadi.
 *
 * QANDAY ISHLAYDI: `AppCompatDelegate.setApplicationLocales` — bu
 * AppCompat'ning rasmiy yo'li. Android 13+ da tizimning o'zi bajaradi,
 * undan pastda (bizda minSdk 21) AppCompat orqaga moslashtirib beradi.
 * YANGI KUTUBXONA QO'SHILMADI — `androidx.appcompat` allaqachon bor va
 * 23 ta ekranning hammasi `AppCompatActivity` dan meros oladi.
 *
 * NEGA ALOHIDA PREFS: til maxfiy ma'lumot emas, shuning uchun u
 * `Config` ning shifrlangan omboriga TEGMAYDI. Shifrlash mantig'ini
 * o'zgartirmaslik — tokenlar xavfsizligiga tegmaslik demakdir.
 *
 * MoySklad'dan kelgan TOVAR NOMLARI tarjima qilinmaydi. Til faqat
 * ilovaning o'z yozuvlariga tegishli.
 */
object Lang {

    const val UZ = "uz"
    const val RU = "ru"

    private const val PREFS = "jamlov_lang"
    private const val KEY = "lang"

    /** Saqlangan til. Hech qachon tanlanmagan bo'lsa — o'zbekcha. */
    fun current(ctx: Context): String =
        try {
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, UZ) ?: UZ
        } catch (_: Throwable) {
            UZ
        }

    /**
     * Tilni o'zgartiradi va darhol qo'llaydi.
     * Ochiq ekranlar Android tomonidan o'zi qayta chiziladi.
     */
    fun set(ctx: Context, code: String) {
        val v = if (code == RU) RU else UZ
        try {
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, v).apply()
        } catch (_: Throwable) {
        }
        applyCode(v)
    }

    /** Ilova ishga tushganda chaqiriladi. */
    fun apply(ctx: Context) = applyCode(current(ctx))

    private fun applyCode(code: String) {
        // Til — ikkinchi darajali narsa. Bu yerdagi istisno ILOVANI
        // ochilmaydigan qilib qo'ymasligi kerak.
        try {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(code))
        } catch (_: Throwable) {
        }
    }

    /**
     * Tilning ekranda ko'rinadigan nomi.
     * TARJIMA QILINMAYDI: har til o'z nomida yoziladi, shunda xodim
     * o'zinikini tanishi mumkin.
     */
    fun label(code: String): String = if (code == RU) "Русский" else "O'zbekcha"
}
