package com.stackbot

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class BotService : Service() {
    private class Sample(val t: Long, val x: Float, val y: Float)

    private val TAG = "StackBot"
    private val SCALE = 4
    private val MIN_PIX = 30          // changed pixels needed to say "a block is moving"
    private val DIFF = 45             // per-pixel colour change threshold (sum of RGB)
    private val WHITE_MIN = 100       // white pixels needed to say "PERFECT!" is showing
    private val STEP = 3f             // search step (scaled px) when the centre guess misses

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private lateinit var handler: Handler
    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var btn: TextView? = null
    @Volatile private var armed = false

    private var w = 0; private var h = 0; private var sw = 0; private var sh = 0
    private var prev: IntArray? = null
    private lateinit var hx: IntArray
    private lateinit var hy: IntArray

    private var leadMs = 60f
    private var maxTaps = 55
    private val hist = ArrayDeque<Sample>()
    // Target x per (axis class, direction): index = cls*2 + dir
    private val tx = FloatArray(4)
    private val base = FloatArray(4)
    private val locked = BooleanArray(4)
    private val misses = IntArray(4)
    private var lastSlot = -1
    private var idleFrames = 0
    private var perfectPrev = false
    private var lastPerfect = 0L
    private var lastTap = 0L
    private var awaiting = 0L
    private var taps = 0; private var perfects = 0
    private var lastNotify = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        leadMs = intent.getFloatExtra("lead", 60f)
        maxTaps = intent.getIntExtra("max", 55)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bot", "StackBot", NotificationManager.IMPORTANCE_LOW))
        startFg(notif("Ready - tap the floating button in the game"))

        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")!!
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(intent.getIntExtra("code", Activity.RESULT_OK), data)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels; sh = dm.heightPixels
        w = sw / SCALE; h = sh / SCALE
        hx = IntArray(w); hy = IntArray(h)
        for (i in 0..3) { tx[i] = w / 2f; base[i] = w / 2f }

        thread = HandlerThread("bot").also { it.start() }
        handler = Handler(thread!!.looper)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd = projection!!.createVirtualDisplay("bot", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        addOverlay(dm.density)
        handler.post { step() }
        return START_NOT_STICKY
    }

    // ---------- floating button ----------
    private fun addOverlay(d: Float) {
        val size = (60 * d).toInt()
        val tv = TextView(this).apply {
            text = "GO"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#7E57C2"))
                setStroke((2 * d).toInt(), Color.WHITE)
            }
            alpha = 0.92f
        }
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (12 * d).toInt(); lp.y = (sh * 0.12).toInt()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { lp.x = (ox + dx).toInt(); lp.y = (oy + dy).toInt(); wm?.updateViewLayout(tv, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) setArmed(!armed); true }
                else -> false
            }
        }
        btn = tv
        wm!!.addView(tv, lp)
    }

    private fun setArmed(on: Boolean) {   // call on UI thread
        armed = on
        btn?.text = if (on) "STOP" else "GO"
        (btn?.background as? GradientDrawable)?.setColor(Color.parseColor(if (on) "#2E7D32" else "#7E57C2"))
        handler.post {
            hist.clear(); lastTap = 0L; awaiting = 0L
            if (on) { taps = 0; perfects = 0 }
        }
    }

    // ---------- foreground notification ----------
    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun notif(text: String): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, BotService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "bot")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("StackBot").setContentText(text)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    private fun status(text: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastNotify < 400) return
        lastNotify = now
        getSystemService(NotificationManager::class.java).notify(1, notif(text))
    }

    // ---------- capture loop ----------
    private fun step() {
        val rd = reader ?: return
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        var cur: IntArray? = null
        if (img != null) {
            val c = IntArray(w * h)
            val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; val ps = pl.pixelStride
            for (y in 0 until h) {
                var i = y * rs
                for (x in 0 until w) {
                    c[y * w + x] = ((buf.get(i).toInt() and 255) shl 16) or
                        ((buf.get(i + 1).toInt() and 255) shl 8) or (buf.get(i + 2).toInt() and 255)
                    i += ps
                }
            }
            img.close()
            cur = c
        }
        val frame = cur ?: prev
        if (frame != null) analyze(frame)
        handler.postDelayed({ step() }, 8)
    }

    private fun median(hh: IntArray, total: Int): Float {
        var c = 0
        for (i in hh.indices) { c += hh[i]; if (c * 2 >= total) return i.toFloat() }
        return 0f
    }

    // ---------- feedback: find / hold the aligned position ----------
    private fun applySearch(s: Int) {
        val k = misses[s]
        val off = ((k + 1) / 2) * STEP * (if (k % 2 == 1) 1f else -1f)
        tx[s] = base[s] + off
    }

    private fun onMiss() {
        val s = lastSlot; if (s < 0) return
        if (locked[s]) { locked[s] = false; base[s] = tx[s]; misses[s] = 0 }
        misses[s]++
        applySearch(s)
        Log.i(TAG, "miss slot=$s -> tx=${tx[s]}")
    }

    private fun onPerfect() {
        val s = lastSlot; if (s < 0) return
        locked[s] = true; misses[s] = 0; base[s] = tx[s]
        for (i in 0..3) if (!locked[i]) { tx[i] = tx[s]; base[i] = tx[s]; misses[i] = 0 }
        Log.i(TAG, "PERFECT slot=$s locked tx=${tx[s]}")
    }

    private fun analyze(cur: IntArray) {
        val p = prev
        prev = cur
        if (p == null) return
        val now = SystemClock.uptimeMillis()

        // 1) where is the moving block? (median of changed pixels in the play area)
        val y0 = (h * 0.30).toInt(); val y1 = (h * 0.84).toInt()
        java.util.Arrays.fill(hx, 0); java.util.Arrays.fill(hy, 0)
        var cnt = 0
        if (cur !== p) {
            for (y in y0 until y1) for (x in 0 until w) {
                val a = cur[y * w + x]; val b = p[y * w + x]
                if (a == b) continue
                val d = abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
                    abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))
                if (d > DIFF) { hx[x]++; hy[y]++; cnt++ }
            }
        }

        // 2) is "PERFECT!" showing? (white text, upper middle)
        var white = 0
        for (y in (h * 0.38).toInt() until (h * 0.46).toInt()) for (x in (w * 0.2).toInt() until (w * 0.8).toInt()) {
            val a = cur[y * w + x]
            if (((a shr 16) and 255) >= 250 && ((a shr 8) and 255) >= 250 && (a and 255) >= 250) white++
        }
        val perfectNow = white >= WHITE_MIN
        val perfectEdge = perfectNow && !perfectPrev && now - lastPerfect > 600
        perfectPrev = perfectNow
        if (perfectEdge) lastPerfect = now

        if (awaiting != 0L) {
            if (perfectEdge && now - awaiting < 900) { awaiting = 0L; perfects++; onPerfect() }
            else if (now - awaiting > 800) { awaiting = 0L; onMiss() }
        }

        // 3) track position and velocity of the moving block
        var sample: Sample? = null
        var vx = 0f; var vy = 0f
        if (cnt >= MIN_PIX) {
            idleFrames = 0
            sample = Sample(now, median(hx, cnt), median(hy, cnt))
            hist.addLast(sample)
            while (hist.isNotEmpty() && now - hist.first().t > 160) hist.removeFirst()
            if (hist.size >= 3) {
                val a = hist.first(); val b = hist.last(); val dt = (b.t - a.t).toFloat()
                if (dt >= 40f) { vx = (b.x - a.x) / dt; vy = (b.y - a.y) / dt }
            }
        } else {
            idleFrames++
            if (idleFrames >= 3) hist.clear()
        }

        // 4) fire when the block will reach the aligned x within the lead time
        if (armed && sample != null && abs(vx) > 0.02f && now - lastTap > 700 && taps < maxTaps) {
            val cls = if (vx * vy > 0) 0 else 1
            val s = cls * 2 + (if (vx > 0) 1 else 0)
            val dx = tx[s] - sample.x
            if (dx * vx > 0f && dx / vx <= leadMs) {
                val svc = TapService.instance
                if (svc == null) { stopSelf(); return }
                svc.tap(sw * 0.5f, sh * 0.62f)
                lastTap = now; awaiting = now; lastSlot = s; taps++
                hist.clear()
                Log.i(TAG, "tap #$taps slot=$s dx=$dx vx=$vx")
                if (taps >= maxTaps) ui.post { setArmed(false) }
            }
        }
        status(if (armed) "RUNNING  taps $taps  perfect $perfects" else "Ready - tap the floating button in the game")
    }

    override fun onDestroy() {
        try { btn?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        thread?.quitSafely()
        super.onDestroy()
    }
}
