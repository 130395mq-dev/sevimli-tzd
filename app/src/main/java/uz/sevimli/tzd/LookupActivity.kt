package uz.sevimli.tzd

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import uz.sevimli.tzd.databinding.ActivityLookupBinding
import java.text.NumberFormat
import java.util.Locale

class LookupActivity : AppCompatActivity() {

    private lateinit var b: ActivityLookupBinding
    private val fmt = NumberFormat.getInstance(Locale("uz"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLookupBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        // Klaviatura hech qachon ochilmasin - skaner o'zi "yozadi"
        b.scanInput.showSoftInputOnFocus = false

        b.scanInput.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_NEXT ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.action == KeyEvent.ACTION_DOWN)
            if (enter) {
                val code = b.scanInput.text.toString().trim()
                if (code.isNotEmpty()) handleScan(code)
                b.scanInput.setText("")
                true
            } else false
        }
    }

    private fun handleScan(code: String) {
        b.lastCode.text = "Oxirgi skan: $code"
        val p = DemoProducts.find(code)
        if (p != null) {
            b.emptyState.visibility = View.GONE
            b.notFound.visibility = View.GONE
            b.card.visibility = View.VISIBLE
            b.pName.text = p.name
            b.pBarcode.text = p.barcode
            b.pPrice.text = "${fmt.format(p.price)} so'm"
            b.pStock.text = "Qoldiq: ${p.stock} dona"
        } else {
            b.emptyState.visibility = View.GONE
            b.card.visibility = View.GONE
            b.notFound.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        b.scanInput.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) b.scanInput.requestFocus()
    }
}
