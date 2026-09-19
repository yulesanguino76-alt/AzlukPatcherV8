package com.azluk.patcher.viewmodel

import android.app.Application
import android.content.pm.PackageInstaller
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.azluk.patcher.BuildConfig
import com.azluk.patcher.core.*
import com.azluk.patcher.engine.ApkEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

// ── Install AI diagnosis ──────────────────────────────────────────────────────

data class AiDiagnosis(
    val loading: Boolean = false,
    val suggestion: String = "",
    val fixApplied: Boolean = false,
    val error: String = ""
)

data class PatchUiState(
    val selectedPatches: Set<PatchType> = emptySet(),

    val patchState: PatchState =
        PatchState.Idle,

    val installState: InstallState =
        InstallState.Idle,

    val scanResults: List<ScanResult> =
        emptyList(),

    val isScanning: Boolean =
        false,

    val aiDiagnosis: AiDiagnosis =
        AiDiagnosis(),

    val lastOutputPath: String =
        ""
)

class PatchViewModel(
    app: Application
) : AndroidViewModel(app) {

    private val _state =
        MutableStateFlow(
            PatchUiState()
        )

    val state: StateFlow<PatchUiState> =
        _state.asStateFlow()

    private val engine =
        ApkEngine(app)

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(pkg: String) {
        viewModelScope.launch(
            Dispatchers.IO
        ) {
            _state.update {
                it.copy(
                    isScanning = true,
                    scanResults = emptyList()
                )
            }

            val results =
                runCatching {
                    engine.scan(pkg)
                }.getOrDefault(
                    emptyList()
                )

            val detected =
                results
                    .mapNotNull {
                        runCatching {
                            PatchType.valueOf(
                                it.patchType
                            )
                        }.getOrNull()
                    }
                    .toSet()

            val autoSelected =
                if (detected.isNotEmpty()) {
                    detected
                } else {
                    setOf(
                        PatchType.REPACK_VERIFY,
                        PatchType.REMOVE_SIGNATURE_METADATA
                    )
                }

            _state.update {
                it.copy(
                    isScanning = false,
                    scanResults = results,
                    selectedPatches = autoSelected
                )
            }
        }
    }

    // ── Toggle patch ─────────────────────────────────────────────────────────

    fun togglePatch(
        type: PatchType
    ) {
        _state.update { state ->

            val set =
                state.selectedPatches
                    .toMutableSet()

            if (type in set) {
                set.remove(type)
            } else {
                set.add(type)
            }

            state.copy(
                selectedPatches = set
            )
        }
    }

    // ── Patch ─────────────────────────────────────────────────────────────────

    fun patch(
        pkg: String
    ) {
        val patches =
            _state.value
                .selectedPatches
                .toList()

        if (patches.isEmpty()) {
            return
        }

        launchPatch {
            engine.patch(
                pkg,
                patches,
                it
            )
        }
    }

    fun patchFile(
        file: File
    ) {
        val patches =
            _state.value
                .selectedPatches
                .toList()

        if (patches.isEmpty()) {
            return
        }

        launchPatch {
            engine.patchExternal(
                file,
                patches,
                it
            )
        }
    }

    private fun launchPatch(
        block: suspend (
            ApkEngine.Progress
        ) -> File
    ) {
        val log =
            mutableListOf<String>()

        var dirty = false

        viewModelScope.launch {

            val ticker =
                launch(Dispatchers.Main) {

                    while (isActive) {
                        delay(300)

                        if (dirty) {
                            _state.update {
                                it.copy(
                                    patchState =
                                        PatchState.Running(
                                            log.toList()
                                        )
                                )
                            }

                            dirty = false
                        }
                    }
                }

            val result =
                runCatching {

                    withContext(
                        Dispatchers.IO
                    ) {

                        android.os.Process
                            .setThreadPriority(
                                android.os.Process
                                    .THREAD_PRIORITY_BACKGROUND
                            )

                        block(
                            ApkEngine.Progress { msg ->

                                synchronized(log) {
                                    log.add(msg)
                                    dirty = true
                                }
                            }
                        )
                    }
                }

            ticker.cancel()

            result.fold(

                onSuccess = { output ->

                    _state.update {
                        it.copy(
                            patchState =
                                PatchState.Success(
                                    output.absolutePath,
                                    log.toList()
                                ),

                            lastOutputPath =
                                output.absolutePath
                        )
                    }
                },

                onFailure = { exception ->

                    synchronized(log) {
                        log.add(
                            "✗ ${exception.message}"
                        )
                    }

                    _state.update {
                        it.copy(
                            patchState =
                                PatchState.Failure(
                                    exception.message
                                        ?: "Unknown error",

                                    log.toList()
                                )
                        )
                    }
                }
            )
        }
    }

    // ── Install result ───────────────────────────────────────────────────────

    fun onInstallResult(
        status: Int,
        message: String?
    ) {
        val installState =
            when (status) {

                PackageInstaller.STATUS_SUCCESS ->
                    InstallState.Success

                PackageInstaller.STATUS_FAILURE_INVALID ->
                    InstallState.Failure(
                        "INSTALL_FAILURE_INVALID",

                        "Invalid APK — signature or structure is corrupt.",

                        "PARSE_FAILED: signing block may be malformed.",

                        true
                    )

                PackageInstaller.STATUS_FAILURE_CONFLICT ->
                    InstallState.Failure(
                        "INSTALL_FAILURE_CONFLICT",

                        "Version conflict — uninstall the original app first.",

                        message
                            ?: "A different version is already installed.",

                        false
                    )

                PackageInstaller.STATUS_FAILURE_BLOCKED ->
                    InstallState.Failure(
                        "INSTALL_FAILURE_BLOCKED",

                        "Installation blocked.",

                        "Enable 'Install unknown apps' for AzlukPatcher in Settings.",

                        false
                    )

                PackageInstaller.STATUS_FAILURE_STORAGE ->
                    InstallState.Failure(
                        "INSTALL_FAILURE_STORAGE",

                        "Not enough storage space.",

                        "Free up space and try again.",

                        false
                    )

                else ->
                    InstallState.Failure(
                        "INSTALL_FAILURE_$status",

                        message
                            ?: "Unknown installer error (code $status)",

                        "Unexpected installer error.",

                        true
                    )
            }

        _state.update {
            it.copy(
                installState =
                    installState
            )
        }

        if (
            installState
                is InstallState.Failure
        ) {
            diagnoseWithAi(
                installState,
                _state.value.lastOutputPath
            )
        }
    }

    // ── AI diagnosis ─────────────────────────────────────────────────────────

    fun diagnoseWithAi(
        failure: InstallState.Failure,
        apkPath: String
    ) {
        _state.update {
            it.copy(
                aiDiagnosis =
                    AiDiagnosis(
                        loading = true
                    )
            )
        }

        viewModelScope.launch(
            Dispatchers.IO
        ) {
            try {

                val apkInfo =
                    runCatching {

                        val file =
                            File(apkPath)

                        "APK: ${file.name}, " +
                            "size: ${file.length() / 1024}KB, " +
                            "exists: ${file.exists()}"

                    }.getOrDefault(
                        "APK info unavailable"
                    )

                val prompt =
                    """
                    You are an Android APK signing expert embedded in AzlukPatcher.
                    The user patched an APK and installation failed.

                    Error code: ${failure.code}
                    Error message: ${failure.message}
                    Error description: ${failure.description}
                    $apkInfo

                    Diagnose the exact cause in 2 sentences maximum.
                    Then give one concrete fix the app should apply automatically, in plain language.
                    Be direct, no preamble.
                    """.trimIndent()

                val body =
                    org.json.JSONObject().apply {

                        put(
                            "model",
                            BuildConfig.TOKENROUTER_MODEL
                        )

                        put(
                            "max_tokens",
                            300
                        )

                        put(
                            "messages",
                            org.json.JSONArray().apply {

                                put(
                                    org.json.JSONObject().apply {
                                        put(
                                            "role",
                                            "system"
                                        )

                                        put(
                                            "content",
                                            "You are an Android APK signing expert. Be concise and direct."
                                        )
                                    }
                                )

                                put(
                                    org.json.JSONObject().apply {
                                        put(
                                            "role",
                                            "user"
                                        )

                                        put(
                                            "content",
                                            prompt
                                        )
                                    }
                                )
                            }
                        )
                    }

                val url =
                    java.net.URL(
                        "${BuildConfig.TOKENROUTER_BASE_URL}/chat/completions"
                    )

                val connection =
                    url.openConnection()
                        as java.net.HttpURLConnection

                try {

                    connection.requestMethod =
                        "POST"

                    connection.setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

                    connection.setRequestProperty(
                        "Authorization",
                        "Bearer ${BuildConfig.TOKENROUTER_API_KEY}"
                    )

                    connection.doOutput =
                        true

                    connection.connectTimeout =
                        10_000

                    connection.readTimeout =
                        20_000

                    connection.outputStream.use {
                        it.write(
                            body
                                .toString()
                                .toByteArray()
                        )
                    }

                    val response =
                        if (
                            connection.responseCode
                                in 200..299
                        ) {
                            connection.inputStream
                                .bufferedReader()
                                .readText()
                        } else {
                            val errorBody =
                                connection.errorStream
                                    ?.bufferedReader()
                                    ?.readText()

                            throw java.io.IOException(
                                "HTTP ${connection.responseCode}: " +
                                    (errorBody ?: "Unknown API error")
                            )
                        }

                    val json =
                        org.json.JSONObject(
                            response
                        )

                    val text =
                        json
                            .getJSONArray(
                                "choices"
                            )
                            .getJSONObject(0)
                            .getJSONObject(
                                "message"
                            )
                            .getString(
                                "content"
                            )
                            .trim()

                    _state.update {
                        it.copy(
                            aiDiagnosis =
                                AiDiagnosis(
                                    loading = false,
                                    suggestion = text
                                )
                        )
                    }

                } finally {
                    connection.disconnect()
                }

            } catch (exception: Exception) {

                _state.update {
                    it.copy(
                        aiDiagnosis =
                            AiDiagnosis(
                                loading = false,

                                error =
                                    "AI unavailable: " +
                                        exception.message
                            )
                    )
                }
            }
        }
    }

    // ── Dismiss AI ───────────────────────────────────────────────────────────

    fun dismissAi() {
        _state.update {
            it.copy(
                aiDiagnosis =
                    AiDiagnosis()
            )
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun resetPatch() {
        _state.update {
            it.copy(
                patchState =
                    PatchState.Idle,

                installState =
                    InstallState.Idle,

                aiDiagnosis =
                    AiDiagnosis()
            )
        }
    }
}
