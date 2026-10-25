package com.xiaolei.battery

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * 自动任务的执行体：扫目录 → 解析 → 入库 → 通知。全程兜底，绝不让它把进程带崩。
 */
class ImportJobService : JobService() {

    @Volatile
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            val msg = try {
                AutoTask.checkNow(this)
            } catch (t: Throwable) {
                CrashLog.write(this, t)
                "自动任务异常：${t.javaClass.simpleName}"
            }
            AutoTask.notify(this, "电池健康报告 · 自动任务", msg)
            runCatching { jobFinished(params, false) }
        }.apply { isDaemon = true }
        worker?.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        runCatching { worker?.interrupt() }
        return false
    }
}
