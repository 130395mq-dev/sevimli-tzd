package uz.sevimli.tzd

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Skan qabul qilishning ISHONCHLI usuli — barcha skan ekranlari shu orqali ishlaydi.
 *
 * TO'RT kanal bir vaqtda ochiladi:
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
 *  4) KAMERA. Sarlavhaga qo'shiladigan tugma. Lazer o'qiy olmagan holatlar
 *     uchun: shtrix yirtilgan, mayda, qadoq egilgan. Lazer ASOSIY bo'lib
 *     qolaveradi — kamera hech narsani almashtirmaydi, faqat qo'shiladi.
 *
 * To'rtala kanal bir kodni yetkazsa ham, u BIR MARTA ishlanadi (takror-himoya).
 */
object ScanInput {

    /** Skaner yozishni to'xtatgandan keyin shuncha kutamiz (millisekund). */
    private const val QUIET_MS = 150L

    /** Shu muddat ichida kelgan bir xil kod — takror deb hisoblanadi. */
    private const val DEDUP_MS = 400L

    /**
     * Takror-himoya holati HAR EKRAN uchun alohida.
     *
     * ILGARI: holat butun ilova uchun umumiy edi. Приёмкада bir tovarni
     * skanerlab, chiqib, Просмотр'da o'sha tovarni darrov skanerlasa —
     * ikkinchi skan "takror" deb jimgina tashlab yuborilardi.
     */
    private class Dedup {
        private var lastCode = ""
        private var lastAt = 0L

        @Synchronized
        fun accept(code: String, onScan: (String) -> Unit) {
            val c = code.trim()
            if (c.isEmpty()) return
            val now = System.currentTimeMillis()
            if (c == lastCode && now - lastAt < DEDUP_MS) return
            lastCode = c
            lastAt = now
            onScan(c)
        }
    }

    /**
     * Skan ekranini ulaydi. Ekranning o'z Enter tekshiruvi o'rniga shu ishlatiladi.
     *
     * @param input yashirin kiritish maydoni (scanInput)
     * @param onScan kod tayyor bo'lganda chaqiriladi
     */
    fun bind(act: AppCompatActivity, input: EditText, onScan: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val dedup = Dedup()
        input.showSoftInputOnFocus = false

        // --- 1-kanal: Enter ---
        input.setOnEditorActionListener { _, _, event ->
            val isEnterUp = event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action != KeyEvent.ACTION_DOWN
            if (isEnterUp) return@setOnEditorActionListener true   // takror hodisa
            val code = input.text.toString().trim()
            input.setText("")
            if (code.isNotEmpty()) dedup.accept(code, onScan)
            true
        }

        // --- 2-kanal: Enter'siz (jimlik bo'yicha) ---
        val quiet = Runnable {
            val code = input.text.toString().trim()
            if (code.length >= 4) {
                input.setText("")
                dedup.accept(code, onScan)
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

        // --- 4-kanal: kamera ---
        // Takror-himoya AYNAN o'sha `dedup` orqali o'tadi: kamera va lazer
        // bir kodni ketma-ket bersa ham u bir marta ishlanadi.
        bindCamera(act) { code -> dedup.accept(code, onScan) }

        // --- 3-kanal: qurilma skaner signali ---
        ScannerBridge.start(act)

        act.lifecycle.addObserver(LifecycleEventObserver { _: LifecycleOwner, e: Lifecycle.Event ->
            when (e) {
                Lifecycle.Event.ON_RESUME ->
                    ScannerBridge.setListener { code ->
                        act.runOnUiThread { dedup.accept(code, onScan) }
                    }
                Lifecycle.Event.ON_PAUSE -> ScannerBridge.setListener(null)
                Lifecycle.Event.ON_DESTROY -> handler.removeCallbacks(quiet)
                else -> {}
            }
        })
    }

    // ------------------------------------------------------------------
    //  KAMERA
    // ------------------------------------------------------------------

    /**
     * Kamera kanalini ulaydi va sarlavhaga tugma qo'shadi.
     *
     * XATOGA CHIDAMLI: kamerasiz qurilmada tugma UMUMAN qo'shilmaydi
     * (o'lik tugma bosib turishdan yomoni yo'q), ro'yxatga olish
     * bajarilmasa ham ekran oddiy ishlashda davom etadi — lazer tegilmagan.
     */
    private fun bindCamera(act: AppCompatActivity, onCode: (String) -> Unit) {
        val pm = act.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return

        // `registerForActivityResult` ekran START bo'lishidan OLDIN
        // chaqirilishi shart. `bind` onCreate ichidan chaqiriladi, ya'ni
        // shart bajarilgan. Baribir himoyalab qo'yamiz.
        val launcher = try {
            act.registerForActivityResult(ScanContract()) { res ->
                val code = (res.contents ?: "").trim()
                if (code.isNotEmpty()) onCode(code)
            }
        } catch (e: Throwable) {
            return
        }

        addCameraButton(act) {
            try {
                launcher.launch(scanOptions(act))
            } catch (e: Throwable) {
                Toast.makeText(act, act.getString(R.string.camera_failed),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Kamera oynasi sozlamalari.
     *
     * Formatlar ATAYIN cheklangan: ombor shtrixlari (EAN/UPC/Code128/ITF) va
     * 2D kodlar (QR, DataMatrix). Hamma formatni yoqish dekodlashni
     * sekinlashtiradi — TSD kamerasi kuchli emas.
     *
     * Ovoz O'CHIQ: ilovaning o'z signali bor (ScanFeedback), ikkita "bip"
     * bir-birining ustiga tushmasin.
     */
    private fun scanOptions(act: AppCompatActivity): ScanOptions =
        ScanOptions().apply {
            setDesiredBarcodeFormats(
                "EAN_13", "EAN_8", "UPC_A", "UPC_E",
                "CODE_128", "CODE_39", "ITF", "CODABAR",
                "QR_CODE", "DATA_MATRIX")
            setPrompt(act.getString(R.string.camera_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
            setBarcodeImageEnabled(false)
        }

    /**
     * Sarlavhaga kamera tugmasini qo'shadi.
     *
     * Hamma skan ekranining sarlavhasi bir xil tuzilishda:
     *     [←]  [sarlavha matni (weight=1)]  [ixtiyoriy tugma: ✓ yoki +]
     *
     * Kamera matndan KEYIN qo'yiladi — o'ngdagi mavjud tugma o'z joyida
     * qoladi va xodimning odati buzilmaydi.
     */
    private fun addCameraButton(act: AppCompatActivity, onClick: () -> Unit) {
        val back = act.findViewById<View>(R.id.btnBack) ?: return
        val header = back.parent as? LinearLayout ?: return
        if (header.findViewById<View>(R.id.btnCamera) != null) return   // takror

        val d = act.resources.displayMetrics.density
        val size = (48 * d).toInt()
        val pad = (12 * d).toInt()

        val iv = ImageView(act)
        iv.id = R.id.btnCamera
        iv.setImageResource(R.drawable.ic_camera)
        iv.setPadding(pad, pad, pad, pad)
        // ContextCompat — `getColor(Int)` API 23 dan, bizniki minSdk 21.
        iv.setColorFilter(ContextCompat.getColor(act, R.color.white))
        iv.contentDescription = act.getString(R.string.camera_scan)
        iv.isClickable = true
        iv.isFocusable = true
        val tv = TypedValue()
        if (act.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            iv.setBackgroundResource(tv.resourceId)
        }
        iv.setOnClickListener { onClick() }

        var idx = header.childCount
        for (i in 0 until header.childCount) {
            val lp = header.getChildAt(i).layoutParams as? LinearLayout.LayoutParams
            if ((lp?.weight ?: 0f) > 0f) {
                idx = i + 1
                break
            }
        }
        header.addView(iv, idx, LinearLayout.LayoutParams(size, size))
    }
}
