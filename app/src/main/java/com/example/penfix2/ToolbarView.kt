package com.example.penfix2

import android.graphics.Color
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class ToolbarView(
    private val service: PenOverlayService,
    private val draw: DrawingView,
    private val wm: WindowManager,
    private val params: WindowManager.LayoutParams
) : LinearLayout(service) {

    private val colors = intArrayOf(
        Color.BLUE, Color.RED, Color.BLACK, Color.rgb(0, 150, 0), Color.rgb(255, 140, 0)
    )
    private val widths = floatArrayOf(5f, 9f, 16f)
    private var colorIndex = 0
    private var widthIndex = 1
    private var writing = false
    private var collapsed = false
    private var clearArmed = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String): Button = Button(service).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private val handle = TextView(service).apply {
        text = "\u2261"
        textSize = 24f
        setTextColor(Color.WHITE)
        setPadding(dp(10), 0, dp(10), 0)
    }
    private val writeBtn = btn("Write: OFF")
    private val toolBtn = btn("Pen")
    private val colorBtn = btn("\u25CF")
    private val sizeBtn = btn("Size 2")
    private val undoBtn = btn("Undo")
    private val redoBtn = btn("Redo")
    private val clearBtn = btn("Clear")
    private val gearBtn = btn("\u2699")
    private val hideBtn = btn("\u25BE")

    private val panel = LinearLayout(service).apply {
        orientation = VERTICAL
        visibility = View.GONE
        setPadding(dp(8), dp(4), dp(8), dp(8))
    }
    private val pressureLabel = TextView(service).apply { setTextColor(Color.WHITE) }
    private val pressureBar = SeekBar(service).apply { max = 40; progress = 4 }
    private val distBtn = btn("Hover-distance filter: OFF")
    private val guardBtn = btn("Trail guard: ON")
    private val debugText = TextView(service).apply {
        setTextColor(Color.rgb(160, 255, 160))
        typeface = Typeface.MONOSPACE
        textSize = 12f
        text = "(hover or write to see values)"
    }
    private val quitBtn = btn("Quit Pen Fix")

    private val extras: List<View> =
        listOf(toolBtn, colorBtn, sizeBtn, undoBtn, redoBtn, clearBtn, gearBtn)

    init {
        orientation = VERTICAL
        setBackgroundColor(0xE6202020.toInt())
        setPadding(dp(4), dp(4), dp(4), dp(4))

        val row = LinearLayout(service).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(handle)
        row.addView(writeBtn)
        for (b in extras) row.addView(b)
        row.addView(hideBtn)
        addView(row)

        panel.addView(pressureLabel)
        panel.addView(pressureBar)
        panel.addView(distBtn)
        panel.addView(guardBtn)
        panel.addView(debugText)
        panel.addView(quitBtn)
        addView(panel)

        colorBtn.setTextColor(colors[colorIndex])
        updatePressureLabel()
        wireUp()
    }

    private fun wireUp() {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(this, params)
                }
            }
            true
        }

        writeBtn.setOnClickListener {
            writing = !writing
            service.setWriting(writing)
            writeBtn.text = if (writing) "Write: ON" else "Write: OFF"
            writeBtn.setTextColor(if (writing) Color.rgb(120, 255, 120) else Color.WHITE)
        }
        toolBtn.setOnClickListener {
            draw.eraserMode = !draw.eraserMode
            toolBtn.text = if (draw.eraserMode) "Eraser" else "Pen"
        }
        colorBtn.setOnClickListener {
            colorIndex = (colorIndex + 1) % colors.size
            draw.color = colors[colorIndex]
            colorBtn.setTextColor(colors[colorIndex])
        }
        sizeBtn.setOnClickListener {
            widthIndex = (widthIndex + 1) % widths.size
            draw.baseWidth = widths[widthIndex]
            sizeBtn.text = "Size " + (widthIndex + 1)
        }
        undoBtn.setOnClickListener { draw.undo() }
        redoBtn.setOnClickListener { draw.redo() }
        clearBtn.setOnClickListener {
            if (clearArmed) {
                draw.clearAll()
                clearArmed = false
                clearBtn.text = "Clear"
            } else {
                clearArmed = true
                clearBtn.text = "Sure?"
                postDelayed({
                    clearArmed = false
                    clearBtn.text = "Clear"
                }, 3000)
            }
        }
        gearBtn.setOnClickListener {
            val show = panel.visibility != View.VISIBLE
            panel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                draw.debugListener = { s: String -> debugText.text = s }
            } else {
                draw.debugListener = null
            }
        }
        hideBtn.setOnClickListener {
            collapsed = !collapsed
            for (b in extras) b.visibility = if (collapsed) View.GONE else View.VISIBLE
            if (collapsed) {
                panel.visibility = View.GONE
                draw.debugListener = null
            }
            hideBtn.text = if (collapsed) "\u25B8" else "\u25BE"
        }
        pressureBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                draw.minPressure = progress / 100f
                updatePressureLabel()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        distBtn.setOnClickListener {
            draw.useDistanceFilter = !draw.useDistanceFilter
            distBtn.text = "Hover-distance filter: " + if (draw.useDistanceFilter) "ON" else "OFF"
        }
        guardBtn.setOnClickListener {
            draw.trailGuard = !draw.trailGuard
            guardBtn.text = "Trail guard: " + if (draw.trailGuard) "ON" else "OFF"
        }
        quitBtn.setOnClickListener { service.disableSelf() }
    }

    private fun updatePressureLabel() {
        val solid = minOf(pressureBar.progress * 2.5f, 30f).toInt()
        pressureLabel.text = "Min pressure to ink: " + pressureBar.progress + "% (solid touch: " + solid + "%)"
    }
}
