package com.xiaolei.battery.core

/**
 * 电量计/传感器原始值的单位归一化。
 *
 * 同一个字段在不同机型 ROM 上单位不一样，这是真机上最坑的部分：
 *  - charge_*      绝大多数是 µAh，但小米部分机型直接报 mAh（差 1000 倍）
 *  - voltage_now   有 mV / µV / V 三种写法
 *  - temp          基本是 1/10 ℃，但也有直接给 ℃ 的
 *  - current_now   一般是 µA，少数给 mA 或 A
 * 全部按"物理合理区间"反推单位，而不是假设单位。
 */
object Units {

    /** 手机电池合理容量：µAh 落在 50 万~1 亿，mAh 落在 500~20000 */
    fun chargeToMah(raw: Double?): Double? {
        val v = raw ?: return null
        if (v <= 0 || v.isNaN()) return null
        return when {
            v in 500.0..20000.0 -> v          // 已经是 mAh
            v >= 100000.0 -> v / 1000.0        // µAh
            else -> null
        }
    }

    /** MW 修正：部分固件把 Ah 写成 "4.00"（设计容量 4000mAh 却显示 4） */
    fun capacityTextToMah(value: Double, unit: String? = null): Double {
        val u = (unit ?: "").trim().lowercase()
        return when {
            u.startsWith("ah") && !u.startsWith("mah") -> value * 1000.0
            value > 0 && value < 100.0 -> value * 1000.0   // 明显是 Ah
            else -> value                                   // mAh
        }
    }

    /** 归一化到 mV（单节锂电 2500~4600 mV） */
    fun voltageToMv(raw: Double?): Double? {
        val v = raw ?: return null
        if (v <= 0 || v.isNaN()) return null
        return when {
            v in 2000.0..6000.0 -> v                        // mV
            v > 100000.0 -> v / 1000.0                      // µV
            v in 2.0..6.0 -> v * 1000.0                     // V
            else -> null
        }
    }

    /** 归一化到 ℃。sysfs 惯例是 1/10 ℃（294 → 29.4℃），少数直给 ℃ */
    fun tempToC(raw: Double?): Double? {
        val v = raw ?: return null
        if (v.isNaN()) return null
        val a = kotlin.math.abs(v)
        if (a > 1000.0) return null                          // 不像温度，丢弃
        return if (a > 100.0) v / 10.0 else v
    }

    /** 归一化到 mA（正值充电、负值放电） */
    fun currentToMa(raw: Double?): Double? {
        val v = raw ?: return null
        if (v.isNaN()) return null
        val a = kotlin.math.abs(v)
        return when {
            a < 10.0 -> v * 1000.0        // A
            a > 100000.0 -> v / 1000.0    // µA
            else -> v                     // mA
        }
    }

    /**
     * 用「当前电量计读数 ÷ 当前百分比」反推满充容量。
     * 只有在电量较高时才有意义（低电量时库仑计误差被放大）。
     */
    fun extrapolateFull(counterMah: Double?, level: Int?): Double? {
        val c = counterMah ?: return null
        val l = level ?: return null
        if (l < 20 || l > 100) return null
        return c / (l / 100.0)
    }
}
