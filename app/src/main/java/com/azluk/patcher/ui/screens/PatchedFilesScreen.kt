package com.azluk.patcher.ui.screens

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.azluk.patcher.ui.theme.*
import com.azluk.patcher.utils.StorageUtils
import com.azluk.patcher.viewmodel.AiDiagnosis
import com.azluk.patcher.viewmodel.InstallerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchedFilesScreen(
    navController: NavController,
    vm: InstallerViewModel = viewModel()
) {
    val ctx   = LocalContext.current
    val state by vm.state.collectAsState()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    val fmt   = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val result = runCatching { StorageUtils.getPatchedFiles() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { files = result }
        }
    }

    // Install broadcast receiver — fixed
    DisposableEffect(Unit) {
        val filter = IntentFilter("com.azluk.patcher.INSTALL_RESULT_LOCAL")
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

    fun refresh() { files = StorageUtils.getPatchedFiles() }

    Scaffold(
        containerColor = AzlukBg,
        topBar = {
            TopAppBar(
                title = { Text("Patched Files", color = AzlukOnBg, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = AzlukOnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = ::refresh) {
                        Icon(Icons.Default.Refresh, null, tint = AzlukOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AzlukSurface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── Active install status banner ───────────────────────────────────
            AnimatedVisibility(state.isInstalling || state.installResult != null) {
                InstallerStatusBanner(
                    vmState   = state,
                    onDismiss = { vm.reset() },
                    onRetry   = { state.currentApkPath?.let { vm.install(ctx, File(it)) } }
                )
            }

            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOff, null, tint = AzlukOnSurface,
                            modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No patched files yet", color = AzlukOnSurface)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding      = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(files, key = { it.absolutePath }) { file ->
                        PatchedFileCard(
                            file      = file,
                            date      = fmt.format(Date(file.lastModified())),
                            isInstalling = state.isInstalling && state.currentApkPath == file.absolutePath,
                            onInstall = { vm.install(ctx, file) },
                            onUninstallAndInstall = { pkg ->
                                vm.uninstallThenInstall(ctx, pkg, file)
                            },
                            onDelete  = { file.delete(); refresh() },
                            ctx       = ctx
                        )
                    }
                }
            }
        }
    }
}

// ── Installer status banner ───────────────────────────────────────────────────

@Composable
private fun InstallerStatusBanner(
    vmState:  com.azluk.patcher.viewmodel.InstallerState,
    onDismiss: () -> Unit,
    onRetry:   () -> Unit
) {
    val (bg, border, icon, text) = when {
        vmState.isInstalling -> listOf(
            AzlukBlue.copy(.07f), AzlukBlue.copy(.25f),
            Icons.Default.InstallMobile, "Installing…"
        )
        vmState.installResult == true -> listOf(
            AzlukSuccess.copy(.07f), AzlukSuccess.copy(.25f),
            Icons.Default.CheckCircle, "Installed successfully!"
        )
        else -> listOf(
            AzlukError.copy(.07f), AzlukError.copy(.25f),
            Icons.Default.Error, vmState.errorMessage ?: "Install failed"
        )
    }

    Surface(
        color  = bg as Color,
        border = BorderStroke(1.dp, border as Color),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (vmState.isInstalling) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = AzlukBlue, strokeWidth = 2.dp)
                } else {
                    Icon(icon as androidx.compose.ui.graphics.vector.ImageVector, null,
                        tint = if (vmState.installResult == true) AzlukSuccess else AzlukError,
                        modifier = Modifier.size(18.dp))
                }
                Text(text as String,
                    color = if (vmState.installResult == true) AzlukSuccess
                            else if (vmState.isInstalling) AzlukBlue else AzlukError,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
                if (!vmState.isInstalling) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, null, tint = AzlukOnSurface, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // AI diagnosis card — seamlessly integrated, no "AI" label visible
            if (!vmState.isInstalling && vmState.installResult == false) {
                val d = vmState.aiDiagnosis
                if (d.loading || d.suggestion.isNotEmpty() || d.error.isNotEmpty()) {
                    Divider(color = AzlukSurfaceVar, thickness = 0.5.dp)
                    when {
                        d.loading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = AzlukBlue, strokeWidth = 2.dp)
                            Text("Analyzing…", color = AzlukOnSurface, fontSize = 12.sp)
                        }
                        d.suggestion.isNotEmpty() ->
                            Text(d.suggestion, color = AzlukOnBg, fontSize = 12.sp, lineHeight = 18.sp)
                        d.error.isNotEmpty() ->
                            TextButton(onClick = onRetry) { Text("Retry install", color = AzlukBlue) }
                    }
                }
            }
        }
    }
}

// ── Patched file card ─────────────────────────────────────────────────────────

@Composable
private fun PatchedFileCard(
    file:                 File,
    date:                 String,
    isInstalling:         Boolean,
    onInstall:            () -> Unit,
    onUninstallAndInstall: (pkg: String) -> Unit,
    onDelete:             () -> Unit,
    ctx:                  Context
) {
    // Try to get package name from filename (pkg_azluk_v8.apk → pkg)
    val guessedPkg = remember(file.name) {
        file.nameWithoutExtension.removeSuffix("_azluk_v8").removeSuffix("_azluk")
    }
    val isInstalled = remember(guessedPkg) {
        try { ctx.packageManager.getPackageInfo(guessedPkg, 0); true }
        catch (_: Exception) { false }
    }
    var showActions by remember { mutableStateOf(false) }

    Surface(
        color  = AzlukSurface,
        shape  = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, AzlukSurfaceVar),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File icon
                Surface(color = AzlukBlue.copy(.1f), shape = RoundedCornerShape(10.dp)) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Android, null, tint = AzlukBlue, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = AzlukOnBg, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${"%.1f".format(file.length() / 1048576f)} MB · $date",
                        color = AzlukOnSurface, fontSize = 11.sp)
                    if (isInstalled) {
                        Spacer(Modifier.height(3.dp))
                        Surface(color = AzlukSuccess.copy(.1f), shape = RoundedCornerShape(4.dp)) {
                            Text("Installed", color = AzlukSuccess, fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                IconButton(onClick = { showActions = !showActions }) {
                    Icon(if (showActions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = AzlukOnSurface)
                }
            }

            // Expandable action row
            AnimatedVisibility(showActions) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Install button
                    Button(
                        onClick  = onInstall,
                        enabled  = !isInstalling,
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = AzlukBlue)
                    ) {
                        if (isInstalling) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.InstallMobile, null, Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (isInstalling) "Installing…" else "Install",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Uninstall + Install (if app is installed)
                    if (isInstalled) {
                        OutlinedButton(
                            onClick  = { onUninstallAndInstall(guessedPkg) },
                            enabled  = !isInstalling,
                            modifier = Modifier.weight(1.3f),
                            shape    = RoundedCornerShape(10.dp),
                            border   = BorderStroke(1.dp, AzlukWarning.copy(.5f))
                        ) {
                            Icon(Icons.Default.SwapHoriz, null, Modifier.size(14.dp), tint = AzlukWarning)
                            Spacer(Modifier.width(4.dp))
                            Text("Reinstall", fontSize = 12.sp, color = AzlukWarning, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Delete
                    IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.DeleteOutline, null, tint = AzlukError, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
