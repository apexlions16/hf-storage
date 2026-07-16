package com.apexlions.hfstorage.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.nio.charset.StandardCharsets

private val strictCommitJson = Json {
    isLenient = false
    ignoreUnknownKeys = false
    explicitNulls = false
}

private const val DEFAULT_COMMIT_SUMMARY = "Batch upload with HF Storage Android"
private val NDJSON_MEDIA_TYPE = "application/x-ndjson".toMediaType()

/**
 * Rebuilds the first NDJSON record exactly like huggingface_hub's official
 * `_prepare_commit_payload`: {"key":"header","value":{"summary":...,"description":...}}.
 *
 * The remaining operation records are preserved byte-for-byte (apart from the
 * final newline), so large base64 records are not decoded or duplicated as JSON
 * trees. This also strips a possible UTF-8 BOM and guarantees that the first
 * record always contains a non-empty string summary.
 */
internal fun canonicalizeCommitNdjson(
    raw: String,
    fallbackSummary: String = DEFAULT_COMMIT_SUMMARY,
): String {
    val withoutBom = raw.removePrefix("\uFEFF").trimStart('\r', '\n')
    val firstNewline = withoutBom.indexOf('\n')
    val firstLine = (if (firstNewline >= 0) withoutBom.substring(0, firstNewline) else withoutBom)
        .trimEnd('\r')
    val remainder = if (firstNewline >= 0) withoutBom.substring(firstNewline + 1) else ""

    val parsedHeader = runCatching {
        strictCommitJson.parseToJsonElement(firstLine).jsonObject
    }.getOrNull()?.takeIf { objectValue ->
        objectValue["key"]?.jsonPrimitive?.contentOrNull == "header"
    }

    val originalValue = parsedHeader?.get("value") as? JsonObject
    val originalSummary = (originalValue?.get("summary") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.trim()
    val summary = originalSummary
        .takeUnless { it.isNullOrBlank() }
        ?: fallbackSummary.trim().ifBlank { DEFAULT_COMMIT_SUMMARY }
    val description = (originalValue?.get("description") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        .orEmpty()
    val parentCommit = (originalValue?.get("parentCommit") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

    val header = buildJsonObject {
        put("key", "header")
        put(
            "value",
            buildJsonObject {
                put("summary", summary)
                put("description", description)
                parentCommit?.let { put("parentCommit", it) }
            },
        )
    }.toString()

    val operations = if (parsedHeader != null) remainder else withoutBom
    val cleanedOperations = operations.trim('\r', '\n')
    return if (cleanedOperations.isBlank()) {
        "$header\n"
    } else {
        "$header\n$cleanedOperations\n"
    }
}

/**
 * OkHttp request guard for every Hub `/commit/` call. The body is rewritten as
 * exact UTF-8 NDJSON with a canonical header before it leaves the device.
 */
internal fun Request.withCanonicalCommitBody(): Request {
    if (method != "POST" || !url.encodedPath.contains("/commit/")) return this
    val originalBody = body ?: return this

    val buffer = Buffer()
    originalBody.writeTo(buffer)
    val raw = buffer.readString(StandardCharsets.UTF_8)
    val canonical = canonicalizeCommitNdjson(raw)
    val bytes = canonical.toByteArray(StandardCharsets.UTF_8)

    return newBuilder()
        .removeHeader("Content-Type")
        .header("Content-Type", "application/x-ndjson")
        .method(method, bytes.toRequestBody(NDJSON_MEDIA_TYPE))
        .build()
}
