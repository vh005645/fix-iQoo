package com.example.fcmkeeper

import android.app.*
import android.content.ContentResolver
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.IBinder
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*

class KeeperService : Service() {
    private val ex = Executors.newSingleThreadScheduledExecutor()
    private var task: ScheduledFuture<*>? = null
    private var cb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var online = false

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("k", "FCM Keeper", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, note("Đang chạy"))
        online = netOk()
        if (cb == null) {
            cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                    val up = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (up != online) { online = up; schedule() }
                }
                override fun onLost(n: Network) { if (online) { online = false; schedule() } }
            }
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb!!)
        }
        schedule()
        return START_STICKY
    }

    // Có mạng: chạy ngay rồi lặp theo chu kỳ. Mất mạng: dừng hẳn cho tới khi có mạng lại.
    @Synchronized private fun schedule() {
        task?.cancel(false); task = null
        if (online) {
            val min = getSharedPreferences("p", 0).getInt("min", 3).coerceAtLeast(1).toLong()
            task = ex.scheduleWithFixedDelay({ run() }, 0, min, TimeUnit.MINUTES)
        } else { setConn(false); setStatus("Không có mạng - tạm dừng kiểm tra") }
    }

    private fun setConn(c: Boolean) {
        val p = getSharedPreferences("p", 0); val prev = p.getBoolean("conn", false); val e = p.edit()
        if (c && !prev) e.putBoolean("conn", true).putLong("since", System.currentTimeMillis())
        else if (!c) {
            if (prev || p.getLong("lastDisc", 0) == 0L) e.putLong("lastDisc", System.currentTimeMillis())
            e.putBoolean("conn", false).putLong("since", 0)
        }
        e.apply()
    }

    private fun note(t: String) = Notification.Builder(this, "k")
        .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("FCM Keeper").setContentText(t).build()

    private fun setStatus(s: String) {
        getSharedPreferences("p", 0).edit().putString("status", s).apply()
        getSystemService(NotificationManager::class.java).notify(1, note(s))
    }

    private fun netOk(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun reach(): Boolean = listOf(5228, 443).any {
        try { Socket().use { s -> s.connect(InetSocketAddress("mtalk.google.com", it), 5000) }; true } catch (e: Exception) { false }
    }

    private fun kick(): String {
        val p = getSharedPreferences("p", 0)
        var g = 0; var fb = 0
        // 1) Đánh thức Google Play Services / MCS
        listOf("GTALK_RECONNECT", "GTALK_HEARTBEAT", "MCS_HEARTBEAT").forEach {
            try { sendBroadcast(Intent("com.google.android.intent.action.$it").setPackage("com.google.android.gms")) } catch (_: Exception) {}
        }
        // 2) Đồng bộ Lịch (Google) và Facebook (nếu tài khoản Facebook nhìn thấy được)
        try {
            val b = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true)
            }
            for (acc in android.accounts.AccountManager.get(this).accounts) {
                if (acc.type == "com.google") {
                    try { ContentResolver.setSyncAutomatically(acc, "com.android.calendar", true) } catch (_: Exception) {}
                    try { ContentResolver.requestSync(acc, "com.android.calendar", b); g++ } catch (_: Exception) {}
                } else if (acc.type.contains("facebook", true)) {
                    ContentResolver.getSyncAdapterTypes().filter { it.accountType == acc.type }.forEach {
                        try { ContentResolver.requestSync(acc, it.authority, b); fb++ } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
        // 3) Dự phòng (tùy chọn): bật/tắt đồng bộ chung
        if (p.getBoolean("toggle", false)) try {
            val was = ContentResolver.getMasterSyncAutomatically()
            if (was) { ContentResolver.setMasterSyncAutomatically(false); Thread.sleep(2000); ContentResolver.setMasterSyncAutomatically(true) }
            else { ContentResolver.setMasterSyncAutomatically(true); Thread.sleep(8000); ContentResolver.setMasterSyncAutomatically(false) }
        } catch (_: Exception) {}
        return "Lịch:$g FB:$fb"
    }

    // Cần cấp quyền DUMP 1 lần qua adb.
    private fun readDump(p: android.content.SharedPreferences): Boolean? {
        return try {
            val pr = ProcessBuilder("dumpsys", "activity", "service", "GcmService").redirectErrorStream(true).start()
            val txt = pr.inputStream.bufferedReader().readText().take(300000)
            val lines = txt.lines().filter { it.contains("connect", true) }.map { it.trim() }.take(8)
            if (txt.contains("Permission Denial", true) || lines.isEmpty()) { p.edit().putString("dump", "(không đọc được - chưa cấp quyền DUMP?)").apply(); null }
            else {
                p.edit().putString("dump", lines.joinToString("\n")).apply()
                lines.any { it.contains("connected", true) } && lines.none { Regex("(?i)disconnect|not connected|false").containsMatchIn(it) }
            }
        } catch (_: Exception) { null }
    }

    private fun run() {
        if (!online || !netOk()) return
        val p = getSharedPreferences("p", 0)
        var src = "mtalk"
        var ok: Boolean? = readDump(p)
        if (ok != null) src = "dump" else ok = reach()
        setConn(ok)
        val did = !ok || p.getBoolean("force", true)
        val info = if (did) kick() else ""
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        setStatus("$t | mạng: ok | FCM($src): ${if (ok) "Connected" else "lỗi"} | ${if (did) "đã đánh thức ($info)" else "bỏ qua"}")
    }

    override fun onDestroy() {
        cb?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        task?.cancel(false); ex.shutdown()
    }
    override fun onBind(i: Intent?): IBinder? = null
}
