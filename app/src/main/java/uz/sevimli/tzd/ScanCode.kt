package uz.sevimli.tzd

/**
 * Skanerdan kelgan matnni tovar kodiga aylantiradi.
 *
 * Skaner QR yoki DataMatrix o'qiganda ichidagi MATN keladi — bu shtrix emas.
 * Uch xil bo'lishi mumkin:
 *
 *  1) GS1 formati ("Asl Belgi" markirovkasi, blok qadoqlar):
 *       0104780016420147215Km1TjPBWMuC<GS>93EE10
 *     "01" — GTIN-14 (tovar kodi), "21" — seriya, "17" — muddat.
 *     Bizga faqat GTIN kerak.
 *
 *  2) Havola: https://shop.uz/p/4780016420147 — ba'zilarida kod bor.
 *
 *  3) Oddiy shtrix — o'zgarishsiz o'tadi.
 *
 * BLOK va DONA farqi GTIN ning o'zida: blokning o'z GTIN'i, donaning o'z
 * GTIN'i bo'ladi. Ikkalasi ham bazada (blok — pack_qty bilan), shuning uchun
 * GTIN ajratilgach qolgani avvalgidek ishlaydi.
 *
 * MUHIM: klaviatura rejimida skaner ajratgich belgisini (GS, 29) yubormasligi
 * mumkin va kod qirqilib kelishi mumkin. GTIN har doim BOSHIDA turgani uchun
 * qirqilgan kod ham to'g'ri o'qiladi — mantiq shunga moslab yozilgan.
 *
 * Serverdagi tzd/barcodes.py bilan bir xil ishlaydi.
 */
object ScanCode {

    data class Result(
        val code: String,        // qidirish uchun kod ("" — tanib bo'lmadi)
        val kind: String,        // "barcode" | "gs1" | "url" | "unknown"
        val serial: String? = null,
        val batch: String? = null,
        val expiry: String? = null,
        /** BLOK/QUTI ichidagi dona soni (GS1 "37"/"30") — bo'lsa */
        val count: Int? = null,
        /** GTIN-14 ning birinchi raqami: 1-8 bo'lsa BLOK/QUTI, aks holda null */
        val packLevel: Int? = null,
    )

    /** EAN-13 nazorat raqami (12 xonali asosdan). */
    private fun ean13Check(base12: String): String {
        var total = 0
        for ((i, c) in base12.withIndex()) total += (c - '0') * (if (i % 2 == 0) 1 else 3)
        return ((10 - total % 10) % 10).toString()
    }

    /**
     * GTIN-14 dan EAN-13 ga TO'G'RI o'tkazish.
     * Birinchi raqamni shunchaki tashlash NOTO'G'RI — nazorat raqami boshqacha.
     * Masalan 14780012960099 -> 4780012960092 (sodda usul ...099 berardi).
     */
    fun gtin14ToEan13(g: String): String? {
        if (g.length != 14 || !g.all { it.isDigit() }) return null
        val base = g.substring(1, 13)
        return base + ean13Check(base)
    }

    /**
     * Nazorat raqami to'g'rimi — EAN-8, UPC-A, EAN-13, GTIN-14 uchun.
     * Har bir GTIN ning oxirgi raqami qolganlaridan hisoblanadi, shuning uchun
     * "bu haqiqiy shtrixmi yoki tasodifiy raqammi" degan savolga javob beradi.
     */
    fun gtinCheckOk(code: String?): Boolean {
        val c = code ?: return false
        if (!c.all { it.isDigit() } || c.length !in listOf(8, 12, 13, 14)) return false
        var total = 0
        val body = c.substring(0, c.length - 1)
        for ((i, ch) in body.reversed().withIndex()) {
            total += (ch - '0') * (if (i % 2 == 0) 3 else 1)
        }
        return ((10 - total % 10) % 10).toString() == c.last().toString()
    }

    /**
     * Aralash matn ichidan HAQIQIY shtrixni topadi.
     *
     * Ba'zi korxonalar QR ichiga o'z formatini yozadi, masalan:
     *   GBS-020-4780069000192-BE644-05f09676-24dc-4765-ac16-56dbe4b5ce27
     * Bu GS1 ham emas, havola ham emas. Lekin ichida haqiqiy EAN-13
     * (4780069000192) turibdi — atrofi ichki raqamlar va UUID.
     *
     * XAVFSIZLIK: faqat nazorat raqami TO'G'RI keladigan bo'lak olinadi,
     * aks holda UUID ichidagi tasodifiy raqam noto'g'ri tovar chiqarib qo'yardi.
     * Ataylab faqat ajratgich bilan bo'lingan BUTUN bo'laklar ko'riladi.
     */
    fun embeddedBarcode(text: String?): String? {
        val s = (text ?: "").trim()
        if (s.isEmpty() || s.all { it.isDigit() }) return null
        var best: String? = null
        for (tok in s.split(Regex("\\D+"))) {
            if (gtinCheckOk(tok) && (best == null || tok.length > best!!.length)) best = tok
        }
        return best
    }

    /** Qadoqlash darajasi: 0 = dona, 1-8 = BLOK/QUTI, 9 = o'zgaruvchan og'irlik. */
    fun packLevelOf(g: String): Int? {
        if (g.length != 14 || !g.all { it.isDigit() }) return null
        val d = g[0] - '0'
        return if (d in 1..8) d else null
    }

    /** Uzunligi qat'iy belgilangan AI'lar */
    private val FIXED_AI = mapOf(
        "00" to 18, "01" to 14, "02" to 14,
        "11" to 6, "12" to 6, "13" to 6, "15" to 6, "16" to 6, "17" to 6,
        "20" to 2, "41" to 13,
    )

    private val SEPARATORS = listOf("\u001D", "\u241D", "<GS>", "{GS}")
    private val AIM_PREFIXES = listOf("]d2", "]Q3", "]C1", "]e0", "]d1", "]Q1")

    private fun clean2d(raw: String): String {
        var s = raw
        for (p in AIM_PREFIXES) if (s.startsWith(p)) { s = s.substring(p.length); break }
        for (sep in SEPARATORS) s = s.replace(sep, "\u001D")
        return s.trim().trim('\u001D')
    }

    /** GS1 kodini AI'larga ajratadi. GTIN (01) bo'lmasa null. */
    fun parseGs1(raw: String): Map<String, String>? {
        val s = clean2d(raw)
        if (s.length < 16 || !s.substring(0, 2).all { it.isDigit() }) return null

        val out = HashMap<String, String>()
        var i = 0
        val n = s.length
        while (i + 2 <= n) {
            val ai = s.substring(i, i + 2)
            if (!ai.all { it.isDigit() }) break
            i += 2
            val size = FIXED_AI[ai]
            if (size != null) {
                if (i + size > n) break
                out[ai] = s.substring(i, i + size)
                i += size
            } else {
                val j = s.indexOf('\u001D', i)
                if (j == -1) { out[ai] = s.substring(i); i = n }
                else { out[ai] = s.substring(i, j); i = j + 1 }
            }
            while (i < n && s[i] == '\u001D') i++
        }
        // "01" — tovarning o'z GTIN'i. "02" — QUTI/BLOK ichidagi tovar GTIN'i
        // (bunda "37" yoki "30" da ichidagi dona soni turadi).
        return if (out["01"] != null || out["02"] != null) out else null
    }

    /**
     * Havoladan tovar kodini ajratishga urinadi — faqat ANIQ holatlarda.
     * Tasodifiy raqamni tovar kodi deb olib, noto'g'ri mahsulot chiqarmaymiz.
     */
    private fun codeFromUrl(raw: String): String? {
        val s = raw.trim()
        val low = s.lowercase()
        for (key in listOf("barcode=", "ean=", "gtin=", "code=", "sku=")) {
            val pos = low.indexOf(key)
            if (pos != -1) {
                var v = s.substring(pos + key.length)
                for (stop in listOf("&", "#", "/")) {
                    val cut = v.indexOf(stop)
                    if (cut != -1) v = v.substring(0, cut)
                }
                v = v.trim()
                if (v.isNotEmpty() && v.all { it.isDigit() } && v.length in 8..14) return v
            }
        }
        val tail = low.split("?")[0].trimEnd('/').substringAfterLast('/')
        if (tail.isNotEmpty() && tail.all { it.isDigit() } && tail.length in 8..14) return tail
        return null
    }

    fun parse(raw: String?): Result {
        val s = (raw ?: "").trim()
        if (s.isEmpty()) return Result("", "unknown")

        parseGs1(s)?.let { ai ->
            val cnt = (ai["37"] ?: ai["30"])?.toIntOrNull()?.takeIf { it > 0 }
            return Result(
                code = ai["01"] ?: ai["02"] ?: "",
                kind = "gs1",
                serial = ai["21"],
                batch = ai["10"],
                expiry = ai["17"],
                count = cnt,
                packLevel = packLevelOf(ai["01"] ?: ai["02"] ?: ""),
            )
        }

        if (s.length >= 4 && s.substring(0, 4).lowercase() == "http") {
            return Result(codeFromUrl(s) ?: embeddedBarcode(s) ?: "", "url")
        }

        val clean = clean2d(s)
        if (clean.isNotEmpty() && clean.all { it.isDigit() }) return Result(clean, "barcode")

        // Korxonaning o'z QR formati — ichida shtrix bor
        // ("GBS-020-4780069000192-BE644-..." kabi)
        embeddedBarcode(clean)?.let {
            return Result(it, "embedded", packLevel = packLevelOf(it))
        }

        return Result(clean, "unknown")
    }
}
