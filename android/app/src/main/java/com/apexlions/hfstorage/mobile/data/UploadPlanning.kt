package com.apexlions.hfstorage.mobile.data

const val MAX_FILES_PER_COMMIT = 100

fun <T> planCommitBatches(items: List<T>): List<List<T>> = items.chunked(MAX_FILES_PER_COMMIT)

fun normalizeRemotePath(destination: String, relative: String): String =
    listOf(destination.replace('\\', '/').trim('/'), relative.replace('\\', '/').trim('/'))
        .filter { it.isNotBlank() }
        .joinToString("/")
