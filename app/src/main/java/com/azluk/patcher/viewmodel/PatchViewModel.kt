package com.azluk.patcher.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.azluk.patcher.BuildConfig
import com.azluk.patcher.core.*
import com.azluk.patcher.engine.ApkEngine
import com.azluk.patcher.engine.PatchForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class AiDiagnosis(
    val loading:    Boolean = false,
    val suggestion: String  = "",
    val error:      String  = ""
)

data class PatchUiState(
    val selectedPatches: Set<PatchType>   = emptySet(),
    val patchState:      PatchState       = PatchState.Idle,
    val installState:    InstallState     = InstallState.Idle,
    val scanResults:     List<ScanResult> = emptyList(),
    val isScanning:      Boolean          = false,
    val aiDiagnosis:     AiDiagnosis      = AiDiagnosis(),
    val lastOutputPath:  String           = "",
    val isInBackground:  Boolean          = false  // true when foreground service is running
)

class PatchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PatchUiState())
    val state: StateFlow<PatchUiState> = _state.asStateFlow()
    private val engine = ApkEngine(app)

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isScanning = true, scanResults = emptyList()) }
            val results = runCatching { engine.scan(pkg) }.getOrDefault(emptyList())
            val detected = results.mapNotNull {
                runCatching { PatchType.valueOf(it.patchType) }.getOrNull()
            }.toSet()
            _state.update {
                it.copy(
                    isScanning      = false,
                    scanResults     = results,
                    selectedPatches = if (detected.isNotEmpty()) detected
                                     else setOf(PatchType.LICENSE_BYPASS, PatchType.REMOVE_ADS)
                )
            }
        }
    }

    fun togglePatch(type: PatchType) {
        _state.update { s ->
            val set = s.selectedPatches.toMutableSet()
            if (type in set) set.remove(type) else set.add(type)
            s.copy(selectedPatches = set)
        }
    }

    // ── Patch via ForegroundService ───────────────────────────────────────────
    // Registers itself as the KotlinBridge callback, then starts the Java
    // PatchForegroundService. The service calls back into launchPatchWork().
    // This is what enables true background operation with a persistent notification.

    fun patch(pkg: String) {
        val patches = _state.value.selectedPatches.toList()
        if (patches.isEmpty()) return
        startForegroundPatch(pkg, null, patches)
    }

    fun patchFile(file: File) {
        val patches = _state.value.selectedPatches.toList()
        if (patches.isEmpty()) return
        startForegroundPatch(null, file.absolutePath, patches)
    }

    private fun startForegroundPatch(pkg: String?, apkPath: String?, patches: List<PatchType>) {
        val ctx = getApplication<Application>()
        val log = mutableListOf<String>()
        var dirty = false

        // Register this ViewModel as the callback the Java service will call
        PatchForegroundService.KotlinBridge.INSTANCE.callback =
            PatchForegroundService.PatchCallback { _, _, onProgress, onDone ->

                // Ticker — flush log every 300ms to avoid recompose storm
                val ticker = viewModelScope.launch(Dispatchers.Main) {
                    while (isActive) {
                        delay(300)
                        if (dirty) {
                            _state.update { it.copy(patchState = PatchState.Running(log.toList())) }
                            dirty = false
                        }
                    }
                }

                viewModelScope.launch(Dispatchers.IO) {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

                    val result = runCatching {
                        val progress = ApkEngine.Progress { msg ->
                            synchronized(log) { log.add(msg); dirty = true }
                            onProgress?.log(msg)
                        }
                        if (pkg != null) engine.patch(pkg, patches, progress)
                        else engine.patchExternal(File(apkPath!!), patches, progress)
                    }

                    ticker.cancel()

                    result.fold(
                        onSuccess = { out ->
                            _state.update {
                                it.copy(
                                    patchState      = PatchState.Success(out.absolutePath, log.toList()),
                                    lastOutputPath  = out.absolutePath,
                                    isInBackground  = false
                                )
                            }
                            onDone?.done(true, out.absolutePath, null)
                        },
                        onFailure = { e ->
                            synchronized(log) { log.add("Error: ${e.message}") }
                            _state.update {
                                it.copy(
                                    patchState     = PatchState.Failure(e.message ?: "Unknown", log.toList()),
                                    isInBackground = false
                                )
                            }
                            onDone?.done(false, null, e.message)
                        }
                    )
                }
            }

        // Update UI to show background running state
        _state.update { it.copy(patchState = PatchState.Running(emptyList()), isInBackground = true) }

        // Start the foreground service
        val serviceIntent = Intent(ctx, PatchForegroundService::class.java).apply {
            putExtra("pkg", pkg)
            putExtra("apk_path", apkPath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(serviceIntent)
        } else {
            ctx.startService(serviceIntent)
        }
    }

    // ── Install result + silent AI diagnosis ──────────────────────────────────

    fun onInstallResult(status: Int, message: String?) {
        val ist = when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                InstallState.Success
            PackageInstaller.STATUS_FAILURE_INVALID ->
                InstallState.Failure("INSTALL_FAILURE_INVALID",
                    "Invalid APK — signature or structure is corrupt.",
                    "The signing block may be malformed.", true)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                InstallState.Failure("INSTALL_FAILURE_CONFLICT",
                    "Version conflict — uninstall the original app first.",
                    message ?: "A different version is already installed.", false)
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                InstallState.Failure("INSTALL_FAILURE_BLOCKED",
                    "Installation blocked.",
                    "Enable 'Install unknown apps' for AzlukPatcher in Settings.", false)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                InstallState.Failure("INSTALL_FAILURE_STORAGE",
                    "Not enough storage space.",
                    "Free up space and try again.", false)
            else ->
                InstallState.Failure("INSTALL_FAILURE_$status",
                    message ?: "Unknown installer error (code $status)",
                    "Unexpected installer error.", true)
        }
        _state.update { it.copy(installState = ist) }
        if (ist is InstallState.Failure) diagnoseWithAi(ist, _state.value.lastOutputPath)
    }

    fun diagnoseWithAi(failure: InstallState.Failure, apkPath: String) {
        _state.update { it.copy(aiDiagnosis = AiDiagnosis(loading = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apkInfo = runCatching {
                    val f = File(apkPath)
                    "APK: ${f.name}, ${f.length()/1024}KB"
                }.getOrDefault("")

                val prompt = """
Android APK install failed. Give a 2-sentence diagnosis and one specific fix.
Error code: ${failure.code}
Error message: ${failure.message}
$apkInfo
No preamble. Direct answer only.
""".trimIndent()

                val body = org.json.JSONObject().apply {
                    put("model",      BuildConfig.TOKENROUTER_MODEL)
                    put("max_tokens", 250)
                    put("messages",   org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply { put("role","system"); put("content","Android APK signing expert. Be concise.") })
                        put(org.json.JSONObject().apply { put("role","user"); put("content", prompt) })
                    })
                }
                val conn = java.net.URL("${BuildConfig.TOKENROUTER_BASE_URL}/chat/completions")
                    .openConnection() as java.net.HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type",  "application/json")
                    setRequestProperty("Authorization", "Bearer ${BuildConfig.TOKENROUTER_API_KEY}")
                    doOutput = true; connectTimeout = 8000; readTimeout = 15000
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val text = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                _state.update { it.copy(aiDiagnosis = AiDiagnosis(loading = false, suggestion = text)) }
            } catch (e: Exception) {
                _state.update { it.copy(aiDiagnosis = AiDiagnosis(loading = false)) }
            }
        }
    }

    fun dismissAi()    { _state.update { it.copy(aiDiagnosis = AiDiagnosis()) } }
    fun resetPatch()   { _state.update { it.copy(patchState = PatchState.Idle, installState = InstallState.Idle, aiDiagnosis = AiDiagnosis()) } }
}
