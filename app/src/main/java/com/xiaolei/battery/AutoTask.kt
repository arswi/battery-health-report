package com.xiaolei.battery

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.util.Calendar

/**
 * 每日自动任务。
 *
 * 说明白一点：第三方应用**拿不到** android.permission.DUMP（signature|privileged），
 * 也没法替你拨 *#*#284#*#*，所以"无感自动生成 bugreport"在系统层面做不到。
 * 能做到的是这两件：
 *   1. 每天定时扫一次你授权的目录，发现新的 bugreport 就自动解析入库（真·自动）
 *   2. 没发现新报告时发一条提醒，你点一下生成即可（这一步必须人工）
 * 开关默认关闭，由用户在界面里自己开。
 */
object AutoTask {

    const val ACTION_CHECK = "com.xiaolei.battery.ACTION_CHECK"
    const val JOB_ID = 8801
    private const val REQ_ALARM = 4101
    private const val NOTI_ID = 5201
    private const val CHANNEL = "auto_task"

    private const val P_ENABLED = "auto_enabled"
    private const val P_HOUR = "auto_hour"
    private const val P_MIN = "auto_min"
    private const val P_FOLDER = "auto_folder"
    private const val P_LAST_RUN = "auto_last_run"
    private const val P_LAST_MSG = "auto_last_msg"
    private const val P_DONE = "auto_done"

    // ------------------------------------------------------------ 设置

    private fun prefs(ctx: Context) = Store.get(ctx).prefs

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(P_ENABLED, false)
    fun hour(ctx: Context): Int = prefs(ctx).getInt(P_HOUR, 21)
    fun minute(ctx: Context): Int = prefs(ctx).getInt(P_MIN, 0)

    fun folderUri(ctx: Context): Uri? =
        prefs(ctx).getString(P_FOLDER, null)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

    fun lastResult(ctx: Context): String {
        val p = prefs(ctx)
        val ts = p.getLong(P_LAST_RUN, 0L)
        val msg = p.getString(P_LAST_MSG, null) ?: "还没运行过"
        return if (ts <= 0L) msg else "$msg（${java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.CHINA).format(java.util.Date(ts))}）"
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(P_ENABLED, on).apply()
        if (on) schedule(ctx) else cancel(ctx)
    }

    fun setTime(ctx: Context, h: Int, m: Int) {
        prefs(ctx).edit().putInt(P_HOUR, h.coerceIn(0, 23)).putInt(P_MIN, m.coerceIn(0, 59)).apply()
        if (isEnabled(ctx)) schedule(ctx)
    }

    /** 用户授权的目录；同时申请持久化读权限 */
    fun setFolder(ctx: Context, uri: Uri?) {
        if (uri == null) {
            prefs(ctx).edit().remove(P_FOLDER).apply()
            return
        }
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        prefs(ctx).edit().putString(P_FOLDER, uri.toString()).apply()
    }

    // ------------------------------------------------------------ 定时

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, AutoReceiver::class.java).apply { action = ACTION_CHECK }
        val flags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(ctx, REQ_ALARM, i, flags)
    }

    /** 每天都跑一次（Doze 下会延后，但不会漏） */
    fun schedule(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour(ctx))
                set(Calendar.MINUTE, minute(ctx))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            am.cancel(pending(ctx))
            am.setInexactRepeating(AlarmManager.RTC, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pending(ctx))
        }.onFailure { CrashLog.write(ctx, it) }
    }

    fun cancel(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(ctx))
        }
        runCatching {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
            js.cancel(JOB_ID)
        }
    }

    // ------------------------------------------------------------ 检查与导入

    /** 扫目录 → 导入没处理过的报告。返回结果文案（可直接显示给用户） */
    fun runCheck(ctx: Context): String {
        val store = Store.get(ctx)
        val uri = folderUri(ctx)
        if (uri == null) {
            return "还没选择报告目录，跳过本次检查"
        }
        val files = runCatching { listReports(ctx, uri) }.getOrElse {
            CrashLog.write(ctx, it)
            emptyList()
        }
        if (files.isEmpty()) {
            return "目录里没有 bugreport 文件（先在拨号盘输入 *#*#284#*#* 生成）"
        }

        val done = doneSet(ctx).toMutableSet()
        val fresh = files.filter { it.first !in done }.take(2)
        if (fresh.isEmpty()) {
            return "没有发现新的报告（最近一份已导入过）"
        }

        var ok = 0
        var lastErr = ""
        for (f in fresh) {
            val name = f.first
            val out = runCatching { Importer.run(ctx, f.second) { } }.getOrElse {
                ImportOutcome(false, it.message ?: it.toString())
            }
            if (out.ok && out.report != null) {
                val (saved, _) = Ingest.save(ctx, out.report)
                if (saved) {
                    ok++
                    done.add(name)
                } else {
                    lastErr = "这份报告已存在"
                    done.add(name)
                }
            } else {
                lastErr = out.message
            }
        }
        saveDone(ctx, done)
        return when {
            ok > 0 -> "已自动导入 $ok 份报告"
            lastErr.isNotBlank() -> "自动导入未成功：$lastErr"
            else -> "本次没有可导入的新报告"
        }
    }

    /** 手动点"立即检查"时用：同步执行，返回文案 */
    fun checkNow(ctx: Context): String {
        val msg = runCatching { runCheck(ctx) }.getOrElse {
            CrashLog.write(ctx, it)
            "自动检查失败：${it.javaClass.simpleName}"
        }
        prefs(ctx).edit()
            .putLong(P_LAST_RUN, System.currentTimeMillis())
            .putString(P_LAST_MSG, msg)
            .apply()
        return msg
    }

    /** 列出 SAF 目录树里所有 bugreport 文件：(名字, uri)，按修改时间倒序 */
    private fun listReports(ctx: Context, tree: Uri): List<Pair<String, Uri>> {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val out = ArrayList<Triple<String, Uri, Long>>()
        ctx.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        )?.use { c ->
            val iId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val iTime = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val name = if (iName >= 0) c.getString(iName) ?: continue else continue
                if (!name.startsWith("bugreport", true)) continue
                if (!name.endsWith(".zip", true) && !name.endsWith(".txt", true)) continue
                val id = if (iId >= 0) c.getString(iId) ?: continue else continue
                val t = if (iTime >= 0) c.getLong(iTime) else 0L
                out.add(Triple(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), t))
            }
        }
        return out.sortedByDescending { it.third }.map { it.first to it.second }
    }

    // ------------------------------------------------------------ 已处理记录

    private fun doneSet(ctx: Context): Set<String> =
        prefs(ctx).getString(P_DONE, "")?.split('|')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /** 只保留最近 80 条，避免无限增长 */
    private fun saveDone(ctx: Context, set: Set<String>) {
        prefs(ctx).edit().putString(P_DONE, set.take(80).joinToString("|")).apply()
    }

    // ------------------------------------------------------------ 通知

    fun notify(ctx: Context, title: String, text: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = NotificationChannel(CHANNEL, "自动任务", NotificationManager.IMPORTANCE_DEFAULT)
                nm.createNotificationChannel(ch)
            }
            val pi = PendingIntent.getActivity(
                ctx, 99, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(ctx, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(ctx)
            }
            val n = b.setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            if (Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled()) nm.notify(NOTI_ID, n)
        }.onFailure { CrashLog.write(ctx, it) }
    }

    /** 通知权限（Android 13+） */
    fun needsNotifyPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
