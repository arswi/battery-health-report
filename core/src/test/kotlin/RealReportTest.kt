package com.xiaolei.battery.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 拿用户的两份真机 bugreport 做端到端验证。
 *
 * 小米 18 Pro：260MB 外层包 → 201MB 内层 zip → 208MB 主报告排在倒数第 4 条。
 *   这是把"整包读进内存"的实现直接打死的反例，这里必须流式穿过它。
 * 小米 13：65MB 包中包，主报告 170MB。
 */
class RealReportTest {

    private fun find(vararg names: String): File? {
        for (n in names) {
            val f = File(n)
            if (f.exists()) return f
        }
        return null
    }

    private fun openReport(f: File): OpenedReport = ZipReader.open(FileSeekSource(f))

    /** 用较小的堆限制跑，证明内存恒定 */
    private fun parseReport(f: File, stopEarly: Boolean): Pair<BugReportScanner.Found, Long> {
        val rep = openReport(f)
        val scanner = BugReportScanner()
        var lines = 0L
        val stats = rep.stream(
            onLine = {
                lines++
                scanner.feedLine(it)
            },
            shouldStop = if (stopEarly) {
                { scanner.shouldStop() }
            } else null
        )
        return scanner.result() to lines
    }

    // 真机样本不入库：把 zip 放到项目根 testdata/ 即启用（找不到自动跳过）
    private val MI18 = arrayOf(
        "testdata/bugreport-2026-10-21-182853.zip",
        "../testdata/bugreport-2026-10-21-182853.zip"
    )
    private val MI13 = arrayOf(
        "testdata/bugreport-2025-04-10-101939.zip",
        "../testdata/bugreport-2025-04-10-101939.zip"
    )

    @Test fun demoSingleLayerZip() {
        val f = find("../bugreport-analyzer/demo_bugreport.zip", "../../bugreport-analyzer/demo_bugreport.zip")
            ?: return
        val rep = openReport(f)
        val (res, _) = parseReport(f, false)
        assertTrue("应看到机型", res.props.containsKey("ro.product.model"))
        println("[demo] entry=${rep.fname} nested=${rep.nestedFrom} lines=${res.lines}")
    }

    @Test fun xiaomi18ProNested() {
        val f = find(*MI18) ?: run {
            println("[skip] 未找到小米18 Pro 样本"); return
        }
        val t0 = System.currentTimeMillis()
        val rep = openReport(f)
        assertTrue("必须识别出包中包", rep.nestedFrom != null)
        assertEquals("bugreport-hongkong-CP2A.260605.016-2026-10-21-18-28-54.txt", rep.fname)

        val (res, lines) = parseReport(f, stopEarly = true)
        val ms = System.currentTimeMillis() - t0

        assertEquals("市场名应为 Xiaomi 18 Pro", "Xiaomi 18 Pro", res.props["ro.product.marketname"])
        println("[18Pro] ${ms}ms lines=$lines model=${res.props["ro.product.model"]} device=${res.props["ro.product.device"]}")

        assertTrue(lines > 10000)
    }

    @Test fun xiaomi18ProFullHealth() {
        val f = find(*MI18) ?: return
        val scanner = BugReportScanner()
        val rep = openReport(f)
        rep.stream(onLine = { scanner.feedLine(it) }, shouldStop = { scanner.shouldStop() })
        val r = Health.compute(scanner.toGauge())
        assertEquals("Xiaomi 18 Pro", scanner.result().props["ro.product.marketname"])
        // v4.3.2：设计容量按官方典型标称 7000（内核 6949 是额定口径）→ 6921/7000
        assertEquals(98.9, r.healthPct!!, 0.2)
        assertEquals(22, r.cycle)
        assertEquals(76, r.level)
        println("[18Pro health] ${r.healthPct}% actual=${r.actualMah} design=${r.designMah} src=${r.actualSrc}/${r.designSrc}")
    }

    @Test fun xiaomi13Health() {
        val f = find(*MI13) ?: run { println("[skip] 未找到小米13 样本"); return }
        val t0 = System.currentTimeMillis()
        val scanner = BugReportScanner()
        val rep = openReport(f)
        assertTrue("小米13 也是包中包", rep.nestedFrom != null)
        rep.stream(onLine = { scanner.feedLine(it) }, shouldStop = { scanner.shouldStop() })
        val ms = System.currentTimeMillis() - t0
        val res = scanner.result()
        println("[13 DEBUG] psu=${res.psu.keys.take(20)}")
        println("[13 DEBUG] found=${res.found.keys.take(20)} sz=${res.found["est_cap_mah"]} cycle=${res.found["cycle"]}")
        println("[13 DEBUG] bat=${scanner.result().sections["battery"]?.size} pairs=${res.sections["battery"]?.take(8)}")
        println("[13 DEBUG] lines=${res.lines}")
        val r = Health.compute(scanner.toGauge())
        assertEquals("Xiaomi 13", res.props["ro.product.marketname"])
        assertEquals(93.2, r.healthPct!!, 0.3)
        assertEquals(379, r.cycle)
        assertEquals(64, r.level)
        println("[13 health] ${ms}ms ${r.healthPct}% ${r.actualMah}/${r.designMah} src=${r.actualSrc}/${r.designSrc}")
    }
}
