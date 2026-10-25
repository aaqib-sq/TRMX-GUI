package dev.trmx.gui

/*
 * MainActivity (Compact Redesign) — the ultra-streamlined TRMX host.
 * 2 focused destinations: Run (command console + tools) & Activity (jobs + live output).
 * Eliminates services, AI builders, recipes, and multi-layer file explorer clutter.
 */

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.trmx.gui.download.DownloadViewModel
import dev.trmx.gui.download.InstagramItem
import dev.trmx.gui.download.InstagramTool
import dev.trmx.gui.killer.AppKillerViewModel
import dev.trmx.gui.model.ToolExample
import dev.trmx.gui.ui.ActivityScreen
import dev.trmx.gui.ui.ConnectScreen
import dev.trmx.gui.ui.DownloaderScreen
import dev.trmx.gui.ui.InstagramToolScreen
import dev.trmx.gui.ui.JobDetailScreen
import dev.trmx.gui.ui.KillScreen
import dev.trmx.gui.ui.KillerDebugScreen
import dev.trmx.gui.ui.RunScreen
import dev.trmx.gui.ui.SettingsScreen
import dev.trmx.gui.ui.ToolFormScreen
import dev.trmx.gui.ui.Tokens.Palette as P
import dev.trmx.gui.ui.TRMXTheme
import dev.trmx.gui.ui.TrmxNavItemColors
import dev.trmx.gui.ui.TrmxTopBar
import dev.trmx.gui.wizard.PairLink
import dev.trmx.gui.wizard.SetupViewModel
import dev.trmx.gui.wizard.WizardStep
import java.io.File

/** Destinations: Run (homepage + tools), Kill (app killer), & Activity (jobs + output). */
private enum class Screen { RUN, KILL, ACTIVITY }

private const val FILE_PROVIDER = "dev.trmx.gui.fileprovider"

/** Pair-link token waiting for AppRoot (set by the activity from trmx://pair). */
private var pendingPairToken by mutableStateOf<String?>(null)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePairIntent(intent)
        setContent {
            TRMXTheme {
                CrashGate { AppRoot() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairIntent(intent)
    }

    /** Pair-link (trmx://pair?token=… from `trmx pair-link` in Termux). */
    private fun handlePairIntent(intent: Intent?) {
        val token = PairLink.tokenFrom(intent?.dataString) ?: return
        pendingPairToken = token
    }
}

@Composable
fun AppRoot(
    vm: AppViewModel = viewModel(),
    setupVm: SetupViewModel = viewModel(),
    filesVm: FilesViewModel = viewModel(),
    killerVm: AppKillerViewModel = viewModel(),
    downloadVm: DownloadViewModel = viewModel(),
) {
    val setup by setupVm.wizard.collectAsStateWithLifecycle()
    val setupLogText by setupVm.setupLogText.collectAsStateWithLifecycle()
    val connect by setupVm.connect.collectAsStateWithLifecycle()
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val filesState by filesVm.files.collectAsStateWithLifecycle()
    val toolFormState by vm.toolForm.collectAsStateWithLifecycle()
    val artifactsState by vm.artifacts.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(Screen.RUN) }
    var showSettings by remember { mutableStateOf(false) }
    var showKillerDebug by remember { mutableStateOf(false) }
    var selectedInstagramTool by remember { mutableStateOf<InstagramTool?>(null) }
    var isDownloaderOpen by remember { mutableStateOf(false) }
    var stagedDownloadItem by remember { mutableStateOf<InstagramItem?>(null) }

    // Pair-link token adopted on cold start or warm onNewIntent.
    LaunchedEffect(pendingPairToken) {
        pendingPairToken?.let { token ->
            pendingPairToken = null
            setupVm.adoptFromLink(token)
        }
    }

    when {
        setup.step != WizardStep.DONE -> ConnectScreen(
            state = connect,
            manualToken = setupVm.manualToken,
            setupLog = setupLogText,
            onCheckNow = setupVm::checkNow,
            onRefreshLog = setupVm::refreshSetupLog,
            onStartPolling = setupVm::startConnectPolling,
            onStopPolling = setupVm::stopConnectPolling,
        )

        else -> {
            val detailState = detail
            val toolForm = toolFormState

            // Staged artifact open / share via FileProvider
            val pending = filesState.pendingOpen
            LaunchedEffect(pending) {
                if (pending == null) return@LaunchedEffect
                try {
                    val uri = FileProvider.getUriForFile(
                        vm.getApplication<android.app.Application>(),
                        FILE_PROVIDER,
                        File(pending.path)
                    )
                    val intent = if (pending.share) {
                        Intent(Intent.ACTION_SEND).apply {
                            type = pending.mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(File(pending.path).name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, pending.mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    vm.getApplication<android.app.Application>().startActivity(
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    filesVm.afterOpen(null)
                } catch (e: ActivityNotFoundException) {
                    filesVm.afterOpen("No app available to open \u201c${File(pending.path).name}\u201d (${pending.mime})")
                } catch (e: SecurityException) {
                    filesVm.afterOpen("Cannot open: ${e.message}")
                }
            }

            // Live event streaming lifecycle
            LaunchedEffect(Unit) { vm.startEvents() }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopEvents() }
            LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.startEvents() }

            if (detailState != null) {
                BackHandler { vm.selectJob(null) }
                JobDetailScreen(
                    state = detailState,
                    output = output,
                    artifacts = artifactsState,
                    onBack = { vm.selectJob(null) },
                    onCancelJob = vm::cancelJob,
                    onReplayOutput = vm::replayOutput,
                    onOpenArtifact = { a, share -> filesVm.openRemote(a.path, a.name, share) },
                )
            } else if (toolForm.schema != null) {
                BackHandler { vm.closeToolForm() }
                ToolFormScreen(
                    state = toolForm,
                    onBack = vm::closeToolForm,
                    onEdit = vm::editFieldValue,
                    onExample = { ex: ToolExample ->
                        vm.openToolForm(toolForm.schema.id, preset = ex.args)
                    },
                    onSubmit = vm::submitToolForm,
                )
            } else if (showSettings) {
                BackHandler { showSettings = false }
                SettingsScreen(
                    connected = dashboard.error == null,
                    onTestConnection = vm::refresh,
                    onStopBridge = vm::stopBridge,
                    onRerunSetup = {
                        vm.resetAppState()
                        filesVm.resetFiles()
                        setupVm.resetSetup()
                        showSettings = false
                    },
                    onBack = { showSettings = false },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (showKillerDebug) {
                BackHandler { showKillerDebug = false }
                KillerDebugScreen(
                    viewModel = killerVm,
                    onBack = { showKillerDebug = false },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // ---- Main Shell (3 Tabs: Run, Kill, Activity) ------------
                val isInstagramSubScreen = screen == Screen.RUN && selectedInstagramTool != null
                val isDownloaderSubScreen = screen == Screen.RUN && isDownloaderOpen

                BackHandler(enabled = screen != Screen.RUN || isDownloaderSubScreen || isInstagramSubScreen) {
                    when {
                        isDownloaderSubScreen -> {
                            isDownloaderOpen = false
                            stagedDownloadItem = null
                        }
                        isInstagramSubScreen -> selectedInstagramTool = null
                        else -> screen = Screen.RUN
                    }
                }

                val connected = dashboard.error == null
                val hasRunningJobs = dashboard.jobs.any {
                    it.status == "RUNNING" || it.status == "STARTING" || it.status == "QUEUED" || it.status == "CANCELLING"
                }

                Scaffold(
                    topBar = {
                        TrmxTopBar(
                            title = when {
                                screen == Screen.KILL -> "App Killer"
                                screen == Screen.ACTIVITY -> "Activity"
                                isDownloaderSubScreen -> "Downloader"
                                isInstagramSubScreen -> selectedInstagramTool!!.title
                                else -> "TRMX"
                            },
                            subtitle = when {
                                screen == Screen.KILL -> "Automated force-stop"
                                screen == Screen.ACTIVITY -> "Jobs & live output"
                                isDownloaderSubScreen -> "Video & MP3 download engine"
                                isInstagramSubScreen -> selectedInstagramTool!!.description
                                else -> if (connected) "Connected · 127.0.0.1:8080" else "Bridge unreachable"
                            },
                            onBack = when {
                                isDownloaderSubScreen -> {
                                    {
                                        isDownloaderOpen = false
                                        stagedDownloadItem = null
                                    }
                                }
                                isInstagramSubScreen -> {
                                    { selectedInstagramTool = null }
                                }
                                else -> null
                            },
                            actions = {
                                if (screen == Screen.KILL) {
                                    TextButton(onClick = { showKillerDebug = true }) {
                                        Text(
                                            "Debug",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = P.Accent,
                                        )
                                    }
                                }
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(
                                        Icons.Outlined.Settings,
                                        contentDescription = "settings",
                                        tint = P.TextSecondary,
                                    )
                                }
                            },
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = Color(0xFF000000),
                            tonalElevation = 0.dp,
                        ) {
                            NavigationBarItem(
                                selected = screen == Screen.RUN,
                                onClick = {
                                    screen = Screen.RUN
                                    // return to homepage if switching to Run
                                    if (screen != Screen.RUN) {
                                        isDownloaderOpen = false
                                        selectedInstagramTool = null
                                    }
                                },
                                icon = {
                                    Icon(
                                        if (screen == Screen.RUN) Icons.Filled.Terminal
                                        else Icons.Outlined.Terminal,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                label = { Text("Run", style = MaterialTheme.typography.labelSmall) },
                                colors = TrmxNavItemColors(),
                            )

                            NavigationBarItem(
                                selected = screen == Screen.KILL,
                                onClick = { screen = Screen.KILL },
                                icon = {
                                    Icon(
                                        Icons.Outlined.PowerSettingsNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                label = { Text("Kill", style = MaterialTheme.typography.labelSmall) },
                                colors = TrmxNavItemColors(),
                            )

                            NavigationBarItem(
                                selected = screen == Screen.ACTIVITY,
                                onClick = { screen = Screen.ACTIVITY },
                                icon = {
                                    Box {
                                        Icon(
                                            if (screen == Screen.ACTIVITY) Icons.Filled.History
                                            else Icons.Outlined.History,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        if (hasRunningJobs) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .align(Alignment.TopEnd)
                                                    .background(Color(0xFF66BB6A), CircleShape)
                                            )
                                        }
                                    }
                                },
                                label = { Text("Activity", style = MaterialTheme.typography.labelSmall) },
                                colors = TrmxNavItemColors(),
                            )
                        }
                    },
                ) { padding ->
                    when (screen) {
                        Screen.RUN -> {
                            when {
                                isDownloaderOpen -> {
                                    DownloaderScreen(
                                        stagedItem = stagedDownloadItem,
                                        viewModel = downloadVm,
                                        onBack = {
                                            isDownloaderOpen = false
                                            stagedDownloadItem = null
                                        },
                                        modifier = Modifier.padding(padding),
                                    )
                                }
                                selectedInstagramTool != null -> {
                                    val currentTool = selectedInstagramTool!!
                                    InstagramToolScreen(
                                        tool = currentTool,
                                        viewModel = downloadVm,
                                        onOpenDownloader = { item ->
                                            stagedDownloadItem = item
                                            isDownloaderOpen = true
                                        },
                                        onBack = { selectedInstagramTool = null },
                                        modifier = Modifier.padding(padding),
                                    )
                                }
                                else -> {
                                    RunScreen(
                                        onRunCommand = vm::runCommandLine,
                                        onGoToKill = { screen = Screen.KILL },
                                        onOpenDownloader = {
                                            stagedDownloadItem = null
                                            isDownloaderOpen = true
                                        },
                                        onOpenInstagramTool = { tool ->
                                            selectedInstagramTool = tool
                                        },
                                        modifier = Modifier.padding(padding),
                                    )
                                }
                            }
                        }
                        Screen.KILL -> {
                            KillScreen(
                                viewModel = killerVm,
                                modifier = Modifier.padding(padding),
                            )
                        }
                        Screen.ACTIVITY -> {
                            LaunchedEffect(Unit) { if (dashboard.info == null) vm.refresh() }
                            ActivityScreen(
                                state = dashboard,
                                onJobClick = vm::selectJob,
                                onCancelJob = vm::cancelJob,
                                onGoToRun = { screen = Screen.RUN },
                                modifier = Modifier.padding(padding),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Crash-report gate: when the previous run died, the saved stack trace
 * is shown FIRST — on a screen built from plain Compose primitives and
 * no ViewModels, so it renders even if the failing code is in the main
 * UI itself. "Clear and continue" deletes the report and proceeds;
 * if the fault is still present the app will crash again and the next
 * launch will show a fresh report.
 */
@Composable
private fun CrashGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val text = report
    if (text == null) {
        content()
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Last time, TRMX stopped — here is why",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Screenshot this screen and send it — it names the exact fault.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.padding(6.dp))
        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.padding(6.dp))
        Row {
            Button(onClick = { CrashLog.clear(context); report = null }) {
                Text("Clear and continue")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                runCatching {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share crash report"))
                }
            }) {
                Text("Share")
            }
        }
    }
}
