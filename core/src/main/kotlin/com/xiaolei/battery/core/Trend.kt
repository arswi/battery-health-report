package com.xiaolei.battery.core

import kotlin.math.abs

/**
 * 衰减趋势：最小二乘线性回归 + 到达阈值的剩余天数预测。
 *
 * 电池老化在中期近似线性（每满循环掉 0.02%~0.05%），短期抖动主要来自温度和电量，
 * 所以只做线性拟合，不做高阶——高阶拟合到 3 个点就会给出荒唐的预测。
 */
object Trend {

    data class Fit(
        val slopePerDay: Double,
        val intercept: Double,
        val r2: Double,
        val n: Int,
        val xMin: Double,
        val xMax: Double
    )

    /** x = 距首点的天数，y = 健康度 % */
    fun fit(points: List<Pair<Double, Double>>): Fit? {
        val n = points.size
        if (n < 2) return null
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for ((x, y) in points) {
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-9) return null
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n

        val meanY = sy / n
        var ssTot = 0.0
        var ssRes = 0.0
        for ((x, y) in points) {
            ssTot += (y - meanY) * (y - meanY)
            val p = slope * x + intercept
            ssRes += (y - p) * (y - p)
        }
        val r2 = if (ssTot < 1e-9) 1.0 else 1.0 - ssRes / ssTot

        return Fit(slope, intercept, r2, n, points.first().first, points.last().first)
    }

    /** 按当前拟合预测掉到 threshold（默认 80%）还需多少天；已在下降但未到则返回天数，不降返回 null */
    fun daysToThreshold(f: Fit, threshold: Double = 80.0): Double? {
        if (f.slopePerDay >= -1e-4) return null          // 没有明显下降
        if (f.n < 3) return null                          // 样本太少，预测没意义
        val x = (threshold - f.intercept) / f.slopePerDay
        val remain = x - f.xMax
        return if (remain > 0 && remain < 100000) remain else null
    }

    /** 每 100 次循环的衰减率（%），需要同时有循环数样本 */
    fun lossPer100Cycles(points: List<Pair<Double, Double>>): Double? {
        val f = fit(points) ?: return null
        if (f.n < 2) return null
        return -f.slopePerDay * 100.0
    }

    /** 健康区间建议 */
    fun healthRange(minPct: Double?, maxPct: Double?): String {
        val a = minPct ?: return "暂无足够数据"
        val b = maxPct ?: a
        return if (abs(b - a) < 0.05) "%.1f%%".format(a) else "%.1f%% ~ %.1f%%".format(a, b)
    }
}
