package com.xiaolei.battery

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * 主题系统 v4.3：
 *   设置页四选一 —— 跟随系统 / 深色 / 纯黑 / 白天。
 *   "跟随系统"按系统日夜自动切：夜间=深色，日间=白天。
 *
 * 界面全部程序化构建，颜色集中在 Palette 里，
 * 换主题 = 换 Palette + 重建视图，不需要 recreate Activity。
 */
object AppTheme {

    /** 具体主题（current 的取值） */
    const val DARK = 0
    const val BLACK = 1
    const val LIGHT = 2

    /** 设置页里的选项值（存 KEY_MODE） */
    const val MODE_FOLLOW = 0
    const val MODE_DARK = 1
    const val MODE_BLACK = 2
    const val MODE_LIGHT = 3

    // 兼容 v4.2 及以前的键（存的是具体主题），迁移用
    private const val KEY_LEGACY = "theme"
    private const val KEY_MODE = "theme_mode"

    data class Palette(
        val bg: String,
        val card: String,
        val card2: String,      // 头部/强调卡
        val chip: String,       // 未选中机型 chip
        val chipActive: String, // 选中的 chip（用 accent）
        val text: String,
        val text2: String,
        val text3: String,
        val accent: String,
        val grid: String,       // 曲线网格
        val track: String,      // 环形图底环
        val btnBg: String,
        val btnText: String,
        val inputBg: String,
        val divider: String,
        val crashCard: String,
        val lightStatusBar: Boolean
    )

    private val DARK_P = Palette(
        bg = "#0B1016", card = "#151D26", card2 = "#1C2733",
        chip = "#1F2A36", chipActive = "#3D8BFF",
        text = "#E8EEF4", text2 = "#8FA0B2", text3 = "#5C6B7A",
        accent = "#3D8BFF", grid = "#26323D", track = "#1F2A36",
        btnBg = "#1F2A36", btnText = "#C8D4E0", inputBg = "#1F2A36",
        divider = "#223040", crashCard = "#241A1A", lightStatusBar = false
    )

    private val BLACK_P = Palette(
        bg = "#000000", card = "#101010", card2 = "#181818",
        chip = "#161616", chipActive = "#2F6FED",
        text = "#EDEDED", text2 = "#9A9A9A", text3 = "#616161",
        accent = "#2F6FED", grid = "#1E1E1E", track = "#181818",
        btnBg = "#161616", btnText = "#C9C9C9", inputBg = "#161616",
        divider = "#1C1C1C", crashCard = "#1C1010", lightStatusBar = false
    )

    private val LIGHT_P = Palette(
        bg = "#F2F4F6", card = "#FFFFFF", card2 = "#F7F9FA",
        chip = "#E9EDF1", chipActive = "#2F6FED",
        text = "#16191D", text2 = "#5A6570", text3 = "#8A94A0",
        accent = "#2F6FED", grid = "#E8EBEE", track = "#E4E8EC",
        btnBg = "#EDF0F3", btnText = "#333A42", inputBg = "#F0F2F5",
        divider = "#E4E8EC", crashCard = "#FDEEEE", lightStatusBar = true
    )

    /** 用户在设置里选的模式 */
    @Volatile
    var mode: Int = MODE_DARK
        private set

    /** 实际生效的具体主题（跟随系统时已解析） */
    @Volatile
    var current: Int = DARK
        private set

    fun palette(): Palette = when (current) {
        BLACK -> BLACK_P
        LIGHT -> LIGHT_P
        else -> DARK_P
    }

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 跟随系统的映射：夜间=深色，日间=白天（不选纯黑，纯黑是显式偏好） */
    private fun resolve(m: Int, night: Boolean): Int = when (m) {
        MODE_BLACK -> BLACK
        MODE_LIGHT -> LIGHT
        MODE_DARK -> DARK
        else -> if (night) DARK else LIGHT
    }

    /** 启动时加载。老版本只存具体主题（DARK/BLACK/LIGHT），迁移成对应显式选项 */
    /**
     * 启动时加载。三级偏好：
     *   1. 新版选项（theme_mode）——设置页选过就用它；
     *   2. 老版本键（theme，存具体主题）——迁移成对应显式选项；
     *   3. 都没有 = 从没选过 —— v4.4.1 起默认「跟随系统」
     *      （系统夜间深色 / 日间白天），此前默认固定深色。
     */
    fun load(prefs: SharedPreferences, night: Boolean) {
        mode = if (prefs.contains(KEY_MODE)) {
            prefs.getInt(KEY_MODE, MODE_FOLLOW).coerceIn(MODE_FOLLOW, MODE_LIGHT)
        } else if (prefs.contains(KEY_LEGACY)) when (prefs.getInt(KEY_LEGACY, DARK)) {
            BLACK -> MODE_BLACK
            LIGHT -> MODE_LIGHT
            else -> MODE_DARK
        } else MODE_FOLLOW
        current = resolve(mode, night)
    }

    fun setMode(prefs: SharedPreferences, m: Int, night: Boolean) {
        mode = m.coerceIn(MODE_FOLLOW, MODE_LIGHT)
        prefs.edit().putInt(KEY_MODE, mode).apply()
        current = resolve(mode, night)
    }

    /** 系统日夜切换时刷新；返回主题是否变化（变了才需要重建界面） */
    fun onSystemNightChanged(night: Boolean): Boolean {
        val n = resolve(mode, night)
        if (n == current) return false
        current = n
        return true
    }

    fun modeLabel(m: Int, night: Boolean): String = when (m) {
        MODE_BLACK -> "纯黑（AMOLED 省电）"
        MODE_LIGHT -> "白天"
        MODE_DARK -> "深色"
        else -> "跟随系统（当前是${if (night) "深色" else "白天"}）"
    }
}
