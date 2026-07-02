package uz.sevimli.tzd

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import uz.sevimli.tzd.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

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

        b.splashVersion.alpha = 0f
        b.splashVersion.animate().alpha(1f).setStartDelay(400).setDuration(500).start()

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MenuActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 1400)
    }
}
