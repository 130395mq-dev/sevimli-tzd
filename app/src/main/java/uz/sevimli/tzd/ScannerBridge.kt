package uz.sevimli.tzd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Terminal skaneri bilan TO'G'RIDAN-TO'G'RI aloqa.
 *
 * NEGA KERAK: hozirgacha ilova skanerdan kodni "klaviatura" ko'rinishida olardi —
 * skaner belgilarni yozadi va oxirida Enter bosadi. Bu usulning ikki kamchiligi bor:
 *   1) Skaner Enter yubormasa, ilova skan tugaganini bilmaydi;
 *   2) QR/DataMatrix ichidagi boshqaruv belgilari (GS) klaviatura orqali o'tmaydi.
 *
 * Sklad 15 kabi dasturlar shu sababdan klaviatura rejimini ishlatmaydi — ular
 * terminalning o'z skaner xizmatidan signal (broadcast) oladi. Shu sinf ham
 * xuddi shunday qiladi.
 *
 * MUAMMO: har ishlab chiqaruvchi (Urovo, Chainway, iData, Newland, Zebra,
 * Honeywell, Sunmi...) boshqa signal nomini ishlatadi. Qurilma modelini
 * oldindan bilmaganimiz uchun TAXMIN QILMAYMIZ — barcha ma'lum signallarga
 * bir vaqtda quloq solamiz. Qurilma o'zi tushunmagan signalni e'tiborsiz
 * qoldiradi, shuning uchun bu xavfsiz.
 *
 * Signal kelganda uning ichidagi maydon nomi ham turlicha bo'lishi mumkin —
 * shuning uchun BARCHA maydonlarni ko'rib chiqib, kodga o'xshaganini olamiz.
 */
object ScannerBridge {

    /** Ma'lum ishlab chiqaruvchilarning skan natijasi signallari. */
    private val RESULT_ACTIONS = listOf(
        "com.android.server.scannerservice.broadcast",   // ko'p xitoy terminallari
        "android.intent.ACTION_DECODE_DATA",             // Urovo va boshqalar
        "com.rscja.scanner.action.BARCODE",              // Chainway
        "com.rscja.scanner.broadcast",                   // Chainway (eski)
        "android.intent.action.SCANRESULT",              // iData / Speedata
        "nlscan.action.SCANNER_RESULT",                  // Newland
        "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED",   // Sunmi
        "com.symbol.datawedge.api.RESULT_ACTION",        // Zebra DataWedge
        "com.honeywell.decode.DecodeResult",             // Honeywell
        "scan.rcv.message",                              // umumiy
        "com.scanner.broadcast",                         // umumiy
        // "uz.jamlov.SCAN" OLIB TASHLANDI (xavfsizlik).
        //
        // Bu bizning O'Z signalimiz edi va uni HECH KIM yubormasdi — ya'ni
        // hech qanday foyda bermasdi. Lekin skaner signallarini tinglash
        // uchun qabul qiluvchi "eksport" qilingan bo'lishi shart, ya'ni
        // terminalga o'rnatilgan ISTALGAN dastur shu signalni yuborib,
        // ochiq turgan hujjatga o'zi xohlagan tovarni "skanerlab" qo'yishi
        // mumkin edi. Nomi bizga tegishli bo'lgani uchun uni topish ham
        // oson edi. Endi u yo'q.
    )

    /** Kod qaysi maydonda kelishi mumkin — mos kelmasa ham hammasi ko'riladi. */
    private val KNOWN_KEYS = listOf(
        "scannerdata", "barcode_string", "barcode", "value", "data",
        "SCAN_BARCODE1", "decode_data", "barocode", "scan_result",
        "com.symbol.datawedge.data_string",
    )

    @Volatile private var listener: ((String) -> Unit)? = null
    private var receiver: BroadcastReceiver? = null
    private var registeredOn: Context? = null

    /** Skan signali kelganda chaqiriladigan funksiyani o'rnatadi. */
    fun setListener(cb: ((String) -> Unit)?) { listener = cb }

    /** Signallarni tinglashni boshlaydi (ilova ochilganda bir marta). */
    fun start(ctx: Context) {
        if (receiver != null) return
        val app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val code = extractCode(intent) ?: return
                listener?.invoke(code)
            }
        }
        val filter = IntentFilter().apply { RESULT_ACTIONS.forEach { addAction(it) } }
        try {
            // Android 13+ da qabul qiluvchining "eksport" holatini aniq ko'rsatish shart
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, filter)
            }
            receiver = r
            registeredOn = app
        } catch (e: Exception) {
            // Ro'yxatdan o'tolmasa ham klaviatura rejimi ishlayveradi
        }
        enable2d(app)
    }

    fun stop() {
        val r = receiver ?: return
        try { registeredOn?.unregisterReceiver(r) } catch (e: Exception) { }
        receiver = null
        registeredOn = null
    }

    /** Signal ichidan tovar kodini topadi (maydon nomi turlicha bo'lishi mumkin). */
    private fun extractCode(intent: Intent?): String? {
        val action = intent?.action ?: return null
        // XAVFSIZLIK: faqat ro'yxatdagi ISHLAB CHIQARUVCHI signallari qabul
        // qilinadi. Bu qabul qiluvchi "eksport" bo'lishi shart (skaner
        // xizmati boshqa jarayondan yuboradi), shuning uchun kelgan
        // signalning turini qat'iy tekshiramiz.
        if (action !in RESULT_ACTIONS) return null
        val ex = intent.extras ?: return null
        // 1) Avval ma'lum maydon nomlari.
        //    DIQQAT: `sane()` null qaytarsa QIDIRUV DAVOM ETADI. Ilgari shu
        //    yerda `return sane(v)` turgan edi — bitta g'alati maydon
        //    (masalan juda uzun matn) butun skanni yo'q qilib yuborardi,
        //    holbuki keyingi maydonda haqiqiy shtrix turgan bo'lishi mumkin.
        for (k in KNOWN_KEYS) {
            sane(readString(ex.get(k)))?.let { return it }
        }
        // 2) Topilmasa — barcha maydonlarni ko'rib chiqamiz.
        //    (Bu KERAK: har terminal ishlab chiqaruvchisi maydonni o'zicha
        //     nomlaydi va hammasini oldindan bilib bo'lmaydi.)
        for (k in ex.keySet()) {
            if (k.contains("type", true) || k.contains("label", true)) continue
            val v = readString(ex.get(k))
            if (!v.isNullOrBlank() && v.length >= 4) sane(v)?.let { return it }
        }
        return null
    }

    /** Shtrix koddan kutilmagan uzun/ko'p qatorli matnni kesib tashlaydi. */
    private fun sane(v: String?): String? {
        if (v.isNullOrBlank()) return null
        val one = v.lineSequence().firstOrNull()?.trim() ?: return null
        if (one.isEmpty() || one.length > 256) return null
        return one
    }

    private fun readString(v: Any?): String? = when (v) {
        is String -> v.trim()
        is ByteArray -> String(v, Charsets.UTF_8).trim()
        is CharArray -> String(v).trim()
        else -> null
    }

    /**
     * QR va DataMatrix o'qishni DASTURIY yoqishga urinadi.
     *
     * Har ishlab chiqaruvchining o'z usuli bor va hammasida ham mavjud emas.
     * Quyidagilar hujjatlashtirilgan va xavfsiz: qurilma tushunmagan signalni
     * shunchaki e'tiborsiz qoldiradi.
     */
    private fun enable2d(ctx: Context) {
        // Zebra DataWedge — QR va DataMatrix ni yoqish
        try {
            ctx.sendBroadcast(Intent("com.symbol.datawedge.api.ACTION").apply {
                putExtra("com.symbol.datawedge.api.SET_CONFIG", android.os.Bundle().apply {
                    putString("PROFILE_NAME", "Jamlov")
                    putString("PROFILE_ENABLED", "true")
                    putString("CONFIG_MODE", "UPDATE")
                    putBundle("PLUGIN_CONFIG", android.os.Bundle().apply {
                        putString("PLUGIN_NAME", "BARCODE")
                        putString("RESET_CONFIG", "false")
                        putBundle("PARAM_LIST", android.os.Bundle().apply {
                            putString("decoder_qrcode", "true")
                            putString("decoder_datamatrix", "true")
                        })
                    })
                })
            })
        } catch (e: Exception) { }

        // Umumiy xitoy skaner xizmatlari — 2D simvologiyalarni yoqish so'rovi
        for (act in listOf("com.android.scanner.service_settings",
                           "com.scanner.action.SETTINGS")) {
            try {
                ctx.sendBroadcast(Intent(act).apply {
                    putExtra("qrcode", true)
                    putExtra("datamatrix", true)
                    putExtra("enable_2d", true)
                })
            } catch (e: Exception) { }
        }
    }

    /**
     * Qurilma va undagi skaner dasturlari haqida ma'lumot.
     * Serverga yuboriladi — shunda terminal modelini so'ramasdan bilamiz.
     */
    fun deviceInfo(ctx: Context): String {
        val sb = StringBuilder()
        sb.append(Build.MANUFACTURER).append("/").append(Build.BRAND)
            .append("/").append(Build.MODEL).append(" api").append(Build.VERSION.SDK_INT)
        val hints = listOf("scanner", "scan", "datawedge", "rscja", "urovo",
                           "idata", "newland", "honeywell", "sunmi", "barcode", "symbol")
        val found = ArrayList<String>()
        try {
            for (p in ctx.packageManager.getInstalledPackages(0)) {
                val n = p.packageName.lowercase()
                if (hints.any { n.contains(it) }) found.add(p.packageName)
                if (found.size >= 8) break
            }
        } catch (e: Exception) { }
        if (found.isNotEmpty()) sb.append(" | ").append(found.joinToString(","))
        return sb.toString().take(300)
    }
}
