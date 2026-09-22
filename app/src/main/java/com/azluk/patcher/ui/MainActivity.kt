package com.azluk.patcher.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.azluk.patcher.ui.screens.*
import com.azluk.patcher.ui.theme.AzlukTheme
import com.azluk.patcher.viewmodel.SystemCheckViewModel

class MainActivity : ComponentActivity() {

    private val permissionReady = mutableStateOf(false)
    private val systemCheckVm: SystemCheckViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionReady.value = hasPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── FULL SCREEN — draw behind status + nav bars ───────────────────────
        WindowCompat.setDecorFitsSystemWindows(window, false)

        permissionReady.value = hasPermission()
        systemCheckVm.run()

        setContent {
            AzlukTheme {
                val ready by permissionReady
                if (ready) {
                    AzlukApp(systemCheckVm)
                } else {
                    PermissionScreen { requestPermission() }
                }
            }
        }

        if (!hasPermission()) requestPermission()
    }

    override fun onResume() {
        super.onResume()
        permissionReady.value = hasPermission()
    }

    private fun hasPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
             PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        if (hasPermission()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            }.onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            permLauncher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }
    }
}

@Composable
private fun AzlukApp(systemCheckVm: SystemCheckViewModel) {
    val profile by systemCheckVm.profile.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    var splashDone by remember { mutableStateOf(false) }

    if (!splashDone) {
        SplashScreen(profile = profile, onComplete = { splashDone = true })
    } else {
        NavHost(
            navController    = navController,
            startDestination = "home",
            // Fill entire screen including behind system bars
            modifier         = Modifier.fillMaxSize()
        ) {
            composable("home")    { HomeScreen(navController) }
            composable("patched") { PatchedFilesScreen(navController) }
            composable("tools")   { ToolsScreen(navController) }
            composable(
                "detail/{pkg}",
                arguments = listOf(navArgument("pkg") { type = NavType.StringType })
            ) { HomeScreen(navController) }   // back-compat
            composable(
                "patch/{pkg}",
                arguments = listOf(navArgument("pkg") { type = NavType.StringType })
            ) { back ->
                val pkg = back.arguments?.getString("pkg") ?: return@composable
                PatchScreen(pkg = pkg, navController = navController)
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Storage Access Required",
            style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("AzlukPatcher needs full storage access to read and patch APK files.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant Access") }
    }
}

// ── InstallReceiver — all 3 bugs fixed ───────────────────────────────────────
// Bug 1: missing FLAG_UPDATE_CURRENT  → stale PI on retry
// Bug 2: missing setPackage()         → implicit broadcast dropped on API 26+
// Bug 3: forwarded EXTRA_INTENT correctly for PENDING_USER_ACTION
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val msg    = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        ctx.sendBroadcast(Intent("com.azluk.patcher.INSTALL_RESULT_LOCAL").apply {
            setPackage(ctx.packageName)          // FIX 2
            putExtra(PackageInstaller.EXTRA_STATUS, status)
            putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, msg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { putExtra(Intent.EXTRA_INTENT, it) }
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    ?.let { putExtra(Intent.EXTRA_INTENT, it) }
            }
        })
    }
}
