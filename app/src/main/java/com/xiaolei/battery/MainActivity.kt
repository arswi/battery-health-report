package com.xiaolei.battery

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xiaolei.battery.core.Baseline
import com.xiaolei.battery.core.Health
import com.xiaolei.battery.core.LifeClaims
import kotlin.math.roundToInt

/**
 * 电池健康报告 v3
 *
 * 结构：
 *   顶栏：标题 + 导入 + 主题切换（深色 / 纯黑 / 白天）
 *   机型栏：识别到的每台机器一个 chip（名称 + 报告份数），各页面数据按机型隔离
 *   内容区（底部菜单切换）：
 *     概览 —— 机型头卡 + 六宫格基础容量信息（对应简洁模式）
 *     详细 —— 健康度环形 + 变化曲线 + 报告列表（直白展示，不做拟合）
 * 曲线颜色按整体变化：<1% 绿，下降 1~5% 橙，下降 >5% 红。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAB_OVERVIEW = 0
        private const val TAB_DETAIL = 1
        private const val TAB_SETTINGS = 2
        private const val KEY_SEL_DEVICE = "sel_device"
        /** 标称电压：mAh → Wh 换算用（小米主流电芯 3.85V） */
        private const val NOMINAL_V = 3.85
    }

    private lateinit var store: Store
    private lateinit var shell: LinearLayout
    private lateinit var chipsRow: LinearLayout
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var content: FrameLayout
    private lateinit var tvStatus: TextView
    private lateinit var crashBar: TextView

    private var devices: List<DeviceSummary> = emptyList()
    private var selDevice: String? = null
    private var tab = TAB_OVERVIEW
    private var busy = false
    private var handledUri: Uri? = null
    /** 容量卡片瓦时显示模式（点卡片切换） */
    private var whMode = false
    /** 刚导入完成的机型（refreshData 优先选中它，优先级高于"本机优先"） */
    private var pendingDevice: String? = null

    /** 版本号（所有页面底部展示） */
    private val appVersion: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
    }

    private val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
    private val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)

    private class DeviceSummary(
        val key: String,
        val name: String,
        val count: Int,
        val latestTs: Long,
        val importTs: Long,
        /** 是否本机机型（Build.MODEL 匹配） */
        val isLocal: Boolean
    )

    // ------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            store = Store.get(this)
            AppTheme.load(store.prefs, AppTheme.isNight(this))
            rebuildAll()
            handleIncoming(intent)
        } catch (t: Throwable) {
            CrashLog.write(this, t)
            setContentView(TextView(this).apply {
                text = "界面初始化失败：\n$t"
                setTextColor(Color.parseColor("#FF8A8A"))
                setPadding(24, 40, 24, 24)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        runCatching { handleIncoming(intent) }
    }

    /** 跟随系统主题：系统切日夜时同步换肤（Activity 未被重建的场景下兜底） */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (AppTheme.mode == AppTheme.MODE_FOLLOW &&
            AppTheme.onSystemNightChanged(AppTheme.isNight(this))
        ) {
            rebuildAll()
        }
    }

    private fun handleIncoming(i: Intent?) {
        val uri = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        if (uri == handledUri) return
        handledUri = uri
        import(uri)
    }

    // ------------------------------------------------------------ 主题 / 外壳

    private fun applyStatusBar() {
        val pal = AppTheme.palette()
        runCatching {
            window.statusBarColor = Color.parseColor(pal.bg)
            window.navigationBarColor = Color.parseColor(pal.bg)
            if (Build.VERSION.SDK_INT >= 23) {
                val v = window.decorView
                v.systemUiVisibility = if (pal.lightStatusBar) {
                    v.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    v.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            }
        }
    }

    /** 主题切换 / 整体刷新时重建整个外壳 */
    private fun rebuildAll() {
        applyStatusBar()
        shell = buildShell()
        setContentView(shell)
        refreshData()
    }

    private fun buildShell(): LinearLayout {
        val pal = AppTheme.palette()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(pal.bg))
        }

        // ---- 顶栏 ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(6f))
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(label("电池健康报告", 18f, pal.text).apply { setTypeface(null, Typeface.BOLD) })
        tvStatus = label("导入 bugreport 即可开始分析", 10.5f, pal.text3).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleCol.addView(tvStatus)
        top.addView(titleCol)
        top.addView(smallButton("导入", accent = true) { pickFile() })
        top.addView(smallButton("获取", accent = false) { showAddMenu() })
        root.addView(top)

        // ---- 崩溃提示 ----
        crashBar = label("上次运行发生过崩溃 · 点击查看", 11f, "#FF8A8A").apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor(pal.crashCard))
                cornerRadius = dp(8f).toFloat()
            }
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(14f), dp(4f), dp(14f), 0) }
            setOnClickListener { showCrash() }
        }
        root.addView(crashBar)

        // ---- 机型 chips ----
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14f), dp(8f), dp(14f), dp(4f))
            }
            addView(chipsRow)
        }
        root.addView(chipsScroll)

        // ---- 内容区 ----
        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(content)

        // ---- 底部菜单 ----
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)
            )
            setBackgroundColor(Color.parseColor(pal.divider))
        })
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor(pal.card))
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }
        nav.addView(navItem("概览", tab == TAB_OVERVIEW) { switchTab(TAB_OVERVIEW) })
        nav.addView(navItem("详细", tab == TAB_DETAIL) { switchTab(TAB_DETAIL) })
        nav.addView(navItem("设置", tab == TAB_SETTINGS) { switchTab(TAB_SETTINGS) })
        root.addView(nav)
        return root
    }

    private fun switchTab(t: Int) {
        if (tab == t) return
        tab = t
        // 只重画底部高亮与内容区
        val tabs = intArrayOf(TAB_OVERVIEW, TAB_DETAIL, TAB_SETTINGS)
        (shell.getChildAt(shell.childCount - 1) as? LinearLayout)?.let { nav ->
            for (i in 0 until nav.childCount) {
                val item = nav.getChildAt(i) as? TextView ?: continue
                val active = tabs.getOrNull(i) == t
                item.setTextColor(Color.parseColor(if (active) pal().accent else pal().text3))
                item.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        rebuildContent()
    }

    private fun pal() = AppTheme.palette()

    // ------------------------------------------------------------ 数据刷新

    private fun refreshData() {
        try {
            devices = summarize(store.all())
            // 选中机型的优先级：刚导入的 > 本机的 > 上次选中的 > 最近导入的
            // （进 App 默认看到本机；刚导入的报告所在机型优先展示，不打断导入后的查看）
            val cur = selDevice
            val pending = pendingDevice
            pendingDevice = null
            val local = devices.firstOrNull { it.isLocal }
            selDevice = when {
                pending != null && devices.any { it.key == pending } -> pending
                local != null -> local.key
                cur != null && devices.any { it.key == cur } -> cur
                else -> devices.firstOrNull()?.key
            }

            crashBar.visibility = if (CrashLog.read(this) == null) View.GONE else View.VISIBLE
            rebuildChips()
            rebuildContent()
        } catch (t: Throwable) {
            CrashLog.write(this, t)
            tvStatus.text = "刷新出错：${t.javaClass.simpleName}"
        }
    }

    private fun summarize(all: List<ReportRecord>): List<DeviceSummary> {
        val localKey = (Build.MODEL ?: "").trim().lowercase()
        val map = LinkedHashMap<String, MutableList<ReportRecord>>()
        for (r in all) map.getOrPut(r.deviceKey) { ArrayList() }.add(r)
        return map.map { (k, list) ->
            DeviceSummary(
                k, list.last().displayName, list.size,
                list.last().reportTs, list.maxOf { it.importTs },
                isLocal = localKey.isNotBlank() && k == localKey
            )
        }.sortedWith(
            // 本机排最前，其余按最近导入时间倒序（比"报告时间"更贴近"我最近在看的"）
            compareByDescending<DeviceSummary> { it.isLocal }
                .thenByDescending { it.importTs }
        )
    }

    private fun rebuildChips() {
        chipsRow.removeAllViews()
        chipsScroll.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
        val pal = pal()
        for (d in devices) {
            val t = TextView(this).apply {
                text = "${d.name} (${d.count})"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
                val selected = d.key == selDevice
                background = GradientDrawable().apply {
                    cornerRadius = dp(16f).toFloat()
                    setColor(Color.parseColor(if (selected) pal.chipActive else pal.chip))
                }
                setTextColor(Color.parseColor(if (selected) "#FFFFFF" else pal.text2))
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(8f), 0) }
                setOnClickListener {
                    selDevice = d.key
                    store.prefs.edit().putString(KEY_SEL_DEVICE, d.key).apply()
                    rebuildChips()
                    rebuildContent()
                }
            }
            chipsRow.addView(t)
        }
    }

    private fun rebuildContent() {
        content.removeAllViews()
        val pal = pal()
        // 设置页与具体机型无关：顶部机型栏隐藏，空库状态也能进
        chipsScroll.visibility =
            if (tab == TAB_SETTINGS || devices.isEmpty()) View.GONE else View.VISIBLE
        if (tab == TAB_SETTINGS) {
            val page = ScrollView(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isFillViewport = true
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(6f), dp(14f), dp(20f))
            }
            page.addView(col)
            buildSettingsPage(col)
            content.addView(page)
            return
        }
        if (devices.isEmpty()) {
            content.addView(emptyView())
            return
        }
        val key = selDevice ?: return
        val page = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(6f), dp(14f), dp(20f))
        }
        page.addView(col)
        if (tab == TAB_OVERVIEW) buildOverview(col, key) else buildDetail(col, key)
        content.addView(page)
    }

    private fun emptyView(): View {
        val pal = pal()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(40f), dp(24f), dp(24f))
        }
        col.addView(label("还没有任何报告", 17f, pal.text).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        col.addView(label(
            "拨号盘输入 *#*#284#*#* 生成一份 bugreport（1~2 分钟），\n" +
                "点下面「去拨号生成报告」可自动填好代码；\n" +
                "生成后点「导入」选中它（zip 或 txt，可一次多选几份）。\n\n" +
                "每台机器的数据会分开统计，互不混淆；\n" +
                "报告按它自己的生成时间落库，多导几次就能看到衰减。",
            12f, pal.text2
        ).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10f), 0, dp(16f)) }
        })
        col.addView(rowButton("导入 BugReport", true) { pickFile() })
        col.addView(rowButton("去拨号生成报告", false) { dialBugreportCode() })
        col.addView(rowButton("怎么生成报告", false) { showHowTo() })
        return col
    }

    // ------------------------------------------------------------ 设置页

    /** 设置页：主题（跟随系统/深色/纯黑/白天）、使用声明、版本信息 */
    private fun buildSettingsPage(col: LinearLayout) {
        val pal = pal()
        val night = AppTheme.isNight(this)

        // ---- 主题 ----
        val themeCard = card(pal.card)
        themeCard.addView(label("主题", 13.5f, pal.text).apply { setTypeface(null, Typeface.BOLD) })
        themeCard.addView(label(
            "跟随系统时：系统深色模式开 = 深色，关 = 白天。",
            10f, pal.text3
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(3f), 0, dp(6f)) }
        })
        val themeModes = intArrayOf(
            AppTheme.MODE_FOLLOW, AppTheme.MODE_DARK, AppTheme.MODE_BLACK, AppTheme.MODE_LIGHT
        )
        for (m in themeModes) {
            val selected = AppTheme.mode == m
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(Color.parseColor(if (selected) pal.chip else pal.inputBg))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(6f)) }
                setOnClickListener {
                    AppTheme.setMode(store.prefs, m, night)
                    rebuildAll()
                }
            }
            row.addView(label(AppTheme.modeLabel(m, night), 13f, if (selected) pal.text else pal.text2).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            })
            if (selected) {
                row.addView(label("✓", 14f, pal.accent).apply { setTypeface(null, Typeface.BOLD) })
            }
            themeCard.addView(row)
        }
        col.addView(themeCard)

        // ---- 使用声明 ----
        val dc = card(pal.card)
        dc.addView(label("关于", 13.5f, pal.text).apply { setTypeface(null, Typeface.BOLD) })
        dc.addView(rowButton("查看使用声明（非官方 · 数据不上传 · 仅供参考）", false) { showDisclaimer() })
        dc.addView(label(
            "非官方工具 · 数据只在本机解析存储 · 除你主动配置的联网数据地址（仅下载）外不上传任何数据",
            10f, pal.text3
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6f), 0, 0) }
        })
        col.addView(dc)

        // ---- 赞赏 ----
        val supportCard = card(pal.card)
        supportCard.addView(label("赞赏", 13.5f, pal.text).apply { setTypeface(null, Typeface.BOLD) })
        supportCard.addView(entryRow("支持开发者", "让我知道电池健康报告不是白做的") { showDonate() })
        col.addView(supportCard)

        // ---- 致谢 ----
        val thankCard = card(pal.card)
        thankCard.addView(label("致谢", 13.5f, pal.text).apply { setTypeface(null, Typeface.BOLD) })
        thankCard.addView(entryRow("感谢 GLM 与 WorkBuddy", "这个应用背后的代码与工作台") { showThanks() })
        col.addView(thankCard)

        val meta = card(pal.card)
        meta.addView(label(
            "电池健康报告 v$appVersion · 包名 com.xiaolei.battery\n零第三方依赖 · minSdk 26（Android 8+）",
            10.5f, pal.text3
        ))
        col.addView(meta)
    }

    /** 声明全文弹窗（设置页入口） */
    private fun showDisclaimer() {
        val pal = pal()
        val tv = TextView(this).apply {
            text = Disclaimer.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor(pal.text))
            setPadding(dp(18f), dp(12f), dp(18f), dp(6f))
            setLineSpacing(0f, 1.25f)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(Disclaimer.TITLE)
            .setView(sv)
            .setNegativeButton("复制") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("disclaimer", Disclaimer.text))
                Toast.makeText(this, "已复制声明全文", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 设置页条目行：加粗标题 + 灰色副标题 + 右箭头（仿常见"赞赏"入口卡片样式） */
    private fun entryRow(title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val p = pal()
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 13.5f, p.text).apply { setTypeface(null, Typeface.BOLD) })
            addView(label(sub, 10.5f, p.text3))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor(p.inputBg))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2f), 0, dp(6f)) }
            setOnClickListener { onClick() }
            addView(textCol)
            addView(label("›", 18f, p.text3).apply { setPadding(dp(4f), 0, 0, 0) })
        }
    }

    /** 赞赏码弹窗：整张作者赞赏码卡片图，微信扫一扫；Android 10+ 可存相册 */
    private fun showDonate() {
        val bmp = BitmapFactory.decodeResource(resources, R.drawable.donate_qr)
        if (bmp == null) {
            Toast.makeText(this, "赞赏码图片缺失", Toast.LENGTH_SHORT).show()
            return
        }
        val side = (resources.displayMetrics.widthPixels * 0.78f)
            .toInt().coerceAtMost(dp(340f)).coerceAtLeast(dp(240f))
        val iv = ImageView(this).apply {
            setImageBitmap(Bitmap.createScaledBitmap(bmp, side, side, true))
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6f), dp(4f), dp(6f), 0)
            addView(iv)
            addView(label("微信扫一扫 · 感谢支持", 11.5f, pal().text3).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8f), 0, dp(2f))
            })
        }
        AlertDialog.Builder(this)
            .setTitle("赞赏 · 请开发者喝杯咖啡")
            .setView(wrap)
            .setNegativeButton("关闭", null)
            .setPositiveButton("保存到相册") { _, _ -> saveDonateImage(bmp) }
            .show()
    }

    private fun saveDonateImage(bmp: Bitmap) {
        // 应用坚持零权限：Android 10+ 走 MediaStore 免权限写入；更老的系统提示截图
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Android 9 及以下请截图保存（应用不申请存储权限）", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "battery_donate_qr.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/电池健康报告")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                return
            }
            contentResolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: run {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "已保存到 相册/Pictures/电池健康报告", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            CrashLog.write(this, t)
            Toast.makeText(this, "保存失败：${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    /** 致谢弹窗 */
    private fun showThanks() {
        AlertDialog.Builder(this)
            .setTitle("致谢")
            .setMessage(
                "WorkBuddy\n" +
                    "这个应用的基础代码由 WorkBuddy 从零生成——" +
                    "第一版就能解析 bugreport、算出健康度、画出衰减曲线。\n\n" +
                "GLM（智谱 AI 大模型）\n" +
                    "后续的修改与迭代交到了 GLM 手上：官方寿命核对、多选导入、" +
                    "设计容量口径统一、设置页与主题……每一版打磨都由它完成。\n\n" +
                "也感谢每一位认真对待手机电池的你——\n" +
                    "愿意读懂电池数据的人，就是这个应用存在的理由。"
            )
            .setPositiveButton("关闭", null)
            .show()
    }

    // ------------------------------------------------------------ 概览页（简洁）

    private fun buildOverview(col: LinearLayout, key: String) {
        val pal = pal()
        val recs = store.forDevice(key)
        if (recs.isEmpty()) return
        val latest = recs.last()
        val manual = store.manualDesign(key).takeIf { it > 0 }
        val design = manual ?: latest.designMah

        fun pct(v: Double?): String? {
            val d = design ?: return null
            if (v == null || d <= 0) return null
            return "${(v / d * 100).roundToInt()}%"
        }

        // ---- 头卡 ----
        val head = card(pal.card2)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(label(latest.osTag ?: latest.androidVer ?: "—", 11f, pal.text3))
        info.addView(label(latest.displayName, 20f, pal.text).apply {
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2f), 0, 0) }
        })
        info.addView(label("电池健康报告", 13f, pal.text2).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(1f), 0, 0) }
        })
        val h = effHealth(latest, manual)
        if (h != null) {
            info.addView(label(
                "健康度 %.1f%% · ${Health.verdict(h).first}".format(h),
                12.5f, colorForHealth(h)
            ).apply {
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(5f), 0, 0) }
            })
        }
        info.addView(label("报告生成于 ${dateFmt.format(java.util.Date(latest.reportTs))}", 11f, pal.text3).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4f), 0, 0) }
        })
        info.addView(label("新电池开始使用 · ${batteryStartText(key, recs)}", 11f, pal.text3).apply {
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2f), 0, 0) }
            setOnClickListener { editBatteryStart(key) }
        })
        row.addView(info)

        // 设计容量徽标
        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor(pal.chip))
                cornerRadius = dp(12f).toFloat()
            }
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        }
        badge.addView(label(design?.roundToInt()?.toString() ?: "—", 19f, pal.text).apply {
            setTypeface(null, Typeface.BOLD)
        })
        badge.addView(label("设计容量", 10f, pal.text3))
        row.addView(badge)
        head.addView(row)
        col.addView(head)

        // ---- 六宫格 ----
        val r = latest
        fun cellRow(a: View, b: View) {
            val g = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            g.addView(a); g.addView(b)
            col.addView(g)
        }
        cellRow(
            infoCell("满充容量", r.chargeFullMah, "mAh", pct(r.chargeFullMah), "电池硬件层面报告的电池满充容量", toggleable = true),
            infoCell("充电循环", r.cycle?.toDouble(), "次", null, "满充容量会随着循环次数的增加而逐渐减少", 0f)
        )
        cellRow(
            infoCell("估算满充容量", r.estCapMah, "mAh", pct(r.estCapMah), "Estimated battery capacity", toggleable = true),
            infoCell("上次学习容量", r.learnedLastMah, "mAh", pct(r.learnedLastMah), "Last learned battery capacity", 0f, true)
        )
        cellRow(
            infoCell("最小学习容量", r.learnedMinMah, "mAh", pct(r.learnedMinMah), "Min learned battery capacity", toggleable = true),
            infoCell("最大学习容量", r.learnedMaxMah, "mAh", pct(r.learnedMaxMah), "Max learned battery capacity", 0f, true)
        )
        col.addView(label(
            "点容量卡片可在 mAh / Wh 间切换（瓦时按标称电压 3.85V 换算）",
            10.5f, pal.text3
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4f), 0, 0, dp(4f)) }
        })

        // ---- 工具行 ----
        col.addView(label(
            "共 ${recs.size} 份报告 · 数据来源：${r.actualSrc} · 可信度 ${r.confidence}" +
                (manual?.let { " · 设计容量手动校准 ${it.roundToInt()}mAh" } ?: ""),
            10.5f, pal.text3
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4f), dp(2f), 0, dp(8f)) }
        })
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tools.addView(button("设计容量校准", false) { editManualDesign() })
        tools.addView(button("导出 CSV", false) { exportCsv() })
        col.addView(tools)
        val danger = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4f), 0, dp(2f)) }
        }
        danger.addView(button("清空本机型", false) { clearDevice(key) })
        danger.addView(button("清空全部", false) { clearAll() })
        col.addView(danger)

        buildAutoTaskCard(col)
        addVersionFooter(col)
    }

    /** 每个页面最底部的版本信息行 */
    private fun addVersionFooter(col: LinearLayout) {
        col.addView(label("电池健康报告 v$appVersion · 数据仅存本机", 9.5f, pal().text3).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2f), 0, dp(4f)) }
        })
    }

    // ------------------------------------------------------------ 自动任务

    private fun buildAutoTaskCard(col: LinearLayout) {
        val pal = pal()
        val on = AutoTask.isEnabled(this)
        val c = card(pal.card)
        // 上方是「清空」一排按钮，之前整卡顶着按钮没有间距
        (c.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8f)
        c.addView(label("每日自动任务", 13.5f, pal.text).apply { setTypeface(null, Typeface.BOLD) })
        c.addView(label(
            "Android 不允许第三方应用自己生成 bugreport（需要系统签名的 DUMP 权限），" +
                "所以自动任务做的是：每天定时扫你授权的目录，发现新报告就自动解析入库；" +
                "没发现新报告就发一条提醒，你在拨号盘输 *#*#284#*#* 生成一次即可。",
            10.5f, pal.text3
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4f), 0, dp(8f)) }
        })

        val r1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        r1.addView(button(if (on) "关闭自动任务" else "开启自动任务", !on) { toggleAuto() })
        r1.addView(button("立即检查一次", false) { runAutoCheck() })
        c.addView(r1)

        val r2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2f), 0, 0) }
        }
        val folder = AutoTask.folderUri(this)
        r2.addView(button(if (folder == null) "选择报告目录" else "改选报告目录", false) { pickFolder() })
        r2.addView(button("时间 ${AutoTask.hour(this)}:%02d".format(AutoTask.minute(this)), false) { editAutoTime() })
        c.addView(r2)

        c.addView(label(
            "目录：${folder?.let { runCatching { it.path?.substringAfterLast(':') }.getOrNull() ?: "已选择" } ?: "未选择"}" +
                " · 上次：${AutoTask.lastResult(this)}",
            10.5f, pal.text3
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4f), dp(6f), 0, 0) }
        })
        col.addView(c)
    }

    private fun toggleAuto() {
        val on = !AutoTask.isEnabled(this)
        if (on && AutoTask.needsNotifyPermission(this)) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11) }
        }
        AutoTask.setEnabled(this, on)
        if (on) {
            Toast.makeText(this, "已开启：每天 %02d:%02d 自动检查".format(AutoTask.hour(this), AutoTask.minute(this)), Toast.LENGTH_SHORT).show()
            if (AutoTask.folderUri(this) == null) pickFolder()
        } else {
            Toast.makeText(this, "已关闭自动任务", Toast.LENGTH_SHORT).show()
        }
        rebuildContent()
    }

    private fun runAutoCheck() {
        if (busy) return
        busy = true
        tvStatus.text = "自动检查中…"
        Thread {
            val msg = AutoTask.checkNow(this@MainActivity)
            runOnUiThread {
                busy = false
                tvStatus.text = msg
                runCatching { AutoTask.notify(this@MainActivity, "电池健康报告 · 自动任务", msg) }
                refreshData()
            }
        }.start()
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(i, 10)
        } catch (t: Throwable) {
            CrashLog.write(this, t)
            Toast.makeText(this, "打不开目录选择器：${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun editAutoTime() {
        runCatching {
            android.app.TimePickerDialog(
                this,
                { _, h, m ->
                    AutoTask.setTime(this, h, m)
                    Toast.makeText(this, "每天 %02d:%02d 检查".format(h, m), Toast.LENGTH_SHORT).show()
                    rebuildContent()
                },
                AutoTask.hour(this), AutoTask.minute(this), true
            ).show()
        }.onFailure { Toast.makeText(this, "无法打开时间选择器", Toast.LENGTH_SHORT).show() }
    }

    /** 概览/详细共用的"有效健康度"：手动校准优先于报告内计算 */
    private fun effHealth(r: ReportRecord, manual: Double?): Double? {
        if (manual != null && manual > 0) {
            val actual = r.chargeFullMah ?: r.learnedLastMah
                ?: listOfNotNull(r.learnedMinMah, r.learnedMaxMah).takeIf { it.isNotEmpty() }?.average()
            if (actual != null) return kotlin.math.round(actual / manual * 1000) / 10.0
        }
        return r.health
    }

    private fun colorForHealth(p: Double): String = when {
        p >= 88 -> "#2EC56D"
        p >= 80 -> "#FFB020"
        else -> "#FF4D4F"
    }

    private fun infoCell(
        title: String,
        v: Double?,
        unit: String,
        pct: String?,
        desc: String,
        endMargin: Float = 10f,
        toggleable: Boolean = false
    ): View {
        val pal = pal()
        val cell = card(pal.card)
        val showWh = toggleable && whMode && v != null
        cell.addView(label(if (showWh) title.replace("容量", "能量") else title, 12f, pal.text2))
        val vr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        vr.addView(label(
            when {
                showWh -> "%.1f".format(v!! * NOMINAL_V / 1000.0)
                v == null -> "—"
                else -> "%.0f".format(v)
            },
            22f, pal.text
        ).apply { setTypeface(null, Typeface.BOLD) })
        vr.addView(label(
            when {
                v == null -> ""
                showWh -> " Wh"
                unit == "次" -> " 次"
                else -> " $unit"
            }, 12f, pal.text2
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(3f), 0, 0, dp(3f)) }
        })
        if (!pct.isNullOrEmpty()) {
            vr.addView(label(pct, 12f, pal.text3).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(6f), 0, 0, dp(3f)) }
            })
        }
        cell.addView(vr)
        cell.addView(label(desc, 10f, pal.text3).apply {
            // 说明统一占两行：六个格子高度一致（单行的 desc 会把格子压矮）
            minLines = 2
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4f), 0, 0) }
        })
        if (toggleable) {
            cell.setOnClickListener {
                whMode = !whMode
                rebuildContent()
            }
        }
        // 两列等宽
        cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(0, 0, dp(endMargin), dp(10f)) }
        return cell
    }

    /**
     * 新电池开始使用时间：用户手动指定优先；没指定则用报告序列的循环速率
     * （Δ循环 ÷ Δ天数）从最早一份报告倒推"电池循环数归零的那天"；
     * 循环数据不足时退回首份报告的日期并注明。
     */
    private fun batteryStartText(key: String, recs: List<ReportRecord>): String {
        val manual = store.startTs(key)
        if (manual > 0) return "${dayFmt.format(java.util.Date(manual))}（手动设定）"
        val wc = recs.filter { (it.cycle ?: 0) > 0 }
        if (wc.size >= 2) {
            val a = wc.first()
            val b = wc.last()
            val days = (b.reportTs - a.reportTs) / 86400000.0
            val dc = (b.cycle!! - a.cycle!!).toDouble()
            if (days >= 1.0 && dc > 0) {
                val perDay = dc / days
                val startTs = a.reportTs - (a.cycle / perDay * 86400000.0).toLong()
                return "约 ${dayFmt.format(java.util.Date(startTs))}（按 %.2f 次/天循环倒推）".format(perDay)
            }
        }
        return "${dayFmt.format(java.util.Date(recs.first().reportTs))}（首次记录，暂无法倒推）"
    }

    // ------------------------------------------------------------ 详细页

    private fun buildDetail(col: LinearLayout, key: String) {
        val pal = pal()
        val recs = store.forDevice(key)
        if (recs.isEmpty()) return
        val latest = recs.last()
        val manual = store.manualDesign(key).takeIf { it > 0 }
        val h = effHealth(latest, manual)

        // ---- 环形 + 结论 ----
        val hero = card(pal.card)
        val hr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ring = RingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(104f), dp(104f))
        }
        ring.set(h, latest.displayName)
        hr.addView(ring)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(12f), 0, 0, 0) }
        }
        if (h != null) {
            val v = Health.verdict(h)
            info.addView(label(v.first, 15f, colorForHealth(h)).apply { setTypeface(null, Typeface.BOLD) })
        }
        info.addView(label("设计容量 ${latest.designMah?.roundToInt() ?: "—"} mAh · 循环 ${latest.cycle?.toString() ?: "—"} 次", 11.5f, pal.text2))
        info.addView(label("报告生成于 ${dateFmt.format(java.util.Date(latest.reportTs))}", 11f, pal.text3))
        info.addView(label("新电池开始使用：${batteryStartText(key, recs)}", 11f, pal.text3).apply {
            setOnClickListener { editBatteryStart(key) }
        })
        info.addView(label("容量来源：${latest.actualSrc} · 可信度 ${latest.confidence}", 10.5f, pal.text3))
        hr.addView(info)
        hero.addView(hr)
        col.addView(hero)

        // ---- 曲线 ----
        val chartCard = card(pal.card)
        val delta = HealthChartView.deltaOf(recs)
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(weightedLabel("健康度变化曲线", 13f, pal.text2))
        if (delta != null) {
            val c = HealthChartView.colorForChange(recs)
            if (c != null) {
                val word = if (delta < 0) "下降 %.1f%%".format(-delta)
                    else if (delta < 0.05) "基本持平" else "上升 %.1f%%".format(delta)
                titleRow.addView(label(word, 12f, String.format("#%06X", c and 0xFFFFFF)).apply {
                    setTypeface(null, Typeface.BOLD)
                })
            }
        }
        chartCard.addView(titleRow)
        val chart = HealthChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(210f)
            ).apply { setMargins(0, dp(6f), 0, dp(6f)) }
        }
        chart.setData(recs)
        chartCard.addView(chart)
        chartCard.addView(label(
            "绿 变化 <1% · 橙 下降 1~5% · 红 下降 >5% · 虚线为 80% 更换参考线",
            10f, pal.text3
        ))
        val tvPoint = label("", 11f, pal.text2).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6f), 0, 0) }
        }
        chartCard.addView(tvPoint)
        chart.onPointSelected = { r ->
            tvPoint.text = if (r == null) "" else
                "${dateFmt.format(java.util.Date(r.reportTs))} · 健康度 ${r.health?.let { "%.2f%%".format(it) } ?: "—"} · 满充 ${r.chargeFullMah?.roundToInt() ?: "—"}mAh"
        }
        col.addView(chartCard)

        // ---- 到达 80% 推断 ----
        buildPredictCard(col, key)

        // ---- 报告列表（新 → 旧）----
        col.addView(label("全部报告（${recs.size}）", 13f, pal.text2).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4f), dp(4f), 0, dp(6f)) }
        })
        for (r in recs.asReversed()) col.addView(reportCard(r))
        addVersionFooter(col)
    }

    // ------------------------------------------------------------ 到达 80% 的推断（联机基准对比）

    private fun baselineCurve(): Baseline.Curve =
        Baseline.parse(store.baselineCache) ?: Baseline.builtIn

    /** 官方宣称循环寿命：基准地址返回的 claims（在线核对）优先，其次内置核实表 */
    private fun currentClaim(r: ReportRecord): LifeClaims.Claim? {
        val keys = arrayOf(r.market, r.model, r.deviceName, r.displayName)
        val online = LifeClaims.parse(store.baselineCache)
        if (online != null) {
            LifeClaims.match(online, *keys)?.let { return it }
        }
        return LifeClaims.claimFor(*keys)
    }

    private fun buildPredictCard(col: LinearLayout, key: String) {
        val pal = pal()
        val recs = store.forDevice(key)
        if (recs.isEmpty()) return
        val c = card(pal.card)
        c.addView(label("到达 80% 推断（联机基准对比）", 13.5f, pal.text).apply { setTypeface(null, Typeface.BOLD) })

        if (!store.onlineMode) {
            c.addView(label(
                "关闭状态。开启后，会结合「电池开始使用时间」算出的每日循环次数，\n" +
                    "对照同类型机型的群体衰减曲线（按循环次数），推断这台电池掉到 80% 的时间。\n" +
                    "本机数据只在本机计算，不会上传；基准曲线默认使用内置通用模型，" +
                    "也可以在设置里填自己的基准地址做联机同步。",
                10.5f, pal.text3
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(4f), 0, dp(8f)) }
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(button("开启对比", true) {
                store.onlineMode = true
                rebuildContent()
            })
            row.addView(button("基准地址设置", false) { editBaselineUrl() })
            c.addView(row)
            col.addView(c)
            return
        }

        val curve = baselineCurve()
        val series = recs.map { Baseline.Sample(it.reportTs, it.cycle?.toDouble(), it.health) }
        val claim = currentClaim(recs.last())
        val p = Baseline.predict(
            store.startTs(key), series, curve,
            officialCyclesTo80 = claim?.cyclesTo80?.toDouble()
        )

        fun line(text: String, color: String = pal.text2, bold: Boolean = false) {
            c.addView(label(text, 11.5f, color).apply {
                if (bold) setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(3f), 0, 0) }
            })
        }

        val eta = p.etaTs
        val dev = p.deviation
        val baseNow = p.baselineHealthNow
        if (p.alreadyBelow) {
            line("健康度已低于 80%，建议关注电池状态", "#FF4D4F", true)
        } else if (eta != null) {
            line("预计到达 80%：约 ${dayFmt.format(java.util.Date(eta))}", colorForHealth(81.0), true)
            p.daysTo80?.let { line("还有约 ${it.roundToInt()} 天 / ${p.cyclesTo80?.roundToInt() ?: "—"} 次循环") }
        } else {
            line("数据不足，暂时给不出日期", pal.text3, true)
        }
        line(
            if (claim != null)
                "官方宣称寿命：${claim.cyclesTo80} 次循环容量保持 ≥80%（${claim.source}）· 实验室理想值，已作为本机无实测速率时的推断参考"
            else
                "官方宣称寿命：未查到该机型的官方循环寿命数据，推断使用群体基准",
            if (claim != null) pal.text2 else pal.text3
        )
        line("每日循环：${p.cyclesPerDay?.let { "%.2f 次/天".format(it) } ?: "—（需要两份带循环次数的报告，或手动设定开始使用时间）"}")
        line("本机衰减：${p.dropPer100?.let { "每 100 次循环掉 %.2f 个百分点".format(it) } ?: "—（本机报告还不够）"}")
        if (dev != null && baseNow != null) {
            val last = recs.last()
            val word = if (dev >= 0) "比基准好 %.1f 点（衰减更慢）".format(dev)
            else "比基准差 %.1f 点（衰减更快）".format(-dev)
            line("同循环数（${last.cycle ?: "—"} 次）基准为 %.1f%%，你 %.1f%% · %s".format(baseNow, last.health ?: 0.0, word))
        }
        line("依据：${p.basis}", pal.text3)
        line("基准来源：${if (Baseline.parse(store.baselineCache) != null) "联机同步" else "内置通用模型"} · ${store.baselineStatus}", pal.text3)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8f), 0, 0) }
        }
        row.addView(button("关闭对比", false) { store.onlineMode = false; rebuildContent() })
        row.addView(button("立即同步", false) { syncBaseline() })
        c.addView(row)
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2f), 0, 0) }
        }
        row2.addView(button("基准地址", false) { editBaselineUrl() })
        row2.addView(button("开始使用时间", false) { editBatteryStart(key) })
        c.addView(row2)
        col.addView(c)
    }

    private fun editBaselineUrl() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://你的地址/baseline.json"
            setText(store.baselineUrl)
            setTextColor(Color.parseColor(pal().text))
            setHintTextColor(Color.parseColor(pal().text3))
        }
        AlertDialog.Builder(this)
            .setTitle("联机数据地址")
            .setMessage(
                "留空则只用内置数据。地址可返回 [[0,100],[300,93],[800,80]] 这样的数组，\n" +
                    "或 {\"points\":[[...]], \"claims\":[[\"正则\",1600,\"来源\"],...]}——\n" +
                    "points 是群体基准曲线，claims 是官方循环寿命表（可选用，命中机型后作为推断参考）。\n" +
                    "同步只下载数据，不上传任何本机数据。"
            )
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存并同步") { _, _ ->
                store.baselineUrl = input.text.toString()
                syncBaseline()
            }
            .show()
    }

    private fun syncBaseline() {
        val url = store.baselineUrl
        if (url.isBlank()) {
            Toast.makeText(this, "还没填基准地址，当前用内置基准", Toast.LENGTH_SHORT).show()
            rebuildContent()
            return
        }
        Thread {
            val ok = runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val curve = Baseline.parse(text) ?: throw IllegalStateException("返回内容不是合法的曲线数据")
                val claims = LifeClaims.parse(text)
                store.baselineCache = text
                store.baselineStatus = "已同步 ${curve.size} 点" +
                    (claims?.size?.takeIf { it > 0 }?.let { " + $it 条官方寿命" } ?: "") +
                    " · ${dateFmt.format(java.util.Date())}"
                "已同步 ${curve.size} 个基准点" +
                    (claims?.size?.takeIf { it > 0 }?.let { "、$it 条官方寿命数据" } ?: "")
            }
            runOnUiThread {
                val msg = ok.getOrElse { e ->
                    store.baselineStatus = "同步失败：${e.message ?: e.javaClass.simpleName}"
                    "同步失败：${e.message ?: e.javaClass.simpleName}"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                rebuildContent()
            }
        }.start()
    }

    private fun reportCard(r: ReportRecord): View {
        val pal = pal()
        val card = card(pal.card)
        val l1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        l1.addView(weightedLabel(dateFmt.format(java.util.Date(r.reportTs)), 12.5f, pal.text))
        l1.addView(label(r.osTag ?: r.androidVer ?: "", 10.5f, pal.text3))
        card.addView(l1)

        val l2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8f), 0, 0) }
        }
        fun stat(value: String, unit: String, lab: String, color: String?): LinearLayout {
            val c = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val vr = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            vr.addView(label(value, 17f, color ?: pal.text).apply { setTypeface(null, Typeface.BOLD) })
            vr.addView(label(unit, 10.5f, pal.text3).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(2f), 0, 0, dp(2f)) }
            })
            c.addView(vr)
            c.addView(label(lab, 10f, pal.text3))
            return c
        }
        l2.addView(
            stat(
                r.health?.let { "%.2f".format(it) } ?: "—", "%", "健康度",
                r.health?.let { colorForHealth(it) }
            )
        )
        l2.addView(stat(r.chargeFullMah?.roundToInt()?.toString() ?: "—", "mAh", "满充容量", null))
        l2.addView(stat(r.cycle?.toString() ?: "—", "次", "循环次数", null))
        card.addView(l2)
        card.setOnClickListener { showRecordDetail(r) }
        return card
    }

    // ------------------------------------------------------------ 通用控件

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    private fun label(text: String, size: Float, color: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        includeFontPadding = false
    }

    private fun weightedLabel(text: String, size: Float, color: String): TextView =
        label(text, size, color).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun card(color: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(14f).toFloat()
        }
        val p = dp(14f)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(10f)) }
    }

    /** 纵向卡片里用的整行按钮——button() 的 0 宽 + 权重写法只适合横向行，
     *  直接塞进纵向容器会得到 0 宽的隐形按钮（v4.3 设置页声明入口就是这么丢的） */
    private fun rowButton(text: String, accent: Boolean, onClick: () -> Unit): TextView =
        button(text, accent, onClick).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) }
        }

    private fun button(text: String, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor(if (accent) pal().accent else pal().btnBg))
            }
            setTextColor(Color.parseColor(if (accent) "#FFFFFF" else pal().btnText))
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(dp(4f), 0, dp(4f), 0) }
            setOnClickListener { if (!busy) onClick() }
        }

    /** 顶栏小按钮 */
    private fun smallButton(text: String, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(Color.parseColor(if (accent) pal().accent else pal().btnBg))
            }
            setTextColor(Color.parseColor(if (accent) "#FFFFFF" else pal().btnText))
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6f), 0, 0, 0) }
            setOnClickListener { if (!busy) onClick() }
        }

    private fun navItem(text: String, active: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6f), 0, dp(6f))
            setTextColor(Color.parseColor(if (active) pal().accent else pal().text3))
            setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------ 导入

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "text/plain")
            )
            // v4.4：允许一次勾选多份 bugreport 逐个入库
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val chosen = runCatching { Intent.createChooser(i, "选择 bugreport（zip 或 txt，可多选）") }.getOrNull() ?: i
        try {
            startActivityForResult(chosen, 9)
        } catch (t: Throwable) {
            CrashLog.write(this, t)
            Toast.makeText(this, "打不开文件选择器：${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("legacy")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != Activity.RESULT_OK) return
        when (req) {
            9 -> {
                // 多选时 URI 在 clipData 里；单选多数返回 data
                val uris = ArrayList<Uri>()
                data?.data?.let { uris.add(it) }
                data?.clipData?.let { cd ->
                    for (k in 0 until cd.itemCount) {
                        cd.getItemAt(k).uri.let { if (it !in uris) uris.add(it) }
                    }
                }
                if (uris.isEmpty()) {
                    tvStatus.text = "没有拿到文件（选择器没返回内容）"
                    return
                }
                import(uris)
            }
            10 -> {
                val uri = data?.data
                if (uri == null) {
                    tvStatus.text = "没有拿到目录"
                    return
                }
                AutoTask.setFolder(this, uri)
                Toast.makeText(this, "已记住报告目录，自动任务会每天扫它", Toast.LENGTH_SHORT).show()
                rebuildContent()
            }
        }
    }

    /** 「文件管理 → 打开方式」进来的单文件入口 */
    private fun import(uri: Uri) = import(listOf(uri))

    /** 一份文件解析入库的结果（多选导入汇总用） */
    private class ImportDone(
        val fileName: String,      // 选择器里的文件名（失败时点名用）
        val title: String,        // 市场/型号名（成功时展示用）
        val parsedOk: Boolean,    // 解析成功且拿到了报告
        val storeOk: Boolean,     // 写入数据库成功
        val message: String,      // 失败原因
        val deviceKey: String?,
        val healthTxt: String?,
        val reportTimeTxt: String?,
        val elapsedMs: Long
    )

    /**
     * 多选导入：逐个解析入库，全部完成后统一汇总。
     * 中途某个文件失败不影响其它文件；每解析完一份立刻落库，
     * 即使后面出错，前面成功的也已经保存。
     */
    private fun import(uris: List<Uri>) {
        if (busy) return
        busy = true
        tvStatus.text = if (uris.size == 1) "正在解析…" else "正在导入 1/${uris.size}…"
        Thread {
            val results = ArrayList<ImportDone>()
            uris.forEachIndexed { idx, uri ->
                if (uris.size > 1) {
                    runOnUiThread { tvStatus.text = "正在导入 ${idx + 1}/${uris.size}（${fileName(uri)}）" }
                }
                val out = Importer.run(this@MainActivity, uri) { msg ->
                    runOnUiThread { tvStatus.text = msg }
                }
                val p = out.report
                var storeOk = false
                var healthTxt: String? = null
                var timeTxt: String? = null
                if (out.ok && p != null) {
                    try {
                        val (okStore, _) = Ingest.save(this@MainActivity, p)
                        storeOk = okStore
                        healthTxt = com.xiaolei.battery.core.Health.compute(
                            p.gauge.copy(manualDesignMah = store.manualDesign(p.deviceKey).takeIf { it > 0 })
                        ).healthPct?.let { "%.2f%%".format(it) }
                        timeTxt = dateFmt.format(java.util.Date(p.reportTs)) + "（${p.tsFrom}）"
                    } catch (t: Throwable) {
                        CrashLog.write(this@MainActivity, t)
                    }
                }
                results.add(
                    ImportDone(
                        fileName = fileName(uri),
                        title = p?.market ?: p?.model ?: fileName(uri),
                        parsedOk = out.ok && p != null,
                        storeOk = storeOk,
                        message = out.message,
                        deviceKey = p?.deviceKey,
                        healthTxt = healthTxt,
                        reportTimeTxt = timeTxt,
                        elapsedMs = out.elapsedMs
                    )
                )
            }
            runOnUiThread {
                busy = false
                // 跳到最后一台成功导入的机型（多份同机报告也成立）
                results.lastOrNull { it.parsedOk && it.storeOk && it.deviceKey != null }?.let { last ->
                    selDevice = last.deviceKey
                    pendingDevice = last.deviceKey
                    store.prefs.edit().putString(KEY_SEL_DEVICE, last.deviceKey).apply()
                    refreshData()
                }
                tvStatus.text = importSummary(results)
            }
        }.start()
    }

    private fun importSummary(rs: List<ImportDone>): String {
        val r = rs[0]
        if (rs.size == 1) {
            if (!r.parsedOk) return "导入失败：${r.message}"
            return buildString {
                append("已导入 ${r.title}")
                r.healthTxt?.let { append(" · 健康度 $it") }
                r.reportTimeTxt?.let { append(" · 报告时间 $it") }
                append(" · 用时 %.1f 秒".format(r.elapsedMs / 1000.0))
                if (!r.storeOk) append(" · ⚠写入数据库失败，未保存")
            }
        }
        val okCnt = rs.count { it.parsedOk && it.storeOk }
        return buildString {
            append("批量导入完成：成功 $okCnt/${rs.size} · 总用时 %.1f 秒".format(rs.sumOf { it.elapsedMs } / 1000.0))
            rs.filter { !it.parsedOk }.takeIf { it.isNotEmpty() }?.let { bad ->
                append(" · 失败：${bad.joinToString("、") { it.fileName }}")
            }
            rs.filter { it.parsedOk && !it.storeOk }.takeIf { it.isNotEmpty() }?.let {
                append(" · ⚠${it.size} 份写入数据库失败")
            }
            rs.lastOrNull { it.parsedOk && it.storeOk }?.let { last ->
                last.healthTxt?.let { h -> append("\n最新一份：${last.title} · 健康度 $h") }
            }
        }
    }

    private fun fileName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "文件"
    } catch (t: Throwable) {
        uri.lastPathSegment ?: "文件"
    }

    /**
     * 跳到拨号盘并预填 *#*#284#*#*（小米工程码，生成 bugreport）。
     * 用 ACTION_DIAL 只预填不拨出——自动拨号需要 CALL_PHONE 权限，
     * 一个电池工具没必要申请电话权限；用户按一下拨号键才开始生成。
     */
    private fun dialBugreportCode() {
        val i = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "*#*#284#*#*", null))
        try {
            startActivity(i)
            Toast.makeText(this, "已填好 *#*#284#*#*，点拨号键开始生成（约 1~2 分钟）", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            CrashLog.write(this, t)
            Toast.makeText(this, "打不开拨号盘：${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------ 其它动作

    /** 用户自己定义"新电池开始使用时间"：换过电池/记得开箱日子的最准 */
    private fun editBatteryStart(key: String) {
        val manual = store.startTs(key)
        AlertDialog.Builder(this)
            .setTitle("新电池开始使用时间")
            .setMessage(
                if (manual > 0) "当前：手动设定 ${dayFmt.format(java.util.Date(manual))}\n\n" +
                    "这个时间会参与「每日循环次数」的计算，进而影响到达 80% 的推断。"
                else "当前：由 App 按循环速率自动倒推\n\n" +
                    "如果你记得新机开箱或换电池的日子，手动指定会明显更准。"
            )
            .setNeutralButton("恢复自动倒推") { _, _ ->
                store.setStartTs(key, 0)
                rebuildContent()
                Toast.makeText(this, "已恢复自动倒推", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("手动指定日期") { _, _ -> showStartPicker(key) }
            .show()
    }

    private fun showStartPicker(key: String) {
        val cal = java.util.Calendar.getInstance()
        val cur = store.startTs(key)
        if (cur > 0) cal.timeInMillis = cur
        runCatching {
            android.app.DatePickerDialog(
                this,
                { _, y, m, d ->
                    val c = java.util.Calendar.getInstance().apply {
                        set(y, m, d, 0, 0, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    store.setStartTs(key, c.timeInMillis)
                    rebuildContent()
                    Toast.makeText(this, "已设定：${dayFmt.format(java.util.Date(c.timeInMillis))}", Toast.LENGTH_SHORT).show()
                },
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            ).show()
        }.onFailure { Toast.makeText(this, "无法打开日期选择器", Toast.LENGTH_SHORT).show() }
    }

    private fun editManualDesign() {
        val key = selDevice ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "例如 4500，留空取消校准"
            setTextColor(Color.parseColor(pal().text))
            setHintTextColor(Color.parseColor(pal().text3))
            val cur = store.manualDesign(key)
            if (cur > 0) setText(cur.roundToInt().toString())
        }
        AlertDialog.Builder(this)
            .setTitle("设计容量校准（mAh）")
            .setMessage(
                "设计容量默认取官方典型标称（内置机型库）；换过第三方电池、或官方值与这台机器明显不符时才需要校准。\n" +
                    "保存后会立即按新设计容量重算该机型全部已存报告的健康度（曲线、列表、CSV 同步更新）。\n" +
                    "留空并确认可取消校准，设计容量自动回到官方标称/固件值。"
            )
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().toDoubleOrNull()
                store.setManualDesign(key, if (v == null || v <= 0) 0.0 else v)
                val n = store.recomputeHealth(key)
                refreshData()
                when {
                    n > 0 -> Toast.makeText(this, "已按新设计容量重算 $n 份报告的健康度", Toast.LENGTH_SHORT).show()
                    n == 0 -> Unit   // 数值没变，不用打扰
                    else -> Toast.makeText(this, "重算时出错，详情见崩溃日志", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun exportCsv() {
        try {
            val f = java.io.File(cacheDir, "battery_health.csv")
            f.writeText(store.exportCsv())
            val uri = Uri.parse("content://com.xiaolei.battery.csv/battery_health.csv")
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "电池健康报告数据")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(share, "导出 CSV")) }
                .onFailure { Toast.makeText(this, "已保存到 ${f.absolutePath}", Toast.LENGTH_LONG).show() }
        } catch (t: Throwable) {
            CrashLog.write(this, t)
            Toast.makeText(this, "导出失败：${t.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearDevice(key: String) {
        val name = devices.firstOrNull { it.key == key }?.name ?: key
        AlertDialog.Builder(this)
            .setTitle("清空 $name")
            .setMessage("只删除这台机器的全部报告，其它机型不受影响。确定？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                store.clearDevice(key)
                refreshData()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun clearAll() {
        AlertDialog.Builder(this)
            .setTitle("清空全部报告")
            .setMessage("会删除所有机型的全部记录。确定？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                store.clear()
                refreshData()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showRecordDetail(r: ReportRecord) {
        val pal = pal()
        fun pct(v: Double?): String {
            val d = r.designMah ?: return ""
            if (v == null || d <= 0) return ""
            return "（${(v / d * 100).roundToInt()}%）"
        }
        val msg = buildString {
            append("机型：${r.displayName}")
            r.model?.let { append("（$it）") }
            append('\n')
            r.osTag?.let { append("系统版本：$it\n") }
            r.androidVer?.let { append("Android：$it\n") }
            append("报告生成：${dateFmt.format(java.util.Date(r.reportTs))}\n")
            append("健康度：${r.health?.let { "%.2f%%".format(it) } ?: "—"}\n\n")
            append("满充容量：${r.chargeFullMah?.roundToInt() ?: "—"} mAh ${pct(r.chargeFullMah)}\n")
            append("设计容量：${r.designMah?.roundToInt() ?: "—"} mAh\n")
            append("估算满充：${r.estCapMah?.roundToInt() ?: "—"} mAh ${pct(r.estCapMah)}\n")
            append("上次学习：${r.learnedLastMah?.roundToInt() ?: "—"} mAh ${pct(r.learnedLastMah)}\n")
            append("最小学习：${r.learnedMinMah?.roundToInt() ?: "—"} mAh ${pct(r.learnedMinMah)}\n")
            append("最大学习：${r.learnedMaxMah?.roundToInt() ?: "—"} mAh ${pct(r.learnedMaxMah)}\n")
            append("循环次数：${r.cycle ?: "—"}\n")
            append("电量：${r.level ?: "—"}% · 温度：${r.tempC?.let { "%.1f℃".format(it) } ?: "—"}\n")
            append("电压：${r.voltMv?.roundToInt()?.let { "$it mV" } ?: "—"} · 电流：${r.currentMa?.roundToInt()?.let { "$it mA" } ?: "—"}\n")
            append("\n容量来源：${r.actualSrc} · 可信度 ${r.confidence}\n")
            r.note.takeIf { it.isNotBlank() }?.let { append("备注：$it\n") }
            r.entry?.let { append("文件：${it.substringAfterLast('/')}\n") }
        }
        val tv = TextView(this).apply {
            text = msg
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.parseColor(pal.text))
            setPadding(dp(18f), dp(12f), dp(18f), dp(6f))
        }
        AlertDialog.Builder(this)
            .setTitle(dateFmt.format(java.util.Date(r.reportTs)))
            .setView(tv)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showCrash() {
        val txt = CrashLog.read(this) ?: "最近没有记录到崩溃。"
        val tv = TextView(this).apply {
            text = txt
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor(pal().text2))
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("崩溃日志")
            .setView(sv)
            .setNeutralButton("复制") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("crash", txt))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("清除") { _, _ -> CrashLog.clear(this); refreshData() }
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 顶栏「添加」：添加报告的所有途径（原 ？ 帮助入口并入这里） */
    private fun showAddMenu() {
        val items = arrayOf(
            "导入文件（zip/txt，可多选）",
            "去拨号生成报告（自动填好 *#*#284#*#*）",
            "怎么生成报告（帮助）"
        )
        AlertDialog.Builder(this)
            .setTitle("获取报告")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickFile()
                    1 -> dialBugreportCode()
                    else -> showHowTo()
                }
            }
            .show()
    }

    private fun showHowTo() {
        AlertDialog.Builder(this)
            .setTitle("怎么拿到 bugreport")
            .setMessage(
                "1. 打开拨号盘，输入 *#*#284#*#*\n" +
                    "   （点下方「去拨号」会自动填好），手机开始生成报告，\n" +
                    "   约 1~2 分钟，完成后通知栏会有提示。\n\n" +
                    "2. 文件在「文件管理 → 手机」的 Download 或 MIUI/debug_log 目录，\n" +
                    "   名字类似 bugreport-2026-10-21-182853.zip。\n\n" +
                    "3. 点右上角「导入」选中它，可一次勾选多份；\n" +
                    "   也可以直接在文件管理里点这个 zip，\n" +
                    "   选择用「电池健康报告」打开。\n\n" +
                    "4. 每台机器的数据分开统计（顶部机型栏切换）；\n" +
                    "   报告按它自己的生成时间入库，隔几周导一次就能看到衰减。\n\n" +
                    "提示：导入失败多半是选错了文件，或来源不允许读取——\n" +
                    "换用「文件管理」进入选择即可。"
            )
            .setNegativeButton("去拨号（已填好）") { _, _ -> dialBugreportCode() }
            .setPositiveButton("知道了", null)
            .show()
    }
}
