package com.fpsoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Köşede duran küçük gösterge. Diğer uygulamaların üzerine çizilir. */
class OverlayService : Service() {
    companion object {
        const val ACTION_STOP = "com.fpsoverlay.STOP"
        const val ACTION_CLICKTHROUGH = "com.fpsoverlay.CLICKTHROUGH"
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 1

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun canDraw(ctx: Context) = AndroidSettings.canDrawOverlays(ctx)
        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }

    private val Green = Color.parseColor("#7CFC9A")
    private val Yellow = Color.parseColor("#FFD166")
    private val Red = Color.parseColor("#FF6B6B")
    private val Gray = Color.parseColor("#8B949E")
    private val Light = Color.parseColor("#E6EDF3")

    private lateinit var wm: WindowManager
    private lateinit var s: Settings
    private lateinit var sampler: Sampler
    private var root: LinearLayout? = null
    private var lines: List<TextView> = emptyList()
    private lateinit var lp: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var acquired = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        s = Settings.get(this)
        sampler = Sampler.get(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startAsForeground()

        if (!canDraw(this)) { stopSelf(); return }

        try {
            buildView()
        } catch (e: Exception) {
            stopSelf()
            return
        }

        sampler.acquire()
        acquired = true
        scope.launch { sampler.snap.collect { render(it) } }

        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            applySettings()
            render(sampler.snap.value)
            updateNotification()
        }
        prefListener = l
        s.prefs.registerOnSharedPreferenceChangeListener(l)

        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_CLICKTHROUGH -> s.clickThrough = !s.clickThrough
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        prefListener?.let { s.prefs.unregisterOnSharedPreferenceChangeListener(it) }
        scope.cancel()
        try { root?.let { wm.removeView(it) } } catch (_: Exception) {}
        root = null
        if (acquired) { sampler.release(); acquired = false }
        super.onDestroy()
    }

    // --- Bildirim (ön plan servisi) ---
    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Gösterge", NotificationManager.IMPORTANCE_LOW)
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code,
            Intent(this, OverlayService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ctLabel = if (s.clickThrough) "Tıklamayı geri al" else "Tıklamayı geçir"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("FPS Overlay çalışıyor")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, ctLabel, action(ACTION_CLICKTHROUGH, 1)).build())
            .addAction(Notification.Action.Builder(null, "Durdur", action(ACTION_STOP, 2)).build())
            .build()
    }

    private fun startAsForeground() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification())
    }

    // --- Görünüm ---
    private fun buildView() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (5 * d).toInt(), (8 * d).toInt(), (5 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                cornerRadius = 10 * d
            }
        }
        val tvs = List(5) {
            TextView(this).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Light)
                textSize = 12f
                setSingleLine(true)
            }.also { box.addView(it) }
        }
        root = box
        lines = tvs

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        applyLayout()
        box.setOnTouchListener(dragListener())
        wm.addView(box, lp)
    }

    private fun applyLayout() {
        val r = root ?: return
        val d = resources.displayMetrics.density
        r.alpha = s.opacity
        lines.forEach { it.textSize = 12f * s.scale }

        if (s.position == "custom") {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = s.customX
            lp.y = s.customY
        } else {
            val code = s.position.padEnd(2, 'C')
            val v = code[0]
            val h = code[1]
            val m = (8 * d).toInt()
            lp.gravity =
                (when (v) { 'T' -> Gravity.TOP; 'B' -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }) or
                (when (h) { 'L' -> Gravity.START; 'R' -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL })
            lp.x = if (h == 'C') 0 else m
            lp.y = if (v == 'M') 0 else m
        }

        lp.flags = if (s.clickThrough)
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
    }

    private fun applySettings() {
        val r = root ?: return
        applyLayout()
        try { wm.updateViewLayout(r, lp) } catch (_: Exception) {}
    }

    private fun dragListener() = object : View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    ox = loc[0]; oy = loc[1]; sx = e.rawX; sy = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (!moved && abs(dx) + abs(dy) < 8) return true
                    moved = true
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.x = ox + dx
                    lp.y = oy + dy
                    try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        s.setCustomPosition(lp.x, lp.y)
                    }
                }
            }
            return true
        }
    }

    // --- İçerik ---
    private fun n(v: Double, d: Int) = Fmt.n(v, d)
    private fun pct(v: Double?) = if (v != null) n(v, 0) + "%" else "--"
    private fun temp(v: Double?) = if (v != null) n(v, 0) + "°C" else "--"

    private fun render(sn: Snap) {
        if (root == null || lines.size < 5) return
        val v = sn.values

        // FPS satırı
        val fp = ArrayList<String>()
        var fpsColor = Gray
        if (s.isOn("fps") || s.isOn("frametime") || s.isOn("fps1")) {
            if (!sn.fpsAvailable) {
                fp.add("FPS yok")
            } else {
                val f = v["fps"]
                if (s.isOn("fps")) fp.add(if (f != null) "FPS " + n(f, 0) else "FPS --")
                if (s.isOn("frametime")) fp.add(v["frametime"]?.let { n(it, 1) + "ms" } ?: "--ms")
                if (s.isOn("fps1")) fp.add(v["fps1"]?.let { "1% " + n(it, 0) } ?: "1% --")
                if (f != null) fpsColor = if (f >= 55) Green else if (f >= 30) Yellow else Red
            }
        }

        val cp = ArrayList<String>()
        if (s.isOn("cpu_load")) cp.add(pct(v["cpu_load"]))
        if (s.isOn("cpu_temp")) cp.add(temp(v["cpu_temp"]))
        if (s.isOn("cpu_clock")) cp.add(v["cpu_clock"]?.let { n(it, 1) + "GHz" } ?: "--")

        val gp = ArrayList<String>()
        if (s.isOn("gpu_load")) gp.add(pct(v["gpu_load"]))
        if (s.isOn("gpu_temp")) gp.add(temp(v["gpu_temp"]))

        var ram: String? = null
        if (s.isOn("ram_used")) {
            val used = v["ram_used"]
            val total = sn.ramTotalGb
            ram = if (used != null)
                "RAM " + n(used, 1) + (if (total != null) "/" + n(total, 0) else "") + "G"
            else "RAM --"
        }

        val bp = ArrayList<String>()
        if (s.isOn("bat_level")) bp.add(pct(v["bat_level"]))
        if (s.isOn("bat_temp")) bp.add(temp(v["bat_temp"]))

        setLine(lines[0], if (fp.isNotEmpty()) fp.joinToString("  ") else null)
        lines[0].setTextColor(fpsColor)
        setLine(lines[1], if (cp.isNotEmpty()) "CPU " + cp.joinToString("  ") else null)
        setLine(lines[2], if (gp.isNotEmpty()) "GPU " + gp.joinToString("  ") else null)
        setLine(lines[3], ram)
        setLine(lines[4], if (bp.isNotEmpty()) "PİL " + bp.joinToString("  ") else null)

        if (fp.isEmpty() && cp.isEmpty() && gp.isEmpty() && ram == null && bp.isEmpty()) {
            setLine(lines[0], "Değer seçilmedi")
            lines[0].setTextColor(Gray)
        }
    }

    private fun setLine(tv: TextView, text: String?) {
        tv.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) tv.text = text
    }
}
