package com.xiaolei.battery

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.xiaolei.battery.core.Gauge
import com.xiaolei.battery.core.Health

/**
 * 一份 bugreport = 一条记录。
 *
 * v3 起按机型分组存储（device_key + report_ts 联合主键）：
 * 不同机型的报告永远不会混进同一条曲线。
 * reportTs 是**报告自身的生成时间**（正文 `== dumpstate:` 行），
 * 不是导入时刻——曲线的横轴必须有物理意义。
 */
data class ReportRecord(
    val deviceKey: String,
    val reportTs: Long,
    val importTs: Long,
    val model: String?,
    val market: String?,
    val deviceName: String?,
    val androidVer: String?,
    val osTag: String?,
    val board: String?,
    val health: Double?,
    val chargeFullMah: Double?,
    val designMah: Double?,
    val estCapMah: Double?,
    val learnedLastMah: Double?,
    val learnedMinMah: Double?,
    val learnedMaxMah: Double?,
    val cycle: Int?,
    val level: Int?,
    val tempC: Double?,
    val voltMv: Double?,
    val currentMa: Double?,
    val actualSrc: String,
    val designSrc: String,
    val confidence: String,
    val note: String,
    val entry: String?
) {
    /** 展示名：优先市场名（如"小米 13"），其次型号 */
    val displayName: String
        get() = market?.takeIf { it.isNotBlank() }
            ?: model?.takeIf { it.isNotBlank() }
            ?: "未知机型"
}

class Store private constructor(ctx: Context) {

    companion object {
        private const val DB = "battery.db"
        private const val VER = 3
        const val PREFS = "cfg"
        private const val KEY_MANUAL_PREFIX = "manual_design_"
        private const val KEY_START_PREFIX = "start_ts_"
        private const val KEY_BASE_URL = "baseline_url"
        private const val KEY_BASE_CACHE = "baseline_cache"
        private const val KEY_BASE_STATUS = "baseline_status"
        private const val KEY_ONLINE = "online_mode"
        private const val KEY_ALGO_VER = "algo_ver"

        /** v4.3.2：设计容量改为官方标称优先（此前固件电源档案优先 + 机型库 K70U 误写 5000） */
        private const val ALGO_VER = 2

        @Volatile
        private var inst: Store? = null

        fun get(context: Context): Store =
            inst ?: synchronized(this) {
                inst ?: Store(context.applicationContext).also { inst = it }
            }
    }

    private val helper = Helper(ctx)
    private val db: SQLiteDatabase
        get() = helper.writableDatabase

    /** 给 CrashLog 用的应用级上下文（重算失败时落崩溃日志） */
    private val appCtx: Context = ctx.applicationContext

    val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateAlgo()
    }

    private class Helper(c: Context) : SQLiteOpenHelper(c, DB, null, VER) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS samples")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS reports (
                    device_key TEXT NOT NULL,
                    report_ts INTEGER NOT NULL,
                    import_ts INTEGER,
                    model TEXT, market TEXT, device_name TEXT,
                    android_ver TEXT, os_tag TEXT, board TEXT,
                    health REAL, actual REAL, design REAL, est_cap REAL,
                    learned_last REAL, learned_min REAL, learned_max REAL,
                    cycle INTEGER, level INTEGER, temp REAL, volt REAL, cur REAL,
                    src_actual TEXT, src_design TEXT, confidence TEXT,
                    note TEXT, entry TEXT,
                    PRIMARY KEY(device_key, report_ts))"""
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            // v1: samples（每日采样时代，作废）
            // v2: reports，主键 report_ts，无机型分组
            // v3: reports，主键 (device_key, report_ts)，增加容量细项字段
            db.execSQL("DROP TABLE IF EXISTS samples")
            if (o >= 2) {
                // 尽力把 v2 数据搬进 v3，按 model/market 补出 device_key
                try {
                    db.execSQL("ALTER TABLE reports RENAME TO reports_v2_old")
                    onCreate(db)
                    db.execSQL(
                        """INSERT OR REPLACE INTO reports
                            (device_key, report_ts, import_ts, model, market, device_name,
                             android_ver, os_tag, board, health, actual, design, est_cap,
                             learned_last, learned_min, learned_max, cycle, level, temp, volt, cur,
                             src_actual, src_design, confidence, note, entry)
                            SELECT lower(COALESCE(NULLIF(model,''), NULLIF(market,''), 'unknown')),
                                report_ts, import_ts, model, market, NULL,
                                android_ver, NULL, board, health, actual, design, NULL,
                                NULL, NULL, NULL, cycle, level, temp, volt, cur,
                                src_actual, src_design, confidence, note, entry
                            FROM reports_v2_old"""
                    )
                    db.execSQL("DROP TABLE IF EXISTS reports_v2_old")
                    return
                } catch (t: Throwable) {
                    runCatching { db.execSQL("DROP TABLE IF EXISTS reports_v2_old") }
                }
            }
            runCatching { db.execSQL("DROP TABLE IF EXISTS reports") }
            onCreate(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, o: Int, n: Int) {
            runCatching { db.execSQL("DROP TABLE IF EXISTS reports") }
            onCreate(db)
        }
    }

    // ------------------------------------------------------------ 读写

    fun insert(r: ReportRecord): Boolean = try {
        val cv = ContentValues().apply {
            put("device_key", r.deviceKey)
            put("report_ts", r.reportTs)
            put("import_ts", r.importTs)
            put("model", r.model)
            put("market", r.market)
            put("device_name", r.deviceName)
            put("android_ver", r.androidVer)
            put("os_tag", r.osTag)
            put("board", r.board)
            put("health", r.health)
            put("actual", r.chargeFullMah)
            put("design", r.designMah)
            put("est_cap", r.estCapMah)
            put("learned_last", r.learnedLastMah)
            put("learned_min", r.learnedMinMah)
            put("learned_max", r.learnedMaxMah)
            put("cycle", r.cycle)
            put("level", r.level)
            put("temp", r.tempC)
            put("volt", r.voltMv)
            put("cur", r.currentMa)
            put("src_actual", r.actualSrc)
            put("src_design", r.designSrc)
            put("confidence", r.confidence)
            put("note", r.note)
            put("entry", r.entry)
        }
        db.insertWithOnConflict("reports", null, cv, SQLiteDatabase.CONFLICT_REPLACE) > 0
    } catch (t: Throwable) {
        false
    }

    private fun cursorToList(c: android.database.Cursor): List<ReportRecord> {
        val out = ArrayList<ReportRecord>()
        fun s(col: String): String? {
            val i = c.getColumnIndex(col)
            return if (i < 0 || c.isNull(i)) null else c.getString(i)
        }
        fun d(col: String): Double? {
            val i = c.getColumnIndex(col)
            return if (i < 0 || c.isNull(i)) null else c.getDouble(i)
        }
        fun i(col: String): Int? {
            val idx = c.getColumnIndex(col)
            return if (idx < 0 || c.isNull(idx)) null else c.getInt(idx)
        }
        fun l(col: String): Long {
            val idx = c.getColumnIndex(col)
            return if (idx < 0) 0L else c.getLong(idx)
        }
        while (c.moveToNext()) {
            val model = s("model")
            val market = s("market")
            out.add(
                ReportRecord(
                    deviceKey = s("device_key") ?: (model ?: market ?: "unknown").lowercase(),
                    reportTs = l("report_ts"), importTs = l("import_ts"),
                    model = model, market = market, deviceName = s("device_name"),
                    androidVer = s("android_ver"), osTag = s("os_tag"), board = s("board"),
                    health = d("health"), chargeFullMah = d("actual"), designMah = d("design"),
                    estCapMah = d("est_cap"),
                    learnedLastMah = d("learned_last"),
                    learnedMinMah = d("learned_min"),
                    learnedMaxMah = d("learned_max"),
                    cycle = i("cycle"), level = i("level"), tempC = d("temp"),
                    voltMv = d("volt"), currentMa = d("cur"),
                    actualSrc = s("src_actual") ?: "", designSrc = s("src_design") ?: "",
                    confidence = s("confidence") ?: "", note = s("note") ?: "", entry = s("entry")
                )
            )
        }
        return out
    }

    /** 全部记录，按机型再按报告时间升序 */
    fun all(): List<ReportRecord> = try {
        db.rawQuery("SELECT * FROM reports ORDER BY device_key ASC, report_ts ASC", null)?.use {
            cursorToList(it)
        } ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    /** 指定机型的记录，报告时间升序 */
    fun forDevice(key: String): List<ReportRecord> = try {
        db.rawQuery(
            "SELECT * FROM reports WHERE device_key = ? ORDER BY report_ts ASC",
            arrayOf(key)
        )?.use { cursorToList(it) } ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    fun count(): Int = try {
        db.rawQuery("SELECT COUNT(*) FROM reports", null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        } ?: 0
    } catch (t: Throwable) {
        0
    }

    fun clear(): Boolean = try {
        db.delete("reports", null, null); true
    } catch (t: Throwable) {
        false
    }

    fun clearDevice(key: String): Boolean = try {
        db.delete("reports", "device_key = ?", arrayOf(key)) > 0
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------ 每机型设计容量校准

    fun manualDesign(deviceKey: String): Double =
        prefs.getFloat(KEY_MANUAL_PREFIX + deviceKey, 0f).toDouble()
            .let { if (it <= 0) 0.0 else it }

    fun setManualDesign(deviceKey: String, v: Double) {
        prefs.edit()
            .putFloat(KEY_MANUAL_PREFIX + deviceKey, if (v > 0) v.toFloat() else 0f)
            .apply()
    }

    /**
     * 设计容量校准变化后，把该机型**全部已存报告**的健康度按新设计容量重算落库。
     *
     * 为什么必须落库而不是展示时现算：详细页曲线、报告列表、推断、CSV 导出
     * 读的都是记录里存的 health——只改偏好会让"头卡变了、曲线和列表没变"互相打架。
     *
     * 实际容量侧（满充/学习容量）与设计容量无关，保持原值只动 health/design 两个字段；
     * 清除校准时，当初按手动值入库的设计回退到"自然设计"（复用 Health 的
     * 电源档案→机型库链，不另写一份规则）。
     *
     * @return 实际重算的记录条数（-1 = 中途出错）
     */
    fun recomputeHealth(deviceKey: String): Int {
        val manual = manualDesign(deviceKey).takeIf { it > 0 }
        var n = 0
        try {
            db.beginTransaction()
            for (r in forDevice(deviceKey)) {
                val newDesign: Double? = when {
                    manual != null -> manual
                    // 清除校准：当初以手动值入库 → 回退自然设计；其余本来就没手动，不动
                    r.designSrc == "手动设定" -> naturalDesign(r) ?: r.designMah
                    else -> r.designMah
                }
                val newHealth: Double? = if (newDesign != null && newDesign > 0) {
                    val actual = r.chargeFullMah ?: r.learnedLastMah
                        ?: listOfNotNull(r.learnedMinMah, r.learnedMaxMah)
                            .takeIf { it.isNotEmpty() }?.average()
                    actual?.let { kotlin.math.round(it / newDesign * 1000) / 10.0 }
                } else r.health
                if (newHealth != r.health || newDesign != r.designMah) {
                    val cv = ContentValues().apply {
                        if (newHealth == null) putNull("health") else put("health", newHealth)
                        if (newDesign == null) putNull("design") else put("design", newDesign)
                    }
                    db.update(
                        "reports", cv, "device_key=? AND report_ts=?",
                        arrayOf(deviceKey, r.reportTs.toString())
                    )
                    n++
                }
            }
            db.setTransactionSuccessful()
        } catch (t: Throwable) {
            CrashLog.write(appCtx, t)
            n = -1
        } finally {
            runCatching { db.endTransaction() }
        }
        return n
    }

    /**
     * v4.3.2 算法迁移：设计容量改为"官方标称优先"。
     * 升级安装后把已入库记录按新链重算 design/health/来源/置信度/备注，
     * 用户不重新导入 bugreport，曲线和列表也会立即自愈。
     */
    private fun migrateAlgo() {
        if (prefs.getInt(KEY_ALGO_VER, 0) >= ALGO_VER) return
        try {
            val keys = db.rawQuery("SELECT DISTINCT device_key FROM reports", null).use { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) out.add(c.getString(0))
                out
            }
            for (k in keys) recomputeNatural(k)
        } catch (t: Throwable) {
            CrashLog.write(appCtx, t)
        } finally {
            prefs.edit().putInt(KEY_ALGO_VER, ALGO_VER).apply()
        }
    }

    /** 按当前算法重算某机型全部记录的设计容量侧（实际容量字段保持入库原值不动） */
    private fun recomputeNatural(deviceKey: String) {
        val manual = manualDesign(deviceKey).takeIf { it > 0 }
        try {
            db.beginTransaction()
            for (r in forDevice(deviceKey)) {
                val computed = Health.compute(
                    Gauge(
                        chargeFullRaw = r.chargeFullMah,
                        learnedMinMah = r.learnedMinMah,
                        learnedMaxMah = r.learnedMaxMah,
                        profileDesignMah = r.estCapMah,
                        deviceKeys = listOf(r.market, r.model, r.deviceName, r.displayName)
                    )
                )
                val newDesign = manual ?: computed.designMah ?: r.designMah
                val newSrc = if (manual != null) "手动设定" else computed.designSrc
                // 实际容量沿用入库值：导入时的 Gauge 可能带 counter/level 推算项，这里没有
                val actual = r.chargeFullMah
                    ?: r.learnedLastMah
                    ?: listOfNotNull(r.learnedMinMah, r.learnedMaxMah)
                        .takeIf { it.isNotEmpty() }?.average()
                val newHealth: Double? = if (newDesign != null && newDesign > 0) {
                    actual?.let { kotlin.math.round(it / newDesign * 1000) / 10.0 }
                } else r.health
                val newConf = Health.confidenceOf(newHealth, r.actualSrc, newSrc)
                // 手动校准期间固件对照备注没有意义，保持原备注
                val newNote = if (manual != null) r.note else computed.notes.joinToString("；")
                if (newDesign != r.designMah || newHealth != r.health || newSrc != r.designSrc ||
                    newConf != r.confidence || newNote != r.note
                ) {
                    val cv = ContentValues().apply {
                        if (newHealth == null) putNull("health") else put("health", newHealth)
                        if (newDesign == null) putNull("design") else put("design", newDesign)
                        put("src_design", newSrc)
                        put("confidence", newConf)
                        put("note", newNote)
                    }
                    db.update(
                        "reports", cv, "device_key=? AND report_ts=?",
                        arrayOf(deviceKey, r.reportTs.toString())
                    )
                }
            }
            db.setTransactionSuccessful()
        } catch (t: Throwable) {
            CrashLog.write(appCtx, t)
        } finally {
            runCatching { db.endTransaction() }
        }
    }

    /**
     * 记录里能重建出的"自然设计容量"：走一遍 Health 的非手动判定链
     * （v4.3.2 起：机型库官方标称 → 固件电源档案 → 内核值）。
     */
    private fun naturalDesign(r: ReportRecord): Double? =
        Health.compute(
            Gauge(
                chargeFullRaw = r.chargeFullMah,
                learnedMinMah = r.learnedMinMah,
                learnedMaxMah = r.learnedMaxMah,
                profileDesignMah = r.estCapMah,
                deviceKeys = listOf(r.market, r.model, r.deviceName, r.displayName)
            )
        ).designMah

    // ------------------------------------------------------------ 用户自定义的"新电池开始使用时间"

    /** 0 = 没设定（走自动倒推） */
    fun startTs(deviceKey: String): Long = prefs.getLong(KEY_START_PREFIX + deviceKey, 0L)

    fun setStartTs(deviceKey: String, ts: Long) {
        prefs.edit().putLong(KEY_START_PREFIX + deviceKey, if (ts > 0) ts else 0L).apply()
    }

    // ------------------------------------------------------------ 联机基准

    /** 基准曲线地址；空 = 只用内置基准 */
    var baselineUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        set(v) { prefs.edit().putString(KEY_BASE_URL, v.trim()).apply() }

    /** 最近一次联机同步到的曲线文本（JSON） */
    var baselineCache: String
        get() = prefs.getString(KEY_BASE_CACHE, "") ?: ""
        set(v) { prefs.edit().putString(KEY_BASE_CACHE, v).apply() }

    /** 最近一次联机同步结果说明 */
    var baselineStatus: String
        get() = prefs.getString(KEY_BASE_STATUS, "未同步（使用内置基准）") ?: "未同步（使用内置基准）"
        set(v) { prefs.edit().putString(KEY_BASE_STATUS, v).apply() }

    /** 是否开启联机基准对比 */
    var onlineMode: Boolean
        get() = prefs.getBoolean(KEY_ONLINE, false)
        set(v) { prefs.edit().putBoolean(KEY_ONLINE, v).apply() }

    // ------------------------------------------------------------ 导出

    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.append("机型,报告时间,导入时间,健康度%,满充容量mAh,设计容量mAh,估算满充mAh,上次学习mAh,最小学习mAh,最大学习mAh,循环次数,电量%,温度℃,电压mV,电流mA,容量来源,设计容量来源,可信度,报告文件\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
        for (r in all()) {
            sb.append(csv(r.displayName)).append(',')
            sb.append(fmt.format(java.util.Date(r.reportTs))).append(',')
            sb.append(fmt.format(java.util.Date(r.importTs))).append(',')
            sb.append(r.health?.let { "%.2f".format(it) } ?: "").append(',')
            sb.append(r.chargeFullMah?.let { "%.0f".format(it) } ?: "").append(',')
            sb.append(r.designMah?.let { "%.0f".format(it) } ?: "").append(',')
            sb.append(r.estCapMah?.let { "%.0f".format(it) } ?: "").append(',')
            sb.append(r.learnedLastMah?.let { "%.0f".format(it) } ?: "").append(',')
            sb.append(r.learnedMinMah?.let { "%.0f".format(it) } ?: "").append(',')
            sb.append(r.learnedMaxMah?.let { "%.0f".format(it) } ?: "").append(',')
            sb.append(r.cycle ?: "").append(',')
            sb.append(r.level ?: "").append(',')
            sb.append(r.tempC?.let { "%.1f".format(it) } ?: "").append(',')
            sb.append(r.voltMv?.let { "%.0f".format(it) } ?: "").append(',')
            sb.append(r.currentMa?.let { "%.0f".format(it) } ?: "").append(',')
            sb.append(csv(r.actualSrc)).append(',')
            sb.append(csv(r.designSrc)).append(',')
            sb.append(csv(r.confidence)).append(',')
            sb.append(csv(r.entry)).append('\n')
        }
        return sb.toString()
    }

    private fun csv(v: String?): String {
        val s = v ?: ""
        return if (s.contains(",") || s.contains("\"") || s.contains("\n")) "\"${s.replace("\"", "\"\"")}\"" else s
    }
}
