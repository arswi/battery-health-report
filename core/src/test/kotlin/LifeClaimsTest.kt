package com.xiaolei.battery.core

import org.junit.Assert.*
import org.junit.Test

/** 官方循环寿命表：只认有据可查的机型，边界不能误吞 */
class LifeClaimsTest {

    @Test fun knownModels() {
        // 有官方数字的机型
        assertEquals(1600, LifeClaims.claimFor("Xiaomi 15")!!.cyclesTo80)
        assertEquals(1600, LifeClaims.claimFor("Xiaomi 15 Pro")!!.cyclesTo80)
        assertEquals(1600, LifeClaims.claimFor("Xiaomi 15 Ultra")!!.cyclesTo80)
        assertEquals(1600, LifeClaims.claimFor("Xiaomi 14 Ultra")!!.cyclesTo80)
        assertEquals(1600, LifeClaims.claimFor("Xiaomi 17 Pro Max")!!.cyclesTo80)
        assertEquals(1600, LifeClaims.claimFor("Xiaomi 17T")!!.cyclesTo80)
        assertEquals(1600, LifeClaims.claimFor("Redmi Note 14 Pro+")!!.cyclesTo80)
        assertEquals(1600, LifeClaims.claimFor("Redmi Turbo 4 Pro")!!.cyclesTo80)
        assertEquals(1600, LifeClaims.claimFor("Redmi K80 Pro")!!.cyclesTo80)
        // 每条都带来源说明（界面上要展示）
        assertTrue(LifeClaims.claimFor("Xiaomi 15")!!.source.isNotBlank())
    }

    @Test fun unknownModelsReturnNull() {
        // 查不到官方数字的机型必须返回 null，绝不能编
        assertNull(LifeClaims.claimFor("Xiaomi 13"))          // 13 系列无官方宣称
        assertNull(LifeClaims.claimFor("2211133C"))           // 小米13 的 model 码
        assertNull(LifeClaims.claimFor("Xiaomi 14"))          // 14 标准版无
        assertNull(LifeClaims.claimFor("Xiaomi 16"))          // 16 未查到官方宣称
        assertNull(LifeClaims.claimFor("Xiaomi 18 Pro"))      // 18 系列未查到官方宣称
        assertNull(LifeClaims.claimFor("Redmi K70"))
        assertNull(LifeClaims.claimFor("Redmi Note 13 Pro"))
        assertNull(LifeClaims.claimFor(null, "", "  "))
    }

    @Test fun boundaryNoOverreach() {
        // "Xiaomi 15T" 没有 15 系列的宣称待遇（15T 不在已核实清单里）
        assertNull(LifeClaims.claimFor("Xiaomi 15T"))
        // 键按 marketname → model → device 依次放宽
        assertEquals(1600, LifeClaims.claimFor(null, "24129PN0CC", null)!!.cyclesTo80)
    }

    @Test fun parseOnlineClaims() {
        // 基准地址返回的 JSON 可同时带 points + claims
        val json = """{"points":[[0,100],[800,80]],
            "claims":[["xiaomi\\s*99", 2000, "测试源（虚构机型）"],
                      ["bad regex ((((", 3000, "应被跳过"]]}"""
        val list = LifeClaims.parse(json)!!
        assertEquals(2, list.size)
        val hit = LifeClaims.match(list, "Xiaomi 99 Pro", "20999XX0C")
        assertNotNull(hit)
        assertEquals(2000, hit!!.cyclesTo80)
        assertEquals("测试源（虚构机型）", hit.source)
        // 非法正则的条目不能让整个匹配炸掉，也匹配不到
        assertNull(LifeClaims.match(list, "bad regex"))
    }

    @Test fun parseBackwardCompatible() {
        // 纯曲线格式（v4.0 的老地址）没有 claims → null，不影响曲线解析
        assertNull(LifeClaims.parse("[[0,100],[800,80]]"))
        assertNull(LifeClaims.parse("""{"points":[[0,100]]}"""))
        assertNull(LifeClaims.parse("not json at all"))
        // claims 里数字不合理也整条丢弃
        assertNull(LifeClaims.parse("""{"claims":[["x", 5, "太小的数"]]}"""))
    }

    /** 官方宣称参与推断：参考点从群体基准 800 换成官方 1600 */
    @Test fun predictUsesOfficialClaim() {
        // 每天 2 次循环，200 次时健康 95%（无下降趋势 → 走参考点分支）
        val t0 = 1_700_000_000_000L
        val series = listOf(
            Baseline.Sample(t0, 100.0, 95.0),
            Baseline.Sample(t0 + 50 * 86400000L, 200.0, 95.0)
        )
        val byBase = Baseline.predict(0L, series, Baseline.builtIn, nowTs = t0 + 50 * 86400000L)
        assertEquals(800.0 - 200.0, byBase.cyclesTo80!!, 0.01)
        assertEquals(300.0, byBase.daysTo80!!, 0.5)
        assertTrue(byBase.basis.contains("群体基准"))

        val byClaim = Baseline.predict(
            0L, series, Baseline.builtIn,
            nowTs = t0 + 50 * 86400000L, officialCyclesTo80 = 1600.0
        )
        assertEquals(1400.0, byClaim.cyclesTo80!!, 0.01)
        assertEquals(700.0, byClaim.daysTo80!!, 0.5)
        assertEquals(1600.0, byClaim.officialCyclesTo80!!, 0.01)
        assertTrue(byClaim.basis.contains("官方宣称"))
    }

    /** 本机实测衰减速率依然最优先，官方宣称不覆盖实测 */
    @Test fun measuredRateStillWins() {
        val t0 = 1_700_000_000_000L
        val series = listOf(
            Baseline.Sample(t0, 100.0, 96.0),
            Baseline.Sample(t0 + 100 * 86400000L, 200.0, 94.0)   // 2 点/100 循环
        )
        val p = Baseline.predict(
            0L, series, Baseline.builtIn,
            nowTs = t0 + 100 * 86400000L, officialCyclesTo80 = 1600.0
        )
        assertTrue(p.basis.contains("本机实测"))
        assertEquals((94.0 - 80.0) / 2.0 * 100.0, p.cyclesTo80!!, 0.01)
    }
}
