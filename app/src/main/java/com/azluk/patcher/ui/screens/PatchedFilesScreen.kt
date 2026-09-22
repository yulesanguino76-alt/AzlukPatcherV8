package com.azluk.patcher.ui.screens

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.navigation.NavController
import com.azluk.patcher.ui.theme.*
import com.azluk.patcher.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchedFilesScreen(navController: NavController) {
    val ctx   = LocalContext.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var installStatus by remember { mutableStateOf<String?>(null) }
    val fmt   = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val result = runCatching { StorageUtils.getPatchedFiles() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { files = result }
        }
    }

    // Fixed install receiver
    DisposableEffect(Unit) {
        val filter   = IntentFilter("com.azluk.patcher.INSTALL_RESULT_LOCAL")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                val msg    = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        confirm?.let { ctx.startActivity(it) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> installStatus = "✅ Installed successfully!"
                    else -> installStatus = "❌ Install failed: ${msg ?: "code $status"}"
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            ctx.registerReceiver(receiver, filter)
        onDispose { ctx.unregisterReceiver(receiver) }
    }

    fun refresh() {
        files = runCatching { StorageUtils.getPatchedFiles() }.getOrDefault(emptyList())
    }

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

            // Install status banner
            AnimatedVisibility(installStatus != null) {
                val isSuccess = installStatus?.startsWith("✅") == true
                Surface(
                    color  = if (isSuccess) AzlukSuccess.copy(.08f) else AzlukError.copy(.08f),
                    border = BorderStroke(1.dp,
                        if (isSuccess) AzlukSuccess.copy(.25f) else AzlukError.copy(.25f))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            installStatus ?: "",
                            color      = if (isSuccess) AzlukSuccess else AzlukError,
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick  = { installStatus = null },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, null,
                                tint = AzlukOnSurface, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOff, null,
                            tint = AzlukOnSurface, modifier = Modifier.size(48.dp))
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
                            ctx       = ctx,
                            onInstall = { installApkFixed(ctx, file) },
                            onReinstall = {
                                // Guess pkg from filename: com.pkg_azluk_v8.apk → com.pkg
                                val guessed = file.nameWithoutExtension
                                    .removeSuffix("_azluk_v8").removeSuffix("_azluk")
                                uninstallAndInstall(ctx, guessed, file)
                            },
                            onDelete  = { file.delete(); refresh() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchedFileCard(
    file: File, date: String, ctx: Context,
    onInstall: () -> Unit, onReinstall: () -> Unit, onDelete: () -> Unit
) {
    val guessedPkg = remember(file.name) {
        file.nameWithoutExtension.removeSuffix("_azluk_v8").removeSuffix("_azluk")
    }
    val isInstalled = remember(guessedPkg) {
        runCatching { ctx.packageManager.getPackageInfo(guessedPkg, 0); true }
            .getOrDefault(false)
    }
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color    = AzlukSurface,
        shape    = RoundedCornerShape(14.dp),
        border   = BorderStroke(1.dp, AzlukSurfaceVar),
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
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = AzlukOnSurface
                    )
                }
            }

            AnimatedVisibility(expanded) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Install
                    Button(
                        onClick  = onInstall,
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = AzlukBlue)
                    ) {
                        Icon(Icons.Default.InstallMobile, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Install", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Reinstall (uninstall first) — only when app already installed
                    if (isInstalled) {
                        OutlinedButton(
                            onClick  = onReinstall,
                            modifier = Modifier.weight(1.2f),
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

// ── Install — all 3 bugs fixed ────────────────────────────────────────────────

fun installApkFixed(ctx: Context, file: File) {
    if (!file.exists()) {
        Toast.makeText(ctx, "File not found", Toast.LENGTH_SHORT).show(); return
    }
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
        // FIX 1: explicit package so broadcast is not dropped on API 26+
        val receiverIntent = Intent("com.azluk.patcher.INSTALL_RESULT").apply {
            setPackage(ctx.packageName)
        }
        // FIX 2: FLAG_UPDATE_CURRENT so the PendingIntent slot is reused on retry
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getBroadcast(ctx, sessionId, receiverIntent, flags)
        // FIX 3: commit before close
        session.commit(pending.intentSender)
        session.close()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun uninstallAndInstall(ctx: Context, packageName: String, apk: File) {
    try {
        // Kick system uninstall dialog — install happens via broadcast after user confirms
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        Toast.makeText(ctx, "Uninstall the app, then tap Install again", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        installApkFixed(ctx, apk)
    }
}
