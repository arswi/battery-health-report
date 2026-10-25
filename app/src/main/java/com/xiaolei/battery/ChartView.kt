package com.xiaolei.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 健康度变化曲线（纯 Canvas 手绘）。
 *
 * 按需求做得直白：
 *  - 不做拟合、不给预测，就是报告点连线；
 *  - 线的颜色由"整体变化幅度"决定：
 *      |变化| < 1%            → 绿
 *      下降 1% ~ 5%          → 橙
 *      下降 > 5%             → 红
 * onDraw 整体兜 try/catch：绘制期抛异常会被 ViewRootImpl 反复重放，宁可少画也不能崩。
 */
class HealthChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onPointSelected: ((ReportRecord?) -> Unit)? = null

    private val padL: Float get() = PAD_L_DP * resources.displayMetrics.density
    private val padR: Float get() = PAD_R_DP * resources.displayMetrics.density
    private val padT: Float get() = PAD_T_DP * resources.displayMetrics.density
    private val padB: Float get() = PAD_B_DP * resources.displayMetrics.density

    private var points: List<ReportRecord> = emptyList()
    private var selected: Int = -1
    private var lineColor: Int = Color.parseColor("#2EC56D")

    companion object {
        // 留白按屏幕密度算。Y 轴标签最宽 "100%"（10sp，放大字体也够）：
        // 36dp 左留白足够；右留白 6dp 只需容纳末端点（半径+描边约 4.6dp），
        // 让折线尽量占满横向空间。底边要放下日期标签。
        private const val PAD_L_DP = 36f
        private const val PAD_R_DP = 6f
        private const val PAD_T_DP = 16f
        private const val PAD_B_DP = 34f

        const val GREEN = "#2EC56D"
        const val ORANGE = "#FF9F1C"
        const val RED = "#FF4D4F"

        /** 整体变化 → 曲线颜色；返回 null 表示点太少或数据不足 */
        fun colorForChange(pts: List<ReportRecord>): Int? {
            val hs = pts.mapNotNull { it.health }
            if (hs.size < 2) return null
            val delta = hs.last() - hs.first()
            return Color.parseColor(
                when {
                    delta <= -5.0 -> RED
                    delta <= -1.0 -> ORANGE
                    else -> GREEN
                }
            )
        }

        fun deltaOf(pts: List<ReportRecord>): Double? {
            val hs = pts.mapNotNull { it.health }
            if (hs.size < 2) return null
            return hs.last() - hs.first()
        }
    }

    fun setData(list: List<ReportRecord>) {
        points = list.filter { it.health != null }
        selected = -1
        lineColor = colorForChange(points) ?: Color.parseColor(AppTheme.palette().accent)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        try {
            drawInternal(c)
        } catch (t: Throwable) {
            runCatching {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(AppTheme.palette().text3)
                    textAlign = Paint.Align.CENTER
                    textSize = 11f * resources.displayMetrics.density
                }
                c.drawText("曲线绘制异常，已跳过（${t.javaClass.simpleName}）", width / 2f, height / 2f, p)
            }
        }
    }

    private fun drawInternal(c: Canvas) {
        val pal = AppTheme.palette()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 60 || h < 60) return

        if (points.isEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(pal.text3); textAlign = Paint.Align.CENTER
                textSize = 12f * resources.displayMetrics.density
            }
            c.drawText("还没有报告 · 先导入一份 bugreport", w / 2, h / 2, p)
            return
        }

        val base = points.first().reportTs
        val xs = points.map { (it.reportTs - base) / 86400000.0 }
        val ys = points.map { it.health!! }

        val rawMin = xs.min()
        val rawMax = xs.max()
        val xMin: Double
        val xMax: Double
        if (rawMax - rawMin < 1e-9) {
            xMin = rawMin - 0.5; xMax = rawMax + 0.5
        } else {
            xMin = rawMin; xMax = rawMax
        }
        val yLo = min(78.0, ys.min() - 2.0)
        val yHi = max(100.0, ys.max() + 2.0)
        val ySpan = (yHi - yLo).let { if (it < 8.0) 8.0 else it }

        val pl = padL
        val pr = padR
        val pt = padT
        val plotW = (w - pl - pr).coerceAtLeast(20f)
        val plotH = (h - pt - padB).coerceAtLeast(20f)
        fun px(x: Double) = pl + ((x - xMin) / (xMax - xMin) * plotW).toFloat()
        fun py(y: Double) = pt + ((yHi - y) / ySpan * plotH).toFloat()

        // ---- 网格 + Y 轴刻度 ----
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(pal.grid); strokeWidth = 1f
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(pal.text3); textAlign = Paint.Align.RIGHT
            textSize = 10f * resources.displayMetrics.density
        }
        // Y 轴刻度：字号也跟着密度走，文字基线按字高居中，不再贴边
        label.textSize = 10f * resources.displayMetrics.density
        var v = kotlin.math.ceil(yLo / 2.0) * 2.0
        var guard = 0
        while (v <= yHi && guard++ < 40) {
            val y = py(v)
            c.drawLine(pl, y, w - pr, y, grid)
            c.drawText("%.0f%%".format(v), pl - 8f, y + label.textSize * 0.35f, label)
            v += 2.0
        }

        // ---- 80% 更换参考线 ----
        if (80.0 in yLo..yHi) {
            val warn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#66FF4D4F"); strokeWidth = 1f
                pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
            }
            c.drawLine(pl, py(80.0), w - pr, py(80.0), warn)
            val wl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#AAFF4D4F"); textAlign = Paint.Align.LEFT
                textSize = 9f * resources.displayMetrics.density
            }
            c.drawText("80% 更换参考线", pl + 4f, py(80.0) - 5f, wl)
        }

        // ---- 面积填充 ----
        val area = Path().apply {
            moveTo(px(xs.first()), py(ys.first()))
            for (i in ys.indices) lineTo(px(xs[i]), py(ys[i]))
            lineTo(px(xs.last()), pt + plotH)
            lineTo(px(xs.first()), pt + plotH)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, pt, 0f, pt + plotH,
                (lineColor and 0x00FFFFFF) or 0x55000000,
                (lineColor and 0x00FFFFFF) or 0x00000000,
                Shader.TileMode.CLAMP
            )
        }
        c.drawPath(area, fill)

        // ---- 主曲线 ----
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor; strokeWidth = 2.2f * resources.displayMetrics.density
            style = Paint.Style.STROKE
        }
        val path = Path()
        path.moveTo(px(xs.first()), py(ys.first()))
        for (i in 1 until ys.size) path.lineTo(px(xs[i]), py(ys[i]))
        c.drawPath(path, line)

        // ---- 数据点 ----
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor }
        val dotSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(pal.card); strokeWidth = 1.5f
            style = Paint.Style.FILL
        }
        for (i in ys.indices) {
            val cx = px(xs[i])
            val cy = py(ys[i])
            val r = if (i == selected) 6f else 3.4f
            c.drawCircle(cx, cy, r + 1.2f, ring)
            c.drawCircle(cx, cy, r, if (i == selected) dotSel else dot)
        }

        // ---- X 轴标签 ----
        val xlab = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(pal.text3); textSize = 9.5f * resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }
        val sdf = java.text.SimpleDateFormat("M/d", java.util.Locale.CHINA)
        xlab.textSize = 10f * resources.displayMetrics.density
        val yBase = h - padB + xlab.textSize * 1.1f   // 落在底部留白里，不再被卡片切掉
        // 首/末标签贴边对齐，中间居中，避免首尾被裁
        xlab.textAlign = Paint.Align.LEFT
        c.drawText(sdf.format(java.util.Date(points.first().reportTs)), pl - 2f, yBase, xlab)
        xlab.textAlign = Paint.Align.RIGHT
        c.drawText(sdf.format(java.util.Date(points.last().reportTs)), w - pr + 2f, yBase, xlab)
        if (points.size >= 3) {
            xlab.textAlign = Paint.Align.CENTER
            val mid = points.size / 2
            c.drawText(sdf.format(java.util.Date(points[mid].reportTs)), px(xs[mid]), yBase, xlab)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (points.isEmpty()) return true
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            try {
                val base = points.first().reportTs
                val xs = points.map { (it.reportTs - base) / 86400000.0 }
                if (xs.isNotEmpty()) {
                    val xMin = xs.min()
                    val xMax = xs.max().let { if (it - xMin < 1e-9) xMin + 1.0 else it }
                    val plotW = width.toFloat() - padL - padR
                    val target = ((e.x - padL) / plotW * (xMax - xMin)) + xMin
                    var best = -1
                    var bd = Double.MAX_VALUE
                    xs.forEachIndexed { i, x ->
                        val d = abs(x - target)
                        if (d < bd) { bd = d; best = i }
                    }
                    if (best >= 0 && best < points.size) {
                        selected = best
                        onPointSelected?.invoke(points[best])
                        invalidate()
                    }
                }
            } catch (t: Throwable) {
                /* 选点失败不影响使用 */
            }
            return true
        }
        return super.onTouchEvent(e)
    }
}
