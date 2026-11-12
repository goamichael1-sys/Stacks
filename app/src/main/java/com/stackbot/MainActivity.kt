package com.stackbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var lead: EditText
    private lateinit var maxTaps: EditText
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "1. Enable accessibility (StackBot)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(TextView(this).apply { text = "Tap lead (ms) - raise if taps land late, lower if early" })
        lead = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("60") }
        root.addView(lead)
        root.addView(TextView(this).apply { text = "Stop after N auto taps" })
        maxTaps = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("55") }
        root.addView(maxTaps)
        root.addView(Button(this).apply {
            text = "2. Start bot, then switch to the game"
            setOnClickListener { start() }
        })
        root.addView(TextView(this).apply {
            text = "Play ~8 blocks yourself with perfect timing. The bot learns the target from your PERFECT taps, then takes over. Stop it from the notification."
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (TapService.instance != null) "Accessibility: ON" else "Accessibility: OFF"
    }

    private fun start() {
        if (TapService.instance == null) {
            Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK && data != null) {
            val i = Intent(this, BotService::class.java)
                .putExtra("code", res).putExtra("data", data)
                .putExtra("lead", lead.text.toString().toFloatOrNull() ?: 60f)
                .putExtra("max", maxTaps.text.toString().toIntOrNull() ?: 55)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
