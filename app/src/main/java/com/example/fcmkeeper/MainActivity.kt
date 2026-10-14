package com.example.fcmkeeper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var st: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(if (Build.VERSION.SDK_INT >= 33) arrayOf("android.permission.POST_NOTIFICATIONS", "android.permission.GET_ACCOUNTS") else arrayOf("android.permission.GET_ACCOUNTS"), 1)
        val p = getSharedPreferences("p", 0)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((24 * d).toInt(), (48 * d).toInt(), (24 * d).toInt(), 24 * d.toInt()) }
        fun tv(t: String, s: Float = 16f) = TextView(this).apply { text = t; textSize = s; setPadding(0, 12, 0, 12) }
        val bold = android.graphics.Typeface.BOLD
        val title = tv("IQOO fix", 26f).apply { gravity = Gravity.CENTER; setTextColor(Color.parseColor("#FFC400")); setTypeface(null, bold) }
        val tcv = tv("Time connected : 00:00:00", 24f).apply { gravity = Gravity.CENTER; setTextColor(Color.parseColor("#00E676")); setTypeface(null, bold) }
        val dcv = tv("", 14f).apply { gravity = Gravity.CENTER; setTextColor(Color.parseColor("#FF8A80")) }
        root.addView(title); root.addView(tcv); root.addView(dcv)
        st = tv("..."); root.addView(st)
        val min = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "Chu kỳ kiểm tra (phút)"; setText(p.getInt("min", 3).toString()) }
        val force = CheckBox(this).apply { text = "Luôn kích hoạt lại mỗi chu kỳ (khuyên dùng)"; isChecked = p.getBoolean("force", true) }
        val diag = CheckBox(this).apply { text = "Dự phòng: bật/tắt đồng bộ chung (tốn pin hơn)"; isChecked = p.getBoolean("toggle", false) }
        root.addView(min); root.addView(force); root.addView(diag)
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }.also { root.addView(it) }
        btn("Bắt đầu") {
            p.edit().putInt("min", min.text.toString().toIntOrNull() ?: 3).putBoolean("force", force.isChecked).putBoolean("toggle", diag.isChecked).putBoolean("on", true).apply()
            startForegroundService(Intent(this, KeeperService::class.java))
        }
        btn("Dừng") { p.edit().putBoolean("on", false).apply(); stopService(Intent(this, KeeperService::class.java)) }
        btn("Cho phép chạy nền / bỏ tối ưu pin") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        btn("Xem dữ liệu dump FCM (gỡ lỗi)") {
            AlertDialog.Builder(this).setMessage(p.getString("dump", "Chưa có dữ liệu") + "\n\nLệnh cấp quyền (chạy 1 lần trên máy tính):\nadb shell pm grant $packageName android.permission.DUMP").setPositiveButton("OK", null).show()
        }
        setContentView(ScrollView(this).apply { addView(root) })
        fun refresh() {
            val since = p.getLong("since", 0); val on = p.getBoolean("conn", false) && since > 0
            val sec = if (on) (System.currentTimeMillis() - since) / 1000 else 0
            tcv.text = "Time connected : %02d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
            val d = p.getLong("lastDisc", 0)
            dcv.text = if (d == 0L) "Chưa ghi nhận mất kết nối" else "Mất kết nối lần cuối (Disconnect): " +
                java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(d))
        }
        h.post(object : Runnable { override fun run() { refresh(); st.text = p.getString("status", "Chưa chạy") + "\nDump FCM:\n" + p.getString("dump", "-"); h.postDelayed(this, 1000) } })
    }
}
