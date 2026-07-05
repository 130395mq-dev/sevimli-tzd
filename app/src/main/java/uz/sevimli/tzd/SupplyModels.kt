package uz.sevimli.tzd

/** Приёмка ichidagi bitta mahsulot qatori. */
data class SupplyItem(
    val productMoyskladId: String,
    val barcode: String,
    val name: String,
    val price: Long,
    var quantity: Double
)

/** Tanlangan kontragent. */
data class Counterparty(val id: Int, val name: String)
