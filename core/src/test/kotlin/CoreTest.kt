package com.xiaolei.battery.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

class FileSeekSource(private val file: File) : SeekSource {
    private val raf: RandomAccessFile get() = RandomAccessFile(file, "r")
    override val size: Long get() = file.length()

    override fun read(pos: Long, len: Int): ByteArray {
        RandomAccessFile(file, "r").use { f ->
            f.seek(pos)
            val b = ByteArray(len)
            val n = f.read(b, 0, len)
            return if (n <= 0) ByteArray(0) else if (n == len) b else b.copyOf(n)
        }
    }
}

class UnitsTest {

    @Test fun chargeUnits() {
        assertEquals(4196.0, Units.chargeToMah(4196000.0)!!, 0.001)   // µAh
        assertEquals(2630.0, Units.chargeToMah(2630.0)!!, 0.001)      // 已经是 mAh
        assertNull(Units.chargeToMah(4.0))                            // Ah 乱码，拒绝采信
        assertNull(Units.chargeToMah(null))
    }

    @Test fun capacityTextAh() {
        assertEquals(4000.0, Units.capacityTextToMah(4.0, "Ah"), 0.001)
        assertEquals(4500.0, Units.capacityTextToMah(4500.0, "mAh"), 0.001)
        assertEquals(4500.0, Units.capacityTextToMah(4.5), 0.001)
    }

    @Test fun voltageMagic() {
        assertEquals(3936.0, Units.voltageToMv(3936.0)!!, 0.01)      // mV
        assertEquals(3936.0, Units.voltageToMv(3936000.0)!!, 0.01)   // µV
        assertEquals(3900.0, Units.voltageToMv(3.9)!!, 0.01)         // V
    }

    @Test fun tempMagic() {
        assertEquals(41.0, Units.tempToC(410.0)!!, 0.001)
        assertEquals(29.4, Units.tempToC(294.0)!!, 0.001)
        assertEquals(29.4, Units.tempToC(29.4)!!, 0.001)
    }

    @Test fun currentMagic() {
        assertEquals(-520.0, Units.currentToMa(-520000.0)!!, 0.001)  // µA
        assertEquals(-520.0, Units.currentToMa(-520.0)!!, 0.001)     // mA
        assertEquals(-520.0, Units.currentToMa(-0.52)!!, 0.001)      // A
    }

    @Test fun extrapolate() {
        assertEquals(4109.375, Units.extrapolateFull(2630.0, 64)!!, 0.01)
        assertNull(Units.extrapolateFull(2630.0, 10))  // 太低电量不可信
    }
}

class HealthTest {

    @Test fun xiaomi13Real() {
        val g = Gauge(
            chargeFullRaw = 4196000.0,
            cycleRaw = 379,
            levelRaw = 64,
            tempRaw = 410.0,
            voltRaw = 3936000.0,
            deviceKeys = listOf("Xiaomi 13", "2211133C", "fuxi")
        )
        val r = Health.compute(g)
        assertEquals(93.2, r.healthPct!!, 0.05)
        assertEquals(4196.0, r.actualMah!!, 0.5)
        assertEquals(4500.0, r.designMah!!, 0.5)
        assertEquals(379, r.cycle)
        assertEquals(41.0, r.tempC!!, 0.05)
        // 设计容量取官方标称（机型库）、实际容量来自内核电量计 → 高
        assertEquals("高", r.confidence)
        assertEquals("内核电量计 charge_full", r.actualSrc)
    }

    @Test fun xiaomi18ProReal() {
        val g = Gauge(
            chargeFullRaw = 6921000.0,
            chargeFullDesignRaw = 6949000.0,
            cycleRaw = 22,
            levelRaw = 76,
            tempRaw = 294.0,
            deviceKeys = listOf("Xiaomi 18 Pro", "M610BB", "hongkong")
        )
        val r = Health.compute(g)
        // v4.3.2：内核 charge_full_design 的 6949 是"额定值"口径，
        // 官方典型标称 7000 才是健康度基准（6921/7000 = 98.9%）
        assertEquals(98.9, r.healthPct!!, 0.1)
        assertEquals(7000.0, r.designMah!!, 0.5)
        assertEquals(22, r.cycle)
        assertEquals(29.4, r.tempC!!, 0.05)
        assertTrue(r.designSrc.contains("机型库"))
        assertTrue(r.notes.any { it.contains("额定") })
    }

    @Test fun officialBeatsFirmwareProfile() {
        // v4.3.2：固件档案 6949（额定口径）即使与官方标称差 <8% 也不当设计容量
        val g = Gauge(
            chargeFullRaw = 6921000.0,
            chargeFullDesignRaw = 6949000.0,
            profileDesignMah = 6949.0,
            deviceKeys = listOf("Xiaomi 18 Pro", "M610BB")
        )
        val r = Health.compute(g)
        assertEquals(7000.0, r.designMah!!, 0.5)
        assertTrue(r.designSrc.contains("机型库"))
        assertEquals(98.9, r.healthPct!!, 0.1)
        assertTrue(r.notes.any { it.contains("额定") })
    }

    @Test fun k70UltraOfficialCapacity() {
        // v4.3.2 修正：K70 至尊版官方典型容量 5500（此前机型库误写 5000，
        // 导致 >8% 防跳保护反而把固件里正确的 5500 也拒掉了）
        val g = Gauge(
            learnedMinMah = 4768.0, learnedMaxMah = 4768.0,
            profileDesignMah = 5500.0,
            deviceKeys = listOf("Redmi K70 Ultra", "2407FRK8EC")
        )
        val r = Health.compute(g)
        assertEquals(5500.0, r.designMah!!, 0.5)
        assertEquals(86.7, r.healthPct!!, 0.1)   // 4768/5500
    }

    @Test fun profileFallbackWhenModelUnknown() {
        // 机型库不认识的机型才退回固件电源档案
        val g = Gauge(chargeFullRaw = 4196000.0, profileDesignMah = 4300.0, deviceKeys = listOf("Foo Bar X99"))
        val r = Health.compute(g)
        assertEquals(4300.0, r.designMah!!, 0.5)
        assertTrue(r.designSrc.contains("电源档案"))
    }

    @Test fun dynamicProfileEstimateRejected() {
        // 小米15 真机坑：Estimated battery capacity 是随报告变化的动态估算
        // （4873/4574），与官方标称差 >8% —— 必须弃用，不能当设计容量
        val g = Gauge(
            chargeFullRaw = null,
            learnedMinMah = 4560.0, learnedMaxMah = 4574.0,
            profileDesignMah = 4873.0,
            deviceKeys = listOf("Xiaomi 15", "24129PN74C", "dada")
        )
        val r = Health.compute(g)
        assertEquals(5400.0, r.designMah!!, 0.5)
        assertTrue(r.designSrc.contains("机型库"))
        assertTrue(r.notes.any { it.contains("疑似随用电变化的估算值") })
    }

    @Test fun profileVsOfficial() {
        // 固件电源档案写的 4000 与官方 4500 差 11% —— 必须以官方标称为准
        val g = Gauge(chargeFullRaw = 4196000.0, profileDesignMah = 4000.0, deviceKeys = listOf("Xiaomi 13"))
        val r = Health.compute(g)
        assertEquals(4500.0, r.designMah!!, 0.5)
        assertEquals(93.2, r.healthPct!!, 0.05)
        assertTrue(r.notes.any { it.contains("官方标称") })
    }

    @Test fun manualOverrideWins() {
        val g = Gauge(chargeFullRaw = 4196000.0, chargeFullDesignRaw = 4500000.0, manualDesignMah = 4820.0)
        val r = Health.compute(g)
        assertEquals(4820.0, r.designMah!!, 0.5)
        assertEquals("手动设定", r.designSrc)
        assertEquals(87.1, r.healthPct!!, 0.1)
    }

    @Test fun learnedFallback() {
        val g = Gauge(learnedMinMah = 4100.0, learnedMaxMah = 4250.0, deviceKeys = listOf("Xiaomi 13"))
        val r = Health.compute(g)
        assertEquals(4175.0, r.actualMah!!, 0.5)
        assertEquals("系统学习容量 (learned)", r.actualSrc)
        assertTrue(r.confidence.startsWith("中"))
    }

    @Test fun counterFallbackWarnsAtLowLevel() {
        val g = Gauge(counterRaw = 2630000.0, levelRaw = 64, deviceKeys = listOf("Xiaomi 13"))
        val r = Health.compute(g)
        assertEquals("充电计数推算", r.actualSrc)
        assertTrue(r.notes.any { it.contains("满电") })
        assertEquals("低（仅供参考）", r.confidence)
    }

    @Test fun noDataMeansNoGuess() {
        val r = Health.compute(Gauge())
        assertNull(r.healthPct)
        assertEquals("无数据", r.confidence)
    }

    @Test fun socLookup() {
        assertEquals("第六代骁龙8至尊版 (Snapdragon 8 Elite Gen 6)", DeviceDb.socName("sm8950"))
        assertEquals("骁龙8 Gen2", DeviceDb.socName("sm8550"))
        assertNull(DeviceDb.socName(null))
    }
}

class TrendTest {

    @Test fun linearFitDecline() {
        val pts = (0..30).map { it.toDouble() to 100.0 - it * 0.05 }
        val f = Trend.fit(pts)!!
        assertEquals(-0.05, f.slopePerDay, 1e-6)
        assertTrue(f.r2 > 0.99)
        assertEquals(31, f.n)
    }

    @Test fun predictDaysTo80() {
        // 从 92% 起每天掉 0.02%，掉到 80% 需要 600 天，已过 100 天 → 还剩 500 天
        val pts = (0..100).map { it.toDouble() to 92.0 - it * 0.02 }
        val f = Trend.fit(pts)!!
        val days = Trend.daysToThreshold(f, 80.0)!!
        assertEquals(500.0, days, 1.0)
    }

    @Test fun notEnoughSamples() {
        val pts = listOf(0.0 to 90.0, 1.0 to 89.0)
        val f = Trend.fit(pts)!!
        assertNull(Trend.daysToThreshold(f))   // 两个点不给预测
    }

    @Test fun risingBatteryNoPrediction() {
        val pts = listOf(0.0 to 90.0, 1.0 to 90.5, 2.0 to 91.0, 3.0 to 91.2)
        val f = Trend.fit(pts)!!
        assertNull(Trend.daysToThreshold(f))
    }
}

/** 报告生成时间提取：曲线横轴全靠它，不能取错 */
class ReportTimeTest {

    @Test fun fromDumpstateHeader() {
        val s = BugReportScanner()
        s.feedLine("========================================================")
        s.feedLine("== dumpstate: 2025-04-10 10:19:39")
        assertEquals("2025-04-10 10:19:39", s.reportDateText())
        val epoch = s.reportEpoch()
        assertNotNull(epoch)
        val got = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(epoch!!))
        assertEquals("2025-04-10 10:19:39", got)
    }

    @Test fun noDateMeansNull() {
        val s = BugReportScanner()
        s.feedLine("[ro.product.model]: [2211133C]")
        s.feedLine("DUMP OF SERVICE battery:")
        assertNull(s.reportEpoch())
    }
}
