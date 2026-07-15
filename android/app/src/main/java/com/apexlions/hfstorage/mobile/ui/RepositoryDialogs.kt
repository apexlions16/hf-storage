@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apexlions.hfstorage.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apexlions.hfstorage.mobile.data.RepoType

@Composable
internal fun CreateRepositoryDialog(
    onDismiss: () -> Unit,
    onCreate: (String, RepoType, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(RepoType.DATASET) }
    var private by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeni Hugging Face deposu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Depo adı") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepoType.entries.forEach { item ->
                        AssistChip(
                            onClick = { type = item },
                            label = { Text(if (type == item) "✓ ${item.label}" else item.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = private, onCheckedChange = { private = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (private) "Özel depo" else "Public depo")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, type, private) }, enabled = name.isNotBlank()) {
                Text("Oluştur")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
