package com.apexlions.hfstorage.mobile.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Android upload pipeline.
 *
 * Every non-empty file is first ingested by the official hf-xet Rust client.
 * There is deliberately no legacy Git-LFS upload fallback. The Hub preupload
 * response still decides whether the final Git commit entry is a regular blob
 * or an lfsFile pointer, exactly like huggingface_hub does on desktop.
 */
class UploadEngine(
    private val context: Context,
    private val api: HfApiClient,
    private val hubToken: String,
) {
    private val resolver = context.contentResolver

    suspend fun upload(job: UploadJob, progress: (UploadProgress) -> Unit): List<String> = withContext(Dispatchers.IO) {
        if (job.items.isEmpty()) throw HfApiException("Yüklenecek dosya bulunamadı.")
        if (!XetNative.isAvailable) {
            throw HfApiException(
                "Xet zorunlu fakat native Xet bileşeni yüklenemedi: ${XetNative.loadError ?: "bilinmeyen hata"}",
            )
        }

        val repository = Repository(job.repoId, RepoType.fromApi(job.repoType), isPrivate = false)
        val commits = mutableListOf<String>()
        val chunks = planCommitBatches(job.items)
        var completed = 0

        chunks.forEachIndexed { chunkIndex, items ->
            val suffix = if (chunks.size > 1) " (${chunkIndex + 1}/${chunks.size})" else ""
            progress(UploadProgress("Analiz", completed, job.items.size, "${items.size} dosya analiz ediliyor$suffix"))
            val metadata = items.mapIndexed { index, item ->
                progress(UploadProgress("Analiz", completed + index, job.items.size, item.displayName))
                calculatePreuploadMetadata(item)
            }

            fetchUploadModes(repository, metadata, job.destination)
            val active = metadata.filterNot { it.shouldIgnore }
            val xetFiles = active.filter { it.size > 0L }

            if (xetFiles.isNotEmpty()) {
                progress(
                    UploadProgress(
                        "Xet",
                        completed,
                        job.items.size,
                        "${xetFiles.size} dosya Xet ile parçalanıyor ve yinelenen bloklar ayıklanıyor$suffix",
                    ),
                )
                NativeSourceGroup.open(context, xetFiles, "${job.id}-$chunkIndex") { current, total, name ->
                    progress(UploadProgress("Xet hazırlığı", completed + current, job.items.size, "$name ($current/$total)"))
                }.use { sources ->
                    val results = XetNative.uploadFiles(
                        context = context,
                        refreshUrl = xetWriteTokenUrl(repository),
                        hubToken = hubToken,
                        filePaths = sources.paths,
                    )
                    if (results.size != xetFiles.size) {
                        throw HfApiException(
                            "Xet ${xetFiles.size} dosya için ${results.size} sonuç döndürdü; commit güvenlik nedeniyle oluşturulmadı.",
                        )
                    }
                    xetFiles.zip(results).forEach { (file, result) ->
                        if (result.sha256.isBlank() || result.xetHash.isBlank()) {
                            throw HfApiException("Xet eksik hash döndürdü: ${file.item.displayName}")
                        }
                        if (result.fileSize != file.size) {
                            throw HfApiException(
                                "Dosya Xet işlemi sırasında değişti: ${file.item.displayName} " +
                                    "(${file.size} bayt bekleniyordu, ${result.fileSize} bayt okundu).",
                            )
                        }
                        file.sha256 = result.sha256
                        file.xetHash = result.xetHash
                    }
                }
            }

            // Empty files cannot be represented by Xet/LFS and are required by
            // the Hub to stay regular Git blobs. They contain no transferable data.
            active.filter { it.size == 0L }.forEach { it.uploadMode = "regular" }

            if (active.isNotEmpty()) {
                progress(UploadProgress("Commit", completed, job.items.size, "Tek batch commit oluşturuluyor$suffix"))
                val commit = createCommit(repository, active, job.destination, job.commitMessage + suffix)
                commits += commit
            }

            completed += items.size
            progress(UploadProgress("Tamamlandı", completed, job.items.size, "$completed/${job.items.size} dosya işlendi"))
        }
        commits
    }

    private fun calculatePreuploadMetadata(item: UploadItem): UploadMetadata {
        val uri = Uri.parse(item.uri)
        val sample = ByteArray(512)
        val sampleCount = resolver.openInputStream(uri)?.use { input ->
            var offset = 0
            while (offset < sample.size) {
                val read = input.read(sample, offset, sample.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        } ?: throw IOException("Dosya açılamadı: ${item.displayName}")

        val size = determineSize(uri, item)
        return UploadMetadata(
            item = item,
            sampleBase64 = Base64.encodeToString(sample.copyOf(sampleCount), Base64.NO_WRAP),
            size = size,
        )
    }

    private fun determineSize(uri: Uri, item: UploadItem): Long {
        if (item.size > 0L) return item.size
        runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize >= 0L) return descriptor.statSize
            }
        }
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
        } ?: throw IOException("Dosya boyutu alınamadı: ${item.displayName}")
        return total
    }

    private fun fetchUploadModes(repository: Repository, files: List<UploadMetadata>, destination: String) {
        files.chunked(256).forEach { chunk ->
            val fileJson = chunk.joinToString(",") {
                "{\"path\":${normalizeRemotePath(destination, it.item.relativePath).jsonQuote()}," +
                    "\"sample\":${it.sampleBase64.jsonQuote()},\"size\":${it.size}}"
            }
            val payload = "{\"files\":[$fileJson]}"
            val url = "${HfApiClient.ENDPOINT}/api/${repository.type.apiSegment}/${repository.id}/preupload/main"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", api.authHeader())
                .header("User-Agent", "hf-storage-android/0.1.1")
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            val response = api.executeJson(request).jsonObject
            val responseFiles = response["files"]?.jsonArray ?: JsonArray(emptyList())
            val byPath = responseFiles.associateBy { it.jsonObject.string("path").orEmpty() }
            chunk.forEach { metadata ->
                val finalPath = normalizeRemotePath(destination, metadata.item.relativePath)
                val info = byPath[finalPath]?.jsonObject
                    ?: throw HfApiException("Hugging Face yükleme modu döndürmedi: ${metadata.item.relativePath}")
                metadata.uploadMode = info.string("uploadMode") ?: "regular"
                metadata.shouldIgnore = info["shouldIgnore"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                metadata.remoteOid = info.string("oid")
                if (metadata.size == 0L) metadata.uploadMode = "regular"
            }
        }
    }

    private fun xetWriteTokenUrl(repository: Repository): String =
        "${HfApiClient.ENDPOINT}/api/${repository.type.apiSegment}/${repository.id}/xet-write-token/main"

    private fun createCommit(
        repository: Repository,
        files: List<UploadMetadata>,
        destination: String,
        message: String,
    ): String {
        val safeMessage = message.trim().ifBlank { "Batch upload with HF Storage Android" }
        val lines = mutableListOf(
            "{\"key\":\"header\",\"value\":{\"summary\":${safeMessage.jsonQuote()},\"description\":\"\"}}",
        )
        files.forEach { file ->
            val path = normalizeRemotePath(destination, file.item.relativePath)
            if (file.uploadMode == "lfs") {
                if (file.sha256.isBlank()) throw HfApiException("Xet SHA-256 eksik: ${file.item.displayName}")
                lines += "{\"key\":\"lfsFile\",\"value\":{\"path\":${path.jsonQuote()}," +
                    "\"algo\":\"sha256\",\"oid\":${file.sha256.jsonQuote()},\"size\":${file.size}}}"
            } else {
                val bytes = resolver.openInputStream(Uri.parse(file.item.uri))?.use { it.readBytes() }
                    ?: throw IOException("Dosya açılamadı: ${file.item.displayName}")
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                lines += "{\"key\":\"file\",\"value\":{\"content\":${base64.jsonQuote()}," +
                    "\"path\":${path.jsonQuote()},\"encoding\":\"base64\"}}"
            }
        }
        val result = api.postCommitBlocking(repository, lines.joinToString("\n", postfix = "\n"))
        return result.string("commitUrl") ?: result.string("commitOid") ?: repository.id
    }
}

/**
 * Keeps seekable document descriptors open while Rust reads /proc/self/fd/N.
 * Providers that only expose a non-seekable pipe are copied to the app-private
 * cache first; Xet remains the uploader in both cases.
 */
private class NativeSourceGroup(
    val paths: List<String>,
    private val descriptors: List<ParcelFileDescriptor>,
    private val temporaryRoot: File?,
) : Closeable {
    override fun close() {
        descriptors.forEach { runCatching { it.close() } }
        temporaryRoot?.deleteRecursively()
    }

    companion object {
        fun open(
            context: Context,
            files: List<UploadMetadata>,
            jobPartId: String,
            onPreparing: (current: Int, total: Int, name: String) -> Unit,
        ): NativeSourceGroup {
            val resolver = context.contentResolver
            val descriptors = mutableListOf<ParcelFileDescriptor>()
            val paths = mutableListOf<String>()
            var temporaryRoot: File? = null

            try {
                files.forEachIndexed { index, metadata ->
                    onPreparing(index + 1, files.size, metadata.item.displayName)
                    val uri = Uri.parse(metadata.item.uri)
                    val descriptor = openSeekableDescriptor(resolver, uri, metadata.size)
                    if (descriptor != null) {
                        descriptors += descriptor
                        paths += "/proc/self/fd/${descriptor.fd}"
                    } else {
                        val root = temporaryRoot ?: File(context.cacheDir, "hf-storage-staging/$jobPartId").also {
                            it.deleteRecursively()
                            if (!it.mkdirs() && !it.isDirectory) {
                                throw IOException("Xet geçici klasörü oluşturulamadı: ${it.absolutePath}")
                            }
                            temporaryRoot = it
                        }
                        val safeName = metadata.item.displayName
                            .replace(Regex("[^A-Za-z0-9._-]"), "_")
                            .takeLast(120)
                            .ifBlank { "file" }
                        val target = File(root, "${index.toString().padStart(4, '0')}-$safeName")
                        val part = File(root, target.name + ".part")
                        resolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(part).use { output -> input.copyTo(output, 1024 * 1024) }
                        } ?: throw IOException("Dosya Xet için açılamadı: ${metadata.item.displayName}")
                        if (part.length() != metadata.size) {
                            throw IOException(
                                "Xet geçici kopyası eksik: ${metadata.item.displayName} " +
                                    "(${metadata.size} yerine ${part.length()} bayt).",
                            )
                        }
                        if (!part.renameTo(target)) {
                            part.copyTo(target, overwrite = true)
                            part.delete()
                        }
                        paths += target.absolutePath
                    }
                }
                return NativeSourceGroup(paths, descriptors, temporaryRoot)
            } catch (error: Throwable) {
                descriptors.forEach { runCatching { it.close() } }
                temporaryRoot?.deleteRecursively()
                throw error
            }
        }

        private fun openSeekableDescriptor(
            resolver: android.content.ContentResolver,
            uri: Uri,
            expectedSize: Long,
        ): ParcelFileDescriptor? {
            val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
            return try {
                val size = descriptor.statSize
                if (size < 0L || size != expectedSize) {
                    descriptor.close()
                    null
                } else {
                    Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
                    descriptor
                }
            } catch (_: Throwable) {
                runCatching { descriptor.close() }
                null
            }
        }
    }
}

private fun HfApiClient.postCommitBlocking(repository: Repository, ndjson: String): JsonObject {
    val url = "${HfApiClient.ENDPOINT}/api/${repository.type.apiSegment}/${repository.id}/commit/main"
    val request = Request.Builder().url(url)
        .header("Authorization", authHeader())
        .header("User-Agent", "hf-storage-android/0.1.1")
        .header("Content-Type", "application/x-ndjson")
        .post(ndjson.toRequestBody("application/x-ndjson".toMediaTypeOrNull()))
        .build()
    return executeJson(request).jsonObject
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
