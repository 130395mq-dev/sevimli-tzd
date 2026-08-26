package uz.sevimli.tzd

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "BUGUNGI ISHLAR" lentasi.
 *
 * BU YANGI FUNKSIYA EMAS. Hech qanday yangi API, yangi jadval yoki yangi
 * ekran yo'q. Bu mavjud `/api/tzd/documents` javobidan bugungi kun bo'yicha
 * sanab chiqarilgan ko'rsatkich, xolos.
 *
 * RAQAMLAR HAQIQIY. Server har hujjatni `tcode` (turi) va `date` (sanasi)
 * bilan qaytaradi. Biz shu ro'yxatdan bugungi sanadagilarni tur bo'yicha
 * sanaymiz. Ma'lumot kelmasa — lenta UMUMAN ko'rsatilmaydi. Hech qachon
 * o'ylab topilgan son chiqarilmaydi.
 *
 * `mine=1` — faqat SHU terminal yaratgan hujjatlar (xodim o'z ishini
 * ko'rishi kerak, bir skladdagi hamma terminalnikini emas).
 */
object TodayStrip {

    /** tcode -> lentadagi qisqa nom. Ekran kichik, uzun nom sig'maydi. */
    private val LABEL = linkedMapOf(
        "supply"    to "Qabul",
        "inventory" to "Inventar",
        "move"      to "Ko'chirish",
        "shipment"  to "Jo'natish",
        "writeoff"  to "Chiqim",
        "preturn"   to "Qaytarish",
        "sreturn"   to "Mijoz qayt.",
    )

    /** Bir "qadam" necha millisekundda bosiladi. */
    private const val STEP_MS = 2600L

    /** Siljish davomiyligi — silliq, keskin sakrashsiz. */
    private const val SLIDE_MS = 900L

    /**
     * Serverdan bugungi sonlarni oladi. FON oqimida chaqiring.
     * Qaytaradi: [(nom, son)] — faqat soni 0 dan katta turlar.
     * Xato bo'lsa yoki bugun ish bo'lmasa — bo'sh ro'yxat.
     */
    fun load(ctx: Context): List<Pair<String, Int>> {
        val r = Api.get(ctx, "documents", mapOf("mine" to "1"))
        val json: JSONObject = when (r) {
            is ApiResult.Success -> r.json
            is ApiResult.Error -> return emptyList()   // jim: internet yo'q bo'lishi odatiy
        }
        val arr = json.optJSONArray("documents") ?: return emptyList()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val counts = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            if (d.optString("date") != today) continue
            val code = d.optString("tcode")
            if (!LABEL.containsKey(code)) continue
            counts[code] = (counts[code] ?: 0) + 1
        }
        // Tartib LABEL dagidek — har ochilishda joyi o'zgarib turmasin.
        return LABEL.entries.mapNotNull { (code, label) ->
            val n = counts[code] ?: 0
            if (n > 0) label to n else null
        }
    }

    private var animator: ValueAnimator? = null

    /**
     * Lentani chizadi va o'zi siljishini boshlaydi.
     *
     * Elementlar IKKI MARTA qo'yiladi. Lenta birinchi nusxaning oxiriga
     * yetganda pozitsiya jimgina 0 ga qaytariladi — ekranda aynan o'sha
     * ko'rinish turgani uchun sakrash sezilmaydi. Shu bilan "oxirgidan
     * keyin yana birinchisiga" aylanish uzluksiz bo'ladi.
     */
    fun render(scroll: HorizontalScrollView, track: LinearLayout,
               items: List<Pair<String, Int>>) {
        stop()
        track.removeAllViews()
        if (items.isEmpty()) return

        val ctx = track.context
        val once = ArrayList<View>()
        fun addAll() {
            for ((idx, it) in items.withIndex()) {
                if (track.childCount > 0) track.addView(dot(ctx))
                val v = item(ctx, it.first, it.second)
                track.addView(v)
                if (idx < items.size) once.add(v)
            }
        }
        addAll()
        // Aylanish uchun ikkinchi nusxa — faqat bittadan ko'p element bo'lsa.
        if (items.size > 1) addAll()

        scroll.post { start(scroll, track, items.size) }
    }

    private fun start(scroll: HorizontalScrollView, track: LinearLayout, count: Int) {
        if (count <= 1) return
        // Ilova ichida animatsiya o'chirilgan bo'lsa (tizim sozlamasi) —
        // lenta qimirlamaydi, sonlar shunchaki turaveradi.
        if (animScale(track.context) == 0f) return

        // Bitta nusxaning kengligi: butun trekning yarmi (ikki marta qo'yilgan).
        val half = track.width / 2
        if (half <= 0) return

        var step = 0
        val perStep = half / count          // bitta element + ajratgich
        animator = ValueAnimator.ofInt(0, 0).apply {
            duration = SLIDE_MS
            addUpdateListener { a -> scroll.scrollX = a.animatedValue as Int }
        }
        val handler = scroll.handler ?: return
        val tick = object : Runnable {
            override fun run() {
                step++
                var to = perStep * step
                if (to >= half) {
                    // Birinchi nusxa tugadi — jimgina boshiga qaytamiz.
                    // Ekranda ayni o'sha ko'rinish turgani uchun bilinmaydi.
                    scroll.scrollX = 0
                    step = 1
                    to = perStep
                }
                animator?.cancel()
                animator = ValueAnimator.ofInt(scroll.scrollX, to).apply {
                    duration = SLIDE_MS
                    addUpdateListener { a -> scroll.scrollX = a.animatedValue as Int }
                    start()
                }
                handler.postDelayed(this, STEP_MS)
            }
        }
        ticker = tick
        tickHandler = handler
        handler.postDelayed(tick, STEP_MS)
    }

    private var ticker: Runnable? = null
    private var tickHandler: android.os.Handler? = null

    /** Ekran yopilganda to'xtatiladi — fonda batareya yeyilmasin. */
    fun stop() {
        animator?.cancel()
        animator = null
        ticker?.let { tickHandler?.removeCallbacks(it) }
        ticker = null
        tickHandler = null
    }

    private fun animScale(ctx: Context): Float =
        try {
            Settings.Global.getFloat(ctx.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (_: Throwable) { 1f }

    private fun item(ctx: Context, name: String, count: Int): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(ctx, null, 0, R.style.T_Sub).apply { text = name })
        row.addView(TextView(ctx, null, 0, R.style.T_TodayNum).apply {
            text = count.toString()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = (6 * ctx.resources.displayMetrics.density).toInt()
            layoutParams = lp
        })
        return row
    }

    private fun dot(ctx: Context): View =
        TextView(ctx, null, 0, R.style.T_Micro).apply {
            text = "·"
            val p = (10 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
        }
}
