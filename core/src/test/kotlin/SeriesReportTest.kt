package com.xiaolei.battery.core

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * 用户 2026-10 提供的三份同机 bugreport（小米 15 / 24129PN74C 时间序列）。
 * 与 RealReportTest 的单报告端到端不同，这里锁的是**序列级**行为：
 * 三份报告串成曲线后，容量来源降级、循环速率、到达 80% 推断这些
 * 多报告链路在真实数据上是否成立。
 *
 * 真实数据快照（解析一次、用例共用，别在用例里重复解析——每份 130 万行）：
 *   10-20 20:42  health 84.6  learned 4567  cycle 297  temp 40.1  level 59
 *   10-24 16:29  health 86.0  learned 4643  cycle 303  temp 41.4  level 42
 *   10-24 19:21  health 87.5  learned 4725  cycle 303  temp 39.3  level 100
 * 注意健康度是"升"的：满充校准后学习容量上修，这正是曲线不拟合、直白画点的理由。
 */
class SeriesReportTest {

    private class Src(file: File) : SeekSource {
        private val raf = RandomAccessFile(file, "r")
        override val size: Long get() = raf.length()
        override fun read(pos: Long, len: Int): ByteArray {
            raf.seek(pos)
            val b = ByteArray(len)
            val n = raf.read(b, 0, len)
            return if (n <= 0) ByteArray(0) else if (n == len) b else b.copyOf(n)
        }
    }

    data class Row(
        val epoch: Long,
        val found: BugReportScanner.Found,
        val health: HealthResult
    )

    companion object {
        // 真机样本不入库：把 zip 放到项目根 testdata/ 即启用（找不到自动跳过）
        private val FILES = arrayOf(
            "bugreport-2026-10-20-204254.zip",
            "bugreport-2026-10-24-162937.zip",
            "bugreport-2026-10-24-192114.zip"
        )

        private fun find(name: String): File? =
            listOf("testdata/$name", "../testdata/$name")
                .firstNotNullOfOrNull { p -> File(p).takeIf { it.exists() } }

        private var rows: List<Row>? = null

        @BeforeClass
        @JvmStatic
        fun parseAll() {
            val fs = FILES.mapNotNull { find(it) }
            if (fs.size < 3) {
                println("[skip] 只找到 ${fs.size}/3 份序列样本（小米 15 用户机）")
                return
            }
            rows = fs.map { f ->
                val scanner = BugReportScanner()
                ZipReader.open(Src(f)).stream(
                    onLine = { scanner.feedLine(it) },
                    shouldStop = { scanner.shouldStop() }
                )
                Row(
                    epoch = scanner.reportEpoch() ?: 0L,
                    found = scanner.result(),
                    health = Health.compute(scanner.toGauge())
                )
            }
        }

        private fun rowsOrSkip(): List<Row> = rows ?: run {
            println("[skip] 样本缺失，跳过断言")
            emptyList()
        }
    }

    /** 三份报告：同一台小米 15，数值与解析结果一致，时间顺序正确 */
    @Test fun seriesEndToEnd() {
        val rs = rowsOrSkip()
        if (rs.isEmpty()) return
        assertEquals(3, rs.size)

        // 同一台机器
        for (r in rs) {
            assertEquals("24129PN74C", r.found.props["ro.product.model"])
            assertEquals("Xiaomi 15", r.found.props["ro.product.marketname"])
        }

        // 报告自身时间（dumpstate 行）按文件名顺序递增
        assertTrue(rs[0].epoch < rs[1].epoch && rs[1].epoch < rs[2].epoch)

        // 逐份锁定关键值（真实数据回归锚点）
        val h = rs.map { it.health.healthPct!! }
        assertEquals(84.6, h[0], 0.15)
        assertEquals(86.0, h[1], 0.15)
        assertEquals(87.5, h[2], 0.15)
        assertEquals(listOf(297, 303, 303), rs.map { it.health.cycle })
        assertEquals(listOf(4567.0, 4643.0, 4725.0), rs.map { it.health.actualMah!! })
        assertEquals(listOf(40.1, 41.4, 39.3), rs.map { it.health.tempC!! })
        assertEquals(listOf(59, 42, 100), rs.map { it.health.level!! })

        for (r in rs) {
            // 小米 15 的这份序列里内核没报 charge_full，容量走学习值兜底
            assertEquals("系统学习容量 (learned)", r.health.actualSrc)
            // 设计容量：内核没给 → 机型库的官方标称 5400
            assertEquals(5400.0, r.health.designMah!!, 0.5)
            assertTrue(r.health.designSrc.contains("机型库"))
            assertEquals("中", r.health.confidence)
        }

        // 学习容量随满充校准上修（10-24 19:21 那份电量 100%）
        assertTrue(rs[0].health.actualMah!! < rs[1].health.actualMah!!)
        assertTrue(rs[1].health.actualMah!! < rs[2].health.actualMah!!)
    }

    /** 三份串成序列后的 80% 推断：循环速率、基准偏差、剩余天数都应有意义 */
    @Test fun predictionOnRealSeries() {
        val rs = rowsOrSkip()
        if (rs.isEmpty()) return
        val series = rs.map { Baseline.Sample(it.epoch, it.health.cycle?.toDouble(), it.health.healthPct) }
        val p = Baseline.predict(0L, series, Baseline.builtIn)

        // 4 天 6 次循环 → ≈1.52 次/天
        assertEquals(1.52, p.cyclesPerDay!!, 0.1)
        // 健康度在涨（学习容量上修），本机实测衰减速率不应被算出来
        assertNull("健康度上升时不应给出衰减速率", p.dropPer100)
        // 同循环数（303）基准 92.9%，本机 87.5% → 差 -5.4 点
        assertEquals(92.9, p.baselineHealthNow!!, 0.15)
        assertEquals(-5.4, p.deviation!!, 0.15)
        // 无本机衰减速率 → 走群体基准：800 - 303 = 497 次循环 ≈ 327 天
        assertEquals(497.0, p.cyclesTo80!!, 0.5)
        assertEquals(327.0, p.daysTo80!!, 15.0)
        assertNotNull(p.etaTs)
        assertFalse(p.alreadyBelow)
    }
}
