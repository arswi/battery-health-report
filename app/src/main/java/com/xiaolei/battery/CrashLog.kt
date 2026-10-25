package com.xiaolei.battery

import android.app.Application
import android.content.Context
import java.io.File
import java.io.StringWriter

/**
 * 崩溃兜底：把未捕获异常落到私有文件，界面上可以直接看/复制。
 *
 * 没有这东西的时候，一次闪退只能靠连电脑抓 logcat；
 * 装上之后用户自己在 App 里就能把堆栈发出来。
 */
object CrashLog {

    private const val NAME = "crash.txt"
    private const val KEEP = 3
    private const val MAX = 24000

    private fun file(c: Context) = File(c.filesDir, NAME)

    fun write(c: Context, t: Throwable) {
        runCatching {
            val sw = StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val block = buildString {
                append("===== ")
                append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                    .format(java.util.Date()))
                append(" =====\n")
                append(sw.toString())
            }
            val f = file(c)
            val old = if (f.exists()) f.readText() else ""
            val merged = (block + "\n" + old)
            val blocks = merged.split("===== ").filter { it.isNotBlank() }
            val kept = blocks.take(KEEP).joinToString("===== ")
            f.writeText(if (kept.length > MAX) kept.substring(0, MAX) else kept)
        }
    }

    fun read(c: Context): String? = runCatching {
        val f = file(c)
        if (!f.exists() || f.length() == 0L) null else f.readText()
    }.getOrNull()

    fun clear(c: Context) = runCatching { file(c).delete() }
}

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            CrashLog.write(this, e)
            runCatching { prev?.uncaughtException(thread, e) }
        }
    }
}
