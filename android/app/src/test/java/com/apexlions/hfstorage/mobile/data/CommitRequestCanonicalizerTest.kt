package com.apexlions.hfstorage.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitRequestCanonicalizerTest {
    private val json = Json { isLenient = false }

    @Test
    fun `keeps an official header and operation`() {
        val raw = """
            {"key":"header","value":{"summary":"Batch upload","description":""}}
            {"key":"lfsFile","value":{"path":"file.bin","algo":"sha256","oid":"abc","size":10}}
        """.trimIndent() + "\n"

        val output = canonicalizeCommitNdjson(raw)
        val lines = output.trim().lines()
        val header = json.parseToJsonElement(lines.first()).jsonObject

        assertEquals("header", header["key"]?.jsonPrimitive?.content)
        assertEquals("Batch upload", header["value"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("\"key\":\"lfsFile\""))
    }

    @Test
    fun `adds fallback summary when header summary is absent`() {
        val raw = """
            {"key":"header","value":{"description":""}}
            {"key":"file","value":{"content":"YQ==","path":"a.txt","encoding":"base64"}}
        """.trimIndent()

        val output = canonicalizeCommitNdjson(raw)
        val header = json.parseToJsonElement(output.lineSequence().first()).jsonObject

        assertEquals(
            "Batch upload with HF Storage Android",
            header["value"]?.jsonObject?.get("summary")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `prepends header when first record is an operation`() {
        val raw = "{\"key\":\"deletedFile\",\"value\":{\"path\":\"old.bin\"}}\n"

        val output = canonicalizeCommitNdjson(raw)
        val lines = output.trim().lines()

        assertEquals(2, lines.size)
        assertEquals("header", json.parseToJsonElement(lines[0]).jsonObject["key"]?.jsonPrimitive?.content)
        assertEquals("deletedFile", json.parseToJsonElement(lines[1]).jsonObject["key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `strips UTF-8 BOM before canonicalization`() {
        val raw = "\uFEFF{\"key\":\"header\",\"value\":{\"summary\":\"Upload\",\"description\":\"\"}}\n"

        val output = canonicalizeCommitNdjson(raw)

        assertTrue(output.startsWith("{\"key\":\"header\""))
        assertTrue(!output.startsWith("\uFEFF"))
    }
}
