package com.xiaolei.battery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.xiaolei.battery.core.BugReportScanner
import com.xiaolei.battery.core.Gauge
import com.xiaolei.battery.core.OpenedReport
import com.xiaolei.battery.core.SeekSource
import com.xiaolei.battery.core.Units
import com.xiaolei.battery.core.ZipReader
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * SAF 选中的 URI → 可随机访问的 SeekSource。
 *
 * 坑点：AssetFileDescriptor.declaredLength 在大量提供器上返回 -1（UNKNOWN_LENGTH），
 * 不能拿它判空。做法：先问 ContentResolver 要 OpenableColumns.SIZE；
 * 再不济就整文件顺序复制到私有缓存，用本地文件做随机读。
 */
private class ContentSeekSource private constructor(
    override val size: Long,
    private val stream: FileInputStream
) : SeekSource {

    private val ch = stream.channel

    override fun read(pos: Long, len: Int): ByteArray {
        if (pos < 0 || len <= 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(len)
        var got = 0
        while (got < len) {
            ch.position(pos + got)
            val n = ch.read(buf)
            if (n <= 0) break
            got += n
        }
        return if (got == len) buf.array() else buf.array().copyOf(got)
    }

    fun close() {
        runCatching { ch.close() }
        runCatching { stream.close() }
    }

    companion object {
        fun open(context: Context, uri: Uri, size: Long): ContentSeekSource {
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IllegalStateException("无法打开所选文件（提供器拒绝了读取）")
            val fis = runCatching { afd.createInputStream() }.getOrNull()
                ?: FileInputStream(afd.fileDescriptor)
            return ContentSeekSource(size, fis)
        }
    }
}

/** 本地缓存文件版本（复制兜底时用） */
private class FileSeekSource(private val file: File) : SeekSource {
    override val size: Long get() = file.length()
    override fun read(pos: Long, len: Int): ByteArray {
        if (pos < 0 || len <= 0) return ByteArray(0)
        return try {
            java.io.RandomAccessFile(file, "r").use { f ->
                if (pos >= f.length()) return ByteArray(0)
                f.seek(pos)
                val b = ByteArray(len)
                val n = f.read(b, 0, len)
                if (n <= 0) ByteArray(0) else if (n == len) b else b.copyOf(n)
            }
        } catch (t: Throwable) {
            ByteArray(0)
        }
    }
}

/** 报告里能直接读出来的各路容量原始值（mAh） */
class RawCapacities(
    val chargeFullMah: Double?,   // POWER_SUPPLY_CHARGE_FULL
    val designMah: Double?,       // POWER_SUPPLY_CHARGE_FULL_DESIGN
    val estCapMah: Double?,       // Estimated battery capacity
    val learnedLastMah: Double?,  // Last learned battery capacity
    val learnedMinMah: Double?,   // Min learned battery capacity
    val learnedMaxMah: Double?    // Max learned battery capacity
)

/** 一份 bugreport 的解析结果（健康度由调用方结合"该机型的设计容量校准"再算） */
class ParsedReport(
    val gauge: Gauge,
    val raw: RawCapacities,
    val model: String?,
    val market: String?,
    val deviceName: String?,
    val androidVer: String?,
    val osTag: String?,
    val board: String?,
    val reportTs: Long,
    val tsFrom: String,
    val entry: String?,
    val nested: String?,
    val displayName: String?,
    val lines: Long,
    val bytes: Long
) {
    /** 机型分组键：model 最稳定，其次 market */
    val deviceKey: String
        get() = (model?.takeIf { it.isNotBlank() }
            ?: market?.takeIf { it.isNotBlank() }
            ?: "unknown").lowercase()
}

class ImportOutcome(
    val ok: Boolean,
    val message: String,
    val report: ParsedReport? = null,
    val elapsedMs: Long = 0
)

object Importer {

    // bugreport-2026-10-21-182853.zip / bugreport-x-2026-10-21-18-28-54.txt 都能认
    private val NAME_TS = Regex("""(\d{4})-(\d{2})-(\d{2})[-_](\d{2})[-_]?(\d{2})[-_]?(\d{2})""")

    private fun querySize(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst() && i >= 0 && !c.isNull(i)) c.getLong(i) else -1L
        } ?: -1L
    } catch (t: Throwable) {
        -1L
    }

    private fun queryName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0 && !c.isNull(i)) c.getString(i) else null
        }
    } catch (t: Throwable) {
        null
    }

    /** 从文件名提报告时间；非法日期一律拒绝（lenient=false） */
    fun tsFromName(name: String?): Long? {
        val n = name ?: return null
        val m = NAME_TS.find(n) ?: return null
        return try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US)
            f.isLenient = false
            f.parse("${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]} " +
                "${m.groupValues[4]}${m.groupValues[5]}${m.groupValues[6]}")?.time
        } catch (t: Throwable) {
            null
        }
    }

    fun run(context: Context, uri: Uri, onProgress: (String) -> Unit): ImportOutcome {
        var content: ContentSeekSource? = null
        var cache: File? = null
        val t0 = android.os.SystemClock.elapsedRealtime()
        return try {
            onProgress("正在打开…")
            val displayName = queryName(context, uri)
            val declaredSize = querySize(context, uri)

            var src: SeekSource? = null
            if (declaredSize > 0) {
                val c = runCatching { ContentSeekSource.open(context, uri, declaredSize) }.getOrNull()
                if (c != null) {
                    content = c
                    val probe = runCatching {
                        if (c.size > 4096) c.read(c.size / 2, 16) else c.read(0, minOf(16, c.size.toInt()))
                    }.getOrNull()
                    if (probe != null && probe.isNotEmpty()) src = c else { c.close(); content = null }
                }
            }

            if (src == null) {
                onProgress("该文件不能随机访问，正在复制到缓存…")
                val dst = File(context.cacheDir, "bugreport_import.bin")
                dst.delete()
                var total = 0L
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    dst.outputStream().use { out ->
                        val buf = ByteArray(1024 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total % (32 * 1024 * 1024) < 1024 * 1024) {
                                onProgress("已复制 ${total / 1048576} MB…")
                            }
                        }
                    }
                }
                if (total <= 0L) {
                    dst.delete()
                    throw IllegalStateException("读不到文件内容。请换用「文件管理」而不是「最近/收藏」进入选择，或直接在文件管理里点这个 zip，选择用本应用打开。")
                }
                cache = dst
                src = FileSeekSource(dst)
            }

            if (src.size <= 0L) throw IllegalStateException("所选文件为空")

            onProgress("正在定位报告文件…")
            val rep: OpenedReport = ZipReader.open(src)

            val scanner = BugReportScanner()
            var lines = 0L
            val stats = rep.stream(
                onLine = { lines++; scanner.feedLine(it) },
                onProgress = { b -> if (b % (8 * 1024 * 1024) == 0L) onProgress("已解析 ${b / 1048576} MB…") },
                shouldStop = { scanner.shouldStop() }
            )
            val found = scanner.result()

            val hasModel = !found.props["ro.product.model"].isNullOrBlank()
            val hasBattery = found.psu.isNotEmpty() || (found.sections["battery"]?.size ?: 0) >= 3
            if (!hasModel && !hasBattery) {
                throw IllegalStateException("没找到 bugreport 的机型/电池信息。请确认选的是 bugreport 主报告（bugreport-*.txt）或它的原始压缩包，而不是截图、日志片段或已被二次压缩的文件。")
            }

            onProgress("正在计算…")
            val gauge = scanner.toGauge()

            val raw = RawCapacities(
                chargeFullMah = Units.chargeToMah(found.psu["charge_full"]?.toDoubleOrNull()),
                designMah = Units.chargeToMah(found.psu["charge_full_design"]?.toDoubleOrNull()),
                estCapMah = found.found["est_cap_mah"] as? Double,
                learnedLastMah = found.found["learned_last"] as? Double,
                learnedMinMah = found.found["learned_min"] as? Double,
                learnedMaxMah = found.found["learned_max"] as? Double
            )

            // 报告时间：正文 dumpstate 行 > 内层报告名 > 外层文件名 > 导入时刻
            val tsInternal = scanner.reportEpoch()
            val tsName = tsFromName(rep.fname)
                ?: tsFromName(rep.nestedFrom)
                ?: tsFromName(displayName)
            val ts = tsInternal ?: tsName ?: System.currentTimeMillis()
            val tsFrom = when {
                tsInternal != null -> "报告内 dumpstate 时间"
                tsName != null -> "报告文件名时间戳"
                else -> "导入时刻（报告里没有时间）"
            }

            // 澎湃 OS / MIUI 版本号：增量版本号里 OS 开头的最准（如 OS4.0.27.0.XFRCNXM）
            val inc = found.props["ro.build.version.incremental"]
            val miui = found.props["ro.miui.ui.version.name"]
            val osTag = when {
                inc != null && inc.startsWith("OS", true) -> inc
                !miui.isNullOrBlank() -> miui
                else -> inc
            }
            val androidVer = found.props["ro.build.version.release"]?.let { v ->
                val sdk = found.props["ro.build.version.sdk"]
                if (sdk.isNullOrBlank()) "Android $v" else "Android $v (API $sdk)"
            }

            ImportOutcome(
                ok = true,
                message = "解析完成",
                report = ParsedReport(                    gauge = gauge,
                    raw = raw,
                    model = found.props["ro.product.model"],
                    market = found.props["ro.product.marketname"],
                    deviceName = found.props["ro.product.device"],
                    androidVer = androidVer,
                    osTag = osTag,
                    board = found.props["ro.board.platform"] ?: found.props["ro.product.board"],
                    reportTs = ts,
                    tsFrom = tsFrom,
                    entry = rep.fname,
                    nested = rep.nestedFrom,
                    displayName = displayName,
                    lines = lines,
                    bytes = stats.bytesOut
                ),
                elapsedMs = android.os.SystemClock.elapsedRealtime() - t0
            )
        } catch (e: Throwable) {
            ImportOutcome(false, e.message ?: e.toString())
        } finally {
            runCatching { content?.close() }
            runCatching { cache?.delete() }
        }
    }
}
