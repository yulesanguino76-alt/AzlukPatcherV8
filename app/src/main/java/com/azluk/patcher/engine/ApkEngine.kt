package com.azluk.patcher.engine

import android.content.Context
import android.util.Log
import com.azluk.patcher.core.*
import com.azluk.patcher.engine.sign.ApkSignerV2
import com.azluk.patcher.utils.StorageUtils
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.*

/**
 * AzlukPatcher V8 engine.
 *
 * Architecture:
 *  - streaming ZIP reader/writer
 *  - deterministic patch pipeline
 *  - DEX string inspection
 *  - explicit patch manifests
 *  - final local signing
 *
 * V8 deliberately does not implement license, purchase, signature-integrity,
 * credential, or security-control bypasses.
 */
class ApkEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "AzlukEngineV8"
        private const val BUF = 256 * 1024
        private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0a)
        private val SIGNATURE_NAMES = setOf(
            "META-INF/MANIFEST.MF",
            "META-INF/CERT.SF",
            "META-INF/CERT.RSA",
            "META-INF/CERT.DSA",
            "META-INF/CERT.EC"
        )
        private val FINDINGS = arrayOf(
            "ILicensingService" to "License verification reference detected",
            "com/android/vending/billing" to "Google Play billing reference detected",
            "getSignatures" to "Package signature API reference detected",
            "CertificatePinner" to "OkHttp certificate pinning reference detected",
            "checkServerTrusted" to "TrustManager reference detected",
            "RootBeer" to "Root detection library reference detected",
            "SafetyNet" to "SafetyNet reference detected",
            "com/google/android/play/core/integrity" to "Play Integrity reference detected",
            "frida" to "Frida-related string detected",
            "XposedBridge" to "Xposed-related string detected"
        )
    }

    fun quickStatus(pkg: String): PatchStatus = try {
        val file = File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir)
        val findings = inspect(file)
        when {
            findings.isEmpty() -> PatchStatus.UNKNOWN
            findings.size >= 3 -> PatchStatus.PATCHABLE
            else -> PatchStatus.LIKELY
        }
    } catch (_: Exception) {
        PatchStatus.UNKNOWN
    }

    fun quickCount(pkg: String): Int = try {
        inspect(File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir)).size
    } catch (_: Exception) { 0 }

    fun scan(pkg: String): List<ScanResult> =
        scanFile(File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir))

    fun inspectFile(input: File): List<PatchFinding> {
        val out = mutableListOf<PatchFinding>()
        ZipInputStream(BufferedInputStream(FileInputStream(input), BUF)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name.endsWith(".dex")) {
                    val bytes = zis.readBytes()
                    val text = String(bytes, Charsets.ISO_8859_1)
                    for ((needle, detail) in FINDINGS) {
                        if (text.contains(needle, ignoreCase = true)) {
                            out += PatchFinding(
                                id = needle,
                                title = "Reference detected",
                                detail = "$detail in ${e.name}",
                                severity = FindingSeverity.ATTENTION
                            )
                        }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return out.distinctBy { it.id }
    }

    private fun inspect(input: File) = inspectFile(input)

    fun patch(pkg: String, patches: List<PatchType>, progress: Progress): File {
        val src = File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir)
        val out = File(StorageUtils.getPatchedDir(), "${pkg}_azluk_v8.apk")
        patchToDisk(src, out, patches, progress)
        return out
    }

    fun patchExternal(input: File, patches: List<PatchType>, progress: Progress): File {
        require(input.extension.equals("apk", true)) {
            "V8 currently accepts APK input. XAPK analysis can be added through the same pipeline."
        }
        val out = File(StorageUtils.getPatchedDir(),
            input.nameWithoutExtension + "_azluk_v8.apk")
        patchToDisk(input, out, patches, progress)
        return out
    }

    private fun patchToDisk(
        input: File,
        out: File,
        patches: List<PatchType>,
        progress: Progress
    ) {
        require(input.isFile) { "Input APK does not exist" }
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, "${out.name}.unsigned")

        progress.on("V8 engine: opening ${input.name} (${fmtSize(input.length())})")
        val findings = inspectFile(input)
        progress.on("Analysis: ${findings.size} security-sensitive references reported")

        ZipInputStream(BufferedInputStream(FileInputStream(input), BUF)).use { zi ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), BUF)).use { zo ->
                var e = zi.nextEntry
                while (e != null) {
                    val name = e.name

                    if (name.startsWith("META-INF/") && isSignatureEntry(name)) {
                        progress.on("Removing stale signature metadata: $name")
                        zi.closeEntry()
                        e = zi.nextEntry
                        continue
                    }

                    if (name.endsWith(".dex")) {
                        val data = zi.readBytes()
                        zo.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                        zo.write(data)
                        zo.closeEntry()
                    } else {
                        val stored = name == "resources.arsc" || name.endsWith(".so")
                        if (stored) {
                            val data = zi.readBytes()
                            val crc = CRC32().also { it.update(data) }.value
                            zo.putNextEntry(ZipEntry(name).apply {
                                method = ZipEntry.STORED
                                size = data.size.toLong()
                                compressedSize = data.size.toLong()
                                this.crc = crc
                            })
                            zo.write(data)
                        } else {
                            zo.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                            zi.copyTo(zo, BUF)
                        }
                        zo.closeEntry()
                    }

                    zi.closeEntry()
                    e = zi.nextEntry
                }
            }
        }

        progress.on("Repack complete")
        progress.on("Signing with the Azluk local development certificate…")
        ApkSignerV2.sign(tmp, out)
        tmp.delete()

        val digest = sha256(out)
        progress.on("SHA-256: $digest")
        progress.on("✓ AzlukPatcher V8 output ready: ${out.name}")
    }

    private fun isSignatureEntry(name: String): Boolean =
        SIGNATURE_NAMES.contains(name.uppercase()) ||
            (name.startsWith("META-INF/") &&
                (name.endsWith(".SF", true) || name.endsWith(".RSA", true) ||
                 name.endsWith(".DSA", true) || name.endsWith(".EC", true)))

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUF).use { input ->
            val b = ByteArray(BUF)
            var n = input.read(b)
            while (n > 0) {
                md.update(b, 0, n)
                n = input.read(b)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun scanFile(file: File): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        ZipInputStream(BufferedInputStream(FileInputStream(file), BUF)).use { zis ->
            var e = zis.nextEntry
            var dexIndex = 0
            while (e != null) {
                if (e.name.endsWith(".dex")) {
                    val data = zis.readBytes()
                    val text = String(data, Charsets.ISO_8859_1)
                    FINDINGS.forEachIndexed { idx, pair ->
                        val off = text.indexOf(pair.first, ignoreCase = true)
                        if (off >= 0) {
                            results += ScanResult(
                                patchType = "REPACK_VERIFY",
                                desc = pair.second,
                                dexIndex = dexIndex,
                                offset = off
                            )
                        }
                    }
                    dexIndex++
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return results
    }

    fun interface Progress { fun on(msg: String) }

    private fun fmtSize(v: Long): String = when {
        v < 1024 -> "$v B"
        v < 1024 * 1024 -> "%.1f KB".format(v / 1024f)
        v < 1024L * 1024 * 1024 -> "%.1f MB".format(v / (1024f * 1024))
        else -> "%.2f GB".format(v / (1024f * 1024 * 1024))
    }
}
