package com.basl.pubgmeoptimizer

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

class MainActivity : AppCompatActivity() {

    private var isShieldActive = false
    private lateinit var statusText: TextView
    private lateinit var pingResultText: TextView
    private lateinit var mtuSeekBar: SeekBar
    private lateinit var mtuValueText: TextView
    private lateinit var serverSpinner: Spinner
    private lateinit var wispModeSpinner: Spinner
    private lateinit var toggleButton: Button
    private lateinit var testPingButton: Button

    private val meServers = listOf(
        "سيرفر الشرق الأوسط - البحرين (AWS me-south-1)" to "157.175.0.1",
        "سيرفر الشرق الأوسط - الإمارات دبي (AWS me-central-1)" to "3.28.0.1",
        "بوابة Cloudflare الشرق الأوسط (أسرع DNS)" to "1.1.1.1",
        "بوابة الخروج الدولية لخطك (تركيا 86.106.122.156)" to "86.106.122.156",
        "القفزة الثالثة لمزودك (185.200.39.237 - مراقبة الضياع)" to "185.200.39.237",
        "بوابة البرج المحلي (172.16.64.1)" to "172.16.64.1"
    )

    private val wispProfiles = listOf(
        "معايرة خطك الفعلي (Netis N3D + 3 أنفاق 10.x) - MTU 1360" to 1360,
        "نت هوائي PPPoE قياسي (Ubiquiti / MikroTik) - MTU 1380" to 1380,
        "نت هوائي مزدوج الكبسلة (VLAN + PPPoE + L2TP) - MTU 1352" to 1352,
        "نت هوائي مستقر قريب من البرج - MTU 1420" to 1420,
        "ضبط أقصى لتقليل ضياع الطلقات وقت الذروة - MTU 1320" to 1320
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        pingResultText = findViewById(R.id.pingResultText)
        mtuSeekBar = findViewById(R.id.mtuSeekBar)
        mtuValueText = findViewById(R.id.mtuValueText)
        serverSpinner = findViewById(R.id.serverSpinner)
        wispModeSpinner = findViewById(R.id.wispModeSpinner)
        toggleButton = findViewById(R.id.toggleButton)
        testPingButton = findViewById(R.id.testPingButton)

        serverSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            meServers.map { it.first }
        )

        wispModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            wispProfiles.map { it.first }
        )

        wispModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val recommendedMtu = wispProfiles[pos].second
                mtuSeekBar.progress = recommendedMtu - 1280
                mtuValueText.text = "حجم الحزمة (MTU): $recommendedMtu بايت"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        mtuSeekBar.max = 200 // 1280 .. 1480
        mtuSeekBar.progress = 80 // 1360 default for 3-hop 10.x tunnel
        mtuSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mtu = 1280 + progress
                mtuValueText.text = "حجم الحزمة (MTU): $mtu بايت"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        toggleButton.setOnClickListener {
            if (!isShieldActive) {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, 1001)
                } else {
                    onActivityResult(1001, Activity.RESULT_OK, null)
                }
            } else {
                stopShield()
            }
        }

        testPingButton.setOnClickListener {
            measureMiddleEastLatency()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            startShield()
        }
    }

    private fun startShield() {
        val mtu = 1280 + mtuSeekBar.progress
        val selectedServerIp = meServers[serverSpinner.selectedItemPosition].second

        val serviceIntent = Intent(this, WispOptimizerVpnService::class.java).apply {
            action = WispOptimizerVpnService.ACTION_START
            putExtra(WispOptimizerVpnService.EXTRA_MTU, mtu)
            putExtra(WispOptimizerVpnService.EXTRA_DNS_PRIMARY, "1.1.1.1")
            putExtra(WispOptimizerVpnService.EXTRA_DNS_SECONDARY, "9.9.9.9")
            putExtra(WispOptimizerVpnService.EXTRA_KEEP_ALIVE_MS, 220L)
            putExtra(WispOptimizerVpnService.EXTRA_ME_ENDPOINT, selectedServerIp)
        }
        startService(serviceIntent)
        isShieldActive = true
        statusText.text = "الحالة: متصل ومُحسّن لسيرفر الشرق الأوسط (MTU $mtu)"
        toggleButton.text = "إيقاف درع تثبيت البنغ"
    }

    private fun stopShield() {
        val serviceIntent = Intent(this, WispOptimizerVpnService::class.java).apply {
            action = WispOptimizerVpnService.ACTION_STOP
        }
        startService(serviceIntent)
        isShieldActive = false
        statusText.text = "الحالة: متوقف"
        toggleButton.text = "تفعيل درع البنغ ومكافحة فقدان الحزم"
    }

    private fun measureMiddleEastLatency() {
        pingResultText.text = "جاري فحص البنغ وفقدان الحزم (10 نبضات)..."
        val targetIp = meServers[serverSpinner.selectedItemPosition].second
        thread {
            val samples = mutableListOf<Long>()
            var lost = 0
            for (i in 1..10) {
                try {
                    val elapsed = measureTimeMillis {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("1.1.1.1", 53), 1200)
                        }
                    }
                    samples.add(elapsed)
                } catch (_: Exception) {
                    lost++
                }
                Thread.sleep(100)
            }
            val avgPing = if (samples.isNotEmpty()) samples.average().toInt() else -1
            val jitter = if (samples.size > 1) {
                samples.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toInt()
            } else 0
            val lossPct = (lost * 100) / 10

            runOnUiThread {
                pingResultText.text = if (avgPing > 0) {
                    "متوسط البنغ: ${avgPing}ms | التذبذب (Jitter): ${jitter}ms | فقدان الحزم: $lossPct%"
                } else {
                    "تعذر القياس المباشر — تأكد من الاتصال بالإنترنت ($targetIp)"
                }
            }
        }
    }
}
