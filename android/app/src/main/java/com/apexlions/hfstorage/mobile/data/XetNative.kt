package com.apexlions.hfstorage.mobile.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class XetNativeUploadResult(
    val sourcePath: String,
    val xetHash: String,
    val sha256: String,
    val fileSize: Long,
)

object XetNative {
    private val json = Json { ignoreUnknownKeys = true }
    private val loadResult: Result<Unit> = runCatching {
        System.loadLibrary("hfstorage_xet")
    }

    val isAvailable: Boolean
        get() = loadResult.isSuccess

    val loadError: String?
        get() = loadResult.exceptionOrNull()?.let { error ->
            error.message ?: error::class.java.simpleName
        }

    fun uploadFiles(
        context: Context,
        refreshUrl: String,
        hubToken: String,
        filePaths: List<String>,
    ): List<XetNativeUploadResult> {
        loadResult.getOrElse { error ->
            throw HfApiException(
                "Xet zorunlu fakat telefonun işlemcisi için native Xet bileşeni yüklenemedi: " +
                    (error.message ?: error::class.java.simpleName),
            )
        }
        if (filePaths.isEmpty()) return emptyList()

        val root = File(context.cacheDir, "hf-storage-native").apply { mkdirs() }
        File(root, "xet").mkdirs()
        File(root, "tmp").mkdirs()
        val response = nativeUploadFiles(
            refreshUrl = refreshUrl,
            hubToken = hubToken,
            cacheDir = root.absolutePath,
            filePaths = filePaths.toTypedArray(),
        )
        return json.decodeFromString(response)
    }

    @JvmStatic
    private external fun nativeUploadFiles(
        refreshUrl: String,
        hubToken: String,
        cacheDir: String,
        filePaths: Array<String>,
    ): String
}
