package com.apexlions.hfstorage.mobile.data

import android.content.ContentResolver
import android.net.Uri
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
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.ceil

class UploadEngine(
    private val resolver: ContentResolver,
    private val api: HfApiClient,
) {
    suspend fun upload(job: UploadJob, progress: (UploadProgress) -> Unit): List<String> = withContext(Dispatchers.IO) {
        if (job.items.isEmpty()) throw HfApiException("Yüklenecek dosya bulunamadı.")
        val repository = Repository(job.repoId, RepoType.fromApi(job.repoType), isPrivate = false)
        val commits = mutableListOf<String>()
        val chunks = planCommitBatches(job.items)
        var completed = 0

        chunks.forEachIndexed { chunkIndex, items ->
            val suffix = if (chunks.size > 1) " (${chunkIndex + 1}/${chunks.size})" else ""
            progress(UploadProgress("Analiz", completed, job.items.size, "${items.size} dosya analiz ediliyor$suffix"))
            val metadata = items.mapIndexed { index, item ->
                progress(UploadProgress("Analiz", completed + index, job.items.size, item.displayName))
                calculateMetadata(item)
            }

            fetchUploadModes(repository, metadata, job.destination)
            val active = metadata.filterNot { it.shouldIgnore }
            val lfs = active.filter { it.uploadMode == "lfs" }
            if (lfs.isNotEmpty()) {
                progress(UploadProgress("LFS", completed, job.items.size, "${lfs.size} büyük dosya hazırlanıyor$suffix"))
                uploadLfs(repository, lfs) { currentName ->
                    progress(UploadProgress("Yükleniyor", completed, job.items.size, currentName))
                }
            }

            progress(UploadProgress("Commit", completed, job.items.size, "Tek batch commit oluşturuluyor$suffix"))
            val commit = createCommit(repository, active, job.destination, job.commitMessage + suffix)
            commits += commit
            completed += items.size
            progress(UploadProgress("Tamamlandı", completed, job.items.size, "$completed/${job.items.size} dosya commit edildi"))
        }
        commits
    }

    private fun calculateMetadata(item: UploadItem): UploadMetadata {
        val digest = MessageDigest.getInstance("SHA-256")
        val sample = ByteArray(512)
        var sampleCount = 0
        var total = 0L
        resolver.openInputStream(Uri.parse(item.uri))?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (sampleCount < sample.size) {
                    val copy = minOf(read, sample.size - sampleCount)
                    buffer.copyInto(sample, sampleCount, 0, copy)
                    sampleCount += copy
                }
                digest.update(buffer, 0, read)
                total += read
            }
        } ?: throw IOException("Dosya açılamadı: ${item.displayName}")
        return UploadMetadata(
            item = item,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            sampleBase64 = Base64.encodeToString(sample.copyOf(sampleCount), Base64.NO_WRAP),
            size = total,
        )
    }

    private fun fetchUploadModes(repository: Repository, files: List<UploadMetadata>, destination: String) {
        files.chunked(256).forEach { chunk ->
            val fileJson = chunk.joinToString(",") {
                "{\"path\":${normalizeRemotePath(destination, it.item.relativePath).jsonQuote()},\"sample\":${it.sampleBase64.jsonQuote()},\"size\":${it.size}}"
            }
            val payload = "{\"files\":[$fileJson]}"
            val url = "${HfApiClient.ENDPOINT}/api/${repository.type.apiSegment}/${repository.id}/preupload/main"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", api.authHeader())
                .header("User-Agent", "hf-storage-android/0.1.0")
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

    private fun uploadLfs(
        repository: Repository,
        files: List<UploadMetadata>,
        onFile: (String) -> Unit,
    ) {
        files.chunked(100).forEach { chunk ->
            val objects = chunk.joinToString(",") { "{\"oid\":${it.sha256.jsonQuote()},\"size\":${it.size}}" }
            val payload = "{\"operation\":\"upload\",\"transfers\":[\"basic\",\"multipart\"],\"objects\":[$objects],\"hash_algo\":\"sha256\",\"ref\":{\"name\":\"main\"}}"
            val prefix = if (repository.type == RepoType.MODEL) "" else "${repository.type.apiSegment}/"
            val url = "${HfApiClient.ENDPOINT}/$prefix${repository.id}.git/info/lfs/objects/batch"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", api.authHeader())
                .header("Accept", "application/vnd.git-lfs+json")
                .header("Content-Type", "application/vnd.git-lfs+json")
                .post(payload.toRequestBody("application/vnd.git-lfs+json".toMediaTypeOrNull()))
                .build()
            val root = api.executeJson(request).jsonObject
            val objectsResponse = root["objects"]?.jsonArray ?: JsonArray(emptyList())
            val byOid = chunk.associateBy { it.sha256 }
            objectsResponse.forEach { element ->
                val obj = element.jsonObject
                obj["error"]?.let { throw HfApiException("LFS: ${it.jsonObject.string("message") ?: it}") }
                val oid = obj.string("oid") ?: return@forEach
                val metadata = byOid[oid] ?: return@forEach
                onFile(metadata.item.displayName)
                val actions = obj["actions"]?.jsonObject ?: return@forEach
                val upload = actions["upload"]?.jsonObject ?: return@forEach
                uploadLfsObject(metadata, upload)
                actions["verify"]?.jsonObject?.let { verifyLfs(metadata, it) }
            }
        }
    }

    private fun uploadLfsObject(file: UploadMetadata, action: JsonObject) {
        val href = action.string("href") ?: throw HfApiException("LFS upload URL eksik.")
        val header = action["header"]?.jsonObject ?: JsonObject(emptyMap())
        val chunkSize = header["chunk_size"]?.jsonPrimitive?.longOrNull
        if (chunkSize == null) {
            val body = UriRequestBody(resolver, Uri.parse(file.item.uri), file.size)
            executePutWithRetry(href, body).close()
            return
        }

        val partUrls = header.entries.mapNotNull { (key, value) ->
            key.toIntOrNull()?.let { it to value.jsonPrimitive.content }
        }.sortedBy { it.first }.map { it.second }
        val expected = ceil(file.size.toDouble() / chunkSize.toDouble()).toInt()
        if (partUrls.size != expected) throw HfApiException("LFS multipart yanıtı geçersiz: $expected parça bekleniyordu.")
        val etags = partUrls.mapIndexed { index, partUrl ->
            val offset = index * chunkSize
            val length = minOf(chunkSize, file.size - offset)
            val response = executePutWithRetry(
                partUrl,
                UriSliceRequestBody(resolver, Uri.parse(file.item.uri), offset, length),
            )
            val etag = response.header("ETag")
            response.close()
            etag ?: throw HfApiException("LFS parçası ETag döndürmedi.")
        }
        val parts = etags.mapIndexed { index, etag ->
            "{\"partNumber\":${index + 1},\"etag\":${etag.jsonQuote()}}"
        }.joinToString(",")
        val completion = "{\"oid\":${file.sha256.jsonQuote()},\"parts\":[$parts]}"
        val request = Request.Builder().url(href)
            .header("Accept", "application/vnd.git-lfs+json")
            .header("Content-Type", "application/vnd.git-lfs+json")
            .post(completion.toRequestBody("application/vnd.git-lfs+json".toMediaTypeOrNull()))
            .build()
        api.executeRaw(request).close()
    }

    private fun executePutWithRetry(url: String, body: RequestBody): okhttp3.Response {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val response = api.client.newCall(Request.Builder().url(url).put(body).build()).execute()
                if (response.isSuccessful) return response
                val message = response.body?.string().orEmpty()
                response.close()
                if (response.code !in 500..599) throw HfApiException("Dosya aktarımı başarısız (${response.code}): $message", response.code)
                last = HfApiException("Geçici aktarım hatası (${response.code})")
            } catch (error: Throwable) {
                last = error
                if (attempt == 2) throw error
                Thread.sleep((attempt + 1) * 1000L)
            }
        }
        throw last ?: HfApiException("Dosya aktarılamadı.")
    }

    private fun verifyLfs(file: UploadMetadata, action: JsonObject) {
        val href = action.string("href") ?: return
        val payload = "{\"oid\":${file.sha256.jsonQuote()},\"size\":${file.size}}"
        val request = Request.Builder().url(href)
            .header("Authorization", api.authHeader())
            .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        api.executeRaw(request).close()
    }

    private fun createCommit(
        repository: Repository,
        files: List<UploadMetadata>,
        destination: String,
        message: String,
    ): String {
        val lines = mutableListOf(
            "{\"key\":\"header\",\"value\":{\"summary\":${message.jsonQuote()},\"description\":\"\"}}",
        )
        files.forEach { file ->
            val path = normalizeRemotePath(destination, file.item.relativePath)
            if (file.uploadMode == "lfs") {
                lines += "{\"key\":\"lfsFile\",\"value\":{\"path\":${path.jsonQuote()},\"algo\":\"sha256\",\"oid\":${file.sha256.jsonQuote()},\"size\":${file.size}}}"
            } else {
                val bytes = resolver.openInputStream(Uri.parse(file.item.uri))?.use { it.readBytes() }
                    ?: throw IOException("Dosya açılamadı: ${file.item.displayName}")
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                lines += "{\"key\":\"file\",\"value\":{\"content\":${base64.jsonQuote()},\"path\":${path.jsonQuote()},\"encoding\":\"base64\"}}"
            }
        }
        val result = api.postCommitBlocking(repository, lines.joinToString("\n", postfix = "\n"))
        return result.string("commitUrl") ?: result.string("commitOid") ?: repository.id
    }

}

private fun HfApiClient.postCommitBlocking(repository: Repository, ndjson: String): JsonObject {
    val url = "${HfApiClient.ENDPOINT}/api/${repository.type.apiSegment}/${repository.id}/commit/main"
    val request = Request.Builder().url(url)
        .header("Authorization", authHeader())
        .header("User-Agent", "hf-storage-android/0.1.0")
        .header("Content-Type", "application/x-ndjson")
        .post(ndjson.toRequestBody("application/x-ndjson".toMediaTypeOrNull()))
        .build()
    return executeJson(request).jsonObject
}

private class UriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val length: Long,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
    override fun contentLength(): Long = length
    override fun writeTo(sink: BufferedSink) {
        resolver.openInputStream(uri)?.use { input -> sink.writeAll(input.source()) }
            ?: throw IOException("Dosya açılamadı: $uri")
    }
}

private class UriSliceRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val offset: Long,
    private val length: Long,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
    override fun contentLength(): Long = length
    override fun writeTo(sink: BufferedSink) {
        resolver.openInputStream(uri)?.use { input ->
            var remainingSkip = offset
            while (remainingSkip > 0) {
                val skipped = input.skip(remainingSkip)
                if (skipped <= 0) {
                    if (input.read() == -1) throw IOException("Dosya parçasına erişilemedi.")
                    remainingSkip--
                } else remainingSkip -= skipped
            }
            var remaining = length
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Dosya beklenenden kısa.")
                sink.write(buffer, 0, read)
                remaining -= read
            }
        } ?: throw IOException("Dosya açılamadı: $uri")
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
