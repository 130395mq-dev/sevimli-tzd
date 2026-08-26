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
        Fn("supply",    "Приёмка",            "tovar qabul qilish",   R.drawable.ic_receive),
        Fn("inventory", "Инвентаризация",     "qoldiqni sanash",      R.drawable.ic_inventory),
        Fn("move",      "Перемещение",        "skladlar orasida",     R.drawable.ic_move, needsStore = true),
        Fn("shipment",  "Отгрузка",           "tovar chiqarish",      R.drawable.ic_pick),
        Fn("writeoff",  "Списание",           "hisobdan chiqarish",   R.drawable.ic_writeoff),
        // Ilgari bu ikkitasi BOSHQA bo'limlarning ikonkasini takrorlardi
        // (preturn -> ic_writeoff, etiketka -> ic_receive). Menyuда bir xil
        // rasm ikki joyda turardi va xodim adashardi. Endi o'z ikonkasi bor.
        Fn("preturn",   "Возврат поставщику", "yetkazib beruvchiga",  R.drawable.ic_return),
        Fn("etiketka",  "Этикетка / Ценник",  "narx yorlig'i chop",   R.drawable.ic_label),
        // KELAJAKDA: shu yerga yangi qator qo'shsangiz — o'zi menyu va sozlamalarga chiqadi.
    )
}
