@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apexlions.hfstorage.mobile.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apexlions.hfstorage.mobile.data.Repository
import com.apexlions.hfstorage.mobile.data.formatBytes

private enum class MainTab(val label: String) {
    OVERVIEW("Genel"),
    REPOSITORIES("Depolar"),
    FILES("Dosyalar"),
    TRANSFERS("Aktarımlar"),
    SETTINGS("Ayarlar"),
}

@Composable
fun HfStorageApp(viewModel: HfStorageViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF080D18), Color(0xFF0B1324), Color(0xFF080D18)),
                ),
            ),
    ) {
        if (state.loggedIn) MainShell(viewModel, state) else LoginScreen(state, viewModel::login)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun LoginScreen(
    state: HfUiState,
    onLogin: (String, Boolean) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            ),
        ) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Text("🤗", style = MaterialTheme.typography.headlineMedium) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("HF Storage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text("Hugging Face kişisel bulut yöneticisi", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Gizlilik önce gelir", fontWeight = FontWeight.Bold)
                        Text(
                            "Token yalnızca bu telefonda Android Keystore ile şifrelenir. Uygulama doğrudan huggingface.co ile konuşur.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Read + write User Access Token") },
                    placeholder = { Text("hf_…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !state.loading,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = rememberMe,
                        onCheckedChange = { rememberMe = it },
                        enabled = !state.loading,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Bu cihazda güvenli biçimde hatırla")
                }
                Button(
                    onClick = { onLogin(token, rememberMe) },
                    enabled = token.isNotBlank() && !state.loading,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(17.dp),
                ) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text("Güvenli giriş yap", fontWeight = FontWeight.Bold)
                }
                if (state.message.isNotBlank()) {
                    Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MainShell(viewModel: HfStorageViewModel, state: HfUiState) {
    var tab by remember { mutableStateOf(MainTab.OVERVIEW) }
    val title = when (tab) {
        MainTab.OVERVIEW -> "Genel bakış"
        MainTab.REPOSITORIES -> "Depolar"
        MainTab.FILES -> state.selectedRepository?.id?.substringAfterLast('/') ?: "Dosyalar"
        MainTab.TRANSFERS -> "Aktarımlar"
        MainTab.SETTINGS -> "Ayarlar"
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.Bold)
                        Text(
                            "@${state.account?.username.orEmpty()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    when (tab) {
                        MainTab.OVERVIEW, MainTab.REPOSITORIES -> IconButton(
                            onClick = viewModel::refreshRepositories,
                        ) { Icon(Icons.Rounded.Refresh, "Depoları yenile") }
                        MainTab.FILES -> if (state.selectedRepository != null) {
                            IconButton(onClick = viewModel::refreshFiles) {
                                Icon(Icons.Rounded.Refresh, "Dosyaları yenile")
                            }
                        }
                        else -> Unit
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                ),
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)) {
                MainTab.entries.forEach { item ->
                    val icon = when (item) {
                        MainTab.OVERVIEW -> Icons.Rounded.Home
                        MainTab.REPOSITORIES -> Icons.Rounded.Cloud
                        MainTab.FILES -> Icons.Rounded.Folder
                        MainTab.TRANSFERS -> Icons.Rounded.SwapVert
                        MainTab.SETTINGS -> Icons.Rounded.Settings
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                MainTab.OVERVIEW -> OverviewScreen(state) {
                    viewModel.openRepository(it)
                    tab = MainTab.FILES
                }
                MainTab.REPOSITORIES -> RepositoriesScreen(state.repositories, viewModel) {
                    viewModel.openRepository(it)
                    tab = MainTab.FILES
                }
                MainTab.FILES -> FilesScreen(state, viewModel) { tab = MainTab.TRANSFERS }
                MainTab.TRANSFERS -> TransfersScreen(viewModel)
                MainTab.SETTINGS -> SettingsScreen(state, viewModel)
            }
            AnimatedVisibility(state.loading, Modifier.align(Alignment.TopCenter)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun OverviewScreen(state: HfUiState, onOpen: (Repository) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(
                shape = RoundedCornerShape(25.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Kişisel depolama", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text(formatBytes(state.usedBytes), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                    LinearProgressIndicator(
                        progress = { state.usedPercent },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Kalan ${formatBytes((state.capacityBytes - state.usedBytes).coerceAtLeast(0))}")
                        Text("%.2f%%".format(state.usedPercent * 100))
                    }
                    Text(
                        "Kapasite: ${state.fallbackCapacityTb} TB. Mobil API toplam kotayı vermediğinde Ayarlar'daki değer kullanılır.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Depo", state.repositories.size.toString(), Modifier.weight(1f))
                MetricCard("Özel", state.repositories.count { it.isPrivate }.toString(), Modifier.weight(1f))
                MetricCard("Yetki", state.account?.tokenRole ?: "—", Modifier.weight(1f))
            }
        }
        item { Text("Son depolar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(state.repositories.take(8), key = { it.type.name + it.id }) {
            RepositoryCard(it) { onOpen(it) }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun RepositoriesScreen(
    repositories: List<Repository>,
    viewModel: HfStorageViewModel,
    onOpen: (Repository) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    val filtered = repositories.filter { it.id.contains(query, ignoreCase = true) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text("Depolarda ara") },
                    singleLine = true,
                )
            }
            items(filtered, key = { it.type.name + it.id }) {
                RepositoryCard(it) { onOpen(it) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Yeni depo") },
        )
    }

    if (showCreate) {
        CreateRepositoryDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, type, isPrivate ->
                viewModel.createRepository(name, type, isPrivate) { showCreate = false }
            },
        )
    }
}

@Composable
internal fun RepositoryCard(repository: Repository, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Cloud, null, tint = MaterialTheme.colorScheme.secondary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(repository.id, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(repository.type.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (repository.isPrivate) "Özel" else "Public", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (repository.usedStorage > 0) Text(formatBytes(repository.usedStorage), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
