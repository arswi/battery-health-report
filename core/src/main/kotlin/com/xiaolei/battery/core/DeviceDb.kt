package com.xiaolei.battery.core

/**
 * SoC 代号 → 市场名；机型 → 官方标称电池容量（典型值）。
 * v4.3.2 起官方标称是设计容量的第一优先级：固件 power_profile / charge_full_design
 * 常是"额定值"口径或随报告变化的动态估算（真机：18 Pro 6949 vs 官方 7000、
 * 小米15 的 4873/4574），口径不统一会让不同机型的健康度基准不可比。
 */
object DeviceDb {

    private val SOC = mapOf(
        "sm8950" to "第六代骁龙8至尊版 (Snapdragon 8 Elite Gen 6)",
        "sm8975" to "第六代骁龙8超级至尊版 (Snapdragon 8 Elite Extreme Gen 6)",
        "sm8850" to "第五代骁龙8至尊版 (Snapdragon 8 Elite Gen 5)",
        "sm8750" to "骁龙8至尊版 (Snapdragon 8 Elite)",
        "sun" to "骁龙8至尊版 (Snapdragon 8 Elite, SM8750)",
        "sm8650" to "骁龙8 Gen3",
        "pineapple" to "骁龙8 Gen3 (SM8650)",
        "sm8550" to "骁龙8 Gen2",
        "kalama" to "骁龙8 Gen2 (SM8550)",
        "sm8475" to "骁龙8+ Gen1",
        "sm8450" to "骁龙8 Gen1",
        "taro" to "骁龙8/8+ Gen1 (SM8450/SM8475)",
        "sm8350" to "骁龙888",
        "lahaina" to "骁龙888 (SM8350)",
        "sm8250" to "骁龙865/870",
        "kona" to "骁龙865/870 (SM8250)",
        "sm8150" to "骁龙855",
        "msmnile" to "骁龙855 (SM8150)",
        "sm8635" to "骁龙8s Gen3",
        "sm8735" to "骁龙8s Gen4",
        "sm7475" to "骁龙7+ Gen2",
        "yupik" to "骁龙7+ Gen2 (SM7475)",
        "sm7675" to "骁龙7+ Gen3",
        "sm7450" to "骁龙7 Gen1",
        "sm7325" to "骁龙778G/782G",
        "sm7250" to "骁龙765G",
        "lito" to "骁龙765G (SM7250)",
        "sm6150" to "骁龙730",
        "sm6375" to "骁龙695",
        "holi" to "骁龙695 (SM6375)",
        "sm6225" to "骁龙680",
        "khaje" to "骁龙680 (SM6225)",
        "bengal" to "骁龙665/662/460 系列",
        "mt6991" to "天玑9400 系列",
        "mt6986" to "天玑9300",
        "mt6985" to "天玑9200/9200+",
        "mt6983" to "天玑9000/9000+",
        "mt6989" to "天玑8300/8400 系列",
        "mt6895" to "天玑8100/8000",
        "mt6893" to "天玑1200/1100",
        "mt6877" to "天玑900",
        "mt6833" to "天玑700",
        "mt6853" to "天玑720"
    )

    /**
     * 机型 → 官方标称容量 (mAh)。
     * 匹配串同时适用于 ro.product.model / ro.product.marketname / ro.product.device /
     * Build.DEVICE / Build.MODEL，统一转成小写后再匹配，所以只写 Xiaomi / Redmi 前缀即可。
     */
    private val DESIGN = listOf(
        Regex("""xiaomi\s*18\s*pro\s*max""") to (7500 to "Xiaomi 18 Pro Max"),
        Regex("""xiaomi\s*18\s*pro|m610bb""") to (7000 to "Xiaomi 18 Pro"),
        Regex("""xiaomi\s*18(?!\s*(?:pro|ultra|max))""") to (7000 to "Xiaomi 18"),
        Regex("""xiaomi\s*1[67]\s*pro\s*max""") to (7500 to "Xiaomi 16/17 Pro Max"),
        Regex("""xiaomi\s*1[67]\s*pro""") to (6300 to "Xiaomi 16/17 Pro"),
        Regex("""xiaomi\s*1[67](?!\s*(?:pro|ultra|max))""") to (7000 to "Xiaomi 16/17"),
        Regex("""xiaomi\s*15\s*s\s*pro""") to (6100 to "Xiaomi 15S Pro"),
        Regex("""xiaomi\s*15\s*ultra|odin""") to (6000 to "Xiaomi 15 Ultra"),
        Regex("""xiaomi\s*15\s*pro|haotian""") to (6100 to "Xiaomi 15 Pro"),
        Regex("""xiaomi\s*15|dada|24129pn0""") to (5400 to "Xiaomi 15"),
        Regex("""xiaomi\s*14\s*ultra|aurora""") to (5300 to "Xiaomi 14 Ultra"),
        Regex("""xiaomi\s*14\s*pro|shennong""") to (4880 to "Xiaomi 14 Pro"),
        Regex("""xiaomi\s*14t""") to (5000 to "Xiaomi 14T 系列"),
        Regex("""xiaomi\s*14|houji|23127pn0""") to (4610 to "Xiaomi 14"),
        Regex("""xiaomi\s*13\s*ultra|ishtar""") to (5000 to "Xiaomi 13 Ultra"),
        Regex("""xiaomi\s*13\s*pro|nuwa""") to (4820 to "Xiaomi 13 Pro"),
        Regex("""xiaomi\s*13t""") to (5000 to "Xiaomi 13T 系列"),
        Regex("""xiaomi\s*13|fuxi|2211133c|2210132c""") to (4500 to "Xiaomi 13"),
        Regex("""xiaomi\s*12s\s*ultra|thor""") to (4860 to "Xiaomi 12S Ultra"),
        Regex("""xiaomi\s*12s\s*pro|unicorn""") to (4600 to "Xiaomi 12S Pro"),
        Regex("""xiaomi\s*12s|mayfly""") to (4500 to "Xiaomi 12S"),
        Regex("""xiaomi\s*12\s*pro|zeus""") to (4600 to "Xiaomi 12 Pro"),
        Regex("""xiaomi\s*12x|psyche""") to (4500 to "Xiaomi 12X"),
        Regex("""xiaomi\s*12|cupid|2201123c""") to (4500 to "Xiaomi 12"),
        Regex("""(?:xiaomi|mi)\s*11\s*ultra|star""") to (5000 to "Xiaomi 11 Ultra"),
        Regex("""(?:xiaomi|mi)\s*11\s*pro|mars""") to (5000 to "Xiaomi 11 Pro"),
        Regex("""(?:xiaomi|mi)\s*11\s*t""") to (5000 to "Xiaomi 11T 系列"),
        Regex("""(?:xiaomi|mi)\s*11|venus""") to (4600 to "Xiaomi Mi 11"),
        Regex("""mi\s*10\s*ultra|cas""") to (4500 to "Mi 10 Ultra"),
        Regex("""mi\s*10\s*pro|cmi""") to (4500 to "Mi 10 Pro"),
        Regex("""mi\s*10|umi""") to (4780 to "Mi 10"),
        Regex("""redmi\s*k80\s*pro""") to (6000 to "Redmi K80 Pro"),
        Regex("""redmi\s*k80""") to (6550 to "Redmi K80"),
        Regex("""redmi\s*k70\s*ultra""") to (5500 to "Redmi K70 Ultra"),
        Regex("""redmi\s*k70\s*pro|manet""") to (5000 to "Redmi K70 Pro"),
        Regex("""redmi\s*k70e""") to (5500 to "Redmi K70E"),
        Regex("""redmi\s*k70|vermeer""") to (5000 to "Redmi K70"),
        Regex("""redmi\s*k60\s*ultra|corot""") to (5000 to "Redmi K60 Ultra"),
        Regex("""redmi\s*k60\s*pro|socrates""") to (5000 to "Redmi K60 Pro"),
        Regex("""redmi\s*k60|sundi""") to (5500 to "Redmi K60"),
        Regex("""redmi\s*k50\s*ultra|diting""") to (5000 to "Redmi K50 Ultra"),
        Regex("""redmi\s*k50\s*pro""") to (5000 to "Redmi K50 Pro"),
        Regex("""redmi\s*k50|rubens""") to (5500 to "Redmi K50"),
        Regex("""redmi\s*k40\s*gaming""") to (5065 to "Redmi K40 电竞版"),
        Regex("""redmi\s*k40|alioth|m2012k11ac""") to (4520 to "Redmi K40 系列"),
        Regex("""redmi\s*k30\s*ultra""") to (4500 to "Redmi K30 Ultra"),
        Regex("""redmi\s*k30\s*s""") to (5000 to "Redmi K30S"),
        Regex("""redmi\s*k30\s*pro""") to (4700 to "Redmi K30 Pro"),
        Regex("""redmi\s*k30""") to (4500 to "Redmi K30"),
        Regex("""redmi\s*turbo\s*4\s*pro""") to (7550 to "Redmi Turbo 4 Pro"),
        Regex("""redmi\s*turbo\s*4""") to (6550 to "Redmi Turbo 4"),
        Regex("""redmi\s*turbo\s*3""") to (5000 to "Redmi Turbo 3"),
        Regex("""redmi\s*note\s*14\s*pro\s*\+""") to (6200 to "Redmi Note 14 Pro+"),
        Regex("""redmi\s*note\s*14\s*pro""") to (5500 to "Redmi Note 14 Pro"),
        Regex("""redmi\s*note\s*14""") to (5110 to "Redmi Note 14"),
        Regex("""redmi\s*note\s*13\s*pro\s*\+""") to (5000 to "Redmi Note 13 Pro+"),
        Regex("""redmi\s*note\s*13\s*pro""") to (5120 to "Redmi Note 13 Pro"),
        Regex("""redmi\s*note\s*13""") to (5000 to "Redmi Note 13"),
        Regex("""redmi\s*note\s*12\s*pro\s*\+""") to (5000 to "Redmi Note 12 Pro+"),
        Regex("""redmi\s*note\s*12\s*turbo""") to (5000 to "Redmi Note 12 Turbo"),
        Regex("""redmi\s*note\s*12""") to (5000 to "Redmi Note 12"),
        Regex("""redmi\s*note\s*11\s*pro\s*\+""") to (4500 to "Redmi Note 11 Pro+"),
        Regex("""redmi\s*note\s*11\s*pro""") to (5160 to "Redmi Note 11 Pro"),
        Regex("""redmi\s*note\s*11""") to (5000 to "Redmi Note 11"),
        Regex("""redmi\s*note\s*10""") to (5000 to "Redmi Note 10"),
        Regex("""redmi\s*note\s*9""") to (4820 to "Redmi Note 9"),
        Regex("""redmi\s*note\s*8""") to (4000 to "Redmi Note 8"),
        Regex("""redmi\s*14\s*c""") to (5160 to "Redmi 14C"),
        Regex("""mi\s*pad""") to (10000 to "小米平板（默认，请手动校正）")
    )

    fun socName(code: String?): String? {
        val k = (code ?: "").trim().lowercase()
        if (k.isEmpty()) return null
        return SOC[k] ?: SOC.entries.firstOrNull { k.contains(it.key) }?.value
    }

    /** 依次用更宽松的键去试，返回 (容量 mAh, 匹配说明) */
    fun designCapacity(vararg keys: String?): Pair<Int, String>? {
        for (k in keys) {
            val s = (k ?: "").lowercase().trim()
            if (s.isEmpty()) continue
            for ((rx, v) in DESIGN) {
                if (rx.containsMatchIn(s)) return v
            }
        }
        return null
    }
}
