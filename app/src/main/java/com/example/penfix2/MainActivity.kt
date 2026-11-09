package com.example.penfix2

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
        })
        status = TextView(this).apply {
            textSize = 17f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        col.addView(status)
        col.addView(Button(this).apply {
            text = "1. Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(Button(this).apply {
            text = "2. Open app info (Allow restricted settings)"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.help)
            textSize = 15f
            setPadding(0, pad, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (PenOverlayService.instance != null)
            "Service is RUNNING - the toolbar should float on your screen."
        else
            "Service is NOT running yet."
    }
}
