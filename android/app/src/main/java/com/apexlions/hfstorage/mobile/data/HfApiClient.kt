package com.apexlions.hfstorage.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class HfApiException(message: String, val statusCode: Int? = null) : RuntimeException(message)

class HfApiClient(
    private val token: String,
    val client: OkHttpClient = defaultClient(),
) {
    companion object {
        const val ENDPOINT = "https://huggingface.co"
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun encodePath(value: String): String = value.split("/")
            .joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
    }

    private fun requestBuilder(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("User-Agent", "hf-storage-android/0.1.0")
        .header("Accept", "application/json")

    suspend fun authenticate(): Account = withContext(Dispatchers.IO) {
        val root = executeJson(requestBuilder("$ENDPOINT/api/whoami-v2").get().build()).jsonObject
        val auth = root["auth"]?.jsonObject
        val access = auth?.get("accessToken")?.jsonObject
        val role = access?.string("role") ?: access?.string("type") ?: root.string("role") ?: "unknown"
        val username = root.string("name") ?: root.string("user")
            ?: throw HfApiException("Token doğrulandı fakat kullanıcı adı alınamadı.")
        if (role.lowercase() in setOf("read", "readonly", "read-only")) {
            throw HfApiException("Bu token yalnızca okuma yetkili. Read + write token kullanın.", 403)
        }
        Account(
            username = username,
            fullname = root.string("fullname").orEmpty(),
            avatarUrl = root.string("avatarUrl") ?: root.string("avatar_url").orEmpty(),
            tokenRole = role,
        )
    }

    suspend fun listRepositories(username: String): List<Repository> = coroutineScope {
        RepoType.entries.map { type ->
            async(Dispatchers.IO) {
                val author = URLEncoder.encode(username, StandardCharsets.UTF_8)
                val url = "$ENDPOINT/api/${type.apiSegment}?author=$author&limit=100&full=true&sort=lastModified&direction=-1"
                val element = executeJson(requestBuilder(url).get().build())
                val array = when (element) {
                    is JsonArray -> element
                    is JsonObject -> element["items"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                array.mapNotNull { repoElement -> parseRepository(repoElement, type) }
            }
        }.awaitAll().flatten().sortedByDescending { it.updatedAt }
    }

    private fun parseRepository(element: JsonElement, type: RepoType): Repository? {
        val obj = element.jsonObject
        val id = obj.string("id") ?: obj.string("modelId") ?: return null
        val used = obj.long("usedStorage")
            ?: obj.long("storage")
            ?: obj["safetensors"]?.jsonObject?.long("total")
            ?: 0L
        return Repository(
            id = id,
            type = type,
            isPrivate = obj.bool("private") ?: obj.string("visibility")?.equals("private", true) ?: false,
            updatedAt = obj.string("lastModified") ?: obj.string("updatedAt").orEmpty(),
            usedStorage = used,
        )
    }

    suspend fun listFiles(repository: Repository): List<RepoFile> = withContext(Dispatchers.IO) {
        val url = "$ENDPOINT/api/${repository.type.apiSegment}/${repository.id}/tree/main?recursive=true&expand=true"
        val element = executeJson(requestBuilder(url).get().build())
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["items"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        array.mapNotNull { item ->
            val obj = item.jsonObject
            val path = obj.string("path") ?: return@mapNotNull null
            val lfs = obj["lfs"]?.jsonObject
            val xetHash = obj.string("xetHash") ?: obj.string("xet_hash")
            RepoFile(
                path = path,
                size = obj.long("size") ?: 0,
                isDirectory = obj.string("type") in setOf("directory", "folder", "tree"),
                backend = when {
                    obj.string("type") in setOf("directory", "folder", "tree") -> "Klasör"
                    !xetHash.isNullOrBlank() -> "Xet"
                    lfs != null -> "LFS"
                    else -> "Git"
                },
                oid = xetHash ?: lfs?.string("sha256") ?: obj.string("oid").orEmpty(),
            )
        }.sortedWith(compareByDescending<RepoFile> { it.isDirectory }.thenBy { it.path.lowercase() })
    }

    suspend fun createRepository(name: String, type: RepoType, isPrivate: Boolean): String = withContext(Dispatchers.IO) {
        val split = name.trim().trim('/').split('/', limit = 2)
        val organization = split.takeIf { it.size == 2 }?.first()
        val repoName = split.last()
        val payload = buildString {
            append("{\"name\":${repoName.jsonQuote()}")
            append(",\"organization\":${organization?.jsonQuote() ?: "null"}")
            append(",\"visibility\":${(if (isPrivate) "private" else "public").jsonQuote()}")
            append(",\"type\":${type.name.lowercase().jsonQuote()}")
            if (type == RepoType.SPACE) append(",\"sdk\":\"docker\"")
            append("}")
        }
        val request = requestBuilder("$ENDPOINT/api/repos/create")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        executeJson(request).jsonObject.string("url") ?: name
    }

    suspend fun deletePaths(repository: Repository, paths: List<String>, permanent: Boolean): Int = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext 0
        val lines = mutableListOf(
            "{\"key\":\"header\",\"value\":{\"summary\":\"Delete files with HF Storage Android\",\"description\":\"\"}}",
        )
        paths.forEach { path ->
            val file = path.jsonQuote()
            lines += "{\"key\":\"deletedFile\",\"value\":{\"path\":$file}}"
        }
        postCommit(repository, lines.joinToString("\n", postfix = "\n"))
        if (permanent) {
            val lfs = listLfsFiles(repository)
            val shas = lfs.filter { it.first in paths }.map { it.second }
            if (shas.isNotEmpty()) purgeLfs(repository, shas)
            shas.size
        } else 0
    }

    suspend fun postCommit(repository: Repository, ndjson: String): JsonObject = withContext(Dispatchers.IO) {
        val url = "$ENDPOINT/api/${repository.type.apiSegment}/${repository.id}/commit/main"
        val request = requestBuilder(url)
            .header("Content-Type", "application/x-ndjson")
            .post(ndjson.toRequestBody("application/x-ndjson".toMediaType()))
            .build()
        executeJson(request).jsonObject
    }

    suspend fun listLfsFiles(repository: Repository): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val url = "$ENDPOINT/api/${repository.type.apiSegment}/${repository.id}/lfs-files"
        val element = executeJson(requestBuilder(url).get().build())
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["items"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        array.mapNotNull {
            val obj = it.jsonObject
            val name = obj.string("filename") ?: return@mapNotNull null
            val oid = obj.string("fileOid") ?: obj.string("file_oid") ?: obj.string("oid") ?: return@mapNotNull null
            name to oid
        }
    }

    private fun purgeLfs(repository: Repository, shas: List<String>) {
        val shaJson = shas.joinToString(",") { it.jsonQuote() }
        val payload = "{\"deletions\":{\"sha\":[$shaJson],\"rewriteHistory\":true}}"
        val request = requestBuilder("$ENDPOINT/api/${repository.type.apiSegment}/${repository.id}/lfs-files/batch")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        executeRaw(request).close()
    }

    fun downloadUrl(repository: Repository, path: String): String =
        "$ENDPOINT/${if (repository.type == RepoType.MODEL) "" else repository.type.apiSegment + "/"}${repository.id}/resolve/main/${encodePath(path)}?download=true"

    fun authHeader(): String = "Bearer $token"

    internal fun executeJson(request: Request): JsonElement = executeRaw(request).use { response ->
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body)
    }

    internal fun executeRaw(request: Request): okhttp3.Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val detail = response.body?.string().orEmpty().take(1200)
            response.close()
            throw HfApiException(friendlyError(response.code, detail), response.code)
        }
        return response
    }

    private fun friendlyError(code: Int, detail: String): String = when (code) {
        401 -> "Token geçersiz veya süresi dolmuş."
        403 -> "Bu işlem için yazma yetkiniz yok. Token ve depo izinlerini kontrol edin."
        404 -> "Depo, dal veya dosya bulunamadı."
        409 -> "Depoda çakışan bir işlem var. Yenileyip tekrar deneyin."
        413 -> "İstek çok büyük. Dosyalar daha küçük batch'lere ayrılmalı."
        429 -> "Hugging Face işlem sınırına ulaşıldı. Bir süre sonra yeniden deneyin."
        in 500..599 -> "Hugging Face sunucusu geçici hata döndürdü ($code)."
        else -> "Hugging Face isteği başarısız ($code): ${detail.ifBlank { "Ayrıntı yok" }}"
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
internal fun String.jsonQuote(): String = JsonPrimitive(this).toString()
