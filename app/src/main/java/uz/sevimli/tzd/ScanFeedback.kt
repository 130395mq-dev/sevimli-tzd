package uz.sevimli.tzd

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Skan natijasiga ovozli (beep) + tebranish (vibratsiya) qaytaradi.
 * Kun bo'yi skanerlaydigan ishchi ekranga qaramasdan ham natijani biladi:
 *   ok()   — muvaffaqiyatli topildi (qisqa beep + qisqa tebranish)
 *   fail() — topilmadi / xato (past, uzunroq signal + uzun tebranish)
 * Hech qanday ovoz fayli kerak emas (ToneGenerator tizim tonlaridan foydalanadi).
 */
object ScanFeedback {

    fun ok(ctx: Context) {
        beep(ToneGenerator.TONE_PROP_BEEP, 120)
        vibrate(ctx, 40)
    }

    fun fail(ctx: Context) {
        beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
        vibrate(ctx, 200)
    }

    private fun beep(tone: Int, ms: Int) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tg.startTone(tone, ms)
            // Ton tugagach resursni bo'shatamiz
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { tg.release() } catch (_: Exception) {}
            }, (ms + 150).toLong())
        } catch (_: Exception) {
            // ba'zi qurilmalarda ToneGenerator ishlamasligi mumkin — jim o'tkazamiz
        }
    }

    private fun vibrate(ctx: Context, ms: Long) {
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }
}
