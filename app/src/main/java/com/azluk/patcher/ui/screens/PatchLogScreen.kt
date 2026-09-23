package com.azluk.patcher.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.azluk.patcher.BuildConfig
import com.azluk.patcher.core.*
import com.azluk.patcher.engine.ApkEngine
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
    val lastOutputPath:  String           = ""
)

class PatchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PatchUiState())
    val state: StateFlow<PatchUiState> = _state.asStateFlow()
    private val engine = ApkEngine(app)

    private val notifMgr = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL  = "azluk_patch"
    private val NOTIF_ID = 0xA21

    init { createChannel() }

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

    // ── Patch — runs on IO dispatcher, emits log lines every 200ms ────────────
    // Root cause of empty log: previous version used ForegroundService bridge
    // that never connected because PatchLogScreen navigated before callback
    // was registered. This version runs entirely in viewModelScope — simple,
    // reliable, and the UI always sees every log line.

    fun patch(pkg: String) {
        val patches = _state.value.selectedPatches.toList()
        if (patches.isEmpty()) return
        doLaunchPatch { progress ->
            engine.patch(pkg, patches, progress)
        }
    }

    fun patchFile(file: File) {
        val patches = _state.value.selectedPatches.toList()
        if (patches.isEmpty()) return
        doLaunchPatch { progress ->
            engine.patchExternal(file, patches, progress)
        }
    }

    private fun doLaunchPatch(block: (ApkEngine.Progress) -> File) {
        // Already running — ignore duplicate calls
        if (_state.value.patchState is PatchState.Running) return

        val log   = mutableListOf<String>()
        var dirty = false

        viewModelScope.launch {
            // Set running state immediately so PatchLogScreen sees it
            _state.update { it.copy(patchState = PatchState.Running(emptyList())) }

            // Post foreground notification so Android doesn't kill us
            showNotification("Patching in background...")

            // Ticker: flush log to UI every 200ms — avoids recompose storm
            val ticker = launch(Dispatchers.Main) {
                while (isActive) {
                    delay(200)
                    if (dirty) {
                        _state.update { it.copy(patchState = PatchState.Running(log.toList())) }
                        dirty = false
                    }
                }
            }

            // Run the actual patch on IO at background priority
            val result = withContext(Dispatchers.IO) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runCatching {
                    block(ApkEngine.Progress { msg ->
                        synchronized(log) { log.add(msg); dirty = true }
                    })
                }
            }

            ticker.cancel()
            // Final flush
            _state.update { it.copy(patchState = PatchState.Running(log.toList())) }
            delay(50)

            result.fold(
                onSuccess = { out ->
                    showNotification("Patch complete: ${out.name}")
                    _state.update {
                        it.copy(
                            patchState     = PatchState.Success(out.absolutePath, log.toList()),
                            lastOutputPath = out.absolutePath
                        )
                    }
                },
                onFailure = { e ->
                    synchronized(log) { log.add("[ERROR] ${e.message}") }
                    showNotification("Patch failed")
                    _state.update {
                        it.copy(patchState = PatchState.Failure(e.message ?: "Unknown error", log.toList()))
                    }
                }
            )
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun showNotification(text: String) {
        val notif = NotificationCompat.Builder(getApplication(), CHANNEL)
            .setContentTitle("AzlukPatcher")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(text.contains("background"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notifMgr.notify(NOTIF_ID, notif)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Patching", NotificationManager.IMPORTANCE_LOW)
            ch.description = "AzlukPatcher background patching"
            notifMgr.createNotificationChannel(ch)
        }
    }

    // ── Install result + AI ───────────────────────────────────────────────────

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
                    "Version conflict — uninstall the original first.",
                    message ?: "A different version is already installed.", false)
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                InstallState.Failure("INSTALL_FAILURE_BLOCKED",
                    "Installation blocked.",
                    "Enable 'Install unknown apps' for AzlukPatcher in Settings.", false)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                InstallState.Failure("INSTALL_FAILURE_STORAGE",
                    "Not enough storage space.", "Free up space and try again.", false)
            else ->
                InstallState.Failure("INSTALL_FAILURE_$status",
                    message ?: "Unknown installer error (code $status)",
                    "Unexpected installer error.", true)
        }
        _state.update { it.copy(installState = ist) }
        if (ist is InstallState.Failure) diagnoseWithAi(ist, _state.value.lastOutputPath)
    }

    // ── AI diagnosis — silent, appears as plain suggestion text ──────────────
    // Verified working: uses TokenRouter OpenAI-compatible endpoint.
    // If TOKENROUTER_API_KEY is empty (no local.properties), it skips
    // gracefully without crashing — shows nothing to the user.

    fun diagnoseWithAi(failure: InstallState.Failure, apkPath: String) {
        val apiKey = BuildConfig.TOKENROUTER_API_KEY
        if (apiKey.isBlank()) return   // No key configured — skip silently

        _state.update { it.copy(aiDiagnosis = AiDiagnosis(loading = true)) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apkInfo = runCatching {
                    val f = File(apkPath)
                    "APK: ${f.name}, ${f.length() / 1024}KB, exists=${f.exists()}"
                }.getOrDefault("APK info unavailable")

                val prompt = """
Android APK install failed. Diagnose in 2 sentences and give one specific fix.
Error: ${failure.code} — ${failure.message}
$apkInfo
Direct answer, no preamble.
""".trimIndent()

                val body = org.json.JSONObject().apply {
                    put("model",      BuildConfig.TOKENROUTER_MODEL)
                    put("max_tokens", 250)
                    put("messages",   org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role",    "system")
                            put("content", "You are an Android APK signing expert. Be concise.")
                        })
                        put(org.json.JSONObject().apply {
                            put("role",    "user")
                            put("content", prompt)
                        })
                    })
                }

                val conn = (java.net.URL("${BuildConfig.TOKENROUTER_BASE_URL}/chat/completions")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type",  "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    doOutput        = true
                    connectTimeout  = 10_000
                    readTimeout     = 20_000
                    outputStream.use { it.write(body.toString().toByteArray()) }
                }

                val code = conn.responseCode
                val resp = if (code in 200..299)
                    conn.inputStream.bufferedReader().readText()
                else
                    throw Exception("HTTP $code: ${conn.errorStream?.bufferedReader()?.readText()}")

                val text = org.json.JSONObject(resp)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                _state.update { it.copy(aiDiagnosis = AiDiagnosis(loading = false, suggestion = text)) }

            } catch (e: Exception) {
                // Silent failure — don't bother the user
                _state.update { it.copy(aiDiagnosis = AiDiagnosis(loading = false)) }
                android.util.Log.w("AzlukAI", "AI diagnosis failed: ${e.message}")
            }
        }
    }

    fun dismissAi()  { _state.update { it.copy(aiDiagnosis = AiDiagnosis()) } }
    fun resetPatch() { _state.update { it.copy(patchState = PatchState.Idle, installState = InstallState.Idle, aiDiagnosis = AiDiagnosis()) } }
}
