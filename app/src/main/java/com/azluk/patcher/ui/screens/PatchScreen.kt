package com.azluk.patcher.ui.screens

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.lifecycle.viewmodel.compose.viewModel

import androidx.navigation.NavController

import com.azluk.patcher.core.InstallState
import com.azluk.patcher.core.PatchState
import com.azluk.patcher.core.PatchType
import com.azluk.patcher.ui.theme.AzlukBg
import com.azluk.patcher.ui.theme.AzlukBlue
import com.azluk.patcher.ui.theme.AzlukError
import com.azluk.patcher.ui.theme.AzlukOnBg
import com.azluk.patcher.ui.theme.AzlukOnSurface
import com.azluk.patcher.ui.theme.AzlukSuccess
import com.azluk.patcher.ui.theme.AzlukSurface
import com.azluk.patcher.ui.theme.AzlukSurfaceVar
import com.azluk.patcher.viewmodel.AiDiagnosis
import com.azluk.patcher.viewmodel.PatchViewModel

import java.io.File
import java.io.FileInputStream


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchScreen(
    pkg: String,
    navController: NavController,
    vm: PatchViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    val ctx = LocalContext.current

    val logState = rememberLazyListState()

    // -------------------------------------------------------------------------
    // Initial scan
    // -------------------------------------------------------------------------

    LaunchedEffect(pkg) {
        if (
            state.scanResults.isEmpty() &&
            !state.isScanning
        ) {
            vm.scan(pkg)
        }
    }

    // -------------------------------------------------------------------------
    // Patch log
    // -------------------------------------------------------------------------

    val logLines = when (val ps = state.patchState) {
        is PatchState.Running -> ps.log
        is PatchState.Success -> ps.log
        is PatchState.Failure -> ps.log
        else -> emptyList()
    }

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            logState.animateScrollToItem(logLines.size - 1)
        }
    }

    // -------------------------------------------------------------------------
    // PackageInstaller broadcast receiver
    // -------------------------------------------------------------------------

    DisposableEffect(Unit) {

        val filter = IntentFilter(
            "com.azluk.patcher.INSTALL_RESULT"
        )

        val receiver = object : BroadcastReceiver() {

            override fun onReceive(
                c: Context,
                i: Intent
            ) {

                val status = i.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    -999
                )

                val message = i.getStringExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE
                )

                if (
                    status ==
                    PackageInstaller.STATUS_PENDING_USER_ACTION
                ) {

                    val confirmIntent: Intent? =
                        if (
                            Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.TIRAMISU
                        ) {

                            i.getParcelableExtra(
                                Intent.EXTRA_INTENT,
                                Intent::class.java
                            )

                        } else {

                            @Suppress("DEPRECATION")

                            i.getParcelableExtra<Intent>(
                                Intent.EXTRA_INTENT
                            )
                        }

                    confirmIntent?.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )

                    confirmIntent?.let {
                        ctx.startActivity(it)
                    }

                } else {

                    vm.onInstallResult(
                        status,
                        message
                    )
                }
            }
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            ctx.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")

            ctx.registerReceiver(
                receiver,
                filter
            )
        }

        onDispose {
            runCatching {
                ctx.unregisterReceiver(receiver)
            }
        }
    }

    // -------------------------------------------------------------------------
    // UI state
    // -------------------------------------------------------------------------

    val isRunning =
        state.patchState is PatchState.Running

    val isDone =
        state.patchState is PatchState.Success ||
        state.patchState is PatchState.Failure

    // -------------------------------------------------------------------------
    // Screen
    // -------------------------------------------------------------------------

    Scaffold(

        containerColor = AzlukBg,

        topBar = {

            TopAppBar(

                title = {

                    Column {

                        Text(
                            text = "Patch",
                            color = AzlukOnBg,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = pkg,
                            color = AzlukOnSurface,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },

                navigationIcon = {

                    IconButton(
                        onClick = {
                            navController.popBackStack()
                        }
                    ) {

                        Icon(
                            imageVector =
                                Icons.Default.ArrowBack,

                            contentDescription = null,

                            tint = AzlukOnSurface
                        )
                    }
                },

                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = AzlukSurface
                    )
            )
        },

        bottomBar = {

            if (
                !isDone &&
                !isRunning
            ) {

                Surface(
                    color = AzlukSurface,
                    tonalElevation = 3.dp
                ) {

                    Row(

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(12.dp),

                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {

                        OutlinedButton(

                            onClick = {
                                vm.scan(pkg)
                            },

                            enabled =
                                !state.isScanning,

                            modifier =
                                Modifier.weight(1f),

                            shape =
                                RoundedCornerShape(12.dp),

                            border =
                                BorderStroke(
                                    1.dp,
                                    AzlukSurfaceVar
                                )
                        ) {

                            if (state.isScanning) {

                                CircularProgressIndicator(
                                    modifier =
                                        Modifier.size(14.dp),

                                    color =
                                        AzlukBlue,

                                    strokeWidth = 2.dp
                                )

                            } else {

                                Icon(
                                    imageVector =
                                        Icons.Default.Search,

                                    contentDescription = null,

                                    modifier =
                                        Modifier.size(16.dp),

                                    tint =
                                        AzlukBlue
                                )
                            }

                            Spacer(
                                Modifier.width(6.dp)
                            )

                            Text(
                                text = "Rescan",
                                color = AzlukBlue,
                                fontSize = 13.sp,
                                fontWeight =
                                    FontWeight.SemiBold
                            )
                        }

                        Button(

                            onClick = {
                                vm.patch(pkg)
                            },

                            enabled =
                                state.selectedPatches.isNotEmpty(),

                            modifier =
                                Modifier.weight(2f),

                            shape =
                                RoundedCornerShape(12.dp),

                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        AzlukBlue
                                )
                        ) {

                            Icon(
                                imageVector =
                                    Icons.Default.Build,

                                contentDescription = null,

                                modifier =
                                    Modifier.size(16.dp)
                            )

                            Spacer(
                                Modifier.width(8.dp)
                            )

                            Text(
                                text = "Start Patch",
                                fontWeight =
                                    FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

    ) { padding ->

        LazyColumn(

            state = logState,

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),

            contentPadding =
                PaddingValues(16.dp),

            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {

            // =================================================================
            // Scanning
            // =================================================================

            item {

                AnimatedVisibility(
                    visible = state.isScanning
                ) {

                    Surface(

                        color =
                            AzlukSurface,

                        shape =
                            RoundedCornerShape(14.dp)
                    ) {

                        Row(

                            modifier =
                                Modifier.padding(14.dp),

                            verticalAlignment =
                                Alignment.CenterVertically,

                            horizontalArrangement =
                                Arrangement.spacedBy(12.dp)
                        ) {

                            CircularProgressIndicator(

                                modifier =
                                    Modifier.size(16.dp),

                                color =
                                    AzlukBlue,

                                strokeWidth = 2.dp
                            )

                            Text(

                                text =
                                    "Scanning DEX files…",

                                color =
                                    AzlukOnSurface,

                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // =================================================================
            // Scan results
            // =================================================================

            if (state.scanResults.isNotEmpty()) {

                item {

                    val detected =
                        state.scanResults
                            .mapNotNull {

                                runCatching {

                                    PatchType.valueOf(
                                        it.patchType
                                    )

                                }.getOrNull()
                            }
                            .toSet()

                    Surface(

                        color =
                            AzlukBlue.copy(.08f),

                        shape =
                            RoundedCornerShape(14.dp),

                        border =
                            BorderStroke(
                                1.dp,
                                AzlukBlue.copy(.2f)
                            )
                    ) {

                        Column(
                            Modifier.padding(12.dp)
                        ) {

                            Text(

                                text =
                                    "${state.scanResults.size} opportunities detected",

                                color =
                                    AzlukBlue,

                                fontWeight =
                                    FontWeight.SemiBold,

                                fontSize = 13.sp
                            )

                            Spacer(
                                Modifier.height(4.dp)
                            )

                            Text(

                                text =
                                    detected.joinToString(" · ") {
                                        it.displayName
                                    },

                                color =
                                    AzlukOnSurface,

                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // =================================================================
            // Patch options
            // =================================================================

            item {

                Text(
                    text = "Patch Options",
                    color = AzlukOnBg,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            items(
                items = PatchType.entries.toList()
            ) { type ->

                val selected =
                    state.selectedPatches.contains(type)

                Surface(

                    modifier =
                        Modifier.fillMaxWidth(),

                    color =
                        if (selected)
                            AzlukBlue.copy(.08f)
                        else
                            AzlukSurface,

                    shape =
                        RoundedCornerShape(14.dp),

                    border =
                        BorderStroke(
                            1.dp,
                            if (selected)
                                AzlukBlue.copy(.35f)
                            else
                                AzlukSurfaceVar
                        )
                ) {

                    Row(

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.togglePatch(type)
                                }
                                .padding(14.dp),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Checkbox(

                            checked =
                                selected,

                            onCheckedChange = {
                                vm.togglePatch(type)
                            },

                            colors =
                                CheckboxDefaults.colors(
                                    checkedColor =
                                        AzlukBlue
                                )
                        )

                        Spacer(
                            Modifier.width(8.dp)
                        )

                        Column(
                            modifier =
                                Modifier.weight(1f)
                        ) {

                            Text(
                                text =
                                    type.displayName,

                                color =
                                    AzlukOnBg,

                                fontWeight =
                                    FontWeight.SemiBold,

                                fontSize = 13.sp
                            )

                            Spacer(
                                Modifier.height(3.dp)
                            )

                            Text(
                                text =
                                    type.description,

                                color =
                                    AzlukOnSurface,

                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // =================================================================
            // Running patch
            // =================================================================

            if (state.patchState is PatchState.Running) {

                item {

                    Surface(

                        color =
                            AzlukSurface,

                        shape =
                            RoundedCornerShape(14.dp),

                        border =
                            BorderStroke(
                                1.dp,
                                AzlukBlue.copy(.25f)
                            )
                    ) {

                        Column(
                            Modifier.padding(14.dp)
                        ) {

                            Row(
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                CircularProgressIndicator(

                                    modifier =
                                        Modifier.size(18.dp),

                                    color =
                                        AzlukBlue,

                                    strokeWidth = 2.dp
                                )

                                Spacer(
                                    Modifier.width(10.dp)
                                )

                                Text(
                                    text =
                                        "Patching…",

                                    color =
                                        AzlukBlue,

                                    fontWeight =
                                        FontWeight.Bold,

                                    fontSize = 13.sp
                                )
                            }

                            Spacer(
                                Modifier.height(12.dp)
                            )

                            logLines.forEach { line ->

                                Text(

                                    text = line,

                                    color =
                                        AzlukOnSurface,

                                    fontSize = 11.sp,

                                    fontFamily =
                                        androidx.compose.ui.text.font.FontFamily.Monospace,

                                    modifier =
                                        Modifier.padding(
                                            vertical = 2.dp
                                        )
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // Successful patch
            // =================================================================

            if (state.patchState is PatchState.Success) {

                item {

                    Surface(

                        color =
                            AzlukSuccess.copy(.07f),

                        shape =
                            RoundedCornerShape(14.dp),

                        border =
                            BorderStroke(
                                1.dp,
                                AzlukSuccess.copy(.25f)
                            )
                    ) {

                        Column(
                            Modifier.padding(14.dp)
                        ) {

                            Text(
                                text =
                                    "Patch completed successfully",

                                color =
                                    AzlukSuccess,

                                fontWeight =
                                    FontWeight.Bold,

                                fontSize = 14.sp
                            )

                            Spacer(
                                Modifier.height(8.dp)
                            )

                            logLines.forEach { line ->

                                Text(

                                    text = line,

                                    color =
                                        AzlukOnSurface,

                                    fontSize = 11.sp,

                                    fontFamily =
                                        androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }

                            Spacer(
                                Modifier.height(10.dp)
                            )

                            Button(

                                onClick = {

                                    installApk(
                                        ctx,
                                        state.lastOutputPath
                                    )
                                },

                                enabled =
                                    state.lastOutputPath.isNotEmpty(),

                                modifier =
                                    Modifier.fillMaxWidth(),

                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor =
                                            AzlukBlue
                                    ),

                                shape =
                                    RoundedCornerShape(10.dp)
                            ) {

                                Text(
                                    text =
                                        "Install APK"
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // Patch failure
            // =================================================================

            if (state.patchState is PatchState.Failure) {

                item {

                    val failure =
                        state.patchState as PatchState.Failure

                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp)
                    ) {

                        Surface(

                            color =
                                AzlukError.copy(.07f),

                            shape =
                                RoundedCornerShape(14.dp),

                            border =
                                BorderStroke(
                                    1.dp,
                                    AzlukError.copy(.25f)
                                )
                        ) {

                            Column(

                                modifier =
                                    Modifier.padding(14.dp),

                                verticalArrangement =
                                    Arrangement.spacedBy(6.dp)
                            ) {

                                Text(
                                    text =
                                        "Patch failed",

                                    color =
                                        AzlukError,

                                    fontWeight =
                                        FontWeight.Bold,

                                    fontSize = 13.sp
                                )

                                Text(
                                    text =
                                        failure.message,

                                    color =
                                        AzlukOnBg,

                                    fontSize = 12.sp
                                )

                                failure.log.forEach { line ->

                                    Text(
                                        text = line,
                                        color =
                                            AzlukOnSurface,
                                        fontSize = 11.sp,
                                        fontFamily =
                                            androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }

                                Spacer(
                                    Modifier.height(4.dp)
                                )

                                Button(

                                    onClick = {
                                        vm.patch(pkg)
                                    },

                                    colors =
                                        ButtonDefaults.buttonColors(
                                            containerColor =
                                                AzlukBlue
                                        ),

                                    shape =
                                        RoundedCornerShape(10.dp),

                                    modifier =
                                        Modifier.fillMaxWidth()
                                ) {

                                    Icon(
                                        imageVector =
                                            Icons.Default.Refresh,

                                        contentDescription =
                                            null,

                                        modifier =
                                            Modifier.size(16.dp)
                                    )

                                    Spacer(
                                        Modifier.width(6.dp)
                                    )

                                    Text(
                                        text =
                                            "Try Again"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // Installation result
            // =================================================================

            when (val ist = state.installState) {

                InstallState.Installing -> {

                    item {

                        Surface(

                            color =
                                AzlukSurface,

                            shape =
                                RoundedCornerShape(14.dp),

                            border =
                                BorderStroke(
                                    1.dp,
                                    AzlukBlue.copy(.25f)
                                )
                        ) {

                            Row(

                                modifier =
                                    Modifier.padding(14.dp),

                                verticalAlignment =
                                    Alignment.CenterVertically,

                                horizontalArrangement =
                                    Arrangement.spacedBy(10.dp)
                            ) {

                                CircularProgressIndicator(

                                    modifier =
                                        Modifier.size(18.dp),

                                    color =
                                        AzlukBlue,

                                    strokeWidth = 2.dp
                                )

                                Text(

                                    text =
                                        "Installing APK…",

                                    color =
                                        AzlukBlue,

                                    fontWeight =
                                        FontWeight.SemiBold,

                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                is InstallState.Success -> {

                    item {

                        Surface(

                            color =
                                AzlukSuccess.copy(.07f),

                            shape =
                                RoundedCornerShape(14.dp),

                            border =
                                BorderStroke(
                                    1.dp,
                                    AzlukSuccess.copy(.25f)
                                )
                        ) {

                            Text(

                                text =
                                    "✅ Installed successfully!",

                                color =
                                    AzlukSuccess,

                                fontWeight =
                                    FontWeight.Bold,

                                modifier =
                                    Modifier.padding(14.dp)
                            )
                        }
                    }
                }

                is InstallState.Failure -> {

                    item {

                        Column(

                            verticalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {

                            Surface(

                                color =
                                    AzlukError.copy(.07f),

                                shape =
                                    RoundedCornerShape(14.dp),

                                border =
                                    BorderStroke(
                                        1.dp,
                                        AzlukError.copy(.25f)
                                    )
                            ) {

                                Column(

                                    modifier =
                                        Modifier.padding(14.dp),

                                    verticalArrangement =
                                        Arrangement.spacedBy(6.dp)
                                ) {

                                    Text(

                                        text =
                                            "❌ ${ist.code}",

                                        color =
                                            AzlukError,

                                        fontWeight =
                                            FontWeight.Bold,

                                        fontSize = 13.sp
                                    )

                                    Text(

                                        text =
                                            ist.message,

                                        color =
                                            AzlukOnBg,

                                        fontSize = 12.sp
                                    )

                                    Text(

                                        text =
                                            ist.description,

                                        color =
                                            AzlukOnSurface,

                                        fontSize = 11.sp
                                    )

                                    if (ist.canRetry) {

                                        Spacer(
                                            Modifier.height(4.dp)
                                        )

                                        Button(

                                            onClick = {

                                                installApk(
                                                    ctx,
                                                    state.lastOutputPath
                                                )
                                            },

                                            colors =
                                                ButtonDefaults.buttonColors(
                                                    containerColor =
                                                        AzlukBlue
                                                ),

                                            shape =
                                                RoundedCornerShape(10.dp),

                                            modifier =
                                                Modifier.fillMaxWidth()
                                        ) {

                                            Icon(

                                                imageVector =
                                                    Icons.Default.Refresh,

                                                contentDescription =
                                                    null,

                                                modifier =
                                                    Modifier.size(16.dp)
                                            )

                                            Spacer(
                                                Modifier.width(6.dp)
                                            )

                                            Text(
                                                text =
                                                    "Retry Install"
                                            )
                                        }
                                    }
                                }
                            }

                            AiDiagnosisCard(

                                diagnosis =
                                    state.aiDiagnosis,

                                onDismiss = {
                                    vm.dismissAi()
                                },

                                onRetry = {

                                    vm.diagnoseWithAi(
                                        ist,
                                        state.lastOutputPath
                                    )
                                }
                            )
                        }
                    }
                }

                InstallState.Idle -> Unit
            }
        }
    }
}


// =============================================================================
// AI DIAGNOSIS CARD
// =============================================================================

@Composable
private fun AiDiagnosisCard(

    diagnosis: AiDiagnosis,

    onDismiss: () -> Unit,

    onRetry: () -> Unit

) {

    AnimatedVisibility(

        visible =
            diagnosis.loading ||
            diagnosis.suggestion.isNotEmpty() ||
            diagnosis.error.isNotEmpty(),

        enter =
            fadeIn() +
            expandVertically(),

        exit =
            fadeOut() +
            shrinkVertically()
    ) {

        Surface(

            color =
                AzlukSurfaceVar.copy(.6f),

            shape =
                RoundedCornerShape(14.dp),

            border =
                BorderStroke(
                    1.dp,
                    AzlukBlue.copy(.3f)
                )
        ) {

            Column(

                modifier =
                    Modifier.padding(14.dp),

                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {

                Row(

                    verticalAlignment =
                        Alignment.CenterVertically,

                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    Surface(

                        color =
                            AzlukBlue.copy(.15f),

                        shape =
                            RoundedCornerShape(6.dp)
                    ) {

                        Text(

                            text =
                                "✦ AI",

                            color =
                                AzlukBlue,

                            fontSize = 11.sp,

                            fontWeight =
                                FontWeight.Bold,

                            modifier =
                                Modifier.padding(
                                    horizontal = 8.dp,
                                    vertical = 3.dp
                                )
                        )
                    }

                    Text(

                        text =
                            "AzlukAI Diagnosis",

                        color =
                            AzlukOnBg,

                        fontWeight =
                            FontWeight.SemiBold,

                        fontSize = 13.sp,

                        modifier =
                            Modifier.weight(1f)
                    )

                    if (!diagnosis.loading) {

                        IconButton(

                            onClick =
                                onDismiss,

                            modifier =
                                Modifier.size(28.dp)
                        ) {

                            Icon(

                                imageVector =
                                    Icons.Default.Close,

                                contentDescription =
                                    null,

                                tint =
                                    AzlukOnSurface,

                                modifier =
                                    Modifier.size(16.dp)
                            )
                        }
                    }
                }

                when {

                    diagnosis.loading -> {

                        Row(

                            verticalAlignment =
                                Alignment.CenterVertically,

                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {

                            CircularProgressIndicator(

                                modifier =
                                    Modifier.size(16.dp),

                                color =
                                    AzlukBlue,

                                strokeWidth = 2.dp
                            )

                            Text(

                                text =
                                    "Analyzing error…",

                                color =
                                    AzlukOnSurface,

                                fontSize = 12.sp
                            )
                        }
                    }

                    diagnosis.suggestion.isNotEmpty() -> {

                        Text(

                            text =
                                diagnosis.suggestion,

                            color =
                                AzlukOnBg,

                            fontSize = 13.sp,

                            lineHeight =
                                19.sp
                        )
                    }

                    diagnosis.error.isNotEmpty() -> {

                        Text(

                            text =
                                diagnosis.error,

                            color =
                                AzlukError,

                            fontSize = 12.sp
                        )

                        TextButton(
                            onClick =
                                onRetry
                        ) {

                            Text(

                                text =
                                    "Retry analysis",

                                color =
                                    AzlukBlue,

                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}


// =============================================================================
// APK INSTALLER
// =============================================================================

private fun installApk(

    ctx: Context,

    path: String

) {

    if (path.isEmpty()) {

        Toast.makeText(

            ctx,

            "No APK to install",

            Toast.LENGTH_SHORT

        ).show()

        return
    }

    val file = File(path)

    if (!file.exists()) {

        Toast.makeText(

            ctx,

            "APK not found: $path",

            Toast.LENGTH_LONG

        ).show()

        return
    }

    try {

        val packageInstaller =
            ctx.packageManager.packageInstaller

        val params =
            PackageInstaller.SessionParams(

                PackageInstaller.SessionParams
                    .MODE_FULL_INSTALL
            )

        val sessionId =
            packageInstaller.createSession(
                params
            )

        val session =
            packageInstaller.openSession(
                sessionId
            )

        FileInputStream(file).use { fis ->

            session.openWrite(

                "base.apk",

                0,

                file.length()

            ).use { output ->

                fis.copyTo(
                    output,
                    65536
                )

                session.fsync(
                    output
                )
            }
        }

        val receiverIntent =
            Intent(
                "com.azluk.patcher.INSTALL_RESULT"
            ).apply {

                setPackage(
                    ctx.packageName
                )
            }

        val flags =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_MUTABLE

            } else {

                PendingIntent.FLAG_UPDATE_CURRENT
            }

        val pendingIntent =
            PendingIntent.getBroadcast(

                ctx,

                sessionId,

                receiverIntent,

                flags
            )

        session.commit(
            pendingIntent.intentSender
        )

        session.close()

    } catch (e: Exception) {

        Toast.makeText(

            ctx,

            "Install error: ${e.message}",

            Toast.LENGTH_LONG

        ).show()
    }
}
