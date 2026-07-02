package uz.sevimli.tzd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import uz.sevimli.tzd.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    // Ko'p tarqalgan TZD skaner broadcast action'lari (turli brendlar)
    private val scanActions = listOf(
        "android.intent.ACTION_DECODE_DATA",          // Urovo, ba'zi umumiy
        "android.intent.action.SCANRESULT",           // iData
        "nlscan.action.SCANNER_RESULT",               // Newland
        "scan.rcv.message",                           // ba'zi Xitoy TZD
        "com.android.server.scannerservice.broadcast",// ba'zi umumiy
        "com.symbol.datawedge.api.RESULT_ACTION",     // Zebra DataWedge
        "com.honeywell.decode.intent.action.EDIT_DATA", // Honeywell
        "com.rscja.SCANNER_RESULT",                   // Chainway / RSCJA
        "com.scanner.broadcast",                      // Chainway (eski)
        "com.se4500.onDecodeComplete",                // ba'zi
        "com.barcode.sendBroadcast",
        "unitech.scanservice.data",                   // Unitech
        "com.zkc.scancode"                            // ZKC
    )

    // Barcode qiymati bo'lishi mumkin bo'lgan extra kalitlar
    private val barcodeKeys = listOf(
        "barcode_string", "barcode", "SCAN_BARCODE1", "scannerdata",
        "value", "data", "barcode_data", "scanResult",
        "com.symbol.datawedge.data_string", "EXTRA_BARCODE_DECODING_DATA"
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val sb = StringBuilder()
            var found: String? = null

            intent.extras?.let { ex ->
                for (key in ex.keySet()) {
                    val v = ex.get(key)
                    val text = when (v) {
                        is ByteArray -> String(v).trim()
                        else -> v?.toString()?.trim()
                    }
                    sb.append("   [$key] = $text\n")
                    if (found.isNullOrEmpty() && !text.isNullOrEmpty() &&
                        (barcodeKeys.contains(key) ||
                                text.matches(Regex("[0-9A-Za-z\\-]{4,}")))
                    ) {
                        found = text
                    }
                }
            }
            onScan(found ?: "(qiymat topilmadi)", "BROADCAST: $action", sb.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Keyboard-wedge: skaner qiymatni maydonga yozib, Enter yuborsa
        b.wedgeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT
            ) {
                val code = b.wedgeInput.text.toString().trim()
                if (code.isNotEmpty()) {
                    onScan(code, "KEYBOARD-WEDGE (Enter)", "")
                    b.wedgeInput.setText("")
                }
                true
            } else false
        }
    }

    private fun onScan(code: String, method: String, extras: String) {
        runOnUiThread {
            b.lastScan.text = code
            b.methodText.text = "Usul: $method"
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val entry = "[$ts] $method\n   => $code\n$extras"
            b.log.text = "$entry\n${b.log.text}"
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter()
        scanActions.forEach { filter.addAction(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        b.wedgeInput.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }
}
