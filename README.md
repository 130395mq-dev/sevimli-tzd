# Sevimli TZD — Skaner test APK (Qadam 0)

Cleverence «Склад 15» o'rniga o'z APK'mizni qurish loyihasi.
Bu birinchi versiya — faqat skanerni tekshiradi.

## Nima qiladi
- Skan qilinganda kodni katta harflar bilan ekranga chiqaradi
- Qaysi usul ishlaganini ko'rsatadi: BROADCAST (qaysi action) yoki KEYBOARD-WEDGE
- Barcha extra kalitlarni chiqaradi — sening qurilmang qaysi kalitda barcode yuborishini aniqlaymiz

## Build qilish (GitHub Actions)
1. GitHub'da yangi repo och (masalan `sevimli-tzd`)
2. Shu papkadagi hamma fayllarni push qil
3. Actions bo'limida "Build APK" avtomatik ishlaydi (~3 daqiqa)
4. Ishlab bo'lgach: Actions → oxirgi run → pastda **sevimli-tzd-apk** artifact'ni yuklab ol
5. Ichidagi `app-debug.apk`ni TZD'ga ko'chirib, o'rnat (noma'lum manbalarga ruxsat kerak bo'lishi mumkin)

## Test
- APK'ni och, biror mahsulotni skan qil
- Ekrandagi "Usul:" va "Tarix" nima yozganini menga surat qilib yubor
- Shundan qurilmang skanerini qanday ulashni aniq bilamiz
