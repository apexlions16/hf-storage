package com.apexlions.hfstorage.mobile.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class UploadQueue(private val context: Context) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val directory = File(context.filesDir, "upload-jobs").apply { mkdirs() }

    fun save(job: UploadJob) {
        File(directory, "${job.id}.json").writeText(json.encodeToString(job))
    }

    fun load(id: String): UploadJob {
        val file = File(directory, "$id.json")
        if (!file.exists()) error("Yükleme işi bulunamadı: $id")
        return json.decodeFromString(file.readText())
    }

    fun delete(id: String) {
        File(directory, "$id.json").delete()
    }
}
