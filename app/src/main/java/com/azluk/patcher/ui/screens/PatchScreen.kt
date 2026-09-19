package com.azluk.patcher.ui.screens

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.azluk.patcher.core.*
import com.azluk.patcher.ui.theme.*
import com.azluk.patcher.viewmodel.AiDiagnosis
import com.azluk.patcher.viewmodel.PatchViewModel
import java.io.File
import java.io.FileInputStream

// ── Patch type icon mapping ───────────────────────────────────────────────────

private fun patchIcon(type: PatchType) = when (type) {
    PatchType.LICENSE_BYPASS      -> Icons.Default.VerifiedUser
    PatchType.IAP_BYPASS          -> Icons.Default.ShoppingCart
    PatchType.SIGNATURE_BYPASS    -> Icons.Default.Fingerprint
    PatchType.REMOVE_ADS          -> Icons.Default.Block
    PatchType.SSL_BYPASS          -> Icons.Default.LockOpen
    PatchType.ROOT_BYPASS         -> Icons.Default.Security
    PatchType.SAFETYNET_BYPASS    -> Icons.Default.Shield
    PatchType.FRIDA_BYPASS        -> Icons.Default.BugReport
    PatchType.FORCE_DEBUGGABLE    -> Icons.Default.Code
    PatchType.DISABLE_FLAG_SECURE -> Icons.Default.ScreenshotMonitor
    PatchType.EXPORT_ALL_COMPONENTS -> Icons.Default.OpenInNew
    PatchType.OPTIMIZE_ZIP        -> Icons.Default.Compress
}

private fun patchColor(type: PatchType) = when (type.category) {
    "bypass"   -> AzlukBlue
    "ads"      -> AzlukWarning
    "security" -> AzlukError
    "dev"      -> AzlukCyan
    else       -> AzlukOnSurface
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchScreen(pkg: String, navController: NavController, vm: PatchViewModel = viewModel()) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val ctx      = LocalContext.current
    val logState = rememberLazyListState()

    LaunchedEffect(pkg) {
        if (state.scanResults.isEmpty() && !state.isScanning) vm.scan(pkg)
    }

    val logLines = when (val ps = state.patchState) {
        is PatchState.Running -> ps.log
        is PatchState.Success -> ps.log
        is PatchState.Failure -> ps.log
        else -> emptyList()
    }
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) logState.animateScrollToItem(logLines.size - 1)
    }

    // ── Install broadcast receiver — fixed FLAG_UPDATE_CURRENT ────────────────
    DisposableEffect(Unit) {
        val filter   = IntentFilter("com.azluk.patcher.INSTALL_RESULT_LOCAL")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                val msg    = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    confirm?.let { ctx.startActivity(it) }
                } else {
                    vm.onInstallResult(status, msg)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            ctx.registerReceiver(receiver, filter)
        onDispose { ctx.unregisterReceiver(receiver) }
    }

    val isRunning = state.patchState is PatchState.Running
    val isDone    = state.patchState is PatchState.Success || state.patchState is PatchState.Failure

    Scaffold(
        containerColor = AzlukBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Patch", color = AzlukOnBg, fontWeight = FontWeight.SemiBold)
                        Text(pkg, color = AzlukOnSurface, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = AzlukOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AzlukSurface)
            )
        },
        bottomBar = {
            if (!isDone && !isRunning) {
                Surface(color = AzlukSurface, tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick  = { vm.scan(pkg) },
                            enabled  = !state.isScanning,
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(12.dp),
                            border   = BorderStroke(1.dp, AzlukSurfaceVar)
                        ) {
                            if (state.isScanning)
                                CircularProgressIndicator(Modifier.size(14.dp), color = AzlukBlue, strokeWidth = 2.dp)
                            else
                                Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = AzlukBlue)
                            Spacer(Modifier.width(6.dp))
                            Text("Scan", color = AzlukBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick  = { vm.patch(pkg) },
                            enabled  = state.selectedPatches.isNotEmpty(),
                            modifier = Modifier.weight(2f),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = AzlukBlue)
                        ) {
                            Icon(Icons.Default.Build, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Patch (${state.selectedPatches.size})", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state          = logState,
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Scan result banner ────────────────────────────────────────────
            if (state.scanResults.isNotEmpty()) {
                item {
                    val detected = state.scanResults.mapNotNull {
                        runCatching { PatchType.valueOf(it.patchType) }.getOrNull()
                    }.toSet()
                    Surface(
                        color  = AzlukBlue.copy(.08f),
                        shape  = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AzlukBlue.copy(.2f))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Analytics, null, tint = AzlukBlue, modifier = Modifier.size(18.dp))
                                Text("${state.scanResults.size} patchable targets detected",
                                    color = AzlukBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Text(detected.joinToString(" · ") { it.displayName },
                                color = AzlukOnSurface, fontSize = 11.sp)
                        }
                    }
                }
            }

            // ── Categories ────────────────────────────────────────────────────
            val categories = PatchType.values().groupBy { it.category }
            val catLabels = mapOf(
                "bypass"   to "🔓 Bypass",
                "ads"      to "🚫 Ads",
                "security" to "🛡 Anti-Detection",
                "dev"      to "🔧 Developer",
                "util"     to "⚡ Utility"
            )
            val detectedTypes = state.scanResults
                .mapNotNull { runCatching { PatchType.valueOf(it.patchType) }.getOrNull() }.toSet()

            for ((cat, types) in categories) {
                item {
                    Text(catLabels[cat] ?: cat.uppercase(), color = AzlukOnSurface,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                }
                items(types) { type ->
                    val selected    = type in state.selectedPatches
                    val wasDetected = type in detectedTypes
                    val enabled     = !isRunning && !isDone
                    val tint        = patchColor(type)

                    Surface(
                        color  = if (selected) tint.copy(.08f) else AzlukSurface,
                        shape  = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (selected) tint.copy(.35f) else AzlukSurfaceVar),
                        modifier = Modifier.fillMaxWidth().clickable(enabled) { vm.togglePatch(type) }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Category-colored icon
                            Surface(
                                color = tint.copy(.12f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(patchIcon(type), null, tint = tint, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(type.displayName, color = AzlukOnBg,
                                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    if (wasDetected) {
                                        Surface(color = AzlukSuccess.copy(.12f),
                                            shape = RoundedCornerShape(4.dp)) {
                                            Text("FOUND", color = AzlukSuccess,
                                                fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Text(type.description, color = AzlukOnSurface, fontSize = 11.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Checkbox(
                                checked        = selected,
                                onCheckedChange = { if (enabled) vm.togglePatch(type) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor   = tint,
                                    uncheckedColor = AzlukOnSurface.copy(.4f)
                                )
                            )
                        }
                    }
                }
            }

            // ── Running log ───────────────────────────────────────────────────
            if (isRunning) {
                item {
                    Surface(color = AzlukSurface, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp),
                                    color = AzlukBlue, strokeWidth = 2.dp)
                                Text("Patching…", color = AzlukBlue, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(8.dp))
                            logLines.takeLast(15).forEach { line ->
                                Text(line,
                                    color = when {
                                        line.startsWith("✅") || line.startsWith("✓") -> AzlukSuccess
                                        line.startsWith("✗")                         -> AzlukError
                                        line.contains("🔏") || line.contains("🧬")   -> AzlukCyan
                                        else                                         -> AzlukOnSurface
                                    },
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // ── Success ───────────────────────────────────────────────────────
            if (state.patchState is PatchState.Success) {
                val outputPath = (state.patchState as PatchState.Success).outputPath
                item {
                    Surface(
                        color  = AzlukSuccess.copy(.07f),
                        shape  = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, AzlukSuccess.copy(.3f))
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = AzlukSuccess, modifier = Modifier.size(22.dp))
                                Text("AzlukPatcher V8 — Patch Complete",
                                    color = AzlukSuccess, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Text(outputPath, color = AzlukOnSurface, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace)

                            var showLog by remember { mutableStateOf(false) }
                            TextButton(onClick = { showLog = !showLog }) {
                                Text(if (showLog) "Hide log ▲" else "Show full log ▼",
                                    color = AzlukBlue, fontSize = 11.sp)
                            }
                            AnimatedVisibility(showLog) {
                                Column {
                                    logLines.forEach { line ->
                                        Text(line, color = AzlukOnSurface, fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick  = { installApk(ctx, outputPath) },
                                    modifier = Modifier.weight(1f),
                                    colors   = ButtonDefaults.buttonColors(containerColor = AzlukBlue),
                                    shape    = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.InstallMobile, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Install", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick  = { vm.resetPatch() },
                                    modifier = Modifier.weight(1f),
                                    shape    = RoundedCornerShape(12.dp),
                                    border   = BorderStroke(1.dp, AzlukSurfaceVar)
                                ) { Text("Reset", color = AzlukOnSurface) }
                            }
                        }
                    }
                }
            }

            // ── Failure ───────────────────────────────────────────────────────
            if (state.patchState is PatchState.Failure) {
                val err = state.patchState as PatchState.Failure
                item {
                    Surface(color = AzlukError.copy(.07f), shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, AzlukError.copy(.25f))) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("❌ Patch failed", color = AzlukError, fontWeight = FontWeight.Bold)
                            Text(err.error, color = AzlukOnBg, fontSize = 12.sp)
                            Button(onClick = { vm.resetPatch() },
                                colors = ButtonDefaults.buttonColors(containerColor = AzlukBlue),
                                shape  = RoundedCornerShape(12.dp)) { Text("Try Again") }
                        }
                    }
                }
            }

            // ── Install result ────────────────────────────────────────────────
            when (val ist = state.installState) {
                is InstallState.Success -> item {
                    Surface(color = AzlukSuccess.copy(.07f), shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AzlukSuccess.copy(.25f))) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = AzlukSuccess, modifier = Modifier.size(20.dp))
                            Text("Installed successfully!", color = AzlukSuccess, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                is InstallState.Failure -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(color = AzlukError.copy(.07f), shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, AzlukError.copy(.25f))) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("❌ ${ist.code}", color = AzlukError,
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(ist.message, color = AzlukOnBg, fontSize = 12.sp)
                                Text(ist.description, color = AzlukOnSurface, fontSize = 11.sp)
                                if (ist.canRetry) {
                                    Spacer(Modifier.height(4.dp))
                                    Button(
                                        onClick  = { installApk(ctx, state.lastOutputPath) },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors   = ButtonDefaults.buttonColors(containerColor = AzlukBlue),
                                        shape    = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Retry Install")
                                    }
                                }
                            }
                        }
                        // AI diagnosis card
                        AiDiagnosisCard(
                            diagnosis = state.aiDiagnosis,
                            onDismiss = { vm.dismissAi() },
                            onRetry   = { vm.diagnoseWithAi(ist, state.lastOutputPath) }
                        )
                    }
                }
                else -> {}
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ── AI Diagnosis card ─────────────────────────────────────────────────────────

@Composable
fun AiDiagnosisCard(
    diagnosis: AiDiagnosis,
    onDismiss: () -> Unit,
    onRetry:   () -> Unit
) {
    AnimatedVisibility(
        visible = diagnosis.loading || diagnosis.suggestion.isNotEmpty() || diagnosis.error.isNotEmpty(),
        enter   = fadeIn() + expandVertically(),
        exit    = fadeOut() + shrinkVertically()
    ) {
        Surface(
            color  = AzlukSurfaceVar.copy(.6f),
            shape  = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, AzlukBlue.copy(.3f))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = AzlukBlue.copy(.15f), shape = RoundedCornerShape(6.dp)) {
                        Text("✦ AzlukAI", color = AzlukBlue, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Text("Install Diagnosis", color = AzlukOnBg,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (!diagnosis.loading) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, null, tint = AzlukOnSurface, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                when {
                    diagnosis.loading -> Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = AzlukBlue, strokeWidth = 2.dp)
                        Text("Analyzing error with AI…", color = AzlukOnSurface, fontSize = 12.sp)
                    }
                    diagnosis.suggestion.isNotEmpty() ->
                        Text(diagnosis.suggestion, color = AzlukOnBg, fontSize = 13.sp, lineHeight = 19.sp)
                    diagnosis.error.isNotEmpty() -> {
                        Text(diagnosis.error, color = AzlukError, fontSize = 12.sp)
                        TextButton(onClick = onRetry) {
                            Text("Retry", color = AzlukBlue, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Install — fixed FLAG_UPDATE_CURRENT for Android 12+ ──────────────────────

fun installApk(ctx: Context, path: String) {
    if (path.isEmpty()) { Toast.makeText(ctx, "No APK path", Toast.LENGTH_SHORT).show(); return }
    val file = File(path)
    if (!file.exists()) { Toast.makeText(ctx, "APK not found: $path", Toast.LENGTH_LONG).show(); return }
    try {
        val pi       = ctx.packageManager.packageInstaller
        val params   = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pi.createSession(params)
        val session   = pi.openSession(sessionId)
        FileInputStream(file).use { fis ->
            session.openWrite("base.apk", 0, file.length()).use { os ->
                fis.copyTo(os, 65536); session.fsync(os)
            }
        }
        val receiverIntent = Intent("com.azluk.patcher.INSTALL_RESULT").apply {
            setPackage(ctx.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getBroadcast(ctx, sessionId, receiverIntent, flags)
        session.commit(pending.intentSender)
        session.close()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
