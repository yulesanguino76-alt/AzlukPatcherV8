package com.azluk.patcher.ui.screens

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.net.Uri
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
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.azluk.patcher.core.*
import com.azluk.patcher.ui.theme.*
import com.azluk.patcher.viewmodel.PatchViewModel
import java.io.File
import java.io.FileInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchLogScreen(pkg: String, navController: NavController, vm: PatchViewModel = viewModel()) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val ctx      = LocalContext.current
    val logState = rememberLazyListState()

    // ── FIXED: kick patch immediately, only if not already running/done ───────
    // Root cause of empty log: LaunchedEffect was gated on patchState == Idle,
    // but state was already set to Running by PatchScreen before navigation.
    // Fix: call patch() here unconditionally — PatchViewModel guards against
    // duplicate calls (if already Running, it returns early).
    LaunchedEffect(pkg) { vm.patch(pkg) }

    val logLines = when (val ps = state.patchState) {
        is PatchState.Running -> ps.log
        is PatchState.Success -> ps.log
        is PatchState.Failure -> ps.log
        else                  -> emptyList()
    }

    val isRunning = state.patchState is PatchState.Running
    val isSuccess = state.patchState is PatchState.Success
    val isFailure = state.patchState is PatchState.Failure

    // Auto-scroll log
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) logState.animateScrollToItem(logLines.size - 1)
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

    Scaffold(
        containerColor = AzlukBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when {
                                isRunning -> "Patching..."
                                isSuccess -> "Patch Complete"
                                isFailure -> "Patch Failed"
                                else      -> "Patching"
                            },
                            color      = AzlukOnBg,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(pkg, color = AzlukOnSurface, fontSize = 10.sp, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (!isRunning) { vm.resetPatch(); navController.popBackStack() } },
                        enabled = !isRunning
                    ) {
                        Icon(
                            if (isRunning) Icons.Default.HourglassEmpty else Icons.Default.ArrowBack,
                            null,
                            tint = if (isRunning) AzlukOnSurface.copy(.3f) else AzlukOnSurface
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

            // ── Status banner ─────────────────────────────────────────────────
            val (bannerColor, bannerBorder, bannerIcon, bannerText) = when {
                isRunning -> listOf(AzlukBlue.copy(.08f), AzlukBlue.copy(.25f),
                    Icons.Default.Sync, "Patching in background...")
                isSuccess -> listOf(AzlukSuccess.copy(.08f), AzlukSuccess.copy(.25f),
                    Icons.Default.CheckCircle, "Patch complete")
                isFailure -> listOf(AzlukError.copy(.08f), AzlukError.copy(.25f),
                    Icons.Default.Error, "Patch failed")
                else      -> listOf(AzlukSurface, AzlukSurfaceVar,
                    Icons.Default.HourglassEmpty, "Starting...")
            }

            Surface(
                color  = bannerColor as Color,
                shape  = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, bannerBorder as Color)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment          = Alignment.CenterVertically,
                    horizontalArrangement      = Arrangement.spacedBy(12.dp)
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = AzlukBlue, strokeWidth = 2.dp)
                    } else {
                        Icon(bannerIcon as androidx.compose.ui.graphics.vector.ImageVector, null,
                            tint = when { isSuccess -> AzlukSuccess; isFailure -> AzlukError; else -> AzlukBlue },
                            modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text(bannerText as String,
                            color      = when { isSuccess -> AzlukSuccess; isFailure -> AzlukError; else -> AzlukBlue },
                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (isRunning) {
                            Text("You can leave this screen — patch continues",
                                color = AzlukOnSurface, fontSize = 11.sp)
                        }
                    }
                }
            }

            // ── Live log ──────────────────────────────────────────────────────
            Surface(
                color    = AzlukSurface,
                shape    = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment          = Alignment.CenterVertically,
                        horizontalArrangement      = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Terminal, null, tint = AzlukOnSurface, modifier = Modifier.size(16.dp))
                        Text("Log", color = AzlukOnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        if (isRunning) {
                            Spacer(Modifier.width(4.dp))
                            Text("${logLines.size} lines", color = AzlukOnSurface.copy(.5f), fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (logLines.isEmpty() && isRunning) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(12.dp), color = AzlukBlue, strokeWidth = 1.5.dp)
                            Text("Starting engine…", color = AzlukOnSurface, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        LazyColumn(state = logState) {
                            items(logLines) { line ->
                                Text(
                                    line,
                                    color = when {
                                        line.contains("[SUCCESS]") || line.contains("Done") -> AzlukSuccess
                                        line.contains("[ERROR]")  || line.startsWith("Error") -> AzlukError
                                        line.contains("Signing")  || line.contains("DEX") -> AzlukCyan
                                        else -> AzlukOnSurface
                                    },
                                    fontSize   = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            // ── Install controls (shown after success) ─────────────────────────
            if (isSuccess) {
                val outputPath = (state.patchState as PatchState.Success).outputPath

                Surface(color = AzlukSurface, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.InsertDriveFile, null, tint = AzlukBlue, modifier = Modifier.size(16.dp))
                        Text(File(outputPath).name, color = AzlukOnSurface, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // AzlukPatcher install (PackageInstaller + silent AI on failure)
                    Button(
                        onClick  = { doInstall(ctx, File(outputPath), vm) },
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

                // Install result + AI suggestion
                when (val ist = state.installState) {
                    is InstallState.Success -> {
                        Surface(color = AzlukSuccess.copy(.07f), shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AzlukSuccess.copy(.25f))) {
                            Row(Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = AzlukSuccess, modifier = Modifier.size(20.dp))
                                Text("Installed successfully!", color = AzlukSuccess, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    is InstallState.Failure -> {
                        Surface(color = AzlukError.copy(.07f), shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AzlukError.copy(.25f))) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Error, null, tint = AzlukError, modifier = Modifier.size(16.dp))
                                    Text(ist.code, color = AzlukError, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Text(ist.message, color = AzlukOnBg, fontSize = 12.sp)
                                Text(ist.description, color = AzlukOnSurface, fontSize = 11.sp)

                                // AI suggestion — appears as plain text, no "AI" label
                                val ai = state.aiDiagnosis
                                if (ai.loading) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(12.dp), color = AzlukBlue, strokeWidth = 1.5.dp)
                                        Text("Analyzing error…", color = AzlukOnSurface, fontSize = 11.sp)
                                    }
                                } else if (ai.suggestion.isNotEmpty()) {
                                    HorizontalDivider(color = AzlukSurfaceVar, thickness = .5.dp)
                                    Text(ai.suggestion, color = AzlukOnBg, fontSize = 12.sp, lineHeight = 18.sp)
                                }

                                if (ist.canRetry) {
                                    Button(
                                        onClick  = { doInstall(ctx, File(outputPath), vm) },
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

            // Failure — try again button
            if (isFailure) {
                Button(
                    onClick  = { vm.resetPatch(); navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = AzlukBlue)
                ) { Text("Try Again") }
            }
        }
    }
}

// ── Install functions ─────────────────────────────────────────────────────────

fun doInstall(ctx: Context, file: File, vm: PatchViewModel) {
    if (!file.exists()) { Toast.makeText(ctx, "APK not found", Toast.LENGTH_SHORT).show(); return }
    try {
        val pi        = ctx.packageManager.packageInstaller
        val params    = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pi.createSession(params)
        val session   = pi.openSession(sessionId)
        FileInputStream(file).use { fis ->
            session.openWrite("base.apk", 0, file.length()).use { os ->
                fis.copyTo(os, 65536)
                session.fsync(os)
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

fun systemInstall(ctx: Context, file: File) {
    if (!file.exists()) { Toast.makeText(ctx, "APK not found", Toast.LENGTH_SHORT).show(); return }
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Toast.makeText(ctx, "System install error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
