package com.xiaolei.battery.core

import java.util.Locale

/**
 * bugreport 文本解析器（逐行喂入，流式）。
 *
 * 规则全部按小米/红米真机产出校准过，几个反直觉的点：
 *  1. Android 12+ 段落头是 `DUMP OF SERVICE battery:`，不再有 `------` 包裹
 *  2. `Estimated battery capacity` 有些固件写的是 Ah（4 = 4000mAh）
 *  3. `Charge counter` 有时报的是 mAh 而不是 µAh（差 1000 倍）
 *  4. 电池段可能排在全文 54% 处，不能假设"数据在开头"
 */
class BugReportScanner {

    data class Found(
        val props: Map<String, String>,
        val psu: Map<String, String>,
        val sections: Map<String, List<String>>,
        val found: Map<String, Any>,
        val imeis: List<String>,
        val lines: Long
    )

    private val props = HashMap<String, String>()
    private val psu = LinkedHashMap<String, String>()
    private val sections = HashMap<String, MutableList<String>>()
    private val found = HashMap<String, Any>()
    private val imeis = LinkedHashSet<String>()
    private var nlines = 0L
    private var curSection: String? = null
    private var left = 0
    private var weakAtLine = -1L
    private var capacityBlockLine = -1L
    /** 报告生成时间原文，例如 "2025-04-10 10:19:39" */
    private var reportDate: String? = null

    companion object {
        private val PROP = Regex("""^\s*\[([^\]]+)\]:\s*\[(.*)\]\s*$""")
        private val OLD_SERVICE = Regex("""^-{4,}\s*DUMP OF SERVICE\s+(\S+)\s*-{4,}\s*$""")
        private val NEW_SERVICE = Regex("""^\s*DUMP OF SERVICE\s+(\S+?)[\s:]*$""")
        private val EST_CAP = Regex("""Estimated battery capacity[^\d\-]{0,20}([\d.]+)\s*(mAh|Ah|mah|ah)?""", RegexOption.IGNORE_CASE)
        // 真机上最常见的写法是 "Min learned battery capacity: 4196 mAh"（形容词在前），
        // 部分 ROM 才写成 "learned min 4196"。两种都得认。
        private val LEARNED = Regex(
            """(last|min|max)\s+learned battery capacity\s*[:=]\s*([\d.]+)""",
            RegexOption.IGNORE_CASE
        )
        private val LEARNED_ALT = Regex("""learned\s+(min|max)\s+[\d.]+""", RegexOption.IGNORE_CASE)
        private val CYCLE = Regex("""cycle\s*count\s*[:= ]\s*(\d{1,6})""", RegexOption.IGNORE_CASE)
        private val CYCLE_PSU = Regex("""cycle_count\s*=\s*(-?\d+)""")
        private val MEMTOTAL = Regex("""^MemTotal:\s*([\d,]+)\s*kB""")
        private val TOTAL_RAM = Regex("""Total RAM:\s*([\d,]+)""")
        private val DATA_TOTAL = Regex("""Data-total:\s*([\d,]+)K""")
        private val IMEI = Regex("""\b(8[6-9]\d{13}|\d{15})\b""")
        /** `== dumpstate: 2025-04-10 10:19:39` —— 报告生成时间，永远在文件头几行 */
        private val DATE = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})""")

        private val CAPTURE = mapOf("battery" to 400, "batteryproperties" to 300, "batterystats" to 120, "iphonesubinfo" to 40)
    }

    /**
     * 热路径说明：一份 18 Pro 报告有 100 万+ 行，其中 99% 是 logcat。
     * 每行先做首字符/子串的廉价门控，只有可能命中的行才分配子串、跑正则。
     * 语义与逐行全量正则版本完全一致（有真机端到端单测锁着）。
     */
    fun feedLine(rawLine: String) {
        nlines++
        val line = if (rawLine.length > 4000) rawLine.substring(0, 4000) else rawLine
        // 手动裁边：老版本 trim() 对每行带前导空格的 logcat 都会分配一个新字符串
        var st = 0
        var en = line.length
        while (st < en && line[st] <= ' ') st++
        if (st == en) return
        while (en > st && line[en - 1] <= ' ') en--
        val c0 = line[st]

        // ---- 报告生成时间：只在文件头 2000 行内找，命中后整段跳过 ----
        if (reportDate == null && nlines <= 2000 &&
            line.indexOf("dumpstate", st, true) in st until en
        ) {
            val head = line.substring(st, en)
            DATE.find(head)?.let { reportDate = it.value.replace('T', ' ') }
        }

        var ls: String? = null
        fun lineText(): String {
            val cur = ls
            if (cur != null) return cur
            val s = line.substring(st, en)
            ls = s
            return s
        }

        // ---- [ro.product.model]: [2211133C] ----
        if (c0 == '[') {
            val p = PROP.matchEntire(lineText())
            if (p != null) {
                props[p.groupValues[1]] = p.groupValues[2]
                return
            }
        }

        // ---- 段落边界（兼容新旧两种格式）----
        if ((c0 == '-' || c0 == 'D') && en - st <= 96) {
            val name = OLD_SERVICE.find(lineText())?.groupValues?.get(1)
                ?: NEW_SERVICE.find(lineText())?.groupValues?.get(1)
            if (name != null) {
                val cap = CAPTURE[name]
                if (cap != null) {
                    curSection = name
                    left = cap
                    sections.getOrPut(name) { mutableListOf() }.add(ls!!)
                } else {
                    curSection = null
                }
                return
            }
        }

        // ---- 段落内捕获 ----
        val cs = curSection
        if (cs != null && left > 0) {
            sections[cs]!!.add(lineText())
            left--
            if (left == 0) curSection = null
        }

        // ---- POWER_SUPPLY_* ----
        if (c0 == 'P' && en - st > 13 && line.regionMatches(st, "POWER_SUPPLY_", 0, 13)) {
            val kv = lineText().removePrefix("POWER_SUPPLY_").split("=", limit = 2)
            if (kv.size == 2) psu[kv[0].trim().lowercase(Locale.US)] = kv[1].trim()
            val ck = CYCLE_PSU.find(ls!!)
            if (ck != null && !found.containsKey("cycle")) found["cycle"] = ck.groupValues[1].toIntOrNull() ?: 0
            return
        }

        // ---- 容量类 ----
        if (line.indexOf("capacity", st, true) in st until en ||
            line.indexOf("estimated", st, true) in st until en
        ) {
            val t = lineText()
            if (t.contains("Estimated battery capacity", true) && capacityBlockLine < 0) {
                capacityBlockLine = nlines
            }
            EST_CAP.find(t)?.let {
                val v = it.groupValues[1].toDoubleOrNull()
                if (v != null) found["est_cap_mah"] = Units.capacityTextToMah(v, it.groupValues[2])
            }
            val lv = LEARNED.find(t)
            if (lv != null) {
                lv.groupValues[2].toDoubleOrNull()
                    ?.let { found["learned_" + lv.groupValues[1].lowercase(Locale.US)] = it }
            }
            LEARNED_ALT.find(t)?.let { alt ->
                val num = Regex("""([\d.]+)""").find(alt.value.replace(alt.groupValues[1], ""))?.value
                num?.toDoubleOrNull()
                    ?.let { found["learned_" + alt.groupValues[1].lowercase(Locale.US)] = it }
            }
        }

        // ---- 循环次数 ----
        if (line.indexOf("cycle", st, true) in st until en) {
            CYCLE.find(lineText())?.let {
                val v = it.groupValues[1].toIntOrNull()
                if (v != null && v in 1..3000 && !found.containsKey("cycle")) found["cycle"] = v
            }
        }

        // ---- 屏幕信息 ----
        if (c0 == 'D' && en - st >= 17 && line.regionMatches(st, "DisplayDeviceInfo", 0, 17) &&
            !found.containsKey("displayinfo")
        ) {
            found["displayinfo"] = lineText()
        }

        // ---- 内存 / 存储 ----
        when (c0) {
            'M' -> if (line.regionMatches(st, "MemTotal:", 0, 9)) {
                MEMTOTAL.find(lineText())?.let { found["memtotal_kb"] = it.groupValues[1].replace(",", "") }
            }
            'T' -> if (line.regionMatches(st, "Total RAM:", 0, 10)) {
                TOTAL_RAM.find(lineText())?.let {
                    if (!found.containsKey("total_ram_kb")) found["total_ram_kb"] = it.groupValues[1].replace(",", "")
                }
            }
            'D' -> if (line.regionMatches(st, "Data-total:", 0, 11)) {
                DATA_TOTAL.find(lineText())?.let {
                    val v = it.groupValues[1].replace(",", "")
                    found["data_total_kb"] = v
                }
            }
        }

        // ---- IMEI（这类行都很短，用长度门控）----
        if (en - st < 300 && (c0 == 'D' || c0 == 'd' || c0 == 'I' || c0 == 'i')) {
            val t = lineText()
            if (t.contains("Device ID", true) || t.contains("imei", true)) {
                IMEI.findAll(t).forEach { imeis.add(it.value) }
            }
        }
    }

    private fun hasModel() = !props["ro.product.model"].isNullOrBlank()
    private fun batteryLongEnough() = (sections["battery"]?.size ?: 0) >= 5

    /**
     * 弱条件：已经有 battery 段、也已经能算出个大概容量了（哪怕是粗略的计数推算）。
     * 达到这一步不意味着该停，只是开始计时。
     */
    fun batteryReady(): Boolean {
        if (!hasModel() || !batteryLongEnough()) return false
        return psu.containsKey("charge_full") || found.containsKey("est_cap_mah") ||
            found.containsKey("learned_min") || found.containsKey("learned_max") ||
            found.containsKey("learned_last") ||
            batteryPairs().containsKey("charge counter") || batteryPairs().containsKey("level")
    }

    /**
     * 强条件：拿到了电量计原始读数或系统学习容量——这两个才是可信来源。
     * 只靠 "Charge counter ÷ 电量百分比" 推算的话，误差能到 ±10%。
     */
    fun coreReady(): Boolean {
        if (!hasModel() || !batteryLongEnough()) return false
        return psu.containsKey("charge_full") ||
            found.containsKey("learned_min") || found.containsKey("learned_max") ||
            found.containsKey("learned_last") ||
            (found.containsKey("est_cap_mah") && psu.containsKey("charge_full_design"))
    }

    /**
     * 是否该停。三级策略：
     *   1. 拿到电量计/学习容量立刻停
     *   2. 见到 "Estimated battery capacity" 这个容量块，再读 20 行也停
     *      （Min/Max learned 行紧贴其后，第 102 万行这种位置也能精准命中）
     *   3. 上述都没遇上，从弱条件起最多再扫 60 万行后认命
     */
    fun shouldStop(): Boolean {
        if (coreReady()) return true
        if (capacityBlockLine >= 0 && nlines - capacityBlockLine > 20) return true
        if (weakAtLine < 0 && batteryReady()) weakAtLine = nlines
        return weakAtLine >= 0 && nlines - weakAtLine > 600000
    }

    private fun batteryPairs(): Map<String, String> {
        val sec = sections["battery"] ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (i in 1 until sec.size) {
            val kv = sec[i].split(":", limit = 2)
            if (kv.size == 2) out[kv[0].trim().lowercase(Locale.US)] = kv[1].trim()
        }
        return out
    }

    fun result(): Found = Found(props, psu, sections, found, imeis.toList(), nlines)

    /** 报告生成时间原文（yyyy-MM-dd HH:mm:ss），拿不到返回 null */
    fun reportDateText(): String? = reportDate

    /** 报告生成时间（epoch ms，按本机时区解析），拿不到返回 null */
    fun reportEpoch(): Long? {
        val t = reportDate ?: return null
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(t)?.time
        }.getOrNull()
    }

    /** 转成统一的采样快照，和无 root 的 sysfs 采样走同一条计算链路 */
    fun toGauge(manualDesign: Double? = null): Gauge {
        val b = batteryPairs()
        fun pairsNum(key: String): Double? = b[key]?.trim()?.toDoubleOrNull()
        return Gauge(
            chargeFullDesignRaw = psu["charge_full_design"]?.toDoubleOrNull(),
            chargeFullRaw = psu["charge_full"]?.toDoubleOrNull(),
            counterRaw = pairsNum("charge counter") ?: psu["charge_now"]?.toDoubleOrNull(),
            cycleRaw = found["cycle"] as? Int,
            levelRaw = pairsNum("level")?.toInt(),
            tempRaw = pairsNum("temperature"),
            voltRaw = pairsNum("voltage"),
            currentRaw = psu["current_now"]?.toDoubleOrNull(),
            learnedMinMah = found["learned_min"] as? Double,
            learnedMaxMah = found["learned_max"] as? Double,
            profileDesignMah = found["est_cap_mah"] as? Double,
            manualDesignMah = manualDesign,
            deviceKeys = listOf(
                props["ro.product.marketname"], props["ro.product.model"],
                props["ro.product.device"], props["ro.product.name"]
            )
        )
    }

    fun displayInfo(): Triple<String, String, String> {
        val s = found["displayinfo"] as? String ?: return Triple("", "", "")
        var res = ""
        var dpi = ""
        var hz = ""
        Regex("""(?:real\s+)?(\d{3,5})\s*[xX×]\s*(\d{3,5})""").find(s.substringBefore("supportedModes").ifEmpty { s })
            ?.let { res = "${it.groupValues[1]} × ${it.groupValues[2]}" }
        Regex("""density\s+(\d{2,4})""").find(s)?.let { dpi = "${it.groupValues[1]} dpi" }
        Regex("""renderFrameRate[=:\s]+([\d.]+)""").find(s)?.let { fps ->
            val f = fps.groupValues[1].toDoubleOrNull()
            if (f != null) hz = "%g Hz".format(Locale.US, f)
        }
        if (hz.isEmpty()) {
            Regex("""fps=([\d.]+)""").find(s)?.let { fps ->
                val f = fps.groupValues[1].toDoubleOrNull()
                if (f != null) hz = "%g Hz".format(Locale.US, f)
            }
        }
        return Triple(res, dpi, hz)
    }
}
