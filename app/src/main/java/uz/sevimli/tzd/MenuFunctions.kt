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
        // Matn EMAS, resurs havolasi: ilova tili almashganda nom ham
        // almashishi kerak. Matnlar strings.xml / values-ru da.
        @androidx.annotation.StringRes val title: Int,   // menyudagi nom
        @androidx.annotation.StringRes val sub: Int,     // pastki izoh
        // DIQQAT: ilgari bu drawable NOMI (matn) edi va ikonka ish paytida
        // `resources.getIdentifier(...)` bilan qidirilardi. Ikki kamchiligi bor edi:
        //   1) nomni xato yozsak, kompilyator sezmasdi — ikonka jimgina yo'qolardi;
        //   2) R8 resurs tozalashi (shrinkResources) bu ikonkalarga hech qanday
        //      havola ko'rmay, ularni APK'dan olib tashlar edi — dashboard bo'sh
        //      kvadratlar bilan chiqardi.
        // Endi bu to'g'ridan-to'g'ri R.drawable havolasi.
        @androidx.annotation.DrawableRes val icon: Int,
        val needsStore: Boolean = false,
    )

    /** Yoqib/o'chirilishi mumkin bo'lgan bo'limlar (Просмотр va Sozlamalar bундан tashqari — doim ko'rinadi). */
    val LIST: List<Fn> = listOf(
        Fn("supply",    R.string.fn_supply_t,    R.string.fn_supply_s,   R.drawable.ic_receive),
        Fn("inventory", R.string.fn_inventory_t, R.string.fn_inventory_s,      R.drawable.ic_inventory),
        Fn("move",      R.string.fn_move_t,      R.string.fn_move_s,     R.drawable.ic_move, needsStore = true),
        Fn("shipment",  R.string.fn_shipment_t,  R.string.fn_shipment_s,      R.drawable.ic_pick),
        Fn("writeoff",  R.string.fn_writeoff_t,  R.string.fn_writeoff_s,   R.drawable.ic_writeoff),
        Fn("preturn",   R.string.fn_preturn_t,   R.string.fn_preturn_s,  R.drawable.ic_writeoff),
        Fn("etiketka",  R.string.fn_etiketka_t,  R.string.fn_etiketka_s,   R.drawable.ic_receive),
        // KELAJAKDA: shu yerga yangi qator qo'shsangiz — o'zi menyu va sozlamalarga chiqadi.
    )
}
