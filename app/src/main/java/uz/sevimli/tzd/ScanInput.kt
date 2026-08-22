package uz.sevimli.tzd

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Skan qabul qilishning ISHONCHLI usuli — barcha skan ekranlari shu orqali ishlaydi.
 *
 * UCH kanal bir vaqtda ochiladi:
 *
 *  1) ENTER (avvalgidek). Skaner belgilarni yozib, oxirida Enter bosadi.
 *
 *  2) ENTER'SIZ. Skaner Enter yubormasa, matn kelib to'xtaganini (150 ms jimlik)
 *     sezib, skan tugadi deb hisoblaymiz. Ko'p terminallarda 1D shtrixga Enter
 *     sozlangan, 2D (QR) ga esa yo'q — muammo aynan shunda bo'lishi mumkin.
 *
 *  3) QURILMA SIGNALI (ScannerBridge). Terminalning skaner xizmati kodni
 *     to'g'ridan-to'g'ri beradi — klaviatura ham, Enter ham kerak emas.
 *     Sklad 15 shu usulda ishlaydi.
 *
 * Uchala kanal bir kodni yetkazsa ham, u BIR MARTA ishlanadi (takror-himoya).
 */
object ScanInput {

    /** Skaner yozishni to'xtatgandan keyin shuncha kutamiz (millisekund). */
    private const val QUIET_MS = 150L

    /** Shu muddat ichida kelgan bir xil kod — takror deb hisoblanadi. */
    private const val DEDUP_MS = 400L

    @Volatile private var lastCode = ""
    @Volatile private var lastAt = 0L

    /** Kodni bir marta o'tkazadi — qaysi kanaldan kelganidan qat'i nazar. */
    @Synchronized
    private fun accept(code: String, onScan: (String) -> Unit) {
        val c = code.trim()
        if (c.isEmpty()) return
        val now = System.currentTimeMillis()
        if (c == lastCode && now - lastAt < DEDUP_MS) return
        lastCode = c
        lastAt = now
        onScan(c)
    }

    /**
     * Skan ekranini ulaydi. Ekranning o'z Enter tekshiruvi o'rniga shu ishlatiladi.
     *
     * @param input yashirin kiritish maydoni (scanInput)
     * @param onScan kod tayyor bo'lganda chaqiriladi
     */
    fun bind(act: AppCompatActivity, input: EditText, onScan: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        input.showSoftInputOnFocus = false

        // --- 1-kanal: Enter ---
        input.setOnEditorActionListener { _, _, event ->
            val isEnterUp = event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action != KeyEvent.ACTION_DOWN
            if (isEnterUp) return@setOnEditorActionListener true   // takror hodisa
            val code = input.text.toString().trim()
            input.setText("")
            if (code.isNotEmpty()) accept(code, onScan)
            true
        }

        // --- 2-kanal: Enter'siz (jimlik bo'yicha) ---
        val quiet = Runnable {
            val code = input.text.toString().trim()
            if (code.length >= 4) {
                input.setText("")
                accept(code, onScan)
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(quiet)
                if (!s.isNullOrEmpty()) handler.postDelayed(quiet, QUIET_MS)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // --- 3-kanal: qurilma skaner signali ---
        ScannerBridge.start(act)

        act.lifecycle.addObserver(LifecycleEventObserver { _: LifecycleOwner, e: Lifecycle.Event ->
            when (e) {
                Lifecycle.Event.ON_RESUME ->
                    ScannerBridge.setListener { code ->
                        act.runOnUiThread { accept(code, onScan) }
                    }
                Lifecycle.Event.ON_PAUSE -> ScannerBridge.setListener(null)
                Lifecycle.Event.ON_DESTROY -> handler.removeCallbacks(quiet)
                else -> {}
            }
        })
    }
}
