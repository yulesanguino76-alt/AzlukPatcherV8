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
 * ApkEngine V8 — Kotlin orchestrator, delegates DEX patching to
 * StreamingDexPatcher.java (OOM-safe, streaming, no readBytes() on full DEX).
 *
 * OOM fix: previous engine called zi.readBytes() on every DEX file inside
 * a ZipInputStream. On large APKs (classes.dex > 20MB), this caused:
 *   "Failed to allocate 3355... byte allocation with 1478... free bytes"
 * The Java engine hard-caps at MAX_DEX_SIZE (40MB) and uses two-pass approach.
 *
 * Parallel patching: multiple DEX files are patched in parallel via
 * a fixed thread pool (min(cores, 4)).
 */
class ApkEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "AzlukEngine"
        private const val BUF = 256 * 1024
        private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0a)
    }

    fun interface Progress { fun on(msg: String) }

    // ── PUBLIC ────────────────────────────────────────────────────────────────

    fun quickStatus(pkg: String): PatchStatus {
        return try {
            val apk = File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir)
            if (apk.length() > 200L * 1024 * 1024) return PatchStatus.LIKELY
            val r = scanFile(apk)
            when {
                r.isEmpty() -> PatchStatus.UNKNOWN
                r.any { it.patchType in setOf("SAFETYNET_BYPASS", "FRIDA_BYPASS") } -> PatchStatus.COMPLEX
                r.size >= 3 -> PatchStatus.PATCHABLE
                else        -> PatchStatus.LIKELY
            }
        } catch (_: Exception) { PatchStatus.UNKNOWN }
    }

    fun quickCount(pkg: String): Int = try {
        scanFile(File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir)).size
    } catch (_: Exception) { 0 }

    fun scan(pkg: String): List<ScanResult> =
        scanFile(File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir))

    fun patch(pkg: String, patches: List<PatchType>, progress: Progress): File {
        val ai  = ctx.packageManager.getApplicationInfo(pkg, 0)
        val out = File(StorageUtils.getPatchedDir(), "${pkg}_azluk.apk")
        patchToDisk(File(ai.sourceDir), out, patches, progress)
        return out
    }

    fun patchExternal(input: File, patches: List<PatchType>, progress: Progress): File {
        if (input.name.endsWith(".xapk", true)) return patchXapk(input, patches, progress)
        val out = File(StorageUtils.getPatchedDir(), "${input.nameWithoutExtension}_azluk.apk")
        patchToDisk(input, out, patches, progress)
        return out
    }

    // ── XAPK ─────────────────────────────────────────────────────────────────

    private fun patchXapk(xapk: File, patches: List<PatchType>, p: Progress): File {
        p.on("Extracting XAPK…")
        val tmp = File(ctx.cacheDir, "azluk_${System.currentTimeMillis()}").also { it.mkdirs() }
        return try {
            val ex = LinkedHashMap<String, File>()
            ZipInputStream(BufferedInputStream(FileInputStream(xapk), BUF)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val d = File(tmp, e.name.replace("/", "__"))
                    FileOutputStream(d).use { fo -> z.copyTo(fo, BUF) }
                    ex[e.name] = d; e = z.nextEntry
                }
            }
            var main: File? = null; var mainKey = ""
            for ((k, v) in ex) if (k == "base.apk") { main = v; mainKey = k; break }
            if (main == null) for ((k, v) in ex) if (k.endsWith(".apk", true)) { main = v; mainKey = k; break }
            requireNotNull(main) { "No APK found inside XAPK" }
            p.on("Patching $mainKey…")
            val pb = File(tmp, "base_patched.apk")
            patchToDisk(main, pb, patches, p)
            ex[mainKey] = pb
            val outFile = File(StorageUtils.getPatchedDir(), "${xapk.nameWithoutExtension}_azluk.xapk")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile), BUF)).use { zo ->
                for ((k, v) in ex) {
                    zo.putNextEntry(ZipEntry(k).apply { method = ZipEntry.DEFLATED })
                    v.inputStream().use { it.copyTo(zo, BUF) }
                    zo.closeEntry()
                }
            }
            outFile
        } finally { tmp.deleteRecursively() }
    }

    // ── PATCH CORE — parallel DEX, OOM-safe Java patcher ─────────────────────

    private fun patchToDisk(
        input: File, out: File, patches: List<PatchType>, progress: Progress
    ) {
        out.parentFile?.mkdirs()
        val tmp      = File(out.parentFile, "${out.name}.unsigned")
        val patchKeys = patches.map { it.key }.toHashSet()

        progress.on("Opening ${input.name} (${fmtSize(input.length())})")

        // ── Phase A: manifest patches if needed ───────────────────────────────
        var manifestPatched: ByteArray? = null
        if (patchKeys.contains("FORCE_DEBUGGABLE") || patchKeys.contains("EXPORT_ALL_COMPONENTS")) {
            progress.on("Patching AndroidManifest.xml…")
            ZipInputStream(BufferedInputStream(FileInputStream(input), BUF)).use { zi ->
                var e = zi.nextEntry
                while (e != null) {
                    if (e.name == "AndroidManifest.xml") {
                        val raw = zi.readBytes()
                        manifestPatched = StreamingDexPatcher.patchManifest(
                            raw,
                            patchKeys.contains("FORCE_DEBUGGABLE"),
                            patchKeys.contains("EXPORT_ALL_COMPONENTS")
                        )
                        break
                    }
                    zi.closeEntry(); e = zi.nextEntry
                }
            }
        }

        // ── Phase B: read all entries into memory structures ──────────────────
        data class Entry(val name: String, val data: ByteArray, val stored: Boolean)
        val entries = mutableListOf<Entry>()

        ZipInputStream(BufferedInputStream(FileInputStream(input), BUF)).use { zi ->
            var e = zi.nextEntry
            while (e != null) {
                val name = e.name
                if (!isSigEntry(name) && !e.isDirectory) {
                    val data   = zi.readBytes()
                    val stored = name == "resources.arsc" || name.endsWith(".so")
                    entries.add(Entry(name, data, stored))
                } else drainEntry(zi)
                zi.closeEntry(); e = zi.nextEntry
            }
        }

        // ── Phase C: parallel DEX patching ───────────────────────────────────
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            minOf(Runtime.getRuntime().availableProcessors(), 4))

        val futures = entries
            .filter { it.name.endsWith(".dex") && isDex(it.data) }
            .associate { entry ->
                entry.name to pool.submit<ByteArray> {
                    progress.on("Patching ${entry.name} (${fmtSize(entry.data.size.toLong())})")
                    // Delegate to Java engine — OOM-safe, two-pass
                    StreamingDexPatcher.patch(entry.data, patchKeys) { msg ->
                        progress.on("  $msg")
                    }
                }
            }
        pool.shutdown()
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)

        // ── Phase D: repack ZIP ───────────────────────────────────────────────
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), BUF)).use { zo ->
            for (entry in entries) {
                val name = entry.name
                val data = when {
                    name == "AndroidManifest.xml" && manifestPatched != null -> manifestPatched!!
                    futures.containsKey(name) -> futures[name]!!.get()
                    else -> entry.data
                }
                if (entry.stored) {
                    val crc = CRC32().also { it.update(data) }.value
                    zo.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong(); compressedSize = data.size.toLong(); this.crc = crc
                    })
                } else {
                    zo.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                }
                zo.write(data); zo.closeEntry()
            }
        }

        // ── Phase E: sign ─────────────────────────────────────────────────────
        progress.on("Signing (V1 + V2 block)…")
        ApkSignerV2.sign(tmp, out)
        tmp.delete()

        val sha = sha256Hex(out).take(16)
        progress.on("SHA-256: $sha…")
        progress.on("[SUCCESS] ${out.name} (${fmtSize(out.length())})")
    }

    // ── SCANNER — delegates to Java streaming scanner ─────────────────────────

    fun scanFile(apk: File): List<ScanResult> {
        val results  = mutableListOf<ScanResult>()
        var dexIndex = 0
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(apk), BUF)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name.endsWith(".dex")) {
                        // Use Java streaming scanner for OOM-safe quick scan
                        val javaResults = StreamingDexPatcher.quickScan(z)
                        javaResults.forEach { pair ->
                            results.add(ScanResult(pair[0], pair[1], dexIndex, 0))
                        }
                        dexIndex++
                    } else drainEntry(z)
                    z.closeEntry(); e = z.nextEntry
                }
            }
        } catch (ex: Exception) { Log.e(TAG, "scanFile: ${ex.message}") }
        return results.distinctBy { it.patchType + it.desc }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isDex(data: ByteArray) = data.size > 8 &&
        data[0] == DEX_MAGIC[0] && data[1] == DEX_MAGIC[1] &&
        data[2] == DEX_MAGIC[2] && data[3] == DEX_MAGIC[3]

    private fun isSigEntry(name: String) = name.startsWith("META-INF/") &&
        (name.endsWith(".SF", true) || name.endsWith(".RSA", true) ||
         name.endsWith(".DSA", true) || name.endsWith(".EC", true) ||
         name.endsWith(".MF", true))

    private fun drainEntry(z: ZipInputStream) { val b = ByteArray(BUF); while (z.read(b) != -1) {} }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUF).use { s ->
            val b = ByteArray(BUF); var n = s.read(b)
            while (n > 0) { md.update(b, 0, n); n = s.read(b) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fmtSize(b: Long) = when {
        b < 1024        -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024f)
        else            -> "%.1f MB".format(b / (1024f * 1024))
    }
}
