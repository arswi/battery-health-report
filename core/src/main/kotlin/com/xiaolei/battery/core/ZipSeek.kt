package com.xiaolei.battery.core

import java.util.zip.Inflater

/**
 * bugreport 压缩包读取器。
 *
 * 小米/红米的 bugreport 是"包中包"：外层是 MIUI 日志集合包（几百 MB、上百个条目），
 * 真正的报告是里面嵌套的第二层 zip；而第二层里面，主报告 `bugreport-*.txt`
 * 又常常排在倒数几条，前面压着 180MB 的 proto/FS 快照。
 *
 * 所以这里不能有任何"整包读进内存"的动作。做法是双层流式：
 *   外层 seek+inflate → 得到内层 zip 的字节流 → 按 local file header 顺序扫过去
 *   → 命中目标后接第二层 inflate → 逐行吐给解析器。
 * 内存占用恒定在几百 KB，与文件体积无关。
 */

interface SeekSource {
    val size: Long
    fun read(pos: Long, len: Int): ByteArray
}

/** 顺序字节流：只需要往前读/跳，不需要回退，因此可以架在解压缩器上 */
interface SeqReader {
    val eof: Boolean
    fun take(n: Int): ByteArray
    fun skip(n: Long): Boolean
}

private const val SIG_LOCAL = 0x04034b50L
private const val SIG_CENTRAL = 0x02014b50L
private const val SIG_EOCD = 0x06054b50L
private const val SIG_EOCD64LOC = 0x07064b50L
private const val SIG_EOCD64 = 0x06064b50L
private const val SIG_DD = 0x08074b50L

private fun u32(b: ByteArray, o: Int): Long =
    ((b[o].toLong() and 0xff) or
        ((b[o + 1].toLong() and 0xff) shl 8) or
        ((b[o + 2].toLong() and 0xff) shl 16) or
        ((b[o + 3].toLong() and 0xff) shl 24))

private fun u16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

class ZipEntry(
    val fname: String,
    val method: Int,
    val compSize: Long,
    val uncompSize: Long,
    val headerOffset: Long,
    val flag: Int
)

// ---------------------------------------------------------------- 顺序流实现

/** 从不压缩的另一个顺序流里取固定长度的窗口 */
private class LimitedSeqReader(private val inner: SeqReader, private var remain: Long) : SeqReader {
    override val eof: Boolean get() = remain <= 0L || inner.eof
    override fun take(n: Int): ByteArray {
        val want = minOf(n.toLong(), remain).toInt()
        if (want <= 0) return ByteArray(0)
        val b = inner.take(want)
        remain -= b.size
        return b
    }

    override fun skip(n: Long): Boolean {
        var left = n
        while (left > 0) {
            if (remain <= 0) return true
            val step = minOf(left, 64 * 1024 * 1024L).toInt()
            val got = take(step)
            left -= got.size
            if (got.isEmpty()) return false
        }
        return true
    }
}

/** SeekSource 上的一段窗口 → 顺序流 */
private class RangeSeqReader(
    private val src: SeekSource,
    private val start: Long,
    private val end: Long
) : SeqReader {
    private var pos = start
    override val eof: Boolean get() = pos >= end
    override fun take(n: Int): ByteArray {
        if (pos >= end) return ByteArray(0)
        val len = minOf(n.toLong(), end - pos, 8 * 1024 * 1024L).toInt()
        val b = src.read(pos, len)
        pos += b.size
        return b
    }

    override fun skip(n: Long): Boolean {
        val np = pos + n
        return if (np >= end) {
            pos = end; false
        } else {
            pos = np; true
        }
    }
}

/**
 * 顺序字节流 + inflate → 新的顺序字节流。
 * 内部只维护一块滑动窗口缓冲区，输出永不累积。
 */
private class InflateSeqReader(private val inner: SeqReader, private val chunk: Int = 256 * 1024) : SeqReader {
    private val WINDOW = 512 * 1024
    private var buf = ByteArray(WINDOW)
    private var head = 0          // 未消费起点
    private var tail = 0          // 数据终点
    private val infl = Inflater(true)
    private val tmp = ByteArray(chunk)
    private var inputNeeded = true
    private var finished = false

    private fun fill() {
        // 先压缩窗口，腾出空间
        if (head > 0) {
            java.lang.System.arraycopy(buf, head, buf, 0, tail - head)
            tail -= head
            head = 0
        }
        while (tail < buf.size) {
            if (inputNeeded) {
                val b = inner.take(chunk)
                if (b.isNotEmpty()) {
                    infl.setInput(b, 0, b.size)
                    inputNeeded = false
                } else {
                    if (!inner.eof) continue
                    infl.setInput(ByteArray(0))
                    inputNeeded = false
                }
            }
            if (infl.needsInput()) {
                if (inner.eof) break
                inputNeeded = true
                continue
            }
            val room = buf.size - tail
            if (room == 0) break
            val n = try {
                // 关键：单次膨胀量不得超过窗口剩余空间，否则会丢字节
                infl.inflate(tmp, 0, minOf(tmp.size, room))
            } catch (e: java.util.zip.DataFormatException) {
                throw IllegalStateException("解压失败，文件可能已损坏")
            }
            if (n == 0) {
                if (infl.finished()) { finished = true; break }
                if (infl.needsDictionary()) break
                if (infl.needsInput()) {
                    if (inner.eof) { finished = true; break }
                    inputNeeded = true
                }
                continue
            }
            java.lang.System.arraycopy(tmp, 0, buf, tail, n)
            tail += n
        }
    }

    private fun available(): Int = tail - head

    override val eof: Boolean get() = available() == 0 && (finished || inner.eof && infl.needsInput())

    override fun take(n: Int): ByteArray {
        var want = n
        val out = ArrayList<ByteArray>(maxOf(1, want / 65536 + 1))
        var got = 0
        while (got < want) {
            if (available() == 0) {
                fill()
                if (available() == 0) break
            }
            val c = minOf(want - got, available())
            val b = ByteArray(c)
            java.lang.System.arraycopy(buf, head, b, 0, c)
            head += c
            got += c
            out.add(b)
        }
        if (out.isEmpty()) return ByteArray(0)
        if (out.size == 1) return out.first()
        val res = ByteArray(got)
        var o = 0
        for (b in out) {
            java.lang.System.arraycopy(b, 0, res, o, b.size)
            o += b.size
        }
        return res
    }

    override fun skip(n: Long): Boolean {
        var left = n
        while (left > 0) {
            if (available() == 0) {
                fill()
                if (available() == 0) return false
            }
            val c = minOf(left, available().toLong()).toInt()
            head += c
            left -= c
        }
        return true
    }
}

/** 读取到恰好 n 字节；不足则 false */
private fun SeqReader.exact(n: Int): ByteArray? {
    if (n <= 0) return ByteArray(0)
    val b = take(n)
    return if (b.size == n) b else null
}

// ---------------------------------------------------------------- ZIP 结构解析

fun isZipHead(b: ByteArray): Boolean = b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4b.toByte() &&
    (b[2] == 0x03.toByte() || b[2] == 0x05.toByte() || b[2] == 0x07.toByte())

private fun findEocd(s: SeekSource): Long {
    val tailLen = minOf(s.size, 66000L).toInt()
    val tail = s.read(s.size - tailLen, tailLen)
    for (i in tail.size - 22 downTo 0) {
        if (u32(tail, i) == SIG_EOCD) return s.size - tailLen + i
    }
    throw IllegalStateException("找不到 zip 结尾标记（EOCD），文件可能不完整")
}

fun readCentralDirectory(s: SeekSource): List<ZipEntry> {
    val eocdPos = findEocd(s)
    val hdr = s.read(eocdPos, 22)
    var cdSize = u32(hdr, 12)
    var cdOffset = u32(hdr, 16)
    var total = u16(hdr, 10)

    // Zip64：计数/偏移被填满到 0xFFFFFFFF 时才需要
    if (cdOffset == 0xFFFFFFFFL || total == 0xFFFF || cdSize == 0xFFFFFFFFL) {
        val locPos = maxOf(0L, eocdPos - 20)
        val probe = s.read(locPos, minOf(20L, s.size - locPos).toInt())
        for (i in 0..maxOf(0, probe.size - 4)) {
            if (u32(probe, i) == SIG_EOCD64LOC) {
                val disc = u32(probe, i + 8)
                if (disc != 0L) throw IllegalStateException("不支持跨磁盘的 zip 分片")
                val p64 = u32(probe, i + 8 + 8)
                val h = s.read(p64, 56)
                total = u32(h, 32).toInt()
                cdSize = u32(h, 40)
                cdOffset = u32(h, 48)
                break
            }
        }
    }

    val out = ArrayList<ZipEntry>(total)
    var p = cdOffset
    val end = cdOffset + cdSize
    while (p + 46 <= end) {
        val h = s.read(p, 46)
        if (u32(h, 0) != SIG_CENTRAL) break
        val method = u16(h, 10)
        val flag = u16(h, 8)
        val csz = u32(h, 20)
        val usz = u32(h, 24)
        val nlen = u16(h, 28)
        val elen = u16(h, 30)
        val clen = u16(h, 32)
        val off = u32(h, 42)
        val name = String(s.read(p + 46, nlen), Charsets.UTF_8)
        out.add(ZipEntry(name, method, csz, usz, off, flag))
        p += 46L + nlen + elen + clen
    }
    return out
}

/** 从 local file header 算出数据区的起点（多了 lpw comment 等情况） */
fun entryDataStart(s: SeekSource, e: ZipEntry): Long {
    val h = s.read(e.headerOffset, 30)
    val nlen = u16(h, 26)
    val elen = u16(h, 28)
    return e.headerOffset + 30L + nlen + elen
}

fun readSmallEntry(s: SeekSource, e: ZipEntry): ByteArray {
    val start = entryDataStart(s, e)
    val r: SeqReader = RangeSeqReader(s, start, start + e.compSize)
    val dec: SeqReader = if (e.method == 8) InflateSeqReader(r) else r
    return dec.take(Int.MAX_VALUE.coerceAtMost(minOf(e.uncompSize, 16 * 1024 * 1024L).toInt()))
}

/**
 * 找「包中包」：MIUI 的日志集合包里，真正的报告是第二层 zip。
 * 外层通常混着一堆 .txt（version.txt、properties.txt 之类），
 * 若不做这一步，pickEntry 会把某个几十字节的 txt 当成报告。
 */
fun pickNested(entries: List<ZipEntry>, mainTarget: String?): ZipEntry? {
    val cands = entries.filter {
        val base = it.fname.substringAfterLast('/')
        !it.fname.startsWith("FS/", true) &&
            base.endsWith(".zip", true) &&
            (Regex("""^bugreport-[^/]*\.zip$""", RegexOption.IGNORE_CASE).matches(base) ||
                base.contains("bugreport", true) || base == mainTarget)
    }
    if (cands.isEmpty()) return null
    return cands.maxByOrNull { it.uncompSize }
}

fun pickEntry(entries: List<ZipEntry>, mainTarget: String?): ZipEntry? {
    fun isRootTxt(n: String) = !n.startsWith("FS/", true) && n.substringAfterLast('/').endsWith(".txt", true)
    val strict = entries.filter {
        val n = it.fname
        isRootTxt(n) && (Regex("""^bugreport-[^/]*\.txt$""").containsMatchIn(
            n.substringAfterLast('/')
        ) || n == mainTarget)
    }
    val pool = strict.ifEmpty {
        entries.filter { isRootTxt(it.fname) && (it.fname.contains("bugreport", true) || it.fname.contains("dumpstate", true)) }
    }.ifEmpty { entries.filter { isRootTxt(it.fname) } }
    return pool.maxByOrNull { it.uncompSize }
}

// ---------------------------------------------------------------- 包中包下钻

private val TARGET_RX = Regex("""^bugreport-[^/]*\.txt$""")

/** 在内层 zip 的字节流里顺序找目标文件；返回时 reader 已停在目标数据区起点 */
private fun scanNested(inp: SeqReader, wantName: String? = null): Pair<ZipEntry, SeqReader>? {
    var s = inp
    var best: ZipEntry? = null
    for (guard in 0 until 200000) {
        val sigBytes = s.exact(4) ?: return null
        val sig = u32(sigBytes, 0)
        if (sig == SIG_EOCD || sig == SIG_EOCD64 || sig == SIG_EOCD64LOC || sig == SIG_CENTRAL) {
            return best?.let { Pair(it, LimitedSeqReader(object : SeqReader {
                override val eof: Boolean get() = true
                override fun take(n: Int) = ByteArray(0)
                override fun skip(n: Long) = false
            }, 0)) }
        }
    if (sig != SIG_LOCAL) {
        val a = resync(s) ?: return best?.let { rescanHint(it) }
        s = a
        continue
    }

        // head 是签名之后的 26 字节：
        //  0..1 version  2..3 flag  4..5 method  6..7 time  8..9 date
        // 10..13 crc    14..17 csize 18..21 usize  22..23 nlen  24..25 elen
        val head = s.exact(26) ?: return null
        val flag = u16(head, 2)
        val method = u16(head, 4)
        val csz = u32(head, 14)
        val usz = u32(head, 18)
        val nlen = u16(head, 22)
        val elen = u16(head, 24)
        val nameB = s.exact(nlen) ?: return null
        val name = String(nameB, Charsets.UTF_8)
        if (elen > 0) s.skip(elen.toLong())

        if (wantName != null) {
            if (name == wantName) {
                return Pair(ZipEntry(name, method, csz, usz, 0, flag), s)
            }
        } else if (TARGET_RX.matches(name.substringAfterLast('/')) && !name.startsWith("FS/", true)) {
            return Pair(ZipEntry(name, method, csz, usz, 0, flag), s)
        } else if (name.substringAfterLast('/').endsWith(".txt", true) && !name.startsWith("FS/", true)) {
            if (best == null || usz > best!!.uncompSize) best = ZipEntry(name, method, csz, usz, 0, flag)
        }

        // 跳过数据区（bit3 时 sysfs 尺寸为 0，用 central dir 尺寸不可用 → 直接按 csz）
        val step = if (flag and 8 != 0 && csz == 0L) -1L else csz
        if (step >= 0) {
            if (!s.skip(step)) return best?.let { b -> rescanHint(b) }
            if (flag and 8 != 0) {
                val dd = s.take(4)
                if (dd.size == 4 && u32(dd, 0) == SIG_DD) s.skip(12) else s.skip(8)
            }
        } else {
            // 数据描述符模式且本地头没给尺寸：一路扫到下一个 local header
            // bugreport 包不会这样生成，保守起见直接结束
            return best?.let { b -> rescanHint(b) }
        }
    }
    return null
}

private fun rescanHint(e: ZipEntry): Pair<ZipEntry, SeqReader> = Pair(e, object : SeqReader {
    override val eof: Boolean get() = true
    override fun take(n: Int) = ByteArray(0)
    override fun skip(n: Long) = false
})

/** 顺序流前面塞一段已读过的字节，用于重同步后的对齐 */
private class PrefixSeqReader(private var prefix: ByteArray, private val rest: SeqReader) : SeqReader {
    private var p = 0
    override val eof: Boolean get() = (p >= prefix.size) && rest.eof

    override fun take(n: Int): ByteArray {
        if (p < prefix.size) {
            val c = minOf(n, prefix.size - p)
            val b = prefix.copyOfRange(p, p + c)
            p += c
            return b
        }
        return rest.take(n)
    }

    override fun skip(n: Long): Boolean {
        var left = n
        while (left > 0) {
            if (p < prefix.size) {
                val c = minOf(left, (prefix.size - p).toLong()).toInt()
                p += c
                left -= c
            } else {
                val step = minOf(left, 64 * 1024 * 1024L).toInt()
                val b = take(step)
                left -= b.size
                if (b.isEmpty()) return false
            }
        }
        return true
    }
}

/**
 * 向前找下一个 local header 签名，返回一个"已对齐"的新流。
 * 这是遇到错位打包时的自救手段：把命中点之后的字节作为前缀塞回流里。
 * 最多扫 8MB，找不到就放弃。
 */
private fun resync(s: SeqReader): SeqReader? {
    var scanned = 0
    val limit = 8 * 1024 * 1024
    while (scanned < limit) {
        val b = s.take(64 * 1024)
        if (b.isEmpty()) return null
        scanned += b.size
        for (i in 0..maxOf(0, b.size - 4)) {
            if (u32(b, i) == SIG_LOCAL) {
                return PrefixSeqReader(b.copyOfRange(i, b.size), s)
            }
        }
    }
    return null
}

// ---------------------------------------------------------------- 对外入口

class OpenedReport(
    val fname: String,
    val uncompSize: Long,
    val nestedFrom: String?,
    val outerEntries: Int,
    val method: Int = 8,
    private val openCompressed: () -> SeqReader
) {
    data class Stats(val bytesOut: Long, val lines: Long, val stopped: Boolean)

    /** 流式产出文本行 */
    fun stream(
        maxBytes: Long = 900L * 1024 * 1024,
        onLine: (String) -> Unit,
        onProgress: ((Long) -> Unit)? = null,
        shouldStop: (() -> Boolean)? = null
    ): Stats {
        val comp = openCompressed()
        val dec: SeqReader = if (method == 8) InflateSeqReader(comp) else comp
        val cd = Charsets.UTF_8.newDecoder()
        cd.onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
        cd.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)

        val sb = StringBuilder(256)
        var bytesOut = 0L
        var lines = 0L
        var stopped = false
        val inBuf = java.nio.ByteBuffer.allocate(256 * 1024)
        val cbuf = CharArray(128 * 1024)
        val cb = java.nio.CharBuffer.wrap(cbuf)

        fun decodeBytes(b: ByteArray) {
            bytesOut += b.size
            if (bytesOut > maxBytes) throw IllegalStateException("报告解压后超过上限，已中止")
            var off = 0
            while (off < b.size) {
                val n = minOf(inBuf.remaining(), b.size - off)
                inBuf.put(b, off, n)
                off += n
                inBuf.flip()
                // 反复解码直到输入耗尽（输出缓冲满时，半行留在 sb 里续着）
                while (true) {
                    cb.clear()
                    val res = cd.decode(inBuf, cb, false)
                    val cnt = cb.position()
                    // 按 '\n' 批量切行：只做一次数组扫描，避免逐字符虚拟调用
                    var seg = 0
                    for (i in 0 until cnt) {
                        if (cbuf[i] == '\n') {
                            if (i > seg) sb.append(cbuf, seg, i - seg)
                            onLine(sb.toString())
                            lines++
                            sb.setLength(0)
                            seg = i + 1
                        }
                    }
                    if (cnt > seg) sb.append(cbuf, seg, cnt - seg)
                    if (sb.length > 4 * 1024 * 1024) throw IllegalStateException("遇到异常超长行，已中止")
                    if (res.isOverflow && inBuf.hasRemaining()) continue
                    break
                }
                if (inBuf.hasRemaining()) {
                    inBuf.compact()
                } else {
                    inBuf.clear()
                }
            }
        }

        var lastProgress = 0L
        while (true) {
            if (stopped) break
            val b = dec.take(256 * 1024)
            if (b.isEmpty()) break
            decodeBytes(b)
            if (bytesOut - lastProgress > 4 * 1024 * 1024) {
                lastProgress = bytesOut
                onProgress?.invoke(bytesOut)
            }
            if (shouldStop != null && shouldStop.invoke()) { stopped = true; break }
        }
        if (sb.isNotEmpty()) {
            onLine(sb.toString())
            lines++
        }
        onProgress?.invoke(bytesOut)
        return Stats(bytesOut, lines, stopped)
    }
}

object ZipReader {

    /** 自动处理「纯文本 / 单层 zip / 嵌套 zip」，返回可直接 stream 的对象 */
    fun open(src: SeekSource, depth: Int = 0): OpenedReport {
        val head = src.read(0, minOf(4, src.size).toInt())
        val isZip = isZipHead(head)

        if (!isZip) {
            return OpenedReport("(文本文件)", src.size, null, 0, method = 0) {
                RangeSeqReader(src, 0, src.size)
            }
        }

        val entries = readCentralDirectory(src)
        var mainTarget: String? = null
        entries.firstOrNull { it.fname.equals("main_entry.txt", true) }?.let { me ->
            runCatching {
                mainTarget = readSmallEntry(src, me).toString(Charsets.UTF_8)
                    .lineSequence().firstOrNull()?.trim()
            }
        }

        val nested = pickNested(entries, mainTarget)

        if (nested != null && nested.uncompSize > 0) {
            return drill(nested, src, entries.size, depth)
        }

        val entry = pickEntry(entries, mainTarget)
            ?: throw IllegalStateException("压缩包里没有找到 bugreport 报告文本")

        val start = entryDataStart(src, entry)
        return OpenedReport(entry.fname, entry.uncompSize, null, entries.size, method = entry.method) {
            RangeSeqReader(src, start, start + entry.compSize)
        }
    }

    private fun drill(entry: ZipEntry, src: SeekSource, outerCount: Int, depth: Int): OpenedReport {
        if (depth >= 3) throw IllegalStateException("zip 嵌套层数过多，已放弃解析")

        // 包中包：双层流式下钻。
        // 性能关键：只扫一遍。旧版为"取 method"和"惰性打开"把外层 200MB 反复解压了三遍，
        // 现在第一次严格命中后直接持有已定位到数据区起点的流，后续打开零成本复用。
        val outerStart = entryDataStart(src, entry)
        fun outerSeq(): SeqReader = RangeSeqReader(src, outerStart, outerStart + entry.compSize)
        fun outerInflated(): SeqReader =
            if (entry.method == 8) InflateSeqReader(outerSeq()) else outerSeq()

        val first = scanNested(outerInflated())
        val picked = first?.first
            ?: throw IllegalStateException("内层压缩包里没有找到 bugreport 报告文本")
        val strict = TARGET_RX.matches(picked.fname.substringAfterLast('/'))

        var targetName = picked.fname
        var hitEntry: ZipEntry? = if (strict) picked else null
        var hitReader: SeqReader? = if (strict) first.second else null
        if (!strict) {
            // 严格规则没命中，用放宽后选出的名字精准复扫一次（多解一遍前缀，代价可接受）
            val r2 = scanNested(outerInflated(), picked.fname)
            targetName = r2?.first?.fname ?: picked.fname
            hitEntry = r2?.first
            hitReader = r2?.second
        }

        fun reopen(): LimitedSeqReader {
            val f = scanNested(outerInflated(), targetName)
                ?: throw IllegalStateException("内层压缩包结构已变化，读取失败")
            val n = if (f.first.compSize > 0) f.first.compSize else Long.MAX_VALUE
            return LimitedSeqReader(f.second, n)
        }

        val he = hitEntry
        val hr = hitReader
        val openCompressed: () -> SeqReader = when {
            he != null && hr != null -> {
                // 第一次打开直接用已定位的流；万一被二次打开（极少见）才重新扫描
                var used = false
                fun open(): SeqReader {
                    if (used) return reopen()
                    used = true
                    return LimitedSeqReader(hr, if (he.compSize > 0) he.compSize else Long.MAX_VALUE)
                }
                ::open
            }
            else -> ::reopen
        }

        return OpenedReport(targetName, entry.uncompSize, entry.fname, outerCount,
            method = he?.method ?: 8, openCompressed = openCompressed)
    }
}
