package com.xiaolei.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 大号健康度环形图（颜色跟随主题） */
class RingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pct: Double? = null
    private var sub = ""

    fun set(pct: Double?, sub: String) {
        this.pct = pct
        this.sub = sub
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val pal = AppTheme.palette()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10 || h < 10) return
        val cx = w / 2
        val cy = h / 2
        val r = minOf(w, h) / 2 - 10f

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(pal.track); strokeWidth = 11f; style = Paint.Style.STROKE
        }
        c.drawCircle(cx, cy, r - 12f, bg)

        val p = pct
        if (p != null) {
            val color = when {
                p >= 88 -> Color.parseColor("#2EC56D")
                p >= 80 -> Color.parseColor("#FFB020")
                else -> Color.parseColor("#FF4D4F")
            }
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; strokeWidth = 11f; style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            val sweep = (kotlin.math.min(100.0, p) / 100.0 * 360f).toFloat()
            val rect = RectF(cx - (r - 12f), cy - (r - 12f), cx + (r - 12f), cy + (r - 12f))
            c.drawArc(rect, -90f, sweep, false, arc)
        }

        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(pal.text); textAlign = Paint.Align.CENTER; textSize = 38f
            isFakeBoldText = true
        }
        val mid = StringBuilder()
        mid.append(if (p == null) "—" else "%.1f".format(p)).append('%')
        c.drawText(mid.toString(), cx, cy + 2f, big)

        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(pal.text2); textAlign = Paint.Align.CENTER; textSize = 11f
        }
        c.drawText(sub, cx, cy + 22f, small)
        val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(pal.text3); textAlign = Paint.Align.CENTER; textSize = 10f
        }
        c.drawText("电池健康度", cx, cy - 28f, unit)
    }
}
