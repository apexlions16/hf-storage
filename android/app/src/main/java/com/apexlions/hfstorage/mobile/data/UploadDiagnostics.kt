package com.apexlions.hfstorage.mobile.data

import android.content.Context
import java.io.File
import java.time.OffsetDateTime

object UploadDiagnostics {
    fun write(context: Context, job: UploadJob?, token: String?, error: Throwable): File? = runCatching {
        val directory = File(context.filesDir, "logs").apply { mkdirs() }
        val output = File(directory, "mobile-upload-errors.log")
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
}
