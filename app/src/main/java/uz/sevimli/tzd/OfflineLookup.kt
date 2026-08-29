package uz.sevimli.tzd

import android.content.Context
import org.json.JSONObject

/**
 * Internetsiz mahsulot qidirish — backenddagi product_lookup mantig'ini
 * mahalliy baza (LocalDb) ustida takrorlaydi:
 *  1) to'liq shtrix mosligi (upakovka/blok pack_qty bilan)
 *  2) tarozi shtrixi (29-prefiks) — ichki kod (PLU) bo'yicha
 *  3) kod/artikul bo'yicha
 * Javob product_lookup bilan bir xil shaklda (askQuantity o'zgarmasdan ishlaydi).
 */
object OfflineLookup {

    fun lookup(ctx: Context, raw: String): JSONObject {
        val db = LocalDb.get(ctx)

        // QR / DataMatrix bo'lsa — ichidan tovar kodini (GTIN) ajratamiz.
        // Oddiy shtrix bo'lsa hech narsa o'zgarmaydi.
        val scan = ScanCode.parse(raw)
        val code = if (scan.code.isNotEmpty()) scan.code else raw
        if (scan.kind == "url" && scan.code.isEmpty()) {
            return JSONObject().put("found", false).put("barcode", raw)
                .put("offline", true).put("scan_kind", "url")
                .put("hint", ctx.getString(R.string.qr_is_link))
        }

        val (scalePlu, scaleValue) = parseScaleBarcode(code)

        var product: JSONObject? = null
        var packQty: Double? = null
        var usedScale = false

        // 1) To'liq shtrix mosligi — shtrixning boshqa yozilishlari bilan birga.
        //    Skaner bitta tovarni EAN-13, GTIN-14 (oldida qo'shimcha raqam) yoki
        //    nol'lar bilan yuborishi mumkin. Ilgari aynan moslik topilmay,
        //    har skan serverga borardi va 1–2 soniya kutilardi.
        for (v in barcodeVariants(code)) {
            val byBc = db.productByBarcode(v) ?: continue
            product = byBc
            if (byBc.has("pack_qty")) packQty = byBc.optDouble("pack_qty")
            break
        }

        // 2) Tarozi shtrixi (29-prefiks) — PLU bo'yicha
        if (product == null && scalePlu != null && scalePlu.any { it != '0' }) {
            val variants = mutableListOf(scalePlu)
            val stripped = scalePlu.trimStart('0')
            if (stripped.isNotEmpty() && stripped != scalePlu) variants.add(stripped)
            for (v in variants) {
                product = db.productByCodeOrArticle(v)
                if (product != null) { usedScale = true; break }
            }
        }

        // 3) Tarozi emas — kod/artikul bo'yicha
        if (product == null && scalePlu == null) {
            product = db.productByCodeOrArticle(code)
        }

        if (product == null) {
            return JSONObject().put("found", false).put("barcode", code)
                .put("offline", true).put("scan_kind", scan.kind)
        }

        // BLOK/QUTI QR kodida ichidagi dona soni yozilgan bo'lishi mumkin.
        // Bazada upakovka shtrixi bo'lmasa ham, shu son bo'yicha hisoblaymiz.
        if (packQty == null && scan.count != null) packQty = scan.count.toDouble()

        // QR dagi GTIN-14 birinchi raqami 1-8 bo'lsa — bu BLOK kodi, dona emas.
        // Ichida nechta ekani noma'lum bo'lsa, xodim o'zi kiritadi.
        val packUnknown = scan.packLevel != null && (packQty == null || packQty <= 0)

        val price = product.optLong("price", 0)
        val resp = JSONObject()
            .put("ok", true)
            .put("found", true)
            .put("offline", true)
            .put("barcode", code)
            .put("scan_kind", scan.kind)
            .put("moysklad_id", product.optString("moysklad_id"))
            .put("name", product.optString("name"))
            .put("price", price)
            .put("buy_price", product.optLong("buy_price", 0))
            .put("code", product.optString("code"))
            .put("article", product.optString("article"))
            .put("uom", product.optString("uom"))
            .put("store_qty", product.optDouble("store_qty", 0.0))
            .put("pack_qty", packQty ?: JSONObject.NULL)
            .put("is_pack", (packQty != null && packQty > 0) || packUnknown)
            .put("pack_unknown", packUnknown)

        if (usedScale) {
            val weight = Math.round(scaleValue / 1000.0 * 1000.0) / 1000.0  // gramm -> kg
            resp.put("scale", true)
            resp.put("scale_weight", weight)
            resp.put("scale_price", Math.round(weight * price).toInt())
        }
        return resp
    }

    /**
     * Bitta shtrixning mumkin bo'lgan yozilishlari (server tomondagi
     * tzd/barcodes.py bilan bir xil mantiq).
     */
    fun barcodeVariants(code: String): List<String> {
        val c = code.trim()
        val out = ArrayList<String>()
        fun add(v: String) { if (v.isNotEmpty() && !out.contains(v)) out.add(v) }
        add(c)
        if (!c.all { it.isDigit() }) return out
        val s = c.trimStart('0')
        if (c.length == 14) {
            add(c.substring(1))                          // sodda (qoidaga xilof, uchraydi)
            ScanCode.gtin14ToEan13(c)?.let { add(it) }   // to'g'ri hisoblangan
        }
        if (c.length == 13 && c.startsWith("0")) add(c.substring(1))  // -> UPC-A
        if (c.length == 12) add("0$c")                    // UPC-A -> EAN-13
        add(s)
        if (s.isNotEmpty() && s.length < 13) add(s.padStart(13, '0'))
        return out.take(8)
    }

    /**
     * Tarozi shtrixi: 13 xonali, "29" bilan boshlanadi.
     * 29 + kod(5) + og'irlik gramm(5) + nazorat(1). Qaytaradi: (plu, gramm).
     */
    private fun parseScaleBarcode(code: String): Pair<String?, Int> {
        if (code.length == 13 && code.all { it.isDigit() } && code.startsWith("29")) {
            val plu = code.substring(2, 7)
            val value = code.substring(7, 12).toIntOrNull() ?: 0
            return Pair(plu, value)
        }
        return Pair(null, 0)
    }
}
