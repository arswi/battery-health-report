package com.xiaolei.battery.core

import org.junit.Assert.*
import org.junit.Test

/** 基准曲线与 80% 推断：这是"联机对比"的数学部分，必须锁死 */
class BaselineTest {

    private val day = 86400000L

    @Test fun builtInCurveIsMonotonic() {
        var prev = 101.0
        for (c in 0..1000 step 50) {
            val v = Baseline.builtIn.at(c.toDouble())
            assertTrue("基准曲线必须单调下降 @$c", v <= prev + 1e-9)
            prev = v
        }
        assertEquals(100.0, Baseline.builtIn.at(0.0), 1e-9)
        assertEquals(80.0, Baseline.builtIn.at(800.0), 1e-9)
        assertEquals(800.0, Baseline.builtIn.cyclesAt(80.0), 1e-9)
    }

    @Test fun parsePlainArray() {
        val c = Baseline.parse("[[0,100],[300,93],[800,80]]")
        assertNotNull(c)
        assertEquals(93.0, c!!.at(300.0), 1e-9)
    }

    @Test fun parseObjectForm() {
        val c = Baseline.parse("""{"name":"x","points":[[0,100],[500,88],[1000,74]]}""")
        assertNotNull(c)
        assertEquals(88.0, c!!.at(500.0), 1e-9)
        assertEquals(1000.0, c.cyclesAt(74.0), 1e-6)
    }

    @Test fun garbageIsRejected() {
        assertNull(Baseline.parse("not json at all"))
        assertNull(Baseline.parse(""))
    }

    /** 只有一份报告、算不出本机斜率时，用群体基准：100 次循环 → 还剩 700 次到 80% */
    @Test fun predictFromBaseline() {
        val now = 1_700_000_000_000L
        val start = now - 100 * day          // 100 天前开始用，现在 100 次循环 = 1 次/天
        val series = listOf(Baseline.Sample(now, 100.0, 95.0))
        val p = Baseline.predict(start, series, Baseline.builtIn, now)
        assertEquals(1.0, p.cyclesPerDay!!, 0.01)
        assertNull("单份报告算不出本机斜率", p.dropPer100)
        assertEquals(700.0, p.cyclesTo80!!, 1.0)      // 800 - 100
        assertEquals(700.0, p.daysTo80!!, 1.0)
        assertEquals(now + 700 * day, p.etaTs!!)
    }

    /** 本机实测衰减优先于基准：每 100 循环掉 2 点，90% → 80% 需 500 循环 */
    @Test fun measuredDecayWins() {
        val now = 1_700_000_000_000L
        val series = listOf(
            Baseline.Sample(now - 200 * day, 100.0, 96.0),
            Baseline.Sample(now - 100 * day, 200.0, 94.0),
            Baseline.Sample(now, 300.0, 92.0)
        )
        val p = Baseline.predict(0L, series, Baseline.builtIn, now)
        assertEquals(2.0, p.dropPer100!!, 0.01)
        assertEquals(600.0, p.cyclesTo80!!, 1.0)   // (92-80)/0.02
        assertEquals(1.0, p.cyclesPerDay!!, 0.01)
        assertEquals(600.0, p.daysTo80!!, 1.0)
        assertFalse(p.alreadyBelow)
    }

    /** 只靠开始时间 + 当前循环也能算出每日循环次数 */
    @Test fun rateFromStartTsWhenSingleReport() {
        val now = 1_700_000_000_000L
        val start = now - 200 * day
        val series = listOf(Baseline.Sample(now, 400.0, 90.0))
        val p = Baseline.predict(start, series, Baseline.builtIn, now)
        assertEquals(2.0, p.cyclesPerDay!!, 0.01)
        assertEquals(400.0, p.cyclesTo80!!, 1.0)
        assertEquals(200.0, p.daysTo80!!, 1.0)
    }

    @Test fun alreadyBelow80() {
        val now = 1_700_000_000_000L
        val series = listOf(Baseline.Sample(now - 10 * day, 900.0, 78.0), Baseline.Sample(now, 910.0, 77.0))
        val p = Baseline.predict(now - 400 * day, series, Baseline.builtIn, now)
        assertTrue(p.alreadyBelow)
        assertEquals(0.0, p.cyclesTo80!!, 1e-9)
    }

    /** 数据与基准的偏差方向不能搞反 */
    @Test fun deviationSign() {
        val now = 1_700_000_000_000L
        val series = listOf(Baseline.Sample(now - 100 * day, 300.0, 96.0))  // 基准 300 次是 93%
        val p = Baseline.predict(0L, series, Baseline.builtIn, now)
        assertTrue("比基准好应为正", p.deviation!! > 0)
        assertEquals(93.0, p.baselineHealthNow!!, 0.01)
    }
}
