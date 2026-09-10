package uz.sevimli.tzd

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

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
 *  4) KAMERA. Sarlavhadagi tugma. Lazer o'qiy olmagan holatlar uchun:
 *     shtrix yirtilgan, mayda, qadoq egilgan. Lazer ASOSIY bo'lib
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
     * Kamera o'qiydigan formatlar — ATAYIN cheklangan.
     *
     * Ombor shtrixlari (EAN/UPC/Code128/ITF/Codabar) va 2D kodlar
     * (QR, DataMatrix). Hamma formatni yoqish dekodlashni sekinlashtiradi,
     * TSD kamerasi esa kuchli emas.
     */
    private val CAM_FORMATS = listOf(
        BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
        BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
        BarcodeFormat.ITF, BarcodeFormat.CODABAR,
        BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX,
    )

    /**
     * BURILGAN SHTRIXNI HAM O'QISH.
     *
     * ZXing 1D shtrixni suratning gorizontal chiziqlari bo'ylab izlaydi. TSD
     * vertikal ushlanganda shtrix suratda 90° burilgan bo'lib qoladi va
     * topilmaydi — xodim qurilmani gorizontal burishga majbur bo'lardi.
     *
     * `TRY_HARDER` yoqilsa ZXing topolmagan suratni burib QAYTA uradi.
     */
    private val CAM_HINTS: Map<DecodeHintType, Any> =
        mapOf(DecodeHintType.TRY_HARDER to true)

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
        //
        // Kamera EKRANNING O'ZIDA ochiladi, alohida oyna emas — buning sababi
        // pastdagi CameraScan izohida.
        //
        // Takror-himoya AYNAN o'sha `dedup` orqali o'tadi: kamera va lazer
        // bir kodni ketma-ket bersa ham u bir marta ishlanadi.
        val camera = bindCamera(act) { code -> dedup.accept(code, onScan) }

        // --- 3-kanal: qurilma skaner signali ---
        ScannerBridge.start(act)

        act.lifecycle.addObserver(LifecycleEventObserver { _: LifecycleOwner, e: Lifecycle.Event ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    ScannerBridge.setListener { code ->
                        act.runOnUiThread { dedup.accept(code, onScan) }
                    }
                    camera?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    ScannerBridge.setListener(null)
                    camera?.onPause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    handler.removeCallbacks(quiet)
                    camera?.close()
                }
                else -> {}
            }
        })
    }

    // ------------------------------------------------------------------
    //  KAMERA
    // ------------------------------------------------------------------

    /**
     * Kamera — EKRANNING USTIDA ochiladigan oyna.
     *
     * NEGA ALOHIDA EKRAN EMAS. Birinchi variantda kutubxonaning tayyor
     * `CaptureActivity` si ishlatilgan edi, ya'ni kamera ALOHIDA EKRAN
     * ochardi. Natijada: shtrix o'qilardi, ekran qaytardi, tovar chiqardi va
     * DARHOL yo'qolib ketardi. Lazerda bunday bo'lmasdi.
     *
     * Farqning yagona manbai shu edi — lazer ekranni tark etmaydi, kamera esa
     * tark etardi. Ekran almashinuvi vaqtida ochilgan miqdor oynasi va
     * ekranning holati saqlanib qolmasdi.
     *
     * Endi kamera hech qayerga chiqmaydi: `BarcodeView` shu ekranning ustiga
     * qo'yiladi, kod o'qilgach oyna olib tashlanadi va kod AYNAN lazer kabi —
     * o'sha ekran ochiq turgan holatda — uzatiladi.
     */
    private class CameraScan(
        private val act: AppCompatActivity,
        private val onCode: (String) -> Unit,
    ) {
        private var overlay: ViewGroup? = null
        private var view: BarcodeView? = null

        /** Kamera ochiq turganda "orqaga" ilovadan chiqarmasin — oynani yopsin. */
        private val backCb = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = close()
        }

        init {
            try { act.onBackPressedDispatcher.addCallback(act, backCb) }
            catch (e: Throwable) { }
        }

        fun open() {
            if (overlay != null) return
            val root = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            try {
                val d = act.resources.displayMetrics.density
                val bv = BarcodeView(act)
                bv.setDecoderFactory(DefaultDecoderFactory(CAM_FORMATS, CAM_HINTS, null, 0))

                val box = FrameLayout(act)
                box.setBackgroundColor(Color.BLACK)
                // Ostidagi ekranga tasodifan bosilib ketmasin.
                box.isClickable = true
                box.addView(bv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))

                val prompt = TextView(act)
                prompt.text = act.getString(R.string.camera_prompt)
                prompt.setTextColor(Color.WHITE)
                prompt.textSize = 15f
                prompt.gravity = Gravity.CENTER
                prompt.setPadding((16 * d).toInt(), (12 * d).toInt(),
                                  (16 * d).toInt(), (24 * d).toInt())
                box.addView(prompt, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM))

                val btnClose = ImageView(act)
                btnClose.setImageResource(R.drawable.ic_close)
                btnClose.setColorFilter(Color.WHITE)
                btnClose.setPadding((14 * d).toInt(), (14 * d).toInt(),
                                    (14 * d).toInt(), (14 * d).toInt())
                btnClose.contentDescription = act.getString(R.string.camera_close)
                btnClose.setOnClickListener { close() }
                box.addView(btnClose, FrameLayout.LayoutParams(
                    (52 * d).toInt(), (52 * d).toInt(),
                    Gravity.TOP or Gravity.END))

                root.addView(box, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))

                overlay = box
                view = bv
                backCb.isEnabled = true

                bv.decodeSingle(object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult) {
                        val code = (result.text ?: "").trim()
                        // AVVAL oynani yopamiz, KEYIN kodni beramiz — miqdor
                        // oynasi ochilganda kamera ustida qolib ketmasin.
                        close()
                        if (code.isNotEmpty()) onCode(code)
                    }
                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {}
                })
                bv.resume()
            } catch (e: Throwable) {
                close()
                Toast.makeText(act, act.getString(R.string.camera_failed),
                    Toast.LENGTH_SHORT).show()
            }
        }

        fun close() {
            val v = view
            val o = overlay
            view = null
            overlay = null
            backCb.isEnabled = false
            try { v?.pause() } catch (e: Throwable) { }
            try { (o?.parent as? ViewGroup)?.removeView(o) } catch (e: Throwable) { }
        }

        /** Ekran fonga ketdi — kamerani qo'yib yuboramiz (boshqa dastur olsin). */
        fun onPause() {
            try { view?.pause() } catch (e: Throwable) { }
        }

        /** Ekran qaytdi — kamera ochiq turgan bo'lsa davom ettiramiz. */
        fun onResume() {
            if (overlay == null) return
            try { view?.resume() } catch (e: Throwable) { }
        }
    }

    /**
     * Kamera kanalini ulaydi va sarlavhaga tugma qo'shadi.
     *
     * XATOGA CHIDAMLI: kamerasiz qurilmada tugma UMUMAN qo'shilmaydi (o'lik
     * tugma bosib turishdan yomoni yo'q). Ruxsat berilmasa ham ekran oddiy
     * ishlashda davom etadi — lazerga tegilmagan.
     */
    private fun bindCamera(act: AppCompatActivity, onCode: (String) -> Unit): CameraScan? {
        if (!act.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return null
        val cam = CameraScan(act, onCode)

        // `registerForActivityResult` ekran START bo'lishidan OLDIN
        // chaqirilishi shart. `bind` onCreate ichidan chaqiriladi, ya'ni
        // shart bajarilgan. Baribir himoyalab qo'yamiz.
        val ask = try {
            act.registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) cam.open()
                else Toast.makeText(act, act.getString(R.string.camera_denied),
                    Toast.LENGTH_LONG).show()
            }
        } catch (e: Throwable) {
            null
        }

        addCameraButton(act) {
            val granted = ContextCompat.checkSelfPermission(
                act, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            when {
                granted -> cam.open()
                ask != null -> ask.launch(Manifest.permission.CAMERA)
                else -> Toast.makeText(act, act.getString(R.string.camera_failed),
                    Toast.LENGTH_SHORT).show()
            }
        }
        return cam
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
