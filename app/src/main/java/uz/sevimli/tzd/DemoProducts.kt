package uz.sevimli.tzd

data class Product(
    val barcode: String,
    val name: String,
    val price: Long,      // so'mda
    val stock: Double
)

// DEMO REJIM: backend ulanmaguncha vaqtinchalik ma'lumot.
// Keyingi bosqichda bu ro'yxat Railway backend'dagi
// product_cache (20 000+ mahsulot) bilan almashtiriladi.
object DemoProducts {

    private val items = listOf(
        Product("4780000000017", "Non buxanka 400g", 4000, 120.0),
        Product("4780000000024", "Sut Nestle 1L", 16500, 48.0),
        Product("4780000000031", "Coca-Cola 1.5L", 15000, 96.0),
        Product("4780000000048", "Shakar 1kg", 12500, 300.0),
        Product("4780000000055", "Guruch lazer 1kg", 18000, 250.0),
        Product("4780000000062", "Yog' Oila 1L", 24000, 80.0),
        Product("4780000000079", "Choy Ahmad 250g", 32000, 40.0),
        Product("4780000000086", "Makaron Makfa 400g", 9500, 150.0),
        Product("4780000000093", "Tuxum 10 dona", 14000, 60.0),
        Product("4780000000109", "Un oliy nav 2kg", 17000, 90.0)
    )

    private val byBarcode = items.associateBy { it.barcode }

    fun find(code: String): Product? {
        // Aniq mos kelsa
        byBarcode[code]?.let { return it }
        // Demo qulaylik: istalgan kod skan qilinsa ham "topilgan" his
        // berish uchun kodning oxirgi raqamiga qarab mahsulot qaytaramiz.
        // Haqiqiy versiyada bu olib tashlanadi!
        val digit = code.lastOrNull()?.digitToIntOrNull() ?: return null
        return items.getOrNull(digit % items.size)
    }
}
