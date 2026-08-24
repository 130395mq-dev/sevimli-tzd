package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import uz.sevimli.tzd.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var goNext: Runnable? = null

    override fun onDestroy() {
        super.onDestroy()
        goNext?.let { handler.removeCallbacks(it) }   // ekran yopilsa — o'tish bekor
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Logo yumshoq paydo bo'ladi: fade + kichik zoom
        b.splashLogo.alpha = 0f
        b.splashLogo.scaleX = 0.82f
        b.splashLogo.scaleY = 0.82f
        b.splashLogo.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(650)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Haqiqiy versiyani ko'rsatadi (qattiq yozilgan matn emas)
        b.splashVersion.text = "Ombor boshqaruv tizimi · v${BuildConfig.VERSION_NAME}"
        b.splashVersion.alpha = 0f
        b.splashVersion.animate().alpha(1f).setStartDelay(400).setDuration(500).start()

        // ILGARI 1400 ms kutilardi — har ochilishda shuncha bekor vaqt.
        // Logotip animatsiyasi 650 ms, versiya matni 900 ms da tugaydi;
        // 500 ms yetarli va ilova sezilarli tez ochiladi.
        goNext = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            val next = if (Config.isConfigured(this)) MenuActivity::class.java else SetupActivity::class.java
            startActivity(Intent(this, next))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
        handler.postDelayed(goNext!!, 500)
    }
}
