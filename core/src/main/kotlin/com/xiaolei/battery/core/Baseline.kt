package com.xiaolei.battery.core

import kotlin.math.abs

/**
 * 群体基准曲线 + 到达 80% 的时间推断。
 *
 * 两条腿走路，哪条有数据走哪条：
 *  1. 本机实测：用用户自己多份报告的 (循环次数 → 健康度) 斜率外推，最贴合这台机器
 *  2. 群体基准：内置的同类型锂电衰减曲线（按循环次数），本机器数据不足时使用
 *
 * 关于"联机"：内置基准是通用模型，永远可用；用户若配置了基准地址，
 * 会用服务端返回的曲线覆盖内置曲线（见 Baseline.parse）。本模块不产生任何网络行为。
 */
object Baseline {

    /** 基准曲线：循环次数 → 该循环数下的典型健康度（%） */
    class Curve(private val pts: List<Pair<Double, Double>>) {
        init {
            require(pts.size >= 2) { "基准曲线至少需要两个点" }
        }

        val size: Int get() = pts.size

        /** 线性插值取健康度；超出两端按端点值 */
        fun at(cycles: Double): Double {
            if (cycles <= pts.first().first) return pts.first().second
            if (cycles >= pts.last().first) return pts.last().second
            for (i in 1 until pts.size) {
                val (x0, y0) = pts[i - 1]
                val (x1, y1) = pts[i]
                if (cycles <= x1) {
                    if (abs(x1 - x0) < 1e-9) return y1
                    val t = (cycles - x0) / (x1 - x0)
                    return y0 + (y1 - y0) * t
                }
            }
            return pts.last().second
        }

        /** 反解：健康度降到 h 大概需要多少循环 */
        fun cyclesAt(h: Double): Double {
            for (i in 1 until pts.size) {
                val (x0, y0) = pts[i - 1]
                val (x1, y1) = pts[i]
                if (h <= y0 && h >= y1) {
                    if (abs(y1 - y0) < 1e-9) return x0
                    val t = (y0 - h) / (y0 - y1)
                    return x0 + (x1 - x0) * t
                }
            }
            return if (h > pts.first().second) pts.first().first else pts.last().first
        }

        fun snapshot(): List<Pair<Double, Double>> = pts.toList()
    }

    /**
     * 内置群体基准（消费级锂聚合物电芯的典型表现）：
     * 100 次 ≈ 97.5%，300 次 ≈ 93%，500 次 ≈ 88%，800 次掉到 80%，1000 次 ≈ 74%。
     * 这是公开资料里的常见区间，不是任何品牌的官方承诺值。
     */
    val builtIn = Curve(
        listOf(
            0.0 to 100.0,
            100.0 to 97.5,
            300.0 to 93.0,
            500.0 to 88.0,
            800.0 to 80.0,
            1000.0 to 74.0
        )
    )

    /** 解析服务端曲线：`[[0,100],[100,97.5],...]` 或 `{"points":[[...]]}` */
    fun parse(text: String): Curve? = runCatching {
        val t = text.trim()
        val body = if (t.startsWith("{")) {
            val i = t.indexOf("\"points\"")
            if (i < 0) return null
            val a = t.indexOf('[', i)
            if (a < 0) return null
            // 必须按括号配对找到真正的结尾，直接找第一个 ']' 会把数组截断
            var depth = 0
            var b = -1
            for (k in a until t.length) {
                when (t[k]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) { b = k; break }
                    }
                }
            }
            if (b < 0) return null
            t.substring(a, b + 1)
        } else t
        val out = ArrayList<Pair<Double, Double>>()
        val num = Regex("""(-?\d+(?:\.\d+)?)""")
        for (pair in Regex("""\[[^\]]*\]""").findAll(body)) {
            val ns = num.findAll(pair.value).map { it.value.toDouble() }.toList()
            if (ns.size >= 2) out.add(ns[0] to ns[1])
        }
        val c = Curve(out.sortedBy { it.first })
        if (c.size < 2) null else c
    }.getOrNull()

    /** 一份历史样本：报告时间 + 当时的循环次数 + 当时的健康度 */
    data class Sample(val ts: Long, val cycle: Double?, val health: Double?)

    data class Prediction(
        /** 每日循环次数（次/天） */
        val cyclesPerDay: Double?,
        /** 本机实测：每 100 次循环掉多少个百分点（正数表示下降） */
        val dropPer100: Double?,
        /** 同循环数下群体基准的健康度 */
        val baselineHealthNow: Double?,
        /** 本机与群体基准的偏差（正=比基准健康/衰减更慢） */
        val deviation: Double?,
        /** 还需多少循环到 80%（实测优先，其次基准） */
        val cyclesTo80: Double?,
        /** 距 80% 还有多少天 */
        val daysTo80: Double?,
        /** 到达 80% 的预估日期（epoch ms） */
        val etaTs: Long?,
        /** 结论依据，用于界面说明 */
        val basis: String,
        /** 是否已经低于 80% */
        val alreadyBelow: Boolean,
        /** 传入的官方宣称循环寿命（次到 80%），没查到为 null */
        val officialCyclesTo80: Double? = null
    )

    private const val DAY = 86400000.0

    /**
     * 推断到达 80% 的时间。
     *
     * @param startTs 用户定义的"新电池开始使用时间"；没有就传 0
     * @param series  该机型的历史报告（按时间升序）
     * @param curve   基准曲线
     * @param officialCyclesTo80 厂商官方宣称的"X 次循环到 80%"（LifeClaims 查出）；
     *   传入后"无本机实测衰减速率"时的推断参考点用它（实验室理想值），
     *   本机实测衰减速率依然最优先
     */
    fun predict(
        startTs: Long,
        series: List<Sample>,
        curve: Curve = builtIn,
        nowTs: Long = System.currentTimeMillis(),
        officialCyclesTo80: Double? = null
    ): Prediction {
        val withHealth = series.filter { it.health != null }
        val withCycle = series.filter { it.cycle != null && it.cycle > 0 }
        val last = withHealth.lastOrNull()
        val nowHealth = last?.health
        val nowCycle = withCycle.lastOrNull()?.cycle

        // ---- 每日循环次数 ----
        var r: Double? = null
        if (withCycle.size >= 2) {
            val a = withCycle.first()
            val b = withCycle.last()
            val days = (b.ts - a.ts) / DAY
            val dc = b.cycle!! - a.cycle!!
            if (days >= 1.0 && dc > 0) r = dc / days
        }
        if (r == null && nowCycle != null && startTs > 0 && nowTs > startTs) {
            val days = (nowTs - startTs) / DAY
            if (days >= 1.0) r = nowCycle / days
        }

        // ---- 本机实测衰减速率：每 100 循环掉几个点 ----
        var drop100: Double? = null
        val pairs = series.mapNotNull { s ->
            if (s.cycle != null && s.health != null) s.cycle to s.health else null
        }
        if (pairs.size >= 2) {
            val a = pairs.first()
            val b = pairs.last()
            val dc = b.first - a.first
            val dh = b.second - a.second
            if (dc > 0 && dh < 0) drop100 = -dh / dc * 100.0
        }

        // ---- 还需多少循环到 80%：参考点 = 本机实测 > 官方宣称 > 群体基准 ----
        val refCycles = officialCyclesTo80 ?: curve.cyclesAt(80.0)
        val baseNeed = if (nowCycle != null) (refCycles - nowCycle).coerceAtLeast(0.0) else null
        val measuredNeed = when {
            nowHealth == null -> null
            nowHealth <= 80.0 -> 0.0
            drop100 != null && drop100 > 0 -> (nowHealth - 80.0) / drop100 * 100.0
            else -> null
        }
        val need = measuredNeed ?: baseNeed

        val days = if (need != null && r != null && r > 0) need / r else null
        val eta = days?.let { (nowTs + it * DAY).toLong() }

        val baseNow = nowCycle?.let { curve.at(it) }
        val dev = if (nowHealth != null && baseNow != null) nowHealth - baseNow else null

        val basis = buildString {
            when {
                measuredNeed != null && r != null ->
                    append("本机实测衰减 %.2f 点/100 循环 · 每日 %.2f 次循环".format(drop100 ?: 0.0, r))
                measuredNeed != null ->
                    append("本机实测衰减 %.2f 点/100 循环（缺循环速率，给不出日期）".format(drop100 ?: 0.0))
                baseNeed != null && r != null ->
                    if (officialCyclesTo80 != null)
                        append("官方宣称 %.0f 次循环到 80%%（实验室理想值）· 每日 %.2f 次循环".format(officialCyclesTo80, r))
                    else
                        append("群体基准 %.0f 次循环到 80%% · 每日 %.2f 次循环".format(curve.cyclesAt(80.0), r))
                else -> append("数据不足：至少需要两份带循环次数的报告")
            }
        }

        return Prediction(
            cyclesPerDay = r,
            dropPer100 = drop100,
            baselineHealthNow = baseNow,
            deviation = dev,
            cyclesTo80 = need,
            daysTo80 = days,
            etaTs = eta,
            basis = basis,
            alreadyBelow = nowHealth != null && nowHealth <= 80.0,
            officialCyclesTo80 = officialCyclesTo80
        )
    }
}
