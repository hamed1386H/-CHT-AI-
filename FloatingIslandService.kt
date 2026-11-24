package com.chtai.assistant

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.*

class FloatingIslandService : Service() {
    private var windowManager: WindowManager? = null
    private var island: LinearLayout? = null
    private val channelId = "jarvis_island"

    override fun onCreate() {
        super.onCreate()
        createNotification()
        startForeground(731, Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S فعال است")
            .setContentText("جزیره شناور روی برنامه‌ها نمایش داده می‌شود")
            .setOngoing(true).build())
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) { stopSelf(); return }
        showIsland()
    }

    private fun createNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "جزیره شناور جارویس", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun showIsland() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(18, 10, 18, 10)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(5, 14, 25)); cornerRadius = 60f
                setStroke(2, Color.rgb(30, 190, 220))
            }
        }
        val mic = TextView(this).apply { text = "◉"; textSize = 18f; setTextColor(Color.rgb(70,225,245)); setPadding(8,0,8,0); setOnClickListener { openJarvis() } }
        val label = TextView(this).apply { text = "J.A.R.V.I.S"; textSize = 12f; setTextColor(Color.WHITE); setPadding(6,0,6,0); setOnClickListener { openJarvis() } }
        val search = TextView(this).apply { text = "⌕"; textSize = 22f; setTextColor(Color.rgb(70,225,245)); setPadding(8,0,8,0); setOnClickListener { openSearch() } }
        root.addView(mic); root.addView(label); root.addView(search)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 8 }
        var downX=0f; var downY=0f; var startX=0; var startY=0
        root.setOnTouchListener { _, event ->
            when(event.action) {
                MotionEvent.ACTION_DOWN -> { downX=event.rawX; downY=event.rawY; startX=params.x; startY=params.y; true }
                MotionEvent.ACTION_MOVE -> { params.x=startX+(event.rawX-downX).toInt(); params.y=startY+(event.rawY-downY).toInt(); windowManager?.updateViewLayout(root,params); true }
                else -> false
            }
        }
        island=root
        try { windowManager?.addView(root,params) } catch (_: Exception) { stopSelf() }
    }

    private fun openJarvis() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    private fun openSearch() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/" )).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onDestroy() {
        try { island?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        island=null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
