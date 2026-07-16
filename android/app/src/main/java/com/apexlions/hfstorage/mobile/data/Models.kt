package com.apexlions.hfstorage.mobile.data

import android.net.Uri
import kotlinx.serialization.Serializable

enum class RepoType(val apiSegment: String, val label: String) {
    MODEL("models", "Model"),
    DATASET("datasets", "Dataset"),
    SPACE("spaces", "Space");

    companion object {
        fun fromApi(value: String?): RepoType = when (value?.lowercase()) {
            "dataset", "datasets" -> DATASET
            "space", "spaces" -> SPACE
            else -> MODEL
        }
    }
}

data class Account(
    val username: String,
    val fullname: String = "",
    val avatarUrl: String = "",
    val tokenRole: String = "unknown",
)

data class Repository(
    val id: String,
    val type: RepoType,
    val isPrivate: Boolean,
    val updatedAt: String = "",
    val usedStorage: Long = 0,
)

data class RepoFile(
    val path: String,
    val size: Long = 0,
    val isDirectory: Boolean = false,
    val backend: String = "Git",
    val oid: String = "",
)

@Serializable
data class UploadItem(
    val uri: String,
    val relativePath: String,
    val displayName: String,
    val size: Long,
)

@Serializable
data class UploadJob(
    val id: String,
    val repoId: String,
    val repoType: String,
    val destination: String,
    val commitMessage: String,
    val items: List<UploadItem>,
)

data class UploadProgress(
    val stage: String = "Hazırlanıyor",
    val current: Int = 0,
    val total: Int = 0,
    val message: String = "",
)

data class UploadMetadata(
    val item: UploadItem,
    var sha256: String = "",
    val sampleBase64: String,
    val size: Long,
    var xetHash: String = "",
    var uploadMode: String = "regular",
    var shouldIgnore: Boolean = false,
    var remoteOid: String? = null,
)

fun Uri.persistableString(): String = toString()

fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble().coerceAtLeast(0.0)
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000.0
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else "%.2f %s".format(value, units[unit])
}
