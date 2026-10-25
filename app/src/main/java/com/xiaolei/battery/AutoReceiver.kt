package com.xiaolei.battery

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.job.JobInfo
import android.app.job.JobScheduler

/**
 * 每日闹钟 → 起一个一次性 Job（解析 200MB 报告要十几秒，不能在广播里干）。
 */
class AutoReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        // 闹钟重启后会丢，开机时重排一次
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (AutoTask.isEnabled(ctx)) AutoTask.schedule(ctx)
            return
        }
        if (intent.action != AutoTask.ACTION_CHECK) return
        val res = goAsync()
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val info = JobInfo.Builder(AutoTask.JOB_ID, ComponentName(ctx, ImportJobService::class.java))
                .setMinimumLatency(0)
                .setOverrideDeadline(30_000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            js.schedule(info)
        } catch (t: Throwable) {
            CrashLog.write(ctx, t)
        } finally {
            res.finish()
        }
    }
}
