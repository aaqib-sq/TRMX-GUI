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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.trmx.gui.agent.AgentViewModel
import dev.trmx.gui.model.ToolExample
import dev.trmx.gui.ui.AgentScreen
import dev.trmx.gui.ui.ChainsScreen
import dev.trmx.gui.ui.DashboardScreen
import dev.trmx.gui.ui.DiagnosticsSheet
import dev.trmx.gui.ui.SchemaBuilderScreen
import dev.trmx.gui.ui.ServicesScreen
import dev.trmx.gui.ui.SettingsScreen
import dev.trmx.gui.ui.SetupScreen
import dev.trmx.gui.ui.FilesScreen
import dev.trmx.gui.ui.JobDetailScreen
import dev.trmx.gui.ui.ToolFormScreen
import dev.trmx.gui.ui.ToolboxScreen
import dev.trmx.gui.ui.TrmxIconButton
import dev.trmx.gui.ui.TrmxNavItemColors
import dev.trmx.gui.ui.TrmxTopBar
import dev.trmx.gui.ui.TRMXTheme
import dev.trmx.gui.wizard.SetupViewModel
import dev.trmx.gui.wizard.WizardStep
import java.io.File

/** Tabs, in order: the Agent is the front door (revival), Tools second. */
private enum class Screen { AGENT, TOOLS, HOME, FILES, SERVICES }

/** Advanced surfaces, opened as overlays above the tabs (never tab items). */
private enum class Overlay { CHAINS, AI_BUILDER, SETTINGS }

private const val FILE_PROVIDER = "dev.trmx.gui.fileprovider"

/** Recipe shortcut id waiting for the wizard-safe moment (set by the activity). */
private var pendingRecipeId: String? = null

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRecipeIntent(intent)
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
    }

    private fun handleRecipeIntent(intent: Intent?) {
        val id = intent?.getStringExtra("recipe_id") ?: return
        pendingRecipeId = id
    }
}

@Composable
fun AppRoot(
    vm: AppViewModel = viewModel(),
    setupVm: SetupViewModel = viewModel(),
    agentVm: AgentViewModel = viewModel(),
) {
    val setup by setupVm.wizard.collectAsStateWithLifecycle()
    val checks by setupVm.checks.collectAsStateWithLifecycle()
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
    val chainsState by vm.chains.collectAsStateWithLifecycle()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val artifactsState by vm.artifacts.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.AGENT) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Cold-start recipe shortcut (set by MainActivity before composition).
    LaunchedEffect(Unit) {
        pendingRecipeId?.let { id ->
            pendingRecipeId = null
            screen = Screen.TOOLS
            vm.loadTools()
            vm.openRecipe(id)
        }
    }

    when {
        setup.step != WizardStep.DONE -> SetupScreen(
            state = setup,
            checks = checks,
            onResume = setupVm::recheck,
            onOpenRunCommandSettings = setupVm::openRunCommandSettings,
            onOpenTermuxApp = setupVm::openTermuxApp,
            onConfirmAllowExternal = setupVm::confirmAllowExternal,
            onBegin = setupVm::beginSetup,
            onSkipPythonWait = setupVm::skipPythonWait,
            onRetry = setupVm::retrySetup,
            onReset = setupVm::resetSetup,
            existingToken = setupVm.manualToken,
            onVerifyManualPairing = setupVm::verifyManualPairing,
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
            // chains and the self-healing toolbox install ride on this).
            LaunchedEffect(Unit) { vm.startEvents() }

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
            } else if (toolForm.schema != null) {
                // The form renders above the tab content; closing it returns
                // to whichever tab opened it (Toolbox, Chains, or Dashboard).
                BackHandler { vm.closeToolForm() }
                ToolFormScreen(
                    state = toolForm,
                    onBack = vm::closeToolForm,
                    onEdit = vm::editFieldValue,
                    onBrowsePath = { arg ->
                        vm.pickPathFor(arg)
                        screen = Screen.FILES
                    },
                    onExample = { ex: ToolExample ->
                        vm.openToolForm(toolForm.schema.id,
                                        stepIndex = toolForm.stepIndex,
                                        preset = ex.args)
                    },
                    onSubmit = vm::submitToolForm,
                    onSaveRecipe = vm::saveRecipeFromForm,
                    onSaveService = vm::saveServiceFromForm,
                )
            } else if (currentOverlay != null) {
                // Advanced surfaces: Chains, AI builder, Settings — overlays,
                // never tabs.
                BackHandler { overlay = null }
                when (currentOverlay) {
                    Overlay.CHAINS -> Column(Modifier.fillMaxSize()) {
                        TrmxTopBar(title = "Chains", onBack = { overlay = null })
                        ChainsScreen(
                            state = chainsState,
                            tools = toolsState.tools,
                            jobs = dashboard.jobs,
                            onNew = vm::newChainBuilder,
                            onEditDef = vm::editChainDef,
                            onDeleteDef = vm::deleteChainDef,
                            onRun = vm::runChain,
                            onResume = vm::resumeChain,
                            onStopRun = vm::stopChainRun,
                            onSetTitle = vm::setChainTitle,
                            onAddStep = vm::addChainStep,
                            onRemoveStep = vm::removeChainStep,
                            onEditStep = { i -> vm.editChainStep(i) },
                            onSaveDef = vm::saveChainDef,
                            modifier = Modifier.weight(1f),
                        )
                    }
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
                        onRepair = setupVm::runRepair,
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
                                Screen.HOME -> "TRMX"
                                Screen.FILES -> "Files"
                                Screen.SERVICES -> "Services"
                            },
                            actions = {
                                if (screen == Screen.SERVICES) {
                                    TrmxIconButton(
                                        icon = Icons.Outlined.Refresh,
                                        contentDescription = "refresh",
                                        onClick = vm::loadServices)
                                }
                                TrmxIconButton(
                                    icon = Icons.Filled.MoreVert,
                                    contentDescription = "more",
                                    onClick = { menuOpen = true })
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Chains") },
                                        onClick = {
                                            menuOpen = false
                                            overlay = Overlay.CHAINS
                                        })
                                    DropdownMenuItem(
                                        text = { Text("AI builder") },
                                        onClick = {
                                            menuOpen = false
                                            overlay = Overlay.AI_BUILDER
                                        })
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
                        // Monochrome M3 NavigationBar: OLED black, 24 dp
                        // vector icons (outlined -> filled when selected),
                        // white active tint, grey inactive.
                        NavigationBar(
                            containerColor = Color(0xFF000000),
                            tonalElevation = 0.dp,
                        ) {
                            NavigationBarItem(
                                selected = screen == Screen.AGENT,
                                onClick = { screen = Screen.AGENT },
                                icon = {
                                    Icon(
                                        if (screen == Screen.AGENT) Icons.Filled.AutoAwesome
                                        else Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp))
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
                                label = { Text("Home", style = MaterialTheme.typography.labelSmall) },
                                colors = TrmxNavItemColors())
                            NavigationBarItem(
                                selected = screen == Screen.FILES,
                                onClick = {
                                    vm.clearFilesNotice()
                                    screen = Screen.FILES
                                },
                                icon = {
                                    Icon(
                                        if (screen == Screen.FILES) Icons.Filled.Folder
                                        else Icons.Outlined.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp))
                                },
                                label = { Text("Files", style = MaterialTheme.typography.labelSmall) },
                                colors = TrmxNavItemColors())
                            NavigationBarItem(
                                selected = screen == Screen.SERVICES,
                                onClick = {
                                    vm.clearServicesNotice()
                                    screen = Screen.SERVICES
                                },
                                icon = {
                                    Icon(
                                        if (screen == Screen.SERVICES) Icons.Filled.Dns
                                        else Icons.Outlined.Dns,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp))
                                },
                                label = { Text("Services", style = MaterialTheme.typography.labelSmall) },
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
                        Screen.FILES -> {
                            // Back walks up the directory tree first; the tab
                            // bar handles leaving (BackHandler above → Agent).
                            BackHandler {
                                if (!vm.filesUp()) screen = Screen.AGENT
                            }
                            LaunchedEffect(Unit) {
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
                                onPicked = { path -> vm.pathPicked(path) },
                                onCancelPick = { vm.cancelPathPick() },
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
                                onOpenChains = { overlay = Overlay.CHAINS },
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
                        Screen.SERVICES -> {
                            LaunchedEffect(Unit) { vm.loadServices() }
                            ServicesScreen(
                                state = servicesState,
                                onStart = vm::startService,
                                onStop = vm::stopService,
                                onRestart = vm::restartService,
                                onToggleAutostart = vm::setServiceAutostart,
                                onDelete = vm::deleteService,
                                onOpenJob = { vm.selectJob(it) },
                                onOpenToolbox = { screen = Screen.TOOLS },
                                onRefresh = vm::loadServices,
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
                                onRepair = setupVm::runRepair,
                                onNewJob = { screen = Screen.AGENT },
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
