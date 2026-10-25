package com.xiaolei.battery

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** 用 content:// 方式分享 CSV，避开 Android 7+ 的 file:// 暴露限制 */
class CsvProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val name = uri.lastPathSegment ?: "export.csv"
        val f = File(context!!.cacheDir, name)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols, 1)
        val row = ArrayList<Any?>(cols.size)
        for (col in cols) {
            row.add(
                when (col) {
                    OpenableColumns.DISPLAY_NAME -> name
                    OpenableColumns.SIZE -> f.length()
                    "_data" -> f.absolutePath
                    else -> null
                }
            )
        }
        c.addRow(row)
        return c
    }

    override fun getType(uri: Uri): String = "text/csv"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = File(context!!.cacheDir, uri.lastPathSegment ?: "export.csv")
        if (!f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
