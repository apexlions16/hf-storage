package com.apexlions.hfstorage.mobile.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.apexlions.hfstorage.mobile.data.UploadItem

fun selectedFiles(context: Context, uris: List<Uri>): List<UploadItem> = uris.mapNotNull { uri ->
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    queryItem(context.contentResolver, uri, null)
}

fun selectedFolder(context: Context, treeUri: Uri): List<UploadItem> {
    runCatching {
        context.contentResolver.takePersistableUriPermission(treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
    val rootName = root.name ?: "klasor"
    val result = mutableListOf<UploadItem>()

    fun walk(node: DocumentFile, relative: String) {
        node.listFiles().sortedBy { it.name.orEmpty().lowercase() }.forEach { child ->
            val name = child.name ?: return@forEach
            val path = listOf(relative, name).filter { it.isNotBlank() }.joinToString("/")
            if (child.isDirectory) walk(child, path)
            else if (child.isFile) {
                result += UploadItem(child.uri.toString(), path, name, child.length())
            }
        }
    }
    walk(root, rootName)
    return result
}

private fun queryItem(resolver: ContentResolver, uri: Uri, relative: String?): UploadItem? {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "dosya"
    var size = 0L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    return UploadItem(uri.toString(), relative ?: name, name, size)
}
