package dev.trmx.gui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.trmx.gui.agent.AgentViewModel
import dev.trmx.gui.model.ToolExample
import dev.trmx.gui.ui.AgentScreen
import dev.trmx.gui.ui.DashboardScreen
import dev.trmx.gui.ui.DiagnosticsSheet
import dev.trmx.gui.ui.SchemaBuilderScreen
import dev.trmx.gui.ui.ServicesScreen
import dev.trmx.gui.ui.SettingsScreen
import dev.trmx.gui.ui.ConnectScreen
import dev.trmx.gui.ui.FilesScreen
import dev.trmx.gui.ui.JobDetailScreen
import dev.trmx.gui.ui.ToolFormScreen
import dev.trmx.gui.ui.ToolboxScreen
import dev.trmx.gui.ui.TrmxIconButton
import dev.trmx.gui.ui.TrmxNavItemColors
import dev.trmx.gui.ui.TrmxTopBar
import dev.trmx.gui.ui.Tokens.Palette as P
import dev.trmx.gui.ui.TRMXTheme
import dev.trmx.gui.wizard.PairLink
import dev.trmx.gui.wizard.SetupViewModel
import dev.trmx.gui.wizard.WizardStep
import java.io.File

/** Tabs (redesign v2): Agent (front door), Work, Tools. Files and
 *  Services are root layers above the shell, not tabs. */
private enum class Screen { AGENT, TOOLS, HOME }

/** Advanced surfaces, opened as overlays above the tabs (never tab items). */
private enum class Overlay { AI_BUILDER, SETTINGS }

private const val FILE_PROVIDER = "dev.trmx.gui.fileprovider"

/** Recipe shortcut id waiting for the wizard-safe moment (set by the activity). */
private var pendingRecipeId: String? = null

/** Pair-link token waiting for AppRoot (set by the activity). Snapshot
 *  state, NOT a plain var: a warm onNewIntent must recompose AppRoot so
 *  the token is consumed even while the app is already open. */
private var pendingPairToken by mutableStateOf<String?>(null)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRecipeIntent(intent)
        handlePairIntent(intent)
        setContent {
            TRMXTheme {
                CrashGate { AppRoot() }
            }
        }
    }

    /** Home-screen recipe shortcuts (launchMode=singleTask -> onNewIntent). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleRecipeIntent(intent)
        handlePairIntent(intent)
    }

    private fun handleRecipeIntent(intent: Intent?) {
        val id = intent?.getStringExtra("recipe_id") ?: return
        pendingRecipeId = id
    }

    /** Pair-link (trmx://pair?token=… from `trmx pair-link` in Termux):
     *  the bridge's token, adopted by SetupViewModel in AppRoot. */
    private fun handlePairIntent(intent: Intent?) {
        val token = PairLink.tokenFrom(intent?.dataString) ?: return
        pendingPairToken = token
    }
}

@Composable
fun AppRoot(
    vm: AppViewModel = viewModel(),
    setupVm: SetupViewModel = viewModel(),
    agentVm: AgentViewModel = viewModel(),
) {
    val setup by setupVm.wizard.collectAsStateWithLifecycle()
    val setupLogText by setupVm.setupLogText.collectAsStateWithLifecycle()
    val connect by setupVm.connect.collectAsStateWithLifecycle()
    val repair by setupVm.repair.collectAsStateWithLifecycle()
    val agentState by agentVm.state.collectAsStateWithLifecycle()
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val submitForm by vm.submitForm.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val filesState by vm.files.collectAsStateWithLifecycle()
    val toolsState by vm.tools.collectAsStateWithLifecycle()
    val schemaBuilder by vm.schemaBuilder.collectAsStateWithLifecycle()
    val servicesState by vm.services.collectAsStateWithLifecycle()
    val aiConfigState by vm.aiConfig.collectAsStateWithLifecycle()
    val toolFormState by vm.toolForm.collectAsStateWithLifecycle()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val artifactsState by vm.artifacts.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.AGENT) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var filesLayer by remember { mutableStateOf(false) }
    var servicesLayer by remember { mutableStateOf(false) }

    // Cold-start recipe shortcut (set by MainActivity before composition).
    LaunchedEffect(Unit) {
        pendingRecipeId?.let { id ->
            pendingRecipeId = null
            screen = Screen.TOOLS
            vm.loadTools()
            vm.openRecipe(id)
        }
    }

    // Pair-link token (set by MainActivity from a trmx://pair intent,
    // cold start or warm onNewIntent — the snapshot state re-triggers
    // this effect either way). The viewmodel verifies the connection.
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
            // local capture: delegated properties cannot be smart-cast
            val detailState = detail
            val toolForm = toolFormState
            val currentOverlay = overlay

            // Staged open/share (ADR-008): fired at root so it works from
            // every screen — Files, Toolbox (recipe share), artifacts, anywhere.
            val pending = filesState.pendingOpen
            LaunchedEffect(pending) {
                if (pending == null) return@LaunchedEffect
                try {
                    val uri = FileProvider.getUriForFile(
                        vm.getApplication<android.app.Application>(), FILE_PROVIDER,
                        File(pending.path))
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
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    vm.afterOpen(null)
                } catch (e: ActivityNotFoundException) {
                    vm.afterOpen("no app can open \u201c${File(pending.path).name}\u201d " +
                        "(${pending.mime}) \u2014 try Share instead")
                } catch (e: SecurityException) {
                    vm.afterOpen("cannot open: ${e.message}")
                }
            }

            // Live events while the app is open (dashboard updates everywhere;
            // the self-healing toolbox install rides on this).
            LaunchedEffect(Unit) { vm.startEvents() }

            // §6-H4: never reconnect the events stream while backgrounded.
            // (MainActivity is the live-browser owner of these effects; the
            // LaunchedEffect above covers the cold-start case.)
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
                    onOpenArtifact = vm::openArtifact,
                )
            } else if (filesLayer) {
                // Files as a root layer (redesign v2): reached from the
                // Work tab and from a tool form's path picker — it sits
                // ABOVE the form, so picking a path returns straight to it.
                BackHandler {
                    if (!vm.filesUp()) filesLayer = false
                }
                LaunchedEffect(Unit) {
                    vm.clearFilesNotice()
                    if (filesState.entries.isEmpty()) vm.openPath(filesState.path)
                }
                val pickArg = toolFormState.pathArg
                FilesScreen(
                    state = filesState,
                    onOpenPath = vm::openPath,
                    onUp = { vm.filesUp() },
                    onRefresh = vm::refreshFiles,
                    onMakeDir = vm::makeDir,
                    onRename = vm::renameEntry,
                    onDelete = vm::deleteEntry,
                    onDownload = vm::downloadEntry,
                    onUpload = vm::uploadFromUri,
                    onOpenFile = { vm.openEntry(it, false) },
                    onShareFile = { vm.openEntry(it, true) },
                    pickFile = pickArg?.let { arg ->
                        toolFormState.schema?.args
                            ?.firstOrNull { it.name == arg }
                            ?.let { it.path_kind != "dir" }
                    },
                    onPicked = { path ->
                        vm.pathPicked(path)
                        filesLayer = false
                    },
                    onCancelPick = {
                        vm.cancelPathPick()
                        filesLayer = false
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (toolForm.schema != null) {
                // The form renders above the tab content; closing it returns
                // to whichever tab opened it (Toolbox or Dashboard).
                BackHandler { vm.closeToolForm() }
                ToolFormScreen(
                    state = toolForm,
                    onBack = vm::closeToolForm,
                    onEdit = vm::editFieldValue,
                    onBrowsePath = { arg ->
                        vm.pickPathFor(arg)
                        filesLayer = true
                    },
                    onExample = { ex: ToolExample ->
                        vm.openToolForm(toolForm.schema.id,
                                        preset = ex.args)
                    },
                    onSubmit = vm::submitToolForm,
                    onSaveRecipe = vm::saveRecipeFromForm,
                    onSaveService = vm::saveServiceFromForm,
                )
            } else if (servicesLayer) {
                // Services as a root layer (redesign v2): opened from the
                // Tools tab — no longer a bottom-nav tab of its own.
                BackHandler { servicesLayer = false }
                LaunchedEffect(Unit) { vm.loadServices() }
                Column(Modifier.fillMaxSize()) {
                    TrmxTopBar(
                        title = "Services",
                        onBack = { servicesLayer = false },
                        actions = {
                            TrmxIconButton(
                                icon = Icons.Outlined.Refresh,
                                contentDescription = "refresh",
                                onClick = vm::loadServices)
                        })
                    ServicesScreen(
                        state = servicesState,
                        onStart = vm::startService,
                        onStop = vm::stopService,
                        onRestart = vm::restartService,
                        onToggleAutostart = vm::setServiceAutostart,
                        onDelete = vm::deleteService,
                        onOpenJob = { vm.selectJob(it) },
                        onOpenToolbox = {
                            servicesLayer = false
                            screen = Screen.TOOLS
                        },
                        onRefresh = vm::loadServices,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (currentOverlay != null) {
                // Advanced surfaces: AI builder, Settings — overlays,
                // never tabs.
                BackHandler { overlay = null }
                when (currentOverlay) {
                    Overlay.AI_BUILDER -> {
                        LaunchedEffect(Unit) {
                            vm.loadTools()
                            vm.loadAiConfig()
                        }
                        SchemaBuilderScreen(
                            state = schemaBuilder,
                            aiToolInstalled = toolsState.tools
                                .firstOrNull { it.schema.id == "ai-schema-builder" }
                                ?.installed,
                            config = aiConfigState,
                            onLoadConfig = vm::loadAiConfig,
                            onSaveConfig = vm::saveAiConfig,
                            onSetAiMode = vm::setAiMode,
                            onSetAiProvider = vm::setAiProvider,
                            onSetAiEndpoint = vm::setAiEndpoint,
                            onSetAiModel = vm::setAiModel,
                            onSetAiApiKey = vm::setAiApiKey,
                            onForgetAiKey = vm::forgetAiKey,
                            onSetAiCommand = vm::setAiCommand,
                            onEditDescription = vm::editSchemaDescription,
                            onSubmit = vm::submitSchemaBuilder,
                            onOpenToolbox = {
                                overlay = null
                                screen = Screen.TOOLS
                            },
                            onOpenJob = { vm.selectJob(it) },
                            onBack = { overlay = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Overlay.SETTINGS -> SettingsScreen(
                        connected = dashboard.error == null,
                        repair = repair,
                        aiConfig = aiConfigState,
                        onRepair = {
                            vm.resetAppState()
                            setupVm.resetSetup()
                            overlay = null
                        },
                        onApplyLocalPreset = vm::applyLocalGgufPreset,
                        onDismissRepair = setupVm::dismissRepair,
                        onRerunSetup = {
                            vm.resetAppState()
                            setupVm.resetSetup()
                            overlay = null
                        },
                        onLoadAiConfig = vm::loadAiConfig,
                        onBack = { overlay = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                // ---- main shell: bottom navigation -----------------------
                BackHandler(enabled = screen != Screen.AGENT) {
                    screen = Screen.AGENT
                }
                Scaffold(
                    topBar = {
                        TrmxTopBar(
                            title = when (screen) {
                                Screen.AGENT -> "Agent"
                                Screen.TOOLS -> "Tools"
                                Screen.HOME -> "Work"
                            },
                            actions = {
                                TrmxIconButton(
                                    icon = Icons.Filled.MoreVert,
                                    contentDescription = "more",
                                    onClick = { menuOpen = true })
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Diagnostics") },
                                        onClick = {
                                            menuOpen = false
                                            showDiagnostics = true
                                        })
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        onClick = {
                                            menuOpen = false
                                            overlay = Overlay.SETTINGS
                                        })
                                }
                            },
                        )
                    },
                    bottomBar = {
                        // NavigationBar (redesign v2): AMOLED black, 24 dp
                        // vector icons (outlined -> filled when selected),
                        // amber active tint, warm grey inactive.
                        NavigationBar(
                            containerColor = Color(0xFF000000),
                            tonalElevation = 0.dp,
                        ) {
                            NavigationBarItem(
                                selected = screen == Screen.AGENT,
                                onClick = { screen = Screen.AGENT },
                                icon = {
                                    Box {
                                        Icon(
                                            if (screen == Screen.AGENT) Icons.Filled.AutoAwesome
                                            else Icons.Outlined.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp))
                                        // Running dot (§1-M9): a task in
                                        // flight stays visible from any tab.
                                        if (agentState.active) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .align(Alignment.TopEnd)
                                                    .background(P.Accent, CircleShape))
                                        }
                                    }
                                },
                                label = { Text("Agent", style = MaterialTheme.typography.labelSmall) },
                                colors = TrmxNavItemColors())
                            NavigationBarItem(
                                selected = screen == Screen.TOOLS,
                                onClick = { screen = Screen.TOOLS },
                                icon = {
                                    Icon(
                                        if (screen == Screen.TOOLS) Icons.Filled.Build
                                        else Icons.Outlined.Build,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp))
                                },
                                label = { Text("Tools", style = MaterialTheme.typography.labelSmall) },
                                colors = TrmxNavItemColors())
                            NavigationBarItem(
                                selected = screen == Screen.HOME,
                                onClick = { screen = Screen.HOME },
                                icon = {
                                    Icon(
                                        if (screen == Screen.HOME) Icons.Filled.Home
                                        else Icons.Outlined.Home,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp))
                                },
                                label = { Text("Work", style = MaterialTheme.typography.labelSmall) },
                                colors = TrmxNavItemColors())
                        }
                    },
                ) { padding ->
                    when (screen) {
                        Screen.AGENT -> {
                            LaunchedEffect(Unit) { agentVm.loadBackend() }
                            AgentScreen(
                                state = agentState,
                                onInput = agentVm::inputChanged,
                                onSend = agentVm::send,
                                onAllow = agentVm::allow,
                                onDeny = agentVm::deny,
                                onStop = agentVm::stop,
                                onDismissSendError = agentVm::clearSendError,
                                onDismissStreamError = agentVm::clearStreamError,
                                modifier = Modifier.padding(padding),
                            )
                        }
                        Screen.TOOLS -> {
                            LaunchedEffect(Unit) { vm.loadTools() }
                            ToolboxScreen(
                                state = toolsState,
                                recipes = recipes,
                                submitForm = submitForm,
                                onRefresh = vm::refreshToolsNow,
                                onOpenTool = { toolId ->
                                    vm.openToolForm(toolId)
                                },
                                onInstall = vm::installTool,
                                onOpenRecipe = { id -> vm.openRecipe(id) },
                                onDeleteRecipe = vm::deleteRecipe,
                                onShareRecipe = vm::shareRecipe,
                                onImportRecipe = vm::importRecipe,
                                onOpenServices = { servicesLayer = true },
                                onOpenSchemaBuilder = { overlay = Overlay.AI_BUILDER },
                                onSubmitName = vm::editName,
                                onSubmitArgv = vm::editArgvText,
                                onSubmitCwd = vm::editCwd,
                                onSubmitTimeout = vm::editTimeout,
                                onSubmitJob = vm::submitJob,
                                onDismissSubmit = vm::clearSubmitErrors,
                                modifier = Modifier.padding(padding),
                            )
                        }
                        Screen.HOME -> {
                            LaunchedEffect(Unit) { if (dashboard.info == null) vm.refresh() }
                            DashboardScreen(
                                state = dashboard,
                                repair = repair,
                                onJobClick = vm::selectJob,
                                onCancelJob = vm::cancelJob,
                                onRepair = {
                                    // The old in-app repair ran the intent
                                    // pipeline, which cannot act on this
                                    // device — repair IS the connect flow.
                                    vm.resetAppState()
                                    setupVm.resetSetup()
                                },
                                onNewJob = { screen = Screen.AGENT },
                                onOpenFiles = { filesLayer = true },
                                modifier = Modifier.padding(padding),
                            )
                        }
                    }
                }

                // Deep bridge metrics + deliberate actions (UX-audit P1).
                if (showDiagnostics) {
                    DiagnosticsSheet(
                        state = dashboard,
                        onRefresh = vm::refresh,
                        onStopBridge = vm::stopBridge,
                        onRerunWizard = {
                            vm.resetAppState()
                            setupVm.resetSetup()
                            showDiagnostics = false
                        },
                        onDismiss = { showDiagnostics = false },
                    )
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
        Text("Last time, TRMX stopped — here is why",
             fontWeight = FontWeight.Bold,
             style = MaterialTheme.typography.titleMedium)
        Text("Screenshot this screen and send it — it names the exact fault.",
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             style = MaterialTheme.typography.bodySmall)
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
