# =============================================================================
#  Jamlov TZD — R8 (kod tozalash) qoidalari
# =============================================================================
#
#  MAQSAD: APK'dan ISHLATILMAYDIGAN kodni olib tashlash. Ombor Wi-Fi'sida
#  majburiy yangilanish tezroq yuklanadi va ilova biroz tezroq ochiladi.
#
#  ATAYLAB QILINMAGAN NARSA: kod NOMLARI O'ZGARTIRILMAYDI (-dontobfuscate).
#
#  Nega? Nomlarni o'zgartirish (obfuscation) — R8 ning eng xavfli qismi.
#  U refleksiya orqali nom bo'yicha topiladigan sinflarni buzadi va bu
#  KOMPILYATSIYADA emas, ISHLAB TURGANDA sezilади. Bizning holatda:
#    * androidx.security / Tink kalit menejerlarini nom bo'yicha ro'yxatga oladi
#    * Android tizimi Activity'larni manifestdagi NOM bo'yicha ochadi
#    * ViewBinding sinflari generatsiya qilinadi
#  Obfuscation'dan foyda — ichki korporativ ilova uchun deyarli nol,
#  xavf esa yuqori: bitta o'tkazib yuborilgan qoida = terminal ishlamay qoladi.
#
#  Shuning uchun: TOZALASH — HA, NOM O'ZGARTIRISH — YO'Q.
#  Bu R8 ning foydasining katta qismini beradi, xavfining deyarli hammasini
#  olib tashlaydi.
# =============================================================================

# Nomlarni o'zgartirmaymiz (yuqoridagi izohga qarang)
-dontobfuscate

# Xato hisobotlari o'qilishi uchun qator raqamlari saqlanadi
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

# --- Ilovaning o'z kodi -------------------------------------------------------
# Manifestdagi Activity'larni Android NOM bo'yicha ochadi.
-keep class uz.sevimli.tzd.** { *; }

# --- androidx.security + Tink (token shifrlash) -------------------------------
# Tink kalit menejerlarini nom bo'yicha ro'yxatga oladi va protobuf ishlatadi.
# Bu buzilsa — ilova saqlangan tokenni o'qiy olmaydi va terminal tizimdan
# chiqib ketadi. Shuning uchun to'liq saqlanadi.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# --- ViewBinding --------------------------------------------------------------
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(...);
    public static *** bind(...);
}

# --- Android tizimi ishlatadigan konstruktorlar --------------------------------
-keepclassmembers class * extends android.app.Activity { public void *(android.view.View); }
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep class androidx.core.content.FileProvider

# XML'dan yaratiladigan View'lar (konstruktorlar nom bo'yicha chaqiriladi)
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# --- Parcelable / Serializable ------------------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# --- Enum'lar (values()/valueOf() refleksiya orqali chaqirilishi mumkin) --------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- org.json — ilova JSON'ni QO'LDA o'qiydi (Gson/Moshi YO'Q) -----------------
# Ya'ni refleksiya orqali maydon nomiga bog'lanish yo'q. Qo'shimcha qoida
# kerak emas — bu izoh kelajakda JSON kutubxonasi qo'shilsa eslatma bo'lsin.

# --- Ogohlantirishlar ---------------------------------------------------------
# Kutubxonalar ichidagi ixtiyoriy (mavjud bo'lmagan) sinflar haqida
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
