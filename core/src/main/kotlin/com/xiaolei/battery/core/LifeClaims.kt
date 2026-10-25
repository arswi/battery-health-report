package com.xiaolei.battery.core

/**
 * 厂商官方宣称的电池循环寿命（X 次完整循环后容量仍 ≥80%）。
 *
 * 收录原则（和用户约定过的：宁可缺，不编造）：
 *  1. 只收厂商在发布会/官网规格页明确写出的循环寿命数字，每条带来源说明；
 *  2. 查不到官方数字的机型一律不列（界面显示"未查到官方数据"，推断退回群体基准）；
 *  3. 官方数字是实验室理想条件，高温/快充环境下实际寿命通常更低——展示时必须带上这层含义。
 *
 * 表内数字 2026-10 逐条核实过（官方发布/官网规格页，多家媒体交叉）。
 * 联机刷新：基准地址返回的 JSON 里可以带 {"claims":[["正则",次数,"来源"],...]}，
 * 命中即覆盖内置表（见 parse），这样官方出新机后不用发版也能补。
 */
object LifeClaims {

    data class Claim(val cyclesTo80: Int, val source: String)

    // 匹配串含义与 DeviceDb 一致：marketname / model / device 代号，小写包含匹配。
    // 注意边界：`xiaomi\s*15(?:$|\s)` 不会误吞 "Xiaomi 15T"（15T 没有官方寿命数据）。
    private val TABLE = listOf(
        // 小米 14 Ultra —— 金沙江电池首发（2024-02 官方发布：1600 次循环容量保持 ≥80%）
        Regex("""xiaomi\s*14\s*ultra|aurora""") to Claim(1600, "小米官方发布 2024-02（金沙江电池首发）"),
        // 小米 15 全系（15 / 15 Pro / 15 Ultra / 15S Pro）—— 官网规格：1600 次循环 ≥80%
        Regex("""xiaomi\s*15(?:$|\s)|dada|haotian|odin|24129pn0""") to Claim(1600, "小米官方规格页 2024-10（全系金沙江电池）"),
        // 小米 17 / 17 Pro / 17 Pro Max —— 官网规格页脚注：1600 次循环容量保持 80% 以上（小米内部实验室）
        Regex("""xiaomi\s*17(?:$|\s)""") to Claim(1600, "小米官网规格页（17 系列）"),
        // 小米 17T 系列（2026-06 官宣，7000mAh 金沙江）
        Regex("""xiaomi\s*17t""") to Claim(1600, "小米官方 2026-06（17T 系列发布）"),
        // Redmi Note 14 Pro / Pro+ —— 官网 + TÜV 南德长寿命认证
        Regex("""redmi\s*note\s*14\s*pro""") to Claim(1600, "Redmi 官网 · TÜV 南德长寿命认证"),
        // Redmi Turbo 4 / Turbo 4 Pro —— REDMI 官方 2025-01：1600 次循环剩余电量 >80%
        Regex("""redmi\s*turbo\s*4""") to Claim(1600, "REDMI 官方发布 2025-01"),
        // Redmi K80 / K80 Pro —— 官方发布 2024-11（金沙江电池）
        Regex("""redmi\s*k80(?:$|\s)""") to Claim(1600, "REDMI 官方发布 2024-11")
    )

    /** 依次用更宽松的键去试（marketname → model → device 代号） */
    fun claimFor(vararg keys: String?): Claim? {
        for (k in keys) {
            val s = (k ?: "").lowercase().trim()
            if (s.isEmpty()) continue
            for ((rx, c) in TABLE) {
                if (rx.containsMatchIn(s)) return c
            }
        }
        return null
    }

    /** 在联机拉到的表里找（pattern 是正则字符串）；正则非法的条目跳过不炸 */
    fun match(entries: List<Pair<String, Claim>>, vararg keys: String?): Claim? {
        val ks = keys.mapNotNull { it?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } }
        if (ks.isEmpty()) return null
        for ((pat, c) in entries) {
            val rx = runCatching { Regex(pat, RegexOption.IGNORE_CASE) }.getOrNull() ?: continue
            if (ks.any { rx.containsMatchIn(it) }) return c
        }
        return null
    }

    /**
     * 从基准地址返回的 JSON 里解析 "claims" 数组：
     *   {"points":[[0,100],...], "claims":[["xiaomi\\s*99", 2000, "某年官方发布"], ...]}
     * 没有合法 claims 时返回 null（兼容纯曲线格式的旧地址）。
     * 手写解析不引 JSON 库：pattern 里带 \s 等转义，用正则剥字符串会碎。
     */
    fun parse(text: String): List<Pair<String, Claim>>? = runCatching {
        val t = text.trim()
        if (!t.startsWith("{")) return null
        val ki = t.indexOf("\"claims\"")
        if (ki < 0) return null
        var a = t.indexOf('[', ki)
        if (a < 0) return null
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
        val body = t.substring(a + 1, b)

        val out = ArrayList<Pair<String, Claim>>()
        var i = 0
        while (i < body.length) {
            if (body[i] != '[') { i++; continue }
            // 找这个内层数组的结尾（claims 条目里不会再嵌套数组）
            val end = body.indexOf(']', i)
            if (end < 0) break
            val item = body.substring(i + 1, end)
            i = end + 1

            // 逐字符读出字符串/数字字段
            val fields = ArrayList<String>()
            var j = 0
            while (j < item.length && fields.size < 3) {
                val ch = item[j]
                when {
                    ch == '"' -> {
                        val sb = StringBuilder()
                        j++
                        while (j < item.length) {
                            val c2 = item[j]
                            if (c2 == '\\' && j + 1 < item.length) {
                                sb.append(unescape(item[j + 1])); j += 2
                            } else if (c2 == '"') { j++; break } else { sb.append(c2); j++ }
                        }
                        fields.add(sb.toString())
                    }
                    ch.isDigit() || ch == '-' -> {
                        val st = j
                        j++
                        while (j < item.length && (item[j].isDigit())) j++
                        fields.add(item.substring(st, j))
                    }
                    else -> j++
                }
            }
            if (fields.size >= 2) {
                val cyc = fields[1].toIntOrNull()
                if (cyc != null && cyc in 100..10000) {
                    out.add(fields[0] to Claim(cyc, fields.getOrElse(2) { "联机数据" }))
                }
            }
        }
        if (out.isEmpty()) null else out
    }.getOrNull()

    /** JSON 转义还原（只处理会出现在正则/中文文案里的那几种） */
    private fun unescape(c: Char): Char = when (c) {
        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
        else -> c   // \\ \" \/ \s \d 等按原字符返回，\\s 还原成 \s 正好是想要的
    }
}
