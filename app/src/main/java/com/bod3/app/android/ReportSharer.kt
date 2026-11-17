package com.bod3.app.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Writes diagnostic reports to the cache and hands them to the share sheet through a FileProvider. */
object ReportSharer {

    private const val DIRECTORY = "reports"
    private const val KEEP_FILES = 10

    fun write(context: Context, fileName: String, text: String): File {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        // Keep only the newest few reports.
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES - 1)?.forEach { it.delete() }
        return File(directory, fileName).apply { writeText(text) }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "bod3 diagnostic report")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share diagnostic report"))
    }
}
