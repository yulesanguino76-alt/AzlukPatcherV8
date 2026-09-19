package com.azluk.patcher.core

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    val apkPath: String,
    val versionName: String,
    val apkSizeMb: Float,
    var patchStatus: PatchStatus = PatchStatus.UNKNOWN,
    var opportunityCount: Int = 0
)

enum class PatchStatus { UNKNOWN, LIKELY, PATCHABLE, COMPLEX }

/**
 * V8 transformations are intentionally local and auditable.
 * They operate on an APK the user has selected/installed and do not implement
 * licensing, signature-integrity, credential, or security-control bypasses.
 */
enum class PatchType(
    val key: String,
    val displayName: String,
    val description: String
) {
    REPACK_VERIFY(
        "REPACK_VERIFY",
        "Repack + Verify",
        "Rebuild the APK deterministically and verify its ZIP structure."
    ),
    REMOVE_SIGNATURE_METADATA(
        "REMOVE_SIGNATURE_METADATA",
        "Clean Old Signature Metadata",
        "Remove stale META-INF signature artifacts before a new local signature is applied."
    ),
    OPTIMIZE_ZIP(
        "OPTIMIZE_ZIP",
        "Optimize APK ZIP",
        "Recompress ordinary ZIP entries while preserving stored resources and native libraries."
    ),
    DATA_TEXT_PATCH(
        "DATA_TEXT_PATCH",
        "Data Text Patch",
        "Apply explicitly defined text replacements from an Azluk patch manifest."
    )
}

data class ScanResult(
    val patchType: String,
    val desc: String?,
    val dexIndex: Int,
    val offset: Int
)

data class PatchFinding(
    val id: String,
    val title: String,
    val detail: String,
    val severity: FindingSeverity = FindingSeverity.INFO
)

enum class FindingSeverity { INFO, ATTENTION, WARNING }

data class PatchPack(
    val id: String,
    val name: String,
    val icon: String,
    val category: String,
    val description: String
)

sealed class PatchState {
    object Idle : PatchState()
    data class Running(val log: List<String>) : PatchState()
    data class Success(val outputPath: String, val log: List<String>) : PatchState()
    data class Failure(val error: String, val log: List<String>) : PatchState()
}

sealed class InstallState {
    object Idle : InstallState()
    object Installing : InstallState()
    object Success : InstallState()
    data class Failure(
        val code: String,
        val message: String,
        val description: String,
        val canRetry: Boolean
    ) : InstallState()
}
