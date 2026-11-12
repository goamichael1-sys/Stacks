package com.stackbot

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot

class BotService : Service() {
    private class Sample(val t: Long, val x: Float, val y: Float, var cls: Int = -1)
    private class Pt(val x: Float, val y: Float)

    private val TAG = "StackBot"
    private val SCALE = 4
    private val MIN_PIX = 30          // changed pixels needed to say "a block is moving"
    private val DIFF = 45             // per-pixel colour change threshold (sum of RGB)
    private val WHITE_MIN = 100       // white pixels needed to say "PERFECT!" is showing

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private lateinit var handler: Handler
    private var w = 0; private var h = 0; private var sw = 0; private var sh = 0
    private var prev: IntArray? = null
    private lateinit var hx: IntArray
    private lateinit var hy: IntArray

    private var leadMs = 60f
    private var maxTaps = 55
    private val hist = ArrayDeque<Sample>()
    private val recent = ArrayDeque<Sample>()
    private val learn = arrayOf(ArrayList<Pt>(), ArrayList<Pt>())
    private var targets = arrayOfNulls<Pt>(2)
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
        startFg(notif("Starting..."))

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

        thread = HandlerThread("bot").also { it.start() }
        handler = Handler(thread!!.looper)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd = projection!!.createVirtualDisplay("bot", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        handler.post { step() }
        return START_NOT_STICKY
    }

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

    private fun analyze(cur: IntArray) {
        val p = prev
        prev = cur
        if (p == null) return
        val now = SystemClock.uptimeMillis()

        // 1) motion centroid in the play area
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

        // 2) is "PERFECT!" on screen? (white text, upper middle)
        var white = 0
        for (y in (h * 0.38).toInt() until (h * 0.46).toInt()) for (x in (w * 0.2).toInt() until (w * 0.8).toInt()) {
            val a = cur[y * w + x]
            if (((a shr 16) and 255) >= 250 && ((a shr 8) and 255) >= 250 && (a and 255) >= 250) white++
        }
        val perfectNow = white >= WHITE_MIN
        val perfectEdge = perfectNow && !perfectPrev && now - lastPerfect > 600
        perfectPrev = perfectNow
        if (perfectEdge) lastPerfect = now

        var sample: Sample? = null
        var ux = 0f; var uy = 0f; var speed = 0f
        if (cnt >= MIN_PIX) {
            idleFrames = 0
            sample = Sample(now, median(hx, cnt), median(hy, cnt))
            hist.addLast(sample)
            while (hist.isNotEmpty() && now - hist.first().t > 160) hist.removeFirst()
            if (hist.size >= 3) {
                val a = hist.first(); val b = hist.last(); val dt = (b.t - a.t).toFloat()
                if (dt >= 40f) {
                    val vx = (b.x - a.x) / dt; val vy = (b.y - a.y) / dt
                    speed = hypot(vx, vy)
                    if (speed > 0.03f) {
                        ux = vx / speed; uy = vy / speed
                        sample.cls = if (vx * vy > 0) 0 else 1
                    } else speed = 0f
                }
            }
            recent.addLast(sample)
            while (recent.isNotEmpty() && now - recent.first().t > 700) recent.removeFirst()
        } else {
            idleFrames++
            if (idleFrames >= 3) hist.clear()
        }

        val auto = targets[0] != null && targets[1] != null

        // 3) on PERFECT: learn (manual taps) or score (bot taps)
        if (perfectEdge) {
            if (awaiting != 0L && now - awaiting < 900) { perfects++; awaiting = 0L }
            else if (!auto) {
                val s = recent.lastOrNull { it.t <= now - 60 && it.cls >= 0 }
                if (s != null) {
                    learn[s.cls].add(Pt(s.x, s.y))
                    Log.i(TAG, "learned cls=${s.cls} at ${s.x},${s.y} n=${learn[s.cls].size}")
                    for (c in 0..1) if (targets[c] == null && learn[c].size >= 3) {
                        val l = learn[c].takeLast(5)
                        targets[c] = Pt(l.map { it.x }.sorted()[l.size / 2], l.map { it.y }.sorted()[l.size / 2])
                        Log.i(TAG, "target[$c] = ${targets[c]!!.x},${targets[c]!!.y}")
                    }
                }
            }
        }

        // 4) auto mode: fire when the block is about to reach the target
        if (auto) {
            if (sample != null && speed > 0f && sample.cls >= 0 && now - lastTap > 700) {
                val tg = targets[sample.cls]!!
                val s = (sample.x - tg.x) * ux + (sample.y - tg.y) * uy
                if (s < 0f && s + speed * leadMs >= 0f) {
                    val svc = TapService.instance
                    if (svc == null) { stopSelf(); return }
                    svc.tap(sw * 0.5f, sh * 0.62f)
                    lastTap = now; awaiting = now; taps++
                    hist.clear()
                    Log.i(TAG, "tap #$taps s=$s speed=$speed")
                    if (taps >= maxTaps) { stopSelf(); return }
                }
            }
            status("AUTO taps $taps  perfect $perfects")
        } else {
            status("Learning: ${learn[0].size}/3 and ${learn[1].size}/3 - play perfect blocks")
        }
    }

    override fun onDestroy() {
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        thread?.quitSafely()
        super.onDestroy()
    }
}
