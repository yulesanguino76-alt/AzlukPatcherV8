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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.azluk.patcher.core.*
import com.azluk.patcher.ui.theme.*
import com.azluk.patcher.viewmodel.PatchViewModel
import java.io.File
import java.io.FileInputStream

/**
 * PatchLogScreen — separate screen that shows after the patch options screen.
 * Shows live log, then install controls once patch is done.
 * The AI diagnosis runs silently in PatchViewModel.diagnoseWithAi() — no "AI" label here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchLogScreen(pkg: String, navController: NavController, vm: PatchViewModel = viewModel()) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val ctx      = LocalContext.current
    val logState = rememberLazyListState()

    val logLines = when (val ps = state.patchState) {
        is PatchState.Running -> ps.log
        is PatchState.Success -> ps.log
        is PatchState.Failure -> ps.log
        else -> emptyList()
    }

    // Auto-scroll log to bottom
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) logState.animateScrollToItem(logLines.size - 1)
    }

    // Kick patch if it hasn't started yet
    LaunchedEffect(Unit) {
        if (state.patchState is PatchState.Idle) vm.patch(pkg)
    }

    // Install result receiver
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
                } else vm.onInstallResult(status, msg)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            ctx.registerReceiver(receiver, filter)
        onDispose { ctx.unregisterReceiver(receiver) }
    }

    // Background patch started notification
    val isRunning = state.patchState is PatchState.Running
    val isSuccess = state.patchState is PatchState.Success
    val isFailure = state.patchState is PatchState.Failure

    Scaffold(
        containerColor = AzlukBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Patching", color = AzlukOnBg, fontWeight = FontWeight.SemiBold)
                        Text(pkg, color = AzlukOnSurface, fontSize = 10.sp, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!isRunning) navController.popBackStack()
                        // Don't allow back while running — patch is in background
                    }) {
                        Icon(
                            if (isRunning) Icons.Default.HourglassEmpty else Icons.Default.ArrowBack,
                            null, tint = if (isRunning) AzlukOnSurface.copy(.3f) else AzlukOnSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AzlukSurface)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Status header ─────────────────────────────────────────────────
            Surface(
                color  = when {
                    isRunning -> AzlukBlue.copy(.08f)
                    isSuccess -> AzlukSuccess.copy(.08f)
                    isFailure -> AzlukError.copy(.08f)
                    else      -> AzlukSurface
                },
                shape  = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, when {
                    isRunning -> AzlukBlue.copy(.25f)
                    isSuccess -> AzlukSuccess.copy(.25f)
                    isFailure -> AzlukError.copy(.25f)
                    else      -> AzlukSurfaceVar
                })
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when {
                        isRunning -> {
                            CircularProgressIndicator(Modifier.size(20.dp), color = AzlukBlue, strokeWidth = 2.dp)
                            Column {
                                Text("Patching in background…", color = AzlukBlue,
                                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("You can leave this screen — the patch will continue",
                                    color = AzlukOnSurface, fontSize = 11.sp)
                            }
                        }
                        isSuccess -> {
                            Icon(Icons.Default.CheckCircle, null, tint = AzlukSuccess, modifier = Modifier.size(20.dp))
                            Text("Patch complete", color = AzlukSuccess,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        isFailure -> {
                            Icon(Icons.Default.Error, null, tint = AzlukError, modifier = Modifier.size(20.dp))
                            Column {
                                Text("Patch failed", color = AzlukError,
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text((state.patchState as? PatchState.Failure)?.error ?: "",
                                    color = AzlukOnSurface, fontSize = 11.sp)
                            }
                        }
                        else -> {}
                    }
                }
            }

            // ── Log output ────────────────────────────────────────────────────
            Surface(
                color    = AzlukSurface,
                shape    = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Terminal, null, tint = AzlukOnSurface, modifier = Modifier.size(16.dp))
                        Text("Log", color = AzlukOnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(state = logState) {
                        items(logLines) { line ->
                            Text(
                                line,
                                color = when {
                                    line.startsWith("  [SUCCESS]") || line.contains("Done") -> AzlukSuccess
                                    line.startsWith("  [ERROR]")  || line.startsWith("Error") -> AzlukError
                                    line.contains("Signing")      || line.contains("DEX") -> AzlukCyan
                                    else -> AzlukOnSurface
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // ── Install controls — shown after patch done ─────────────────────
            if (isSuccess) {
                val outputPath = (state.patchState as PatchState.Success).outputPath

                // Output path display
                Surface(color = AzlukSurface, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.InsertDriveFile, null, tint = AzlukBlue, modifier = Modifier.size(18.dp))
                        Text(outputPath, color = AzlukOnSurface, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    }
                }

                // Install buttons
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // AzlukPatcher installer (AI-powered, shown as just "Install")
                    Button(
                        onClick  = { azlukInstall(ctx, File(outputPath), vm) },
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = AzlukBlue)
                    ) {
                        Icon(Icons.Default.InstallMobile, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Install", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    // System installer
                    OutlinedButton(
                        onClick  = { systemInstall(ctx, File(outputPath)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(14.dp),
                        border   = BorderStroke(1.dp, AzlukSurfaceVar)
                    ) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp), tint = AzlukOnSurface)
                        Spacer(Modifier.width(8.dp))
                        Text("System Installer", color = AzlukOnSurface, fontSize = 13.sp)
                    }
                }

                // Install result cards
                when (val ist = state.installState) {
                    is InstallState.Success -> {
                        Surface(color = AzlukSuccess.copy(.07f), shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AzlukSuccess.copy(.25f))) {
                            Row(Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = AzlukSuccess, modifier = Modifier.size(20.dp))
                                Text("Installed!", color = AzlukSuccess, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    is InstallState.Failure -> {
                        Surface(color = AzlukError.copy(.07f), shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AzlukError.copy(.25f))) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Error, null, tint = AzlukError, modifier = Modifier.size(18.dp))
                                    Text(ist.code, color = AzlukError, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Text(ist.message,     color = AzlukOnBg,      fontSize = 12.sp)
                                Text(ist.description, color = AzlukOnSurface, fontSize = 11.sp)
                                // AI diagnosis — shows silently as plain text hint
                                val ai = state.aiDiagnosis
                                if (ai.loading) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(14.dp), color = AzlukBlue, strokeWidth = 2.dp)
                                        Text("Analyzing…", color = AzlukOnSurface, fontSize = 11.sp)
                                    }
                                } else if (ai.suggestion.isNotEmpty()) {
                                    HorizontalDivider(color = AzlukSurfaceVar)
                                    Text(ai.suggestion, color = AzlukOnBg, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                                if (ist.canRetry) {
                                    Button(
                                        onClick  = { azlukInstall(ctx, File(outputPath), vm) },
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
                    }
                    else -> {}
                }
            }

            if (isFailure) {
                Button(onClick = { vm.resetPatch(); navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AzlukBlue)) {
                    Text("Try Again")
                }
            }
        }
    }
}

// ── AzlukPatcher installer — AI-powered silently ──────────────────────────────
// The AI diagnosis is already triggered in PatchViewModel.onInstallResult()
// No label "AI" is shown to the user — it just appears as helpful text.

private fun azlukInstall(ctx: Context, file: File, vm: PatchViewModel) {
    if (!file.exists()) { Toast.makeText(ctx, "APK not found", Toast.LENGTH_SHORT).show(); return }
    try {
        val pi        = ctx.packageManager.packageInstaller
        val params    = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pi.createSession(params)
        val session   = pi.openSession(sessionId)
        FileInputStream(file).use { fis ->
            session.openWrite("base.apk", 0, file.length()).use { os ->
                fis.copyTo(os, 65536); session.fsync(os)
            }
        }
        val receiverIntent = Intent("com.azluk.patcher.INSTALL_RESULT").apply { setPackage(ctx.packageName) }
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

// System installer — opens Android's built-in installer dialog
private fun systemInstall(ctx: Context, file: File) {
    if (!file.exists()) { Toast.makeText(ctx, "APK not found", Toast.LENGTH_SHORT).show(); return }
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.provider", file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Toast.makeText(ctx, "System install error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
