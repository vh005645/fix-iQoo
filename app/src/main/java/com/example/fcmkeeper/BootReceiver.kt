package com.example.fcmkeeper

import android.content.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (c.getSharedPreferences("p", 0).getBoolean("on", false))
            c.startForegroundService(Intent(c, KeeperService::class.java))
    }
}
