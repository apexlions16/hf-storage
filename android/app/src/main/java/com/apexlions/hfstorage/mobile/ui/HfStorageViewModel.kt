package com.apexlions.hfstorage.mobile.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.apexlions.hfstorage.mobile.data.Account
import com.apexlions.hfstorage.mobile.data.HfApiClient
import com.apexlions.hfstorage.mobile.data.RepoFile
import com.apexlions.hfstorage.mobile.data.RepoType
import com.apexlions.hfstorage.mobile.data.Repository
import com.apexlions.hfstorage.mobile.data.TokenStore
import com.apexlions.hfstorage.mobile.data.UploadJob
import com.apexlions.hfstorage.mobile.data.UploadQueue
import com.apexlions.hfstorage.mobile.data.XetNative
import com.apexlions.hfstorage.mobile.worker.UploadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HfUiState(
    val loggedIn: Boolean = false,
    val account: Account? = null,
    val repositories: List<Repository> = emptyList(),
    val selectedRepository: Repository? = null,
    val files: List<RepoFile> = emptyList(),
    val loading: Boolean = false,
    val message: String = "",
    val error: String? = null,
    val fallbackCapacityTb: Double = 10.0,
) {
    val usedBytes: Long get() = repositories.sumOf { it.usedStorage.coerceAtLeast(0) }
    val capacityBytes: Long get() = (fallbackCapacityTb * 1_000_000_000_000.0).toLong().coerceAtLeast(1)
    val usedPercent: Float get() = (usedBytes.toDouble() / capacityBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

class HfStorageViewModel(application: Application) : AndroidViewModel(application) {
    companion object { const val UPLOAD_TAG = "hf_storage_upload" }

    private val tokenStore = TokenStore(application)
    private val prefs = application.getSharedPreferences("hf_storage_settings", Context.MODE_PRIVATE)
    private val workManager = WorkManager.getInstance(application)
    private var api: HfApiClient? = null

    private val _state = MutableStateFlow(
        HfUiState(fallbackCapacityTb = prefs.getFloat("capacity_tb", 10f).toDouble()),
    )
    val state: StateFlow<HfUiState> = _state.asStateFlow()
    val uploadWork = workManager.getWorkInfosByTagLiveData(UPLOAD_TAG)

    init {
        if (prefs.getBoolean("remember_login", true)) {
            tokenStore.load()?.let { login(it, remember = true, silent = true) }
        }
    }

    fun login(token: String, remember: Boolean, silent: Boolean = false) {
        if (token.isBlank()) {
            _state.update { it.copy(error = "Token boş olamaz.") }
            return
        }
        _state.update { it.copy(loading = true, error = null, message = "Token doğrulanıyor…") }
        viewModelScope.launch {
            runCatching {
                val candidate = HfApiClient(token.trim())
                val account = candidate.authenticate()
                candidate to account
            }.onSuccess { (candidate, account) ->
                api = candidate
                tokenStore.save(token.trim())
                prefs.edit().putBoolean("remember_login", remember).apply()
                _state.update {
                    it.copy(loggedIn = true, account = account, loading = false, message = "Hoş geldin @${account.username}")
                }
                refreshRepositories()
            }.onFailure { error ->
                _state.update { it.copy(loading = false, error = error.message ?: "Giriş başarısız", message = "") }
                if (silent) tokenStore.clear()
            }
        }
    }

    fun logout() {
        tokenStore.clear()
        prefs.edit().putBoolean("remember_login", false).apply()
        api = null
        _state.value = HfUiState(fallbackCapacityTb = _state.value.fallbackCapacityTb)
    }

    fun refreshRepositories() {
        val client = api ?: return
        val username = _state.value.account?.username ?: return
        _state.update { it.copy(loading = true, error = null, message = "Depolar yenileniyor…") }
        viewModelScope.launch {
            runCatching { client.listRepositories(username) }
                .onSuccess { repos ->
                    val selected = _state.value.selectedRepository?.id?.let { id -> repos.firstOrNull { it.id == id } }
                    _state.update { it.copy(repositories = repos, selectedRepository = selected, loading = false, message = "${repos.size} depo bulundu") }
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.message, message = "") } }
        }
    }

    fun openRepository(repository: Repository) {
        val client = api ?: return
        _state.update { it.copy(selectedRepository = repository, files = emptyList(), loading = true, error = null, message = "Dosyalar alınıyor…") }
        viewModelScope.launch {
            runCatching { client.listFiles(repository) }
                .onSuccess { files -> _state.update { it.copy(files = files, loading = false, message = "${files.size} öğe") } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.message, message = "") } }
        }
    }

    fun refreshFiles() {
        _state.value.selectedRepository?.let(::openRepository)
    }

    fun createRepository(name: String, type: RepoType, isPrivate: Boolean, onDone: () -> Unit) {
        val client = api ?: return
        val fullName = if ('/' in name) name else "${_state.value.account?.username}/$name"
        _state.update { it.copy(loading = true, error = null, message = "Depo oluşturuluyor…") }
        viewModelScope.launch {
            runCatching { client.createRepository(fullName, type, isPrivate) }
                .onSuccess {
                    _state.update { state -> state.copy(loading = false, message = "Depo oluşturuldu") }
                    refreshRepositories()
                    onDone()
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } }
        }
    }

    fun deleteFiles(paths: List<String>, permanent: Boolean, onDone: () -> Unit) {
        val client = api ?: return
        val repository = _state.value.selectedRepository ?: return
        _state.update { it.copy(loading = true, error = null, message = "Dosyalar siliniyor…") }
        viewModelScope.launch {
            runCatching { client.deletePaths(repository, paths, permanent) }
                .onSuccess { purged ->
                    _state.update { it.copy(loading = false, message = if (permanent) "$purged büyük dosya nesnesi kalıcı temizlendi" else "Silme tamamlandı") }
                    refreshFiles()
                    refreshRepositories()
                    onDone()
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } }
        }
    }

    fun enqueueUpload(job: UploadJob) {
        if (!XetNative.isAvailable) {
            _state.update {
                it.copy(
                    error = "Xet zorunlu fakat native bileşen yüklenemedi: ${XetNative.loadError ?: "bilinmeyen hata"}",
                    message = "",
                )
            }
            return
        }

        runCatching {
            UploadQueue(getApplication()).save(job)
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(Data.Builder().putString(UploadWorker.KEY_JOB_ID, job.id).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(UPLOAD_TAG)
                .build()
            workManager.enqueue(request)
        }.onSuccess {
            _state.update { it.copy(error = null, message = "${job.items.size} dosyalık Xet yüklemesi kuyruğa eklendi") }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    error = "Yükleme kuyruğa eklenemedi: ${error.message ?: error::class.java.simpleName}",
                    message = "",
                )
            }
        }
    }

    fun setCapacityTb(value: Double) {
        val safe = value.coerceIn(0.1, 10_000.0)
        prefs.edit().putFloat("capacity_tb", safe.toFloat()).apply()
        _state.update { it.copy(fallbackCapacityTb = safe) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun downloadUrl(file: RepoFile): Pair<String, String>? {
        val client = api ?: return null
        val repository = _state.value.selectedRepository ?: return null
        return client.downloadUrl(repository, file.path) to client.authHeader()
    }
}
