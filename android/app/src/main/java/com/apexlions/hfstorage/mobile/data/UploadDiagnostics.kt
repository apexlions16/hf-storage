package com.apexlions.hfstorage.mobile.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

data class DiagnosticWriteResult(
    val privateFile: File,
    val exportedLocation: String?,
)

object UploadDiagnostics {
    private const val DIRECTORY = "logs"
    private const val FILE_NAME = "mobile-upload-errors.log"
    private const val MAX_LOG_BYTES = 512 * 1024L

    fun logFile(context: Context): File = File(
        File(context.filesDir, DIRECTORY).apply { mkdirs() },
        FILE_NAME,
    )

    fun exists(context: Context): Boolean = logFile(context).let { it.isFile && it.length() > 0L }

    fun write(
        context: Context,
        job: UploadJob?,
        token: String?,
        error: Throwable,
    ): DiagnosticWriteResult? = runCatching {
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
        DiagnosticWriteResult(
            privateFile = output,
            exportedLocation = exportReadableCopy(context, output),
        )
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

    private fun exportReadableCopy(context: Context, source: File): String? = runCatching {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now())
        val displayName = "HFStorage-upload-error-$stamp.log"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/HFStorage")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Downloads kaydı oluşturulamadı.")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Downloads dosyası açılamadı.")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
            "İndirilenler/HFStorage/$displayName"
        } else {
            val directory = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "HFStorage",
            ).apply { mkdirs() }
            val target = File(directory, displayName)
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }
    }.getOrNull()
}
