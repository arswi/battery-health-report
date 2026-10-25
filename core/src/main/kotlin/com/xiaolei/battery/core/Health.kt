package com.xiaolei.battery.core

import kotlin.math.abs
import kotlin.math.round

/**
 * 单次采样快照：所有字段都是"原始值"，单位由 Units 反推。
 * 来源有两个：
 *   1) sysfs 电量计直读（App 每日自动采，无需 root）
 *   2) bugreport 解析出来的 POWER_SUPPLY_* 字段（人工导入校准）
 * 两条路走的是同一套内核字段，所以可以直接混在同一条曲线里。
 */
data class Gauge(
    val chargeFullDesignRaw: Double? = null,
    val chargeFullRaw: Double? = null,
    val counterRaw: Double? = null,
    val cycleRaw: Int? = null,
    val levelRaw: Int? = null,
    val tempRaw: Double? = null,
    val voltRaw: Double? = null,
    val currentRaw: Double? = null,
    val learnedMinMah: Double? = null,
    val learnedMaxMah: Double? = null,
    val profileDesignMah: Double? = null,
    val manualDesignMah: Double? = null,
    val deviceKeys: List<String?> = emptyList()
)

data class HealthResult(
    val healthPct: Double?,
    val actualMah: Double?,
    val actualSrc: String,
    val designMah: Double?,
    val designSrc: String,
    val cycle: Int?,
    val cycleSrc: String,
    val level: Int?,
    val tempC: Double?,
    val voltMv: Double?,
    val currentMa: Double?,
    val notes: List<String>,
    val confidence: String
)

object Health {

    private fun rm1(v: Double?) = if (v == null) null else round(v * 10) / 10.0

    fun compute(g: Gauge): HealthResult {
        val notes = mutableListOf<String>()

        val level = g.levelRaw?.let { if (it in 0..100) it else null }
        val tempC = rm1(Units.tempToC(g.tempRaw))
        val voltMv = Units.voltageToMv(g.voltRaw)?.let { round(it).toInt().toDouble() }
        val currentMa = Units.currentToMa(g.currentRaw)?.let { round(it).toInt().toDouble() }

        // ---------- 设计容量：手动 > 官方标称（机型库） > 固件电源档案 > 内核电量计 ----------
        // 官方典型标称优先。固件里的 power_profile / charge_full_design 有两种坑：
        // ① 口径常是"额定值"而非宣传的"典型值"（真机：Xiaomi 18 Pro 固件 6949 vs 官方 7000），
        //    不同机型口径不一，健康度基准就不可比；
        // ② "Estimated battery capacity" 在部分 ROM 上是随报告变化的动态估算
        //    （真机：小米15 三份报告给出 4873/4574，直接当设计容量会让健康度 93.7%→103.3% 乱跳）。
        // 所以机型库认识这台机器就以官方标称为准，固件值只作备注对照；
        // 机型库不认识才落到固件档案/内核值；换第三方电池用手动校准覆盖。
        var design: Double? = null
        var designSrc = "未知"

        if (g.manualDesignMah != null && g.manualDesignMah > 0) {
            design = g.manualDesignMah
            designSrc = "手动设定"
        } else {
            val profile = g.profileDesignMah?.takeIf { it in 1500.0..20000.0 }
            val sysfs = Units.chargeToMah(g.chargeFullDesignRaw)?.takeIf { it in 1500.0..20000.0 }
            val db = DeviceDb.designCapacity(*g.deviceKeys.toTypedArray())
            when {
                db != null -> {
                    design = db.first.toDouble()
                    designSrc = "内置机型库（${db.second}）"
                    val fw = profile ?: sysfs
                    if (fw != null) {
                        val dev = abs(fw - db.first) / db.first
                        if (dev > 0.08) {
                            notes.add(
                                "固件上报的设计容量 ${fw.toInt()}mAh 与官方标称 ${db.first}mAh 差 %.0f%%，" +
                                    "疑似随用电变化的估算值，已按官方标称计算".format(dev * 100)
                            )
                        } else if (dev > 0.005) {
                            notes.add("固件按额定口径上报 ${fw.toInt()}mAh，官方典型标称 ${db.first}mAh，按典型值计算")
                        }
                    } else {
                        notes.add("固件未上报设计容量，使用机型库官方标称")
                    }
                }
                profile != null -> {
                    design = profile
                    designSrc = "固件电源档案 (power_profile)"
                }
                sysfs != null -> {
                    design = sysfs
                    designSrc = "内核电量计 charge_full_design"
                }
            }
        }

        // ---------- 实际满充容量 ----------
        var actual: Double? = null
        var actualSrc = "未知"

        val full = Units.chargeToMah(g.chargeFullRaw)
        if (full != null && full in 1500.0..20000.0) {
            actual = full
            actualSrc = "内核电量计 charge_full"
        } else {
            val learned = listOfNotNull(g.learnedMinMah, g.learnedMaxMah)
                .filter { it in 1500.0..20000.0 }
                .takeIf { it.isNotEmpty() }
                ?.average()
            if (learned != null) {
                actual = learned
                actualSrc = "系统学习容量 (learned)"
            } else {
                val counterMah = Units.chargeToMah(g.counterRaw)
                val est = Units.extrapolateFull(counterMah, level)
                if (est != null && est in 1500.0..20000.0) {
                    actual = est
                    actualSrc = "充电计数推算"
                }
            }
        }

        // ---------- 循环次数 ----------
        var cycle = g.cycleRaw
        var cycleSrc = if (cycle != null) "内核/电量计上报" else "未上报"
        if ((cycle == null || cycle <= 0) && actual != null && design != null) {
            // 无循环计数时按"满 500 次循环掉到 80%"的行业均值粗推
            val lossPct = 100.0 - (actual / design * 100.0)
            if (lossPct > 0.5) {
                cycle = (lossPct / 0.04).toInt()
                cycleSrc = "按健康度推算（不可靠）"
            }
        }

        // ---------- 结果 ----------
        val pct = if (actual != null && design != null && design > 0) {
            round(actual / design * 1000) / 10.0
        } else null

        if (pct != null && pct > 100.0 && designSrc.startsWith("固件电源档案")) {
            notes.add("健康度 %.1f%% 超过 100%%：电源档案的设计容量多半偏小，想按官方标称算可在概览页「设计容量校准」手动设定".format(pct))
        }
        if (pct != null && pct > 105) {
            notes.add("算出的健康度 >105%，说明 ROM 上报的容量不可信（常见于换过第三方电池或电量计未复位）")
        }
        if (actualSrc == "充电计数推算" && (level ?: 0) < 95) {
            notes.add("当前电量 ${level}% 时的计数推算偏低概率大，满电到 100% 再采样更准")
        }

        val confidence = confidenceOf(pct, actualSrc, designSrc)

        return HealthResult(
            healthPct = pct,
            actualMah = actual?.let { round(it * 10) / 10.0 },
            actualSrc = actualSrc,
            designMah = design,
            designSrc = designSrc,
            cycle = cycle,
            cycleSrc = cycleSrc,
            level = level,
            tempC = tempC,
            voltMv = voltMv,
            currentMa = currentMa,
            notes = notes,
            confidence = confidence
        )
    }

    /** 设计容量来源是否可靠（内核值 / 官方标称 / 手动），供置信度判定 */
    fun designSolid(designSrc: String): Boolean =
        designSrc == "手动设定" ||
            designSrc.startsWith("内核电量计") ||
            designSrc.startsWith("内置机型库")

    /** Store 重算历史记录时也走这里，保证置信度口径和导入时一致 */
    fun confidenceOf(pct: Double?, actualSrc: String, designSrc: String): String = when {
        pct == null -> "无数据"
        actualSrc.startsWith("内核电量计") && designSolid(designSrc) -> "高"
        actualSrc.startsWith("内核电量计") || actualSrc.startsWith("系统学习") -> "中"
        else -> "低（仅供参考）"
    }

    /** 健康度 → 结论文案 + 状态色 key */
    fun verdict(pct: Double?): Pair<String, String> = when {
        pct == null -> "数据不足" to "未知"
        pct >= 95 -> "接近全新" to "good"
        pct >= 88 -> "轻微衰减，正常使用" to "ok"
        pct >= 80 -> "明显衰减，续航已受影响" to "warn"
        pct >= 70 -> "衰减严重，留意续航" to "bad"
        else -> "建议更换电池" to "danger"
    }
}
