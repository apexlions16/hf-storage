package com.apexlions.hfstorage.mobile.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.OffsetDateTime

object UploadDiagnostics {
    private const val DIRECTORY = "logs"
    private const val FILE_NAME = "mobile-upload-errors.log"
    private const val MAX_LOG_BYTES = 512 * 1024L

    fun logFile(context: Context): File = File(
        File(context.filesDir, DIRECTORY).apply { mkdirs() },
        FILE_NAME,
    )

    fun exists(context: Context): Boolean = logFile(context).let { it.isFile && it.length() > 0L }

    fun write(context: Context, job: UploadJob?, token: String?, error: Throwable): File? = runCatching {
        val output = logFile(context)
        if (output.exists() && output.length() > MAX_LOG_BYTES) {
            output.writeText("HF Storage Android tanı günlüğü döndürüldü.\n", Charsets.UTF_8)
        }
        val trace = error.stackTraceToString().let { raw ->
            if (token.isNullOrBlank()) raw else raw.replace(token, "<REDACTED_TOKEN>")
        }
        output.appendText(
            buildString {
                appendLine()
                appendLine("[${OffsetDateTime.now()}]")
                appendLine("job=${job?.id ?: "unknown"}")
                appendLine("repo=${job?.repoId ?: "unknown"}")
                appendLine("type=${job?.repoType ?: "unknown"}")
                appendLine("files=${job?.items?.size ?: 0}")
                appendLine("xetAvailable=${XetNative.isAvailable}")
                appendLine("xetLoadError=${XetNative.loadError ?: "none"}")
                appendLine(trace)
            },
            Charsets.UTF_8,
        )
        output
    }.getOrNull()

    /**
     * Opens Android's share sheet with a temporary read-only content:// URI.
     * The log remains inside the app-private directory and no broad storage
     * permission is requested.
     */
    fun share(context: Context): Boolean = runCatching {
        val file = logFile(context)
        check(file.isFile && file.length() > 0L) { "Paylaşılacak tanı günlüğü bulunamadı." }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "HF Storage Android tanı günlüğü")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, "Tanı günlüğünü paylaş").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }.getOrDefault(false)
}
