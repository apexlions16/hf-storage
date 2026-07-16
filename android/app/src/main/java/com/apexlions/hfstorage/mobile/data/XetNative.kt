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

    @Volatile
    private var verifierInitialization: Result<Unit>? = null

    /**
     * Initializes rustls-platform-verifier with the Android application Context.
     * This must happen before the first Xet HTTPS request. The result is cached
     * for the complete process lifetime because the Rust verifier is one-time.
     */
    @Synchronized
    fun initialize(context: Context): Result<Unit> {
        verifierInitialization?.let { return it }
        val result = runCatching {
            loadResult.getOrThrow()
            nativeInitialize(context.applicationContext)
        }
        verifierInitialization = result
        return result
    }

    val isAvailable: Boolean
        get() = loadResult.isSuccess && verifierInitialization?.isSuccess == true

    val loadError: String?
        get() = loadResult.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
            ?: verifierInitialization?.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }

    fun uploadFiles(
        context: Context,
        refreshUrl: String,
        hubToken: String,
        filePaths: List<String>,
    ): List<XetNativeUploadResult> {
        initialize(context).getOrElse { error ->
            throw HfApiException(
                "Xet TLS doğrulayıcısı başlatılamadı: " +
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
    private external fun nativeInitialize(context: Context)

    @JvmStatic
    private external fun nativeUploadFiles(
        refreshUrl: String,
        hubToken: String,
        cacheDir: String,
        filePaths: Array<String>,
    ): String
}
