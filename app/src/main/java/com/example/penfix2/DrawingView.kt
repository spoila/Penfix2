package com.example.penfix2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/**
 * Full-screen transparent layer. Only the pen draws; fingers are swallowed.
 * Ghost-ink fix: ink starts only when pressure >= minPressure and stops when
 * pressure falls below half of that, so a hovering tip (pressure ~0) writes nothing.
 */
class DrawingView(context: Context) : View(context) {

    class Pt(val x: Float, val y: Float, val p: Float)
    class Stroke(val pts: List<Pt>, val color: Int, val baseWidth: Float)

    // ---- settings, changed from the toolbar ----
    var writingEnabled = false
    var eraserMode = false
    var color = Color.BLUE
    var baseWidth = 9f
    var minPressure = 0.04f
    var useDistanceFilter = false
    var trailGuard = true
    var debugListener: ((String) -> Unit)? = null

    private val keepPressure: Float get() = minPressure * 0.5f
    // a stroke only becomes real ink once pressure reaches this ("solid touch")
    private val confirmPressure: Float get() = (minPressure * 2.5f).coerceAtMost(0.3f)

    // ---- history (every change is a new immutable list; undo/redo move the index) ----
    private val history = ArrayList<List<Stroke>>().apply { add(emptyList()) }
    private var hIndex = 0
    private var eraseWorking: List<Stroke>? = null
    private fun shown(): List<Stroke> = eraseWorking ?: history[hIndex]

    // ---- drawing cache ----
    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // ---- gesture state ----
    private var contact = false
    private var erasing = false
    private var current: ArrayList<Pt>? = null
    private var confirmed = false
    private val pending = ArrayList<Pt>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        cache?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cache = bmp
        cacheCanvas = Canvas(bmp)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun rebuild() {
        val c = cacheCanvas ?: return
        cache?.eraseColor(Color.TRANSPARENT)
        for (s in shown()) drawStroke(c, s)
        invalidate()
    }

    private fun drawSegment(c: Canvas, a: Pt, b: Pt, col: Int, base: Float) {
        paint.color = col
        val p = ((a.p + b.p) / 2f).coerceIn(0f, 1f)
        paint.strokeWidth = base * (0.3f + 1.4f * p)
        c.drawLine(a.x, a.y, b.x, b.y, paint)
    }

    private fun drawStroke(c: Canvas, s: Stroke) {
        if (s.pts.size == 1) {
            drawSegment(c, s.pts[0], s.pts[0], s.color, s.baseWidth)
        } else {
            for (i in 1 until s.pts.size) drawSegment(c, s.pts[i - 1], s.pts[i], s.color, s.baseWidth)
        }
    }

    // ---- touch handling ----
    private fun stylusIndex(e: MotionEvent): Int {
        for (i in 0 until e.pointerCount) {
            val t = e.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) return i
        }
        return -1
    }

    private fun toolName(t: Int): String = when (t) {
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        else -> "OTHER"
    }

    private fun effectivePressure(p: Float, dist: Float): Float =
        if (useDistanceFilter && dist > 0.01f) 0f else p

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!writingEnabled) return false
        val i = stylusIndex(e)
        val action = e.actionMasked
        if (i < 0) {
            // finger or palm: swallow it so it never reaches the app underneath
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) finishGesture()
            return true
        }
        val eraserTool = e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    feed(
                        e.getHistoricalX(i, h),
                        e.getHistoricalY(i, h),
                        effectivePressure(
                            e.getHistoricalPressure(i, h),
                            e.getHistoricalAxisValue(MotionEvent.AXIS_DISTANCE, i, h)
                        ),
                        eraserTool
                    )
                }
                feed(
                    e.getX(i),
                    e.getY(i),
                    effectivePressure(e.getPressure(i), e.getAxisValue(MotionEvent.AXIS_DISTANCE, i)),
                    eraserTool
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishGesture()
            MotionEvent.ACTION_POINTER_UP -> if (e.actionIndex == i) finishGesture()
        }
        report(e, i)
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (writingEnabled && e.pointerCount > 0) report(e, 0)
        return false
    }

    private fun report(e: MotionEvent, i: Int) {
        val l = debugListener ?: return
        l(
            String.format(
                Locale.US, "%s %s p=%.3f dist=%.2f ink=%s",
                MotionEvent.actionToString(e.actionMasked),
                toolName(e.getToolType(i)),
                e.getPressure(i),
                e.getAxisValue(MotionEvent.AXIS_DISTANCE, i),
                if (contact) "yes" else "no"
            )
        )
    }

    private fun feed(x: Float, y: Float, p: Float, eraserTool: Boolean) {
        val pt = Pt(x, y, p)
        if (!contact) {
            if (p < minPressure) return          // hovering or too light: no ink
            contact = true
            erasing = eraserMode || eraserTool
            pending.clear()
            pending.add(pt)
            confirmed = !trailGuard || p >= confirmPressure
            if (confirmed) startFrom(pending)
            return
        }
        if (p < keepPressure) {                  // pen lifted (even if it still hovers)
            finishGesture()
            return
        }
        if (!confirmed) {
            // Possible air trail: hold the points back until the pen presses solidly,
            // then keep only the points right around the touch-down spot.
            pending.add(pt)
            if (p >= confirmPressure) {
                confirmed = true
                startFrom(trimTail(pending))
            } else if (pending.size > 400) {
                pending.removeAt(0)
            }
            return
        }
        if (erasing) {
            eraseAt(x, y)
            return
        }
        val cur = current ?: return
        cacheCanvas?.let { drawSegment(it, cur[cur.size - 1], pt, color, baseWidth) }
        cur.add(pt)
        invalidate()
    }

    private fun trimTail(pts: List<Pt>): List<Pt> {
        val last = pts[pts.size - 1]
        val lim = 24f * 24f
        var start = pts.size - 1
        while (start > 0) {
            val q = pts[start - 1]
            val dx = q.x - last.x
            val dy = q.y - last.y
            if (dx * dx + dy * dy > lim) break
            start--
        }
        return ArrayList(pts.subList(start, pts.size))
    }

    private fun startFrom(pts: List<Pt>) {
        if (erasing) {
            for (q in pts) eraseAt(q.x, q.y)
            return
        }
        val cur = ArrayList(pts)
        current = cur
        val c = cacheCanvas
        if (c != null) {
            if (cur.size == 1) {
                drawSegment(c, cur[0], cur[0], color, baseWidth)
            } else {
                for (i in 1 until cur.size) drawSegment(c, cur[i - 1], cur[i], color, baseWidth)
            }
        }
        invalidate()
    }

    private fun finishGesture() {
        if (contact && confirmed) {
            if (erasing) {
                eraseWorking?.let { push(it) }
                eraseWorking = null
            } else {
                current?.let {
                    if (it.isNotEmpty()) push(history[hIndex] + Stroke(ArrayList(it), color, baseWidth))
                }
            }
        }
        current = null
        pending.clear()
        confirmed = false
        contact = false
        erasing = false
    }

    private fun eraseAt(x: Float, y: Float) {
        val r = 28f
        val base = shown()
        val kept = base.filter { s ->
            s.pts.none { pt ->
                val dx = pt.x - x
                val dy = pt.y - y
                dx * dx + dy * dy <= r * r
            }
        }
        if (kept.size != base.size) {
            eraseWorking = kept
            rebuild()
        }
    }

    private fun push(list: List<Stroke>) {
        while (history.size > hIndex + 1) history.removeAt(history.size - 1)
        history.add(list)
        hIndex++
        if (history.size > 100) {
            history.removeAt(0)
            hIndex--
        }
    }

    // ---- actions used by the toolbar ----
    fun undo() {
        finishGesture()
        if (hIndex > 0) {
            hIndex--
            rebuild()
        }
    }

    fun redo() {
        finishGesture()
        if (hIndex < history.size - 1) {
            hIndex++
            rebuild()
        }
    }

    fun clearAll() {
        finishGesture()
        if (history[hIndex].isNotEmpty()) {
            push(emptyList())
            rebuild()
        }
    }
}
