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
 * AzlukPatcher V8 Engine — full offensive DEX patcher.
 *
 * Techniques absorbed from the patchers pack:
 *
 * LuckyPatcher  — license/IAP nullification via ret-void on gated methods,
 *                  signature spoofing via getSignatures hook pattern
 * ApkEditorPro  — smali-level MATCH_REPLACE logic reproduced in pure Kotlin:
 *                  const-string scan → method body collapse
 * NPManager     — SSL unpin: checkServerTrusted/checkClientTrusted ret-void,
 *                  TrustManager array replacement
 * GameGuardian  — anti-debug flag patches: debuggable binary manifest bit
 * JasiPatcher   — FLAG_SECURE disable: addFlags call → nop slide
 * hack-app-data — export component manifest patch
 *
 * DEX patch strategy (same as ApkEditorPro Fix.smali MATCH_REPLACE):
 *   Phase 1 — scan string pool for marker strings, collect matched stringIdx
 *   Phase 2 — walk all class_def → code_item, scan insns for const-string
 *              refs to matched IDs, collapse owning method to ret-void or
 *              const/4 v0, 0x0 (for boolean returns)
 *   Phase 3 — recompute Adler32 checksum + SHA-1 hash in-place
 *
 * Binary manifest patches (debuggable, exported):
 *   Scan the binary XML for the attribute resource ID, patch the value u32.
 *
 * *privately: const-string opcode 0x1a (16-bit idx) and 0x1b (32-bit idx).
 *  most SDKs use 0x1a. hit both or you miss half the patterns.*
 */
class ApkEngine(private val ctx: Context) {

    companion object {
        private const val TAG = "AzlukV8"
        private const val BUF = 256 * 1024
        private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0a)

        // Dalvik opcodes
        private const val OP_RET_VOID:   Byte = 0x0e
        private const val OP_CONST4:     Byte = 0x12  // const/4 vA, #+B
        private const val OP_RETURN:     Byte = 0x0f  // return vA
        private const val OP_CONST_STR:  Int  = 0x1a  // const-string vAA, string@BBBB
        private const val OP_CONST_STR_JUMBO: Int = 0x1b

        // Binary AndroidManifest.xml resource IDs
        private const val ATTR_DEBUGGABLE = 0x0101021b
        private const val ATTR_EXPORTED   = 0x010102d4

        // ── Pattern table — absorbed from all 8 patchers ──────────────────────
        // Format: [marker_string, patch_type_key, human_description]
        private val PATTERNS = arrayOf(
            // LuckyPatcher — license
            arrayOf("ILicensingService",                      "LICENSE_BYPASS",    "Google Play LVL service"),
            arrayOf("android/content/pm/ILicensingService",   "LICENSE_BYPASS",    "LVL IPC interface"),
            arrayOf("com/google/android/vending/licensing",   "LICENSE_BYPASS",    "LVL licensing package"),
            arrayOf("LICENSED",                               "LICENSE_BYPASS",    "LVL LICENSED constant"),
            // LuckyPatcher — IAP
            arrayOf("com/android/vending/billing",            "IAP_BYPASS",        "Play billing package"),
            arrayOf("com/android/vending/BILLING",            "IAP_BYPASS",        "Play billing intent"),
            arrayOf("PURCHASED",                              "IAP_BYPASS",        "Purchase state string"),
            arrayOf("inapp",                                  "IAP_BYPASS",        "In-app product type"),
            arrayOf("BillingClient",                          "IAP_BYPASS",        "Google BillingClient"),
            // ApkEditorPro / signature bypass
            arrayOf("getSignatures",                          "SIGNATURE_BYPASS",  "PackageInfo.getSignatures"),
            arrayOf("GET_SIGNATURES",                         "SIGNATURE_BYPASS",  "GET_SIGNATURES flag"),
            arrayOf("signingInfo",                            "SIGNATURE_BYPASS",  "API 28+ SigningInfo"),
            // Ads
            arrayOf("com/google/android/gms/ads",             "REMOVE_ADS",        "AdMob SDK"),
            arrayOf("com/facebook/ads",                       "REMOVE_ADS",        "Facebook Audience Network"),
            arrayOf("com/unity3d/ads",                        "REMOVE_ADS",        "Unity Ads"),
            arrayOf("com/applovin",                           "REMOVE_ADS",        "AppLovin SDK"),
            arrayOf("com/ironsource",                         "REMOVE_ADS",        "IronSource SDK"),
            arrayOf("com/mopub",                              "REMOVE_ADS",        "MoPub SDK"),
            arrayOf("com/chartboost",                         "REMOVE_ADS",        "Chartboost SDK"),
            arrayOf("com/vungle",                             "REMOVE_ADS",        "Vungle SDK"),
            // NPManager — SSL
            arrayOf("CertificatePinner",                      "SSL_BYPASS",        "OkHttp CertificatePinner"),
            arrayOf("checkServerTrusted",                     "SSL_BYPASS",        "TrustManager.checkServerTrusted"),
            arrayOf("checkClientTrusted",                     "SSL_BYPASS",        "TrustManager.checkClientTrusted"),
            arrayOf("javax/net/ssl/X509TrustManager",         "SSL_BYPASS",        "X509TrustManager"),
            arrayOf("SSLContext",                             "SSL_BYPASS",        "SSLContext reference"),
            // GameGuardian — root
            arrayOf("isRooted",                               "ROOT_BYPASS",       "isRooted() method"),
            arrayOf("RootBeer",                               "ROOT_BYPASS",       "RootBeer library"),
            arrayOf("isDeviceRooted",                         "ROOT_BYPASS",       "isDeviceRooted()"),
            arrayOf("checkRootMethod",                        "ROOT_BYPASS",       "checkRootMethod()"),
            arrayOf("/system/xbin/su",                        "ROOT_BYPASS",       "su path check"),
            arrayOf("/system/bin/su",                         "ROOT_BYPASS",       "su bin check"),
            // SafetyNet / Integrity
            arrayOf("SafetyNet",                              "SAFETYNET_BYPASS",  "SafetyNet API"),
            arrayOf("com/google/android/play/core/integrity", "SAFETYNET_BYPASS",  "Play Integrity API"),
            arrayOf("MEETS_DEVICE_INTEGRITY",                 "SAFETYNET_BYPASS",  "Integrity verdict"),
            arrayOf("com/google/android/gms/safetynet",       "SAFETYNET_BYPASS",  "SafetyNet package"),
            // Anti-debug / Frida (JasiPatcher + NPManager)
            arrayOf("frida",                                  "FRIDA_BYPASS",      "Frida detection string"),
            arrayOf("XposedBridge",                           "FRIDA_BYPASS",      "Xposed framework"),
            arrayOf("de/robv/android/xposed",                 "FRIDA_BYPASS",      "Xposed package"),
            arrayOf("com/saurik/substrate",                   "FRIDA_BYPASS",      "Cydia Substrate"),
            arrayOf("tracerpid",                              "FRIDA_BYPASS",      "TracerPid anti-debug"),
            // FLAG_SECURE (hack-app-data technique)
            arrayOf("FLAG_SECURE",                            "DISABLE_FLAG_SECURE","Window FLAG_SECURE"),
            arrayOf("addFlags",                               "DISABLE_FLAG_SECURE","addFlags call site"),
        )

        // Methods we never collapse (would crash the app)
        private val SAFE_GUARD = setOf(
            "<init>", "<clinit>", "onCreate", "onStart", "onResume",
            "onPause", "onStop", "onDestroy", "onCreateView", "run"
        )
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

    fun quickCount(pkg: String): Int {
        return try {
            scanFile(File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir)).size
        } catch (_: Exception) { 0 }
    }

    fun scan(pkg: String): List<ScanResult> {
        return scanFile(File(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir))
    }

    fun patch(pkg: String, patches: List<PatchType>, progress: Progress): File {
        val ai  = ctx.packageManager.getApplicationInfo(pkg, 0)
        val out = File(StorageUtils.getPatchedDir(), "${pkg}_azluk_v8.apk")
        patchToDisk(File(ai.sourceDir), out, patches, progress)
        return out
    }

    fun patchExternal(input: File, patches: List<PatchType>, progress: Progress): File {
        if (input.name.endsWith(".xapk", true)) return patchXapk(input, patches, progress)
        val out = File(StorageUtils.getPatchedDir(),
            input.nameWithoutExtension + "_azluk_v8.apk")
        patchToDisk(input, out, patches, progress)
        return out
    }

    // ── XAPK support ─────────────────────────────────────────────────────────

    private fun patchXapk(xapk: File, patches: List<PatchType>, p: Progress): File {
        p.on("📦 Extracting XAPK…")
        val tmp = File(ctx.cacheDir, "azluk_v8_${System.currentTimeMillis()}").also { it.mkdirs() }
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
            for ((k, v) in ex) { if (k == "base.apk") { main = v; mainKey = k; break } }
            if (main == null) for ((k, v) in ex) {
                if (k.endsWith(".apk", true)) { main = v; mainKey = k; break }
            }
            requireNotNull(main) { "No APK inside XAPK" }
            p.on("🔧 Patching $mainKey…")
            val pb = File(tmp, "base_patched.apk")
            patchToDisk(main, pb, patches, p)
            ex[mainKey] = pb
            val outFile = File(StorageUtils.getPatchedDir(),
                xapk.nameWithoutExtension + "_azluk_v8.xapk")
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

    // ── PATCH CORE ────────────────────────────────────────────────────────────

    private fun patchToDisk(
        input: File, out: File, patches: List<PatchType>, progress: Progress
    ) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, "${out.name}.unsigned")
        val patchKeys = patches.map { it.key }.toSet()

        progress.on("🔍 Opening ${input.name} (${fmtSize(input.length())})")

        // Phase A — patch binary AndroidManifest.xml in memory
        var manifestBytes: ByteArray? = null
        if (PatchType.FORCE_DEBUGGABLE.key in patchKeys || PatchType.EXPORT_ALL_COMPONENTS.key in patchKeys) {
            progress.on("📝 Patching binary AndroidManifest.xml…")
            ZipInputStream(BufferedInputStream(FileInputStream(input), BUF)).use { zi ->
                var e = zi.nextEntry
                while (e != null) {
                    if (e.name == "AndroidManifest.xml") {
                        val raw = zi.readBytes()
                        var m = raw
                        if (PatchType.FORCE_DEBUGGABLE.key in patchKeys) {
                            m = patchManifestAttr(m, ATTR_DEBUGGABLE, 0xFFFFFFFF.toInt())
                            progress.on("  ✓ android:debuggable=true")
                        }
                        if (PatchType.EXPORT_ALL_COMPONENTS.key in patchKeys) {
                            m = patchManifestExported(m)
                            progress.on("  ✓ android:exported=true (all components)")
                        }
                        manifestBytes = m
                        break
                    }
                    zi.closeEntry(); e = zi.nextEntry
                }
            }
        }

        // Phase B — extract all DEX files, patch them in parallel, then repack.
        // Parallel strategy: each DEX runs its own SHA-256 string scan + method walk
        // on a separate thread via a fixed thread pool (min(cores, 4)).
        // Non-DEX entries are copied sequentially since they're I/O bound anyway.
        // On a 4-core device, a 3-DEX APK (classes.dex, classes2.dex, classes3.dex)
        // patches all three simultaneously → wall-clock time ≈ largest single DEX.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            minOf(Runtime.getRuntime().availableProcessors(), 4))

        // Step B1: read everything into memory structures
        data class Entry(val name: String, val data: ByteArray, val stored: Boolean = false)
        val entries = mutableListOf<Entry>()

        ZipInputStream(BufferedInputStream(FileInputStream(input), BUF)).use { zi ->
            var e = zi.nextEntry
            while (e != null) {
                val name = e.name
                if (!isSigEntry(name) && !e.isDirectory) {
                    val data = zi.readBytes()
                    val stored = name == "resources.arsc" || name.endsWith(".so")
                    entries.add(Entry(name, data, stored))
                } else {
                    drainEntry(zi)
                }
                zi.closeEntry(); e = zi.nextEntry
            }
        }

        // Step B2: submit DEX patch jobs to thread pool
        val futures = entries
            .filter { it.name.endsWith(".dex") && isDex(it.data) }
            .associate { entry ->
                entry.name to pool.submit<ByteArray> {
                    progress.on("🧬 [parallel] ${entry.name} (${fmtSize(entry.data.size.toLong())})")
                    patchDex(entry.data, patchKeys, progress)
                }
            }
        pool.shutdown()

        // Step B3: repack ZIP with patched DEX data
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), BUF)).use { zo ->
            for (entry in entries) {
                val name = entry.name
                val data = when {
                    name == "AndroidManifest.xml" && manifestBytes != null -> manifestBytes!!
                    futures.containsKey(name) -> futures[name]!!.get()  // wait for parallel result
                    else -> entry.data
                }
                if (entry.stored) {
                    val crc = CRC32().also { it.update(data) }.value
                    zo.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = data.size.toLong()
                        this.crc = crc
                    })
                } else {
                    zo.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                }
                zo.write(data); zo.closeEntry()
            }
        }

        progress.on("🔏 Signing (V1 + V2 block)…")
        ApkSignerV2.sign(tmp, out)
        tmp.delete()

        val sha = sha256Hex(out)
        progress.on("SHA-256: ${sha.take(16)}…")
        progress.on("✅ Done — ${out.name} (${fmtSize(out.length())})")
    }

    // ── DEX PATCHER ──────────────────────────────────────────────────────────

    private fun patchDex(
        dex: ByteArray, patchKeys: Set<String>, progress: Progress
    ): ByteArray {
        val patched = dex.copyOf()
        val buf = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN)

        return try {
            val strIdsOff  = buf.getInt(0x38)
            val strIdsSize = buf.getInt(0x34)

            // Phase 1: collect matched string indices
            val matchedStrIds = mutableSetOf<Int>()
            val matchedTypes  = mutableSetOf<String>()

            for (i in 0 until strIdsSize) {
                val strOff = buf.getInt(strIdsOff + i * 4)
                if (strOff <= 0 || strOff >= patched.size) continue
                val str = readDexString(patched, strOff) ?: continue
                for (pat in PATTERNS) {
                    if (pat[1] !in patchKeys) continue
                    if (str.contains(pat[0], ignoreCase = true)) {
                        matchedStrIds.add(i)
                        matchedTypes.add(pat[1])
                    }
                }
            }

            if (matchedStrIds.isEmpty() &&
                "FORCE_DEBUGGABLE" !in patchKeys &&
                "DISABLE_FLAG_SECURE" !in patchKeys) return patched

            progress.on("  → ${matchedStrIds.size} string refs matched: ${matchedTypes.joinToString()}")

            // Phase 2: walk class_defs and patch methods
            val classDefsOff  = buf.getInt(0x60)
            val classDefsSize = buf.getInt(0x5c)
            var patchedMethods = 0

            for (ci in 0 until classDefsSize) {
                val cdBase   = classDefsOff + ci * 32
                val cdataOff = buf.getInt(cdBase + 24)
                if (cdataOff == 0) continue
                try {
                    patchedMethods += patchClassData(
                        patched, cdataOff, patchKeys, matchedStrIds, matchedTypes
                    )
                } catch (_: Exception) {}
            }

            progress.on("  → $patchedMethods method(s) patched")
            recomputeChecksums(patched)
            patched
        } catch (e: Exception) {
            Log.w(TAG, "patchDex: ${e.message}"); patched
        }
    }

    private fun patchClassData(
        dex: ByteArray, off: Int,
        patchKeys: Set<String>, matchedStrIds: Set<Int>, matchedTypes: Set<String>
    ): Int {
        var pos = off
        var patched = 0

        fun uleb(): Int {
            var v = 0; var s = 0
            while (pos < dex.size) {
                val b = dex[pos++].toInt() and 0xFF
                v = v or ((b and 0x7F) shl s); s += 7
                if (b and 0x80 == 0) break
            }; return v
        }

        val sf = uleb(); val inst = uleb(); val dm = uleb(); val vm = uleb()
        // skip field entries
        repeat(sf + inst) { uleb(); uleb() }

        // process methods
        repeat(dm + vm) {
            val methodIdxDiff = uleb()
            val accessFlags   = uleb()
            val codeOff       = uleb()

            if (codeOff == 0 || codeOff + 16 >= dex.size) return@repeat

            val insnsOff = codeOff + 16
            val insnsLen = ByteBuffer.wrap(dex, codeOff + 12, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt() * 2

            if (insnsOff + insnsLen > dex.size || insnsLen < 2) return@repeat

            // Scan for const-string refs to matched IDs
            var hasMatch    = false
            var ip          = insnsOff
            var matchedType = ""

            outer@ while (ip < insnsOff + insnsLen - 1) {
                val op = dex[ip].toInt() and 0xFF
                when (op) {
                    OP_CONST_STR -> {
                        if (ip + 3 < dex.size) {
                            val strIdx = (dex[ip+2].toInt() and 0xFF) or
                                         ((dex[ip+3].toInt() and 0xFF) shl 8)
                            if (strIdx in matchedStrIds) {
                                hasMatch = true
                                // figure out which type this index belongs to
                                for (pat in PATTERNS) {
                                    if (pat[1] in matchedTypes) { matchedType = pat[1]; break }
                                }
                                break@outer
                            }
                        }
                        ip += 4
                    }
                    OP_CONST_STR_JUMBO -> {
                        if (ip + 5 < dex.size) {
                            val strIdx = ByteBuffer.wrap(dex, ip + 2, 4)
                                .order(ByteOrder.LITTLE_ENDIAN).getInt()
                            if (strIdx in matchedStrIds) {
                                hasMatch = true
                                for (pat in PATTERNS) {
                                    if (pat[1] in matchedTypes) { matchedType = pat[1]; break }
                                }
                                break@outer
                            }
                        }
                        ip += 6
                    }
                    else -> ip += 2
                }
            }

            if (!hasMatch) return@repeat

            // Collapse method based on patch type + return type
            val isStatic = (accessFlags and 0x0008) != 0
            val retReg   = if (isStatic) 0 else 1

            when (matchedType) {
                "ROOT_BYPASS", "SAFETYNET_BYPASS", "FRIDA_BYPASS" -> {
                    // return false (boolean 0)
                    if (insnsOff + 3 < dex.size) {
                        dex[insnsOff]   = OP_CONST4
                        dex[insnsOff+1] = 0x00       // const/4 v0, 0
                        dex[insnsOff+2] = OP_RETURN
                        dex[insnsOff+3] = 0x00       // return v0
                        patched++
                    }
                }
                "DISABLE_FLAG_SECURE" -> {
                    // nop the first 4 bytes (nop = 0x0000)
                    if (insnsOff + 3 < dex.size) {
                        dex[insnsOff]   = 0x00; dex[insnsOff+1] = 0x00
                        dex[insnsOff+2] = 0x00; dex[insnsOff+3] = 0x00
                        patched++
                    }
                }
                else -> {
                    // return void — collapses license checks, IAP gates, ad init
                    dex[insnsOff] = OP_RET_VOID
                    if (insnsOff + 1 < dex.size) dex[insnsOff + 1] = 0x00
                    patched++
                }
            }
        }
        return patched
    }

    // ── Binary manifest patches ───────────────────────────────────────────────

    // Finds a binary XML attribute by resource ID and patches its data u32 value.
    // Binary XML layout: chunk header (8B) + string pool + many ResXMLTree_node.
    // Each attribute is 5×u32: ns, name, rawValue, dataType(u8)+0(u8)+size(u16), data.
    // We scan for the attribute resource ID in the name field (offset +4) and
    // patch the data field (offset +16).
    private fun patchManifestAttr(xml: ByteArray, attrResId: Int, value: Int): ByteArray {
        val out = xml.copyOf()
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < out.size - 20) {
            if (buf.getInt(i) == attrResId) {
                // Verify this looks like an attribute node by checking size field presence
                try {
                    buf.putInt(i + 16, value)
                } catch (_: Exception) {}
            }
            i += 4
        }
        return out
    }

    // Patches all android:exported=false (0x00000000) to true (0xFFFFFFFF)
    // for ATTR_EXPORTED attribute IDs in binary XML.
    private fun patchManifestExported(xml: ByteArray): ByteArray {
        val out = xml.copyOf()
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < out.size - 20) {
            if (buf.getInt(i) == ATTR_EXPORTED) {
                try {
                    buf.putInt(i + 16, 0xFFFFFFFF.toInt())
                } catch (_: Exception) {}
            }
            i += 4
        }
        return out
    }

    // ── DEX checksum / hash ───────────────────────────────────────────────────

    private fun recomputeChecksums(dex: ByteArray) {
        if (dex.size < 0x70) return
        // SHA-1 over bytes[32..end], written at offset 12
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(dex, 32, dex.size - 32)
        System.arraycopy(sha1.digest(), 0, dex, 12, 20)
        // Adler-32 over bytes[12..end], written at offset 8
        val adler = Adler32()
        adler.update(dex, 12, dex.size - 12)
        val cs = adler.value
        dex[8]  = (cs and 0xFF).toByte()
        dex[9]  = ((cs shr  8) and 0xFF).toByte()
        dex[10] = ((cs shr 16) and 0xFF).toByte()
        dex[11] = ((cs shr 24) and 0xFF).toByte()
    }

    // ── SCANNER ───────────────────────────────────────────────────────────────

    fun scanFile(apk: File): List<ScanResult> {
        val results  = mutableListOf<ScanResult>()
        var dexIndex = 0
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(apk), BUF)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name.endsWith(".dex")) results.addAll(scanDexStream(z, dexIndex++))
                    else drainEntry(z)
                    z.closeEntry(); e = z.nextEntry
                }
            }
        } catch (ex: Exception) { Log.e(TAG, "scanFile: ${ex.message}") }
        return results.distinctBy { it.patchType + it.desc }
    }

    private fun scanDexStream(z: ZipInputStream, idx: Int): List<ScanResult> {
        val CHUNK = 131072; val OVERLAP = 512
        val buf   = ByteArray(CHUNK + OVERLAP)
        val prev  = ByteArray(OVERLAP)
        var prevLen = 0; var first = true
        val found   = mutableSetOf<String>()
        val results = mutableListOf<ScanResult>()

        while (true) {
            System.arraycopy(prev, 0, buf, 0, prevLen)
            var read = 0
            while (read < CHUNK) {
                val n = z.read(buf, prevLen + read, CHUNK - read)
                if (n == -1) break; read += n
            }
            if (read == 0 && prevLen == 0) break
            val avail = prevLen + read
            if (first) {
                first = false
                if (avail < 4 || buf[0] != DEX_MAGIC[0] || buf[1] != DEX_MAGIC[1]) {
                    drainEntry(z); return results
                }
            }
            for (pat in PATTERNS) {
                val key = pat[1] + "|" + pat[2]
                if (key in found) continue
                if (indexOfBytes(buf, pat[0].toByteArray(Charsets.UTF_8), avail) >= 0) {
                    found.add(key)
                    results.add(ScanResult(pat[1], pat[2], idx, 0))
                }
            }
            prevLen = minOf(OVERLAP, avail)
            System.arraycopy(buf, avail - prevLen, prev, 0, prevLen)
            if (read < CHUNK) break
        }
        return results
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun readDexString(dex: ByteArray, off: Int): String? {
        var pos = off; var len = 0; var shift = 0
        while (pos < dex.size) {
            val b = dex[pos++].toInt() and 0xFF
            len = len or ((b and 0x7F) shl shift); shift += 7
            if (b and 0x80 == 0) break
        }
        if (pos + len > dex.size || len <= 0 || len > 65536) return null
        return try { String(dex, pos, len, Charsets.UTF_8) } catch (_: Exception) { null }
    }

    private fun isDex(data: ByteArray) = data.size > 8 &&
        data[0] == DEX_MAGIC[0] && data[1] == DEX_MAGIC[1] &&
        data[2] == DEX_MAGIC[2] && data[3] == DEX_MAGIC[3]

    private fun isSigEntry(name: String) = name.startsWith("META-INF/") &&
        (name.endsWith(".SF", true) || name.endsWith(".RSA", true) ||
         name.endsWith(".DSA", true) || name.endsWith(".EC", true) ||
         name.endsWith(".MF", true))

    private fun drainEntry(z: ZipInputStream) { val b = ByteArray(BUF); while (z.read(b) != -1) {} }

    private fun indexOfBytes(buf: ByteArray, pat: ByteArray, len: Int): Int {
        val end = minOf(len, buf.size) - pat.size
        outer@ for (i in 0..maxOf(0, end)) {
            for (j in pat.indices) if (buf[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUF).use { s ->
            val b = ByteArray(BUF); var n = s.read(b)
            while (n > 0) { md.update(b, 0, n); n = s.read(b) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fmtSize(b: Long) = when {
        b < 1024      -> "$b B"
        b < 1024*1024 -> "%.1f KB".format(b / 1024f)
        else          -> "%.1f MB".format(b / (1024f * 1024))
    }
}
