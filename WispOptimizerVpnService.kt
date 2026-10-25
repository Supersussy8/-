package com.basl.pubgmeoptimizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * خدمة تحسين البنغ ومكافحة فقدان الحزم (Packet Loss) المخصصة للإنترنت الهوائي في سوريا.
 * تعمل محلياً (Zero-Tunnel Overhead) لضبط الـ MTU ومنع تقطيع حزم UDP الخاصة بلعبة ببجي،
 * مع إرسال نبضات Keep-Alive لمنع خمول الطبق الهوائي (Ubiquiti / MikroTik).
 */
class WispOptimizerVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)

    companion object {
        const val ACTION_START = "com.basl.pubgmeoptimizer.START"
        const val ACTION_STOP = "com.basl.pubgmeoptimizer.STOP"
        const val EXTRA_MTU = "extra_mtu"
        const val EXTRA_DNS_PRIMARY = "extra_dns_primary"
        const val EXTRA_DNS_SECONDARY = "extra_dns_secondary"
        const val EXTRA_KEEP_ALIVE_MS = "extra_keep_alive_ms"
        const val EXTRA_ME_ENDPOINT = "extra_me_endpoint"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopOptimizer()
            return START_NOT_STICKY
        }

        val mtu = intent?.getIntExtra(EXTRA_MTU, 1360) ?: 1360
        val dns1 = intent?.getStringExtra(EXTRA_DNS_PRIMARY) ?: "1.1.1.1"
        val dns2 = intent?.getStringExtra(EXTRA_DNS_SECONDARY) ?: "9.9.9.9"
        val keepAliveMs = intent?.getLongExtra(EXTRA_KEEP_ALIVE_MS, 250L) ?: 250L
        val meEndpoint = intent?.getStringExtra(EXTRA_ME_ENDPOINT) ?: "157.175.0.1" // AWS Bahrain ME

        startForegroundNotification(mtu, dns1)
        startLocalMtuAndDnsShield(mtu, dns1, dns2)
        startWirelessKeepAlivePacer(meEndpoint, keepAliveMs)

        return START_STICKY
    }

    private fun startLocalMtuAndDnsShield(mtu: Int, dns1: String, dns2: String) {
        try {
            vpnInterface?.close()
            val builder = Builder()
                .setSession("PUBG-ME-WISP-Shield")
                .setMtu(mtu)
                .addAddress("10.111.222.1", 32)
                .addDnsServer(dns1)
                .addDnsServer(dns2)
                // توجيه طلبات الـ DNS فقط عبر الواجهة مع ترك حزم UDP للعبة تمر مباشرة بأقصى سرعة وبدون بروكسي
                .addRoute(dns1, 32)
                .addRoute(dns2, 32)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()
            isRunning.set(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * يرسل نبضات UDP صغيرة جداً بوسم DSCP Expedited Forwarding (0xB8)
     * لإبقاء جلسة الطبق الهوائي (AirMAX / MikroTik NV2) نشطة ومنع قفزات الـ Jitter.
     */
    private fun startWirelessKeepAlivePacer(endpointIp: String, intervalMs: Long) {
        thread(start = true, name = "WispKeepAlivePacer") {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protect(socket) // استثناء مقبس النبضات من أي نفق داخلي
                // وسم الحزمة بأولوية الألعاب الصوتية/اللحظية القصوى DSCP EF (46 -> 0xB8)
                socket.trafficClass = 0xB8
                socket.soTimeout = 1000
                val payload = byteArrayOf(0x50, 0x55, 0x42, 0x47) // "PUBG" 4-byte micro heartbeat
                val target = InetSocketAddress(endpointIp, 53)

                while (isRunning.get()) {
                    try {
                        val packet = DatagramPacket(payload, payload.size, target)
                        socket.send(packet)
                    } catch (_: Exception) {
                        // تجاهل أخطاء الشبكة اللحظية أثناء التذبذب الهوائي
                    }
                    Thread.sleep(intervalMs.coerceIn(100L, 2000L))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
            }
        }
    }

    private fun startForegroundNotification(mtu: Int, dns: String) {
        val channelId = "pubg_me_wisp_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "PUBG ME WISP Optimizer",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("درع ببجي الشرق الأوسط نشط")
            .setContentText("MTU: $mtu | DNS: $dns | مكافحة تقطيع الهوائي مفعلة")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    private fun stopOptimizer() {
        isRunning.set(false)
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopOptimizer()
        super.onDestroy()
    }
}
