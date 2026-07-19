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

    fun lookup(ctx: Context, code: String): JSONObject {
        val db = LocalDb.get(ctx)
        val (scalePlu, scaleValue) = parseScaleBarcode(code)

        var product: JSONObject? = null
        var packQty: Double? = null
        var usedScale = false

        // 1) To'liq shtrix mosligi
        val byBc = db.productByBarcode(code)
        if (byBc != null) {
            product = byBc
            if (byBc.has("pack_qty")) packQty = byBc.optDouble("pack_qty")
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
            return JSONObject().put("found", false).put("barcode", code).put("offline", true)
        }

        val price = product.optLong("price", 0)
        val resp = JSONObject()
            .put("ok", true)
            .put("found", true)
            .put("offline", true)
            .put("barcode", code)
            .put("moysklad_id", product.optString("moysklad_id"))
            .put("name", product.optString("name"))
            .put("price", price)
            .put("code", product.optString("code"))
            .put("article", product.optString("article"))
            .put("uom", product.optString("uom"))
            .put("store_qty", product.optDouble("store_qty", 0.0))
            .put("pack_qty", packQty ?: JSONObject.NULL)
            .put("is_pack", packQty != null && packQty > 0)

        if (usedScale) {
            val weight = Math.round(scaleValue / 1000.0 * 1000.0) / 1000.0  // gramm -> kg
            resp.put("scale", true)
            resp.put("scale_weight", weight)
            resp.put("scale_price", Math.round(weight * price).toInt())
        }
        return resp
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
