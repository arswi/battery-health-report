package com.xiaolei.battery

import android.content.Context
import com.xiaolei.battery.core.Health

/**
 * 一份解析结果 → 落库。手动导入和自动任务走同一条路，避免两处逻辑跑偏。
 */
object Ingest {

    /** @return 是否真的写进去了（重复报告会被唯一键拦掉） */
    fun save(ctx: Context, p: ParsedReport): Pair<Boolean, ReportRecord?> {
        val store = Store.get(ctx)
        return try {
            val manual = store.manualDesign(p.deviceKey).takeIf { it > 0 }
            val r = Health.compute(p.gauge.copy(manualDesignMah = manual))
            val rec = ReportRecord(
                deviceKey = p.deviceKey,
                reportTs = p.reportTs,
                importTs = System.currentTimeMillis(),
                model = p.model,
                market = p.market,
                deviceName = p.deviceName,
                androidVer = p.androidVer,
                osTag = p.osTag,
                board = p.board,
                health = r.healthPct,
                chargeFullMah = p.raw.chargeFullMah ?: r.actualMah,
                designMah = r.designMah,
                estCapMah = p.raw.estCapMah,
                learnedLastMah = p.raw.learnedLastMah,
                learnedMinMah = p.raw.learnedMinMah,
                learnedMaxMah = p.raw.learnedMaxMah,
                cycle = r.cycle,
                level = r.level,
                tempC = r.tempC,
                voltMv = r.voltMv,
                currentMa = r.currentMa,
                actualSrc = r.actualSrc,
                designSrc = r.designSrc,
                confidence = r.confidence,
                note = r.notes.joinToString("；"),
                entry = p.entry
            )
            store.insert(rec) to rec
        } catch (t: Throwable) {
            CrashLog.write(ctx, t)
            false to null
        }
    }
}
