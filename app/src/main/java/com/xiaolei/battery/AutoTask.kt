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

    /**
     * 用户授权的目录：申请**持久化读权限并验证真的登记上了**。
     * 部分第三方文件管理器的选择器不支持持久授权（takePersistableUriPermission
     * 静默失败）——这种目录存下来就是个死 URI，之后每次扫目录都会被系统
     * SecurityException 拒掉。拿不到持久授权就不存，返回 false 让用户重选。
     */
    fun setFolder(ctx: Context, uri: Uri?): Boolean {
        if (uri == null) {
            prefs(ctx).edit().remove(P_FOLDER).apply()
            return true
        }
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val ok = hasFolderAccess(ctx, uri)
        if (ok) {
            prefs(ctx).edit().putString(P_FOLDER, uri.toString()).apply()
        } else {
            prefs(ctx).edit().remove(P_FOLDER).apply()
        }
        return ok
    }

    /** 目录的持久读授权是否还有效（被系统重启清理/权限重置收回后就查不到了） */
    fun hasFolderAccess(ctx: Context, uri: Uri? = folderUri(ctx)): Boolean {
        if (uri == null) return false
        return ctx.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && it.uri.toString() == uri.toString()
        }
    }

    // ------------------------------------------------------------ 定时

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, AutoReceiver::class.java).apply { action = ACTION_CHECK }
        val flags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(ctx, REQ_ALARM, i, flags)
    }

    /**
     * 排下一次检查：一次性闹钟，跑完由 ImportJobService 再排明天的。
     * 以前用 setInexactRepeating（不精确循环闹钟），在 Doze / 国产省电策略下
     * 会拖几小时甚至一直不触发——这正是"设定时间到点不执行"的主因。
     * setAndAllowWhileIdle + RTC_WAKEUP 在 Doze 下也能按时叫醒（系统对它的
     * 限流是每应用约 9 分钟一次，对每日任务绰绰有余）。
     */
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
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending(ctx))
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
        // 先验授权再查：授权被收回时 query 抛 SecurityException——那不是程序
        // 错误而是需要用户重新选目录，给一句人话，不再写崩溃横幅吓人
        if (!hasFolderAccess(ctx, uri)) {
            return "目录访问授权已失效（系统重启/清理或权限重置后会被收回）。请到「每日自动任务」重新选择报告目录"
        }
        val files = runCatching { listReports(ctx, uri) }.getOrElse {
            if (it is SecurityException) {
                return "目录访问授权已失效，请重新选择报告目录"
            }
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
