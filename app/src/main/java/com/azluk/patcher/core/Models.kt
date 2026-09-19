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

// ── V8 Patch Types — full offensive engine ────────────────────────────────────
// Icons are Material Symbols names mapped in the UI
enum class PatchType(
    val key: String,
    val displayName: String,
    val description: String,
    val icon: String,          // Material icon name
    val category: String
) {
    // ── Core bypass patches (from LuckyPatcher + ApkEditorPro techniques) ──
    LICENSE_BYPASS(
        "LICENSE_BYPASS", "License Bypass",
        "Nullifies Google Play LVL license checks. Patches ILicensingService callbacks to always return LICENSED.",
        "verified_user", "bypass"
    ),
    IAP_BYPASS(
        "IAP_BYPASS", "IAP Bypass",
        "Spoofs in-app purchase validation. Patches billing response codes to always return RESULT_OK with PURCHASED state.",
        "shopping_cart", "bypass"
    ),
    SIGNATURE_BYPASS(
        "SIGNATURE_BYPASS", "Signature Bypass",
        "Hooks PackageInfo.getSignatures() to return the original certificate. Prevents signature-mismatch detection after repack.",
        "fingerprint", "bypass"
    ),
    REMOVE_ADS(
        "REMOVE_ADS", "Remove Ads",
        "Kills AdMob, Facebook Audience, Unity Ads, AppLovin, IronSource, MoPub, Chartboost SDKs at the DEX level.",
        "block", "ads"
    ),
    // ── Anti-detection patches (from NPManager + GameGuardian techniques) ──
    SSL_BYPASS(
        "SSL_BYPASS", "SSL Pinning Bypass",
        "Patches OkHttp CertificatePinner, TrustManager.checkServerTrusted and X509TrustManager to accept all certs.",
        "lock_open", "security"
    ),
    ROOT_BYPASS(
        "ROOT_BYPASS", "Root Detection Bypass",
        "Patches RootBeer, isRooted(), isDeviceRooted() to always return false. Removes su binary checks.",
        "security", "security"
    ),
    SAFETYNET_BYPASS(
        "SAFETYNET_BYPASS", "SafetyNet / Integrity Bypass",
        "Patches SafetyNet attestation calls and Play Integrity API to return MEETS_DEVICE_INTEGRITY.",
        "shield", "security"
    ),
    FRIDA_BYPASS(
        "FRIDA_BYPASS", "Anti-Frida / Anti-Debug Bypass",
        "Removes Frida, Xposed, Substrate detection. Patches debugger checks and ptrace anti-debug tricks.",
        "bug_report", "security"
    ),
    // ── DEX transform patches ──────────────────────────────────────────────
    FORCE_DEBUGGABLE(
        "FORCE_DEBUGGABLE", "Force Debuggable",
        "Sets android:debuggable=true in the binary manifest. Enables ADB attach and Frida injection.",
        "adb", "dev"
    ),
    DISABLE_FLAG_SECURE(
        "DISABLE_FLAG_SECURE", "Disable FLAG_SECURE",
        "Patches Window.addFlags(FLAG_SECURE) calls to no-ops. Allows screenshots and screen recording.",
        "screenshot_monitor", "dev"
    ),
    EXPORT_ALL_COMPONENTS(
        "EXPORT_ALL_COMPONENTS", "Export All Components",
        "Patches android:exported=false to true in the binary manifest. Allows external activity/service invocation.",
        "open_in_new", "dev"
    ),
    // ── Optimization ──────────────────────────────────────────────────────
    OPTIMIZE_ZIP(
        "OPTIMIZE_ZIP", "Optimize APK",
        "Recompresses entries, aligns resources.arsc at 4-byte boundaries. Reduces APK size.",
        "compress", "util"
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
