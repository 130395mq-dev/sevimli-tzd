package uz.sevimli.tzd

/**
 * Menyu/dashboard bo'limlari — BITTA RO'YXAT.
 * Yangi funksiya qo'shish uchun shu ro'yxatga bitta qator qo'shing —
 * menyuда ham, Sozlamalar (yoqib/o'chirish)да ham avtomatik chiqadi.
 *
 * needsStore = true bo'lsa, bo'lim ochilishidan oldin sklad tanlanган bo'lishi shart.
 */
object MenuFunctions {

    data class Fn(
        val key: String,       // ichki kalit (Config'da yoqilgan/yo'q holati shu bo'yicha)
        val title: String,     // menyudagi nom
        val sub: String,       // pastki izoh
        val icon: String,      // drawable nomi (ic_...)
        val needsStore: Boolean = false,
    )

    /** Yoqib/o'chirilishi mumkin bo'lgan bo'limlar (Просмотр va Sozlamalar bундан tashqari — doim ko'rinadi). */
    val LIST: List<Fn> = listOf(
        Fn("supply",    "Приёмка",            "tovar qabul qilish",   "ic_receive"),
        Fn("inventory", "Инвентаризация",     "qoldiqni sanash",      "ic_inventory"),
        Fn("move",      "Перемещение",        "skladlar orasida",     "ic_move", needsStore = true),
        Fn("shipment",  "Отгрузка",           "tovar chiqarish",      "ic_pick"),
        Fn("writeoff",  "Списание",           "hisobdan chiqarish",   "ic_writeoff"),
        Fn("preturn",   "Возврат поставщику", "yetkazib beruvchiga",  "ic_writeoff"),
        Fn("etiketka",  "Этикетка / Ценник",  "narx yorlig'i chop",   "ic_receive"),
        // KELAJAKDA: shu yerga yangi qator qo'shsangiz — o'zi menyu va sozlamalarga chiqadi.
    )
}
