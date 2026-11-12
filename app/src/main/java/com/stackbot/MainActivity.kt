package com.stackbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
        root.addView(Button(this).apply {
            text = "2. Allow display over other apps"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(TextView(this).apply { text = "Tap lead (ms) - raise if taps land late, lower if early" })
        lead = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("60") }
        root.addView(lead)
        root.addView(TextView(this).apply { text = "Stop after N auto taps" })
        maxTaps = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("55") }
        root.addView(maxTaps)
        root.addView(Button(this).apply {
            text = "3. Show floating button"
            setOnClickListener { start() }
        })
        root.addView(TextView(this).apply {
            text = "Open the game and start a round (first block moving). Then tap the floating GO button: the bot reads the block's movement and speed and taps when it lines up with the stack. Tap again to pause. Drag the button to move it. Stop the service from its notification."
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        status.text = "Accessibility: " + (if (TapService.instance != null) "ON" else "OFF") +
            "   |   Overlay: " + (if (Settings.canDrawOverlays(this)) "ON" else "OFF")
    }

    private fun start() {
        if (TapService.instance == null || !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable steps 1 and 2 first", Toast.LENGTH_LONG).show()
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
