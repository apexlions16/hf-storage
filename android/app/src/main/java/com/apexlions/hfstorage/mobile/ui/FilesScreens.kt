@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apexlions16.hfstorage.mobile.ui

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.apexlions.hfstorage.mobile.data.RepoFile
import com.apexlions.hfstorage.mobile.data.UploadItem
import com.apexlions.hfstorage.mobile.data.UploadJob
import com.apexlions.hfstorage.mobile.data.formatBytes
import com.apexlions.hfstorage.mobile.worker.UploadWorker
import java.util.UUID

@Composable
internal fun FilesScreen(state: HfUiState, viewModel: HfStorageViewModel, openTransfers: () -> Unit) {
    val context = LocalContext.current
    val selected = remember(state.selectedRepository?.id) { mutableStateListOf<String>() }
    var uploadMenu by remember { mutableStateOf(false) }
    var pendingUpload by remember { mutableStateOf<List<UploadItem>>(emptyList()) }
    var showDelete by remember { mutableStateOf(false) }

    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> pendingUpload = selectedFiles(context, uris) }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> pendingUpload = uri?.let { selectedFolder(context, it) }.orEmpty() }

    val repository = state.selectedRepository
    if (repository == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Folder, null, Modifier.size(70.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("Önce Depolar sekmesinden bir depo seçin.")
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 110.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(repository.id, fontWeight = FontWeight.Bold)
                        Text("${state.files.size} öğe", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected.isNotEmpty()) {
                        FilledIconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Rounded.DeleteForever, "Sil")
                        }
                    }
                }
            }
            items(state.files, key = { it.path }) { file ->
                FileRow(
                    file = file,
                    selected = file.path in selected,
                    onToggle = {
                        if (file.path in selected) selected.remove(file.path) else selected.add(file.path)
                    },
                    onDownload = {
                        viewModel.downloadUrl(file)?.let { (url, auth) ->
                            startDownload(context, url, auth, file.path.substringAfterLast('/'))
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            ExtendedFloatingActionButton(
                onClick = { uploadMenu = true },
                icon = { Icon(Icons.Rounded.CloudUpload, null) },
                text = { Text("Yükle") },
            )
            DropdownMenu(expanded = uploadMenu, onDismissRequest = { uploadMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Dosyaları seç") },
                    leadingIcon = { Icon(Icons.Rounded.InsertDriveFile, null) },
                    onClick = {
                        uploadMenu = false
                        filesLauncher.launch(arrayOf("*/*"))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Klasör seç") },
                    leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                    onClick = {
                        uploadMenu = false
                        folderLauncher.launch(null)
                    },
                )
            }
        }
    }

    if (pendingUpload.isNotEmpty()) {
        UploadConfigDialog(
            count = pendingUpload.size,
            onDismiss = { pendingUpload = emptyList() },
            onStart = { destination, message ->
                viewModel.enqueueUpload(
                    UploadJob(
                        id = UUID.randomUUID().toString(),
                        repoId = repository.id,
                        repoType = repository.type.name.lowercase(),
                        destination = destination,
                        commitMessage = message,
                        items = pendingUpload,
                    ),
                )
                pendingUpload = emptyList()
                openTransfers()
            },
        )
    }
    if (showDelete) {
        DeleteDialog(
            count = selected.size,
            onDismiss = { showDelete = false },
            onConfirm = { permanent ->
                viewModel.deleteFiles(selected.toList(), permanent) {
                    selected.clear()
                    showDelete = false
                }
            },
        )
    }
}

@Composable
private fun FileRow(file: RepoFile, selected: Boolean, onToggle: () -> Unit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Icon(
            imageVector = if (file.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.path, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${file.backend} • ${formatBytes(file.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!file.isDirectory) {
            IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, "İndir") }
        }
    }
}

@Composable
internal fun TransfersScreen(viewModel: HfStorageViewModel) {
    val workInfos by viewModel.uploadWork.observeAsState(emptyList())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (workInfos.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.CloudUpload, null, Modifier.size(50.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text("Henüz aktarım yok.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        itemsIndexed(workInfos.asReversed()) { _, work ->
            val current = work.progress.getInt(
                UploadWorker.KEY_CURRENT,
                work.outputData.getInt(UploadWorker.KEY_CURRENT, 0),
            )
            val total = work.progress.getInt(
                UploadWorker.KEY_TOTAL,
                work.outputData.getInt(UploadWorker.KEY_TOTAL, 0),
            )
            val message = work.progress.getString(UploadWorker.KEY_MESSAGE)
                ?: work.outputData.getString(UploadWorker.KEY_MESSAGE)
                ?: work.state.name
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Batch upload", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        AssistChip(onClick = { }, label = { Text(work.state.name) })
                    }
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(
                        progress = { if (total > 0) current.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    )
                    Text("$current / $total dosya", style = MaterialTheme.typography.bodySmall)
                    if (work.state == WorkInfo.State.FAILED) {
                        Text(
                            work.outputData.getString(UploadWorker.KEY_MESSAGE).orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(state: HfUiState, viewModel: HfStorageViewModel) {
    var capacity by remember(state.fallbackCapacityTb) {
        mutableStateOf(state.fallbackCapacityTb.toString())
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Depolama kapasitesi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Hugging Face toplam kotayı döndürmediğinde progress bar için kullanılır.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = capacity,
                        onValueChange = { capacity = it },
                        label = { Text("Kapasite (TB)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    Button(onClick = { capacity.toDoubleOrNull()?.let(viewModel::setCapacityTb) }) {
                        Text("Kaydet")
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Güvenlik", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Token Android Keystore AES-GCM ile şifrelenir. Telemetri ve aracı sunucu yoktur.")
                    Text(
                        "Aktarımlar HTTPS üzerinden doğrudan Hugging Face Hub'a gider.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Logout, null)
                Spacer(Modifier.width(8.dp))
                Text("Tokenı sil ve çıkış yap")
            }
        }
        item { Text("HF Storage Android v0.1.0", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun UploadConfigDialog(count: Int, onDismiss: () -> Unit, onStart: (String, String) -> Unit) {
    var destination by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Batch upload with HF Storage Android") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$count dosyayı yükle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("En fazla 100 dosya tek commit olur; fazlası otomatik olarak 100'lük batch'lere ayrılır.")
                OutlinedTextField(destination, { destination = it }, label = { Text("Hedef klasör") })
                OutlinedTextField(message, { message = it }, label = { Text("Commit mesajı") })
            }
        },
        confirmButton = {
            Button(onClick = { onStart(destination, message) }, enabled = message.isNotBlank()) {
                Text("Yüklemeyi başlat")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun DeleteDialog(count: Int, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var permanent by remember { mutableStateOf(true) }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$count öğeyi sil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Kalıcı temizlik açıksa LFS/Xet nesneleri ve geçmiş referansları da silinir. İşlem geri alınamaz.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = permanent, onCheckedChange = { permanent = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Storage Usage alanından da kalıcı temizle")
                }
                if (permanent) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Onay için SİL yazın") },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(permanent) },
                enabled = !permanent || confirmation.trim().uppercase() == "SİL",
            ) { Text("Sil") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

private fun startDownload(context: Context, url: String, authorization: String, fileName: String) {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val request = DownloadManager.Request(android.net.Uri.parse(url))
        .addRequestHeader("Authorization", authorization)
        .setTitle(fileName)
        .setDescription("HF Storage üzerinden indiriliyor")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
    manager.enqueue(request)
}
