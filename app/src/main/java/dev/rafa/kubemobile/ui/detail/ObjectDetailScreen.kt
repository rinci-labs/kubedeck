package dev.rafa.kubemobile.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.k8s.long
import dev.rafa.kubemobile.k8s.resourceName
import dev.rafa.kubemobile.k8s.ResourceUsage
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.ops.Status
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.NO_NAMESPACE
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.components.ConfirmDeleteDialog
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.HealthChip
import dev.rafa.kubemobile.ui.components.InfoChip
import dev.rafa.kubemobile.ui.components.KeyValueRow
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.MenuAction
import dev.rafa.kubemobile.ui.components.MonoText
import dev.rafa.kubemobile.ui.components.OverflowMenu
import dev.rafa.kubemobile.ui.components.TabStrip
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.components.SectionCard
import dev.rafa.kubemobile.ui.components.SectionHeader
import dev.rafa.kubemobile.ui.copyToClipboard
import dev.rafa.kubemobile.ui.fullTimestamp
import dev.rafa.kubemobile.ui.formatCpu
import dev.rafa.kubemobile.ui.humanAge
import dev.rafa.kubemobile.ui.humanBytes
import dev.rafa.kubemobile.ui.parseResourceKey
import dev.rafa.kubemobile.ui.routeKey
import dev.rafa.kubemobile.ui.screenViewModel

private val SCALABLE = setOf("Deployment", "StatefulSet", "ReplicaSet", "ReplicationController")
private val RESTARTABLE = setOf("Deployment", "StatefulSet", "DaemonSet")
private val TYPED_CONFIRM = setOf("Secret", "PersistentVolumeClaim", "PersistentVolume", "Namespace")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectDetailScreen(
    app: AppViewModel,
    navController: NavController,
    resourceKey: String,
    namespace: String,
    name: String,
) {
    val ref = remember(resourceKey) { parseResourceKey(resourceKey) }
    if (ref == null) {
        ErrorState(
            error = dev.rafa.kubemobile.ui.UiError(
                R.string.error_generic_title,
                "Unrecognised resource key \"$resourceKey\"",
                null,
            ),
        )
        return
    }

    val vm = screenViewModel(app) { a, _ -> ObjectDetailViewModel(a) }
    val body by vm.objectBody.collectAsStateWithLifecycle()
    val yaml by vm.yaml.collectAsStateWithLifecycle()
    val yamlEditing by vm.yamlEditing.collectAsStateWithLifecycle()
    val yamlDirty by vm.yamlDirty.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val eventsLoading by vm.eventsLoading.collectAsStateWithLifecycle()
    val eventsError by vm.eventsError.collectAsStateWithLifecycle()
    val containers by vm.containers.collectAsStateWithLifecycle()
    val revisions by vm.revisions.collectAsStateWithLifecycle()
    val replicaSets by vm.replicaSets.collectAsStateWithLifecycle()
    val replicaSetsLoading by vm.replicaSetsLoading.collectAsStateWithLifecycle()
    val replicaSetsError by vm.replicaSetsError.collectAsStateWithLifecycle()
    val pods by vm.pods.collectAsStateWithLifecycle()
    val podsLoading by vm.podsLoading.collectAsStateWithLifecycle()
    val podsError by vm.podsError.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val jobsLoading by vm.jobsLoading.collectAsStateWithLifecycle()
    val jobsError by vm.jobsError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var confirmDelete by remember { mutableStateOf(false) }
    var scaleOpen by remember { mutableStateOf(false) }
    var undoOpen by remember { mutableStateOf(false) }
    var syncOpen by remember { mutableStateOf(false) }
    var applyPlan by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var applyError by remember { mutableStateOf<String?>(null) }
    var drainOpen by remember { mutableStateOf(false) }
    val usage by vm.usage.collectAsStateWithLifecycle()
    val usageError by vm.usageError.collectAsStateWithLifecycle()

    val objectNamespace = namespace.takeUnless { it == NO_NAMESPACE }
    val resource = vm.resource

    // Keyed by the object's own route so each object keeps its own selection, and remembered
    // across configuration changes and process death: a rebuild resumes the tab that was open.
    var tab by rememberSaveable(ref.key, namespace, name) { mutableStateOf(DetailTab.SUMMARY) }

    LaunchedEffect(ref.key, name, namespace) {
        vm.start(ref, objectNamespace, name)
    }
    LaunchedEffect(tab, vm.kind) {
        if (tab == DetailTab.EVENTS) vm.loadEvents()
        vm.loadTab(tab)
    }

    val health = remember(body) {
        body?.let { Status.health(it, resource?.kind ?: ref.kind) }
    }
    val kindLabel = resource?.kind ?: ref.kind

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = listOfNotNull(kindLabel, objectNamespace).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = Spacing.ItemGap)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = { vm.load() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                    val actions = detailActions(
                        vm = vm,
                        kind = kindLabel,
                        resource = resource,
                        objectNamespace = objectNamespace,
                        name = name,
                        onOpenLogs = {
                            navController.navigate(
                                Routes.logs(
                                    objectNamespace.orEmpty(),
                                    name,
                                    kind = kindLabel.takeIf { it in WORKLOAD_LOG_KINDS },
                                ),
                            )
                        },
                        onOpenTerminal = {
                            navController.navigate(Routes.terminal(objectNamespace.orEmpty(), name))
                        },
                        onOpenForward = {
                            navController.navigate(Routes.portForward(objectNamespace.orEmpty(), name))
                        },
                        onDelete = { confirmDelete = true },
                        onScale = { scaleOpen = true },
                        onUndo = { undoOpen = true },
                        onSync = { syncOpen = true },
                        onCordon = { vm.setNodeSchedulable(false) { } },
                        onUncordon = { vm.setNodeSchedulable(true) { } },
                        onDrain = { drainOpen = true },
                    )
                    OverflowMenu(actions = actions)
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && body == null -> LoadingState(label = stringResource(R.string.state_loading))

                error != null && body == null -> {
                    val api = error
                    ErrorState(error = api!!, onRetry = { vm.load() })
                }

                body == null -> EmptyState(
                    title = stringResource(R.string.error_generic_title),
                    body = stringResource(R.string.detail_no_object),
                )

                else -> {
                    val document = body!!
                    val tabs = remember(vm.kind, document) { vm.tabs() }
                    // A tab can vanish if the object is re-kinded under us; fall back to Summary
                    // rather than rendering an index that no longer exists.
                    val selected = if (tab in tabs) tab else DetailTab.SUMMARY
                    Column(Modifier.fillMaxSize()) {
                        HeaderStrip(
                            health = health,
                            generation = document.long("metadata/generation"),
                            stale = Status.isStale(document),
                            created = document.str("metadata/creationTimestamp"),
                            namespace = objectNamespace,
                        )
                        TabStrip(
                            tabs = tabs.map { stringResource(it.labelRes()) },
                            selectedIndex = tabs.indexOf(selected),
                            onSelect = { index -> tab = tabs.getOrElse(index) { DetailTab.SUMMARY } },
                            modifier = Modifier.padding(horizontal = Spacing.TightGap),
                        )
                        when (selected) {
                            DetailTab.SUMMARY -> SummaryTab(
                                vm = vm,
                                document = document,
                                kind = kindLabel,
                                usage = usage,
                                usageError = usageError,
                            )

                            DetailTab.YAML -> YamlTab(
                                yaml = yaml,
                                editing = yamlEditing,
                                dirty = yamlDirty,
                                onEdit = { vm.setEditing(true) },
                                onChange = vm::editYaml,
                                onCancel = { vm.discardYamlChanges() },
                                onRefresh = { vm.refreshYaml() },
                                onApply = {
                                    vm.planApply()
                                        .onSuccess { applyPlan = it }
                                        .onFailure { failure ->
                                            applyError = if (failure is ApplyMismatch) {
                                                context.getString(
                                                    R.string.detail_apply_mismatch,
                                                    failure.actualKind,
                                                    failure.actualName,
                                                    failure.expectedKind,
                                                    failure.expectedName,
                                                )
                                            } else {
                                                failure.message ?: "YAML error"
                                            }
                                        }
                                },
                            )

                            DetailTab.EVENTS -> EventsTab(
                                events = events?.items.orEmpty(),
                                loading = eventsLoading,
                                error = eventsError,
                                onRetry = { vm.loadEvents(force = true) },
                            )

                            DetailTab.PODS -> PodsTab(
                                pods = pods,
                                loading = podsLoading,
                                error = podsError,
                                onRetry = { vm.loadPods(force = true) },
                                onOpen = { pod ->
                                    // The route key is derived from the catalog rather than
                                    // hand-written, so it can never disagree with discovery.
                                    vm.routeFor("pods")?.let { key ->
                                        navController.navigate(
                                            Routes.objectDetail(key, pod.namespace ?: NO_NAMESPACE, pod.name),
                                        )
                                    }
                                },
                                onLogs = { pod ->
                                    navController.navigate(
                                        Routes.logs(pod.namespace.orEmpty(), pod.name),
                                    )
                                },
                                onShell = { pod ->
                                    navController.navigate(
                                        Routes.terminal(pod.namespace.orEmpty(), pod.name),
                                    )
                                },
                            )

                            DetailTab.CONTAINERS -> ContainersTab(
                                containers = containers,
                                onLogs = { container ->
                                    navController.navigate(
                                        Routes.logs(objectNamespace.orEmpty(), name, container),
                                    )
                                },
                                onShell = { container ->
                                    navController.navigate(
                                        Routes.terminal(objectNamespace.orEmpty(), name, container),
                                    )
                                },
                            )

                            DetailTab.CONTROLLER -> ControllerTab(vm = vm, navController = navController)

                            DetailTab.REPLICASETS -> ReplicaSetsTab(
                                replicaSets = replicaSets,
                                loading = replicaSetsLoading,
                                error = replicaSetsError,
                                onRetry = { vm.loadReplicaSets(force = true) },
                                onOpen = { rs ->
                                    vm.routeFor("replicasets", "apps")?.let { key ->
                                        navController.navigate(
                                            Routes.objectDetail(key, objectNamespace ?: NO_NAMESPACE, rs.name),
                                        )
                                    }
                                },
                            )

                            DetailTab.JOBS -> JobsTab(
                                jobs = jobs,
                                loading = jobsLoading,
                                error = jobsError,
                                onRetry = { vm.loadJobs(force = true) },
                                onOpen = { job ->
                                    vm.routeFor("jobs", "batch")?.let { key ->
                                        navController.navigate(
                                            Routes.objectDetail(key, objectNamespace ?: NO_NAMESPACE, job.name),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            name = name,
            requireTyping = kindLabel in TYPED_CONFIRM,
            title = stringResource(R.string.list_delete_title, name),
            body = stringResource(R.string.list_delete_body),
            onConfirm = {
                confirmDelete = false
                vm.delete { navController.popBackStack() }
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (scaleOpen) {
        val current = body?.long("spec/replicas")?.toInt() ?: 1
        ScaleDialog(
            name = name,
            current = current,
            onDismiss = { scaleOpen = false },
            onConfirm = { replicas ->
                scaleOpen = false
                vm.scale(replicas) {
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(R.string.list_scaled, name, replicas),
                    )
                }
            },
        )
    }

    if (undoOpen) {
        RevisionPickerDialog(
            revisions = revisions,
            onDismiss = { undoOpen = false },
            onPick = { revision ->
                undoOpen = false
                vm.rolloutUndo(revision) { target ->
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(R.string.detail_undo_done, target),
                    )
                }
            },
        )
    }

    if (syncOpen) {
        ArgoSyncDialog(
            name = name,
            onDismiss = { syncOpen = false },
            onSync = { revision, prune, dryRun ->
                syncOpen = false
                vm.argoSync(revision, prune, dryRun)
            },
        )
    }

    applyPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { applyPlan = null },
            title = { Text(stringResource(R.string.detail_apply_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.detail_apply_check,
                        plan.str("kind").orEmpty(),
                        plan.str("metadata/name").orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = plan
                    applyPlan = null
                    vm.apply(target)
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { applyPlan = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (drainOpen) {
        DrainDialog(
            nodeName = name,
            onDismiss = { drainOpen = false },
            onConfirm = { includeDaemonSets ->
                drainOpen = false
                vm.drainNode(includeDaemonSets) { count ->
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(R.string.drain_done, count, name),
                    )
                }
            },
        )
    }

    applyError?.let { message ->
        AlertDialog(
            onDismissRequest = { applyError = null },
            title = { Text(stringResource(R.string.state_apply_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { applyError = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}

/** The label a tab shows. Every tab in [ObjectDetailViewModel.tabs] has one. */
private fun DetailTab.labelRes(): Int = when (this) {
    DetailTab.SUMMARY -> R.string.label_summary
    DetailTab.YAML -> R.string.label_yaml
    DetailTab.EVENTS -> R.string.label_events
    DetailTab.PODS -> R.string.label_pods
    DetailTab.CONTAINERS -> R.string.detail_containers_tab
    DetailTab.CONTROLLER -> R.string.label_ownership_controller
    DetailTab.REPLICASETS -> R.string.label_replicasets
    DetailTab.JOBS -> R.string.label_jobs
}

/* -------------------------------------------------------------------------------------------- */
/* Actions                                                                                       */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun detailActions(
    vm: ObjectDetailViewModel,
    kind: String,
    resource: dev.rafa.kubemobile.k8s.ApiResource?,
    objectNamespace: String?,
    name: String,
    onOpenLogs: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenForward: () -> Unit,
    onDelete: () -> Unit,
    onScale: () -> Unit,
    onUndo: () -> Unit,
    onSync: () -> Unit,
    onCordon: () -> Unit,
    onUncordon: () -> Unit,
    onDrain: () -> Unit,
): List<MenuAction> = with(vm) {
    val logsLabel = stringResource(R.string.action_logs)
    val shellLabel = stringResource(R.string.action_shell)
    val forwardLabel = stringResource(R.string.action_port_forward)
    val scaleLabel = stringResource(R.string.action_scale)
    val restartLabel = stringResource(R.string.action_restart_rollout)
    val undoLabel = stringResource(R.string.action_rollout_undo)
    val reconcileLabel = stringResource(R.string.action_reconcile)
    val suspendLabel = stringResource(R.string.action_suspend)
    val resumeLabel = stringResource(R.string.action_resume)
    val refreshLabel = stringResource(R.string.detail_argo_refresh)
    val hardRefreshLabel = stringResource(R.string.detail_argo_hard_refresh)
    val syncLabel = stringResource(R.string.action_sync)
    val terminateLabel = stringResource(R.string.action_terminate)
    val deleteLabel = stringResource(R.string.action_delete)
    val cordonLabel = stringResource(R.string.action_cordon)
    val uncordonLabel = stringResource(R.string.action_uncordon)
    val drainLabel = stringResource(R.string.action_drain)
    buildList {
        if (isPod && resource?.supports("get") == true) {
            add(MenuAction(logsLabel, onOpenLogs))
            add(MenuAction(shellLabel, onOpenTerminal))
            add(MenuAction(forwardLabel, onOpenForward))
        } else if (hasMergedLogs && resource?.supports("get") == true) {
            // A workload's logs are the union of its pods'; shell and port-forward stay pod-only.
            add(MenuAction(logsLabel, onOpenLogs))
        }
        if (kind in SCALABLE && resource?.supports("update") == true) {
            add(MenuAction(scaleLabel, onScale))
        }
        if (kind in RESTARTABLE && resource?.supports("patch") == true) {
            add(MenuAction(restartLabel, vm::rolloutRestart))
        }
        if (kind == "Deployment") {
            add(MenuAction(undoLabel, onUndo))
        }
        if (isFlux && resource?.supports("patch") == true) {
            add(MenuAction(reconcileLabel, { vm.reconcile(withSource = false) }))
            if (suspendState()) {
                add(MenuAction(resumeLabel, { vm.setSuspended(false) }))
            } else {
                add(MenuAction(suspendLabel, { vm.setSuspended(true) }))
            }
        }
        if (isArgoApplication && resource?.supports("patch") == true) {
            add(MenuAction(refreshLabel, { vm.argoRefresh(hard = false) }))
            add(MenuAction(hardRefreshLabel, { vm.argoRefresh(hard = true) }))
            add(MenuAction(syncLabel, onSync))
            add(MenuAction(terminateLabel, vm::argoTerminate))
        }
        if (supportsNodeActions() && (resource?.supports("patch") == true || resource?.supports("update") == true)) {
            // Cordon/Uncordon reads the live spec so the label states what the tap will do.
            if (isNodeUnschedulable()) {
                add(MenuAction(uncordonLabel, onUncordon))
            } else {
                add(MenuAction(cordonLabel, onCordon))
            }
            add(MenuAction(drainLabel, onDrain, destructive = true))
        }
        if (resource?.supports("delete") == true) {
            add(MenuAction(deleteLabel, onDelete, destructive = true))
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Header                                                                                        */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun HeaderStrip(
    health: dev.rafa.kubemobile.ops.ResourceHealth?,
    generation: Long?,
    stale: Boolean,
    created: String?,
    namespace: String?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.TopBarToContent),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            health?.let { HealthChip(it) }
            Spacer(Modifier.size(8.dp))
            SecondaryText(
                listOfNotNull(
                    created?.let { humanAge(it) },
                    generation?.let { "gen $it" },
                    stale.takeIf { it }?.let { stringResource(R.string.detail_generation_stale) },
                ).joinToString(" · "),
            )
        }
        health?.detail?.let { detail ->
            Spacer(Modifier.size(4.dp))
            SecondaryText(detail, maxLines = 3)
        }
        health?.progress?.let { progress ->
            if (progress < 1f) {
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Tabs                                                                                          */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun SummaryTab(
    vm: ObjectDetailViewModel,
    document: kotlinx.serialization.json.JsonObject,
    kind: String,
    usage: ResourceUsage?,
    usageError: dev.rafa.kubemobile.ui.UiError?,
) {
    val context = LocalContext.current
    val rows = remember(document, kind) { vm.detailRows() }
    val conditions = remember(document) { vm.conditions() }
    val labels = remember(document) { vm.labels() }
    val annotations = remember(document) { vm.annotations() }
    val allocatable = remember(document) { vm.nodeAllocatable() }
    val capacity = remember(document) { vm.nodeCapacity() }
    val metricsMissing = remember(document, usage) {
        vm.isPod && usage == null
    }

    LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
        if (rows.isNotEmpty()) {
            item {
                SectionCard(title = stringResource(R.string.label_status)) {
                    rows.forEach { row -> KeyValueRow(row.label, row.value, copyable = true) }
                }
            }
        }
        if (vm.isPod) {
            item {
                SectionCard(title = stringResource(R.string.label_usage)) {
                    val cpu = usage?.cpuMillis
                    val memory = usage?.memoryBytes
                    if (cpu != null || memory != null) {
                        cpu?.let { KeyValueRow(stringResource(R.string.label_cpu), formatCpu(it), copyable = true) }
                        memory?.let {
                            KeyValueRow(stringResource(R.string.label_memory), humanBytes(it), copyable = true)
                        }
                    } else {
                        // Absence is stated plainly rather than rendered as zeroes, so a cluster
                        // without metrics-server never looks like a broken pod.
                        SecondaryText(
                            text = if (usageError == null) {
                                stringResource(R.string.metrics_unavailable)
                            } else {
                                stringResource(R.string.metrics_request_failed, usageError.message)
                            },
                            modifier = Modifier.padding(horizontal = Spacing.CardPadding, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        if (kind == "Node") {
            if (allocatable.isNotEmpty() || capacity.isNotEmpty()) {
                item {
                    SectionCard(title = stringResource(R.string.node_allocatable)) {
                        allocatable.forEach { (k, v) -> KeyValueRow(k, v, copyable = true) }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.node_capacity)) {
                        capacity.forEach { (k, v) -> KeyValueRow(k, v, copyable = true) }
                        SecondaryText(
                            text = stringResource(R.string.node_usage_unavailable),
                            modifier = Modifier.padding(horizontal = Spacing.CardPadding, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        item {
            SectionCard(title = stringResource(R.string.label_metadata)) {
                KeyValueRow(stringResource(R.string.label_name), document.resourceName(), copyable = true)
                document.str("metadata/namespace")?.let {
                    KeyValueRow(stringResource(R.string.label_namespace), it, copyable = true)
                }
                KeyValueRow(
                    stringResource(R.string.label_api_version),
                    document.str("apiVersion").orEmpty(),
                    copyable = true,
                )
                document.str("metadata/uid")?.let {
                    KeyValueRow(stringResource(R.string.label_uid), it, copyable = true, monospace = true)
                }
                document.str("metadata/resourceVersion")?.let {
                    KeyValueRow(
                        stringResource(R.string.label_resource_version),
                        it,
                        copyable = true,
                        monospace = true,
                    )
                }
                document.str("metadata/creationTimestamp")?.let {
                    KeyValueRow(stringResource(R.string.label_created), fullTimestamp(it) ?: it)
                }
                document.long("metadata/generation")?.let {
                    KeyValueRow(stringResource(R.string.label_generation), it.toString())
                }
            }
        }
        if (conditions.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.label_conditions)) }
            items(conditions) { condition ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardPadding, vertical = Spacing.RowVertical),
                    colors = CardDefaults.cardColors(
                        containerColor = if (condition.isFalse) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                ) {
                    Column(Modifier.padding(Spacing.CardPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = condition.type,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = condition.status,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (condition.isFalse) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                        if (condition.reason.isNotBlank()) {
                            Spacer(Modifier.size(4.dp))
                            SecondaryText(condition.reason)
                        }
                        if (condition.message.isNotBlank()) {
                            Spacer(Modifier.size(4.dp))
                            Text(condition.message, style = MaterialTheme.typography.bodySmall)
                        }
                        if (condition.lastTransitionTime.isNotBlank()) {
                            Spacer(Modifier.size(4.dp))
                            SecondaryText(
                                listOfNotNull(
                                    humanAge(condition.lastTransitionTime),
                                    condition.observedGeneration?.let { "observed gen $it" },
                                ).joinToString(" · "),
                            )
                        }
                    }
                }
            }
        } else {
            item { SecondaryText(stringResource(R.string.detail_conditions_empty)) }
        }
        if (labels.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.label_labels)) }
            items(labels) { (key, value) ->
                KeyValueRow(key, value, copyable = true, monospace = true)
            }
        }
        if (annotations.isNotEmpty()) {
            item {
                SectionHeader(
                    stringResource(R.string.label_annotations),
                    modifier = Modifier.clickable {
                        context.copyToClipboard(
                            "annotations",
                            annotations.joinToString("\n") { (k, v) -> "$k: $v" },
                        )
                    },
                )
            }
            items(annotations) { (key, value) ->
                KeyValueRow(key, value, copyable = true, monospace = true)
            }
        }
    }
}

@Composable
private fun YamlTab(
    yaml: String,
    editing: Boolean,
    dirty: Boolean,
    onEdit: () -> Unit,
    onChange: (String) -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onApply: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.ScreenPadding)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!editing) {
                OutlinedButton(shape = RectangleShape, onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.detail_edit))
                }
                OutlinedButton(shape = RectangleShape, onClick = {
                    context.copyToClipboard("yaml", yaml)
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.action_copy))
                }
                IconButton(onClick = {
                    dev.rafa.kubemobile.ui.shareText(context, "yaml", yaml)
                }) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.action_share),
                    )
                }
            } else {
                Button(shape = RectangleShape, onClick = onApply, enabled = dirty) {
                    Text(stringResource(R.string.action_apply))
                }
                OutlinedButton(shape = RectangleShape, onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        if (editing) {
            OutlinedTextField(
                value = yaml,
                onValueChange = onChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                label = { Text(stringResource(R.string.label_yaml)) },
            )
        } else {
            SecondaryText(stringResource(R.string.detail_yaml_readonly))
            Spacer(Modifier.size(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxSize(),
            ) {
                SelectionContainer {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(Spacing.ItemGap),
                    ) {
                        // The only horizontal scroller in the app, and only for YAML.
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            MonoText(yaml, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsTab(
    events: List<kotlinx.serialization.json.JsonObject>,
    loading: Boolean,
    error: dev.rafa.kubemobile.ui.UiError?,
    onRetry: () -> Unit,
) {
    when {
        loading && events.isEmpty() -> LoadingState(label = stringResource(R.string.state_loading))

        error != null && events.isEmpty() -> ErrorState(error = error, onRetry = onRetry)

        events.isEmpty() -> EmptyState(
            title = stringResource(R.string.events_empty_title),
            body = stringResource(R.string.detail_no_events),
        )

        else -> LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
            items(events) { event -> EventCard(event) }
        }
    }
}

@Composable
fun EventCard(event: kotlinx.serialization.json.JsonObject, showObject: Boolean = true) {
    val type = event.str("type") ?: "Normal"
    val warning = type.equals("Warning", true)
    val health = Status.health(event, "Event")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (warning) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(Modifier.padding(Spacing.CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.str("reason").orEmpty().ifBlank { type },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                HealthChip(health)
            }
            if (showObject) {
                Spacer(Modifier.size(2.dp))
                SecondaryText(
                    listOfNotNull(
                        event.str("involvedObject/kind"),
                        event.str("involvedObject/namespace"),
                        event.str("involvedObject/name"),
                    ).joinToString(" · "),
                    maxLines = 1,
                )
            }
            if (!event.str("message").isNullOrBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(event.str("message").orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.size(4.dp))
            SecondaryText(
                listOfNotNull(
                    event.long("count")?.takeIf { it > 1 }?.let { "×$it" },
                    event.str("lastTimestamp")?.let { humanAge(it) },
                    event.str("source/component") ?: event.str("reportingComponent"),
                ).joinToString(" · "),
            )
        }
    }
}

@Composable
private fun ContainersTab(
    containers: List<ContainerInfo>,
    onLogs: (String) -> Unit,
    onShell: (String) -> Unit,
) {
    if (containers.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.label_containers),
            body = stringResource(R.string.detail_no_containers),
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
        items(containers, key = { it.name }) { container ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column(Modifier.padding(Spacing.CardPadding)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = container.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = container.state,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (container.state == "Running") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    MonoText(container.image, fontSize = 11.sp)
                    Spacer(Modifier.size(4.dp))
                    SecondaryText(
                        listOfNotNull(
                            if (container.ready) "ready" else "not ready",
                            stringResource(R.string.label_restarts) + " ${container.restarts}",
                            container.startedAt?.let { "since ${humanAge(it)}" },
                        ).joinToString(" · "),
                    )
                    container.stateDetail?.let {
                        Spacer(Modifier.size(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap)) {
                        OutlinedButton(shape = RectangleShape, onClick = { onLogs(container.name) }) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.action_logs))
                        }
                        OutlinedButton(shape = RectangleShape, onClick = { onShell(container.name) }) {
                            Icon(Icons.Filled.Terminal, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.action_shell))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodsTab(
    pods: List<PodInfo>,
    loading: Boolean,
    error: dev.rafa.kubemobile.ui.UiError?,
    onRetry: () -> Unit,
    onOpen: (PodInfo) -> Unit,
    onLogs: (PodInfo) -> Unit,
    onShell: (PodInfo) -> Unit,
) {
    when {
        loading && pods.isEmpty() -> LoadingState(label = stringResource(R.string.state_loading))

        error != null && pods.isEmpty() -> ErrorState(error = error, onRetry = onRetry)

        pods.isEmpty() -> EmptyState(
            title = stringResource(R.string.label_pods),
            body = stringResource(R.string.detail_no_pods),
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
        )

        else -> LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
            items(pods, key = { it.name }) { pod ->
                PodRow(pod = pod, onOpen = { onOpen(pod) }, onLogs = { onLogs(pod) }, onShell = { onShell(pod) })
            }
        }
    }
}

@Composable
private fun PodRow(
    pod: PodInfo,
    onOpen: () -> Unit,
    onLogs: () -> Unit,
    onShell: () -> Unit,
) {
    val statuses = pod.hasStatuses
    val healthy = statuses && pod.ready == pod.total
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(Spacing.CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pod.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InfoChip(
                    label = if (statuses) {
                        stringResource(R.string.detail_pod_ready, pod.ready, pod.total)
                    } else {
                        pod.phase.ifBlank { stringResource(R.string.state_unknown) }
                    },
                    tone = when {
                        !statuses -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.NEUTRAL
                        healthy -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.OK
                        else -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.BAD
                    },
                )
            }
            Spacer(Modifier.size(4.dp))
            if (statuses) {
                SecondaryText(
                    listOfNotNull(
                        pod.phase.takeIf { it.isNotBlank() },
                        stringResource(R.string.detail_pod_restarts, pod.restarts),
                        pod.node,
                        pod.age?.let { humanAge(it) },
                    ).joinToString(" · "),
                    maxLines = 2,
                )
            } else {
                // A pod with no container statuses yet is not "0/0 ready"; say what is missing.
                SecondaryText(
                    listOfNotNull(
                        stringResource(R.string.detail_pod_not_ready),
                        pod.node,
                        pod.age?.let { humanAge(it) },
                    ).joinToString(" · "),
                    maxLines = 2,
                )
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap)) {
                OutlinedButton(shape = RectangleShape, onClick = onLogs, contentPadding = PaddingValues(horizontal = Spacing.ChipPadding)) {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.action_logs))
                }
                OutlinedButton(shape = RectangleShape, onClick = onShell, contentPadding = PaddingValues(horizontal = Spacing.ChipPadding)) {
                    Icon(Icons.Filled.Terminal, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.action_shell))
                }
            }
        }
    }
}

@Composable
private fun ReplicaSetsTab(
    replicaSets: List<ReplicaSetRow>,
    loading: Boolean,
    error: dev.rafa.kubemobile.ui.UiError?,
    onRetry: () -> Unit,
    onOpen: (ReplicaSetRow) -> Unit,
) {
    when {
        loading && replicaSets.isEmpty() -> LoadingState(label = stringResource(R.string.state_loading))

        error != null && replicaSets.isEmpty() -> ErrorState(error = error, onRetry = onRetry)

        replicaSets.isEmpty() -> EmptyState(
            title = stringResource(R.string.label_replicasets),
            body = stringResource(R.string.detail_no_replicasets),
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
        )

        else -> LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
            items(replicaSets, key = { it.name }) { rs ->
                ReplicaSetRowCard(row = rs, onOpen = { onOpen(rs) })
            }
        }
    }
}

@Composable
private fun ReplicaSetRowCard(row: ReplicaSetRow, onOpen: () -> Unit) {
    // The current revision is marked with a tinted container and a chip; older ones stay quiet so
    // the eye lands on what is live now.
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
        colors = CardDefaults.cardColors(
            containerColor = if (row.currentRevision) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(Spacing.CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.label_revision) + " " + row.revision,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (row.currentRevision) {
                    InfoChip(
                        label = stringResource(R.string.label_current_revision),
                        tone = dev.rafa.kubemobile.ops.ResourceHealth.Tone.OK,
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (row.currentRevision) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.size(4.dp))
            SecondaryText(
                listOfNotNull(
                    stringResource(R.string.detail_replicaset_replicas, row.desired, row.current, row.ready),
                    row.age,
                ).joinToString(" · "),
                maxLines = 2,
            )
            row.image?.let {
                Spacer(Modifier.size(4.dp))
                MonoText(it, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun JobsTab(
    jobs: List<JobRow>,
    loading: Boolean,
    error: dev.rafa.kubemobile.ui.UiError?,
    onRetry: () -> Unit,
    onOpen: (JobRow) -> Unit,
) {
    when {
        loading && jobs.isEmpty() -> LoadingState(label = stringResource(R.string.state_loading))

        error != null && jobs.isEmpty() -> ErrorState(error = error, onRetry = onRetry)

        jobs.isEmpty() -> EmptyState(
            title = stringResource(R.string.label_jobs),
            body = stringResource(R.string.detail_no_jobs),
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
        )

        else -> LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
            items(jobs, key = { it.name }) { job ->
                Card(
                    onClick = { onOpen(job) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(Modifier.padding(Spacing.CardPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = job.name,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            InfoChip(
                                label = if (job.complete) {
                                    stringResource(R.string.status_complete)
                                } else if (job.active > 0) {
                                    stringResource(R.string.status_running)
                                } else {
                                    job.age.orEmpty()
                                },
                                tone = when {
                                    job.complete -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.OK
                                    job.failed > 0 -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.BAD
                                    job.active > 0 -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.WARN
                                    else -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.NEUTRAL
                                },
                            )
                        }
                        Spacer(Modifier.size(4.dp))
                        SecondaryText(
                            listOfNotNull(
                                stringResource(R.string.label_active) + " ${job.active}",
                                stringResource(R.string.label_succeeded) + " ${job.succeeded}",
                                job.failed.takeIf { it > 0 }?.let { stringResource(R.string.label_failed) + " $it" },
                                job.age,
                            ).joinToString(" · "),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The ownership chain plus the controlling spec: what owns this object, and the fields that decide
 * how it behaves. Same labelled-row language as Summary — no dense tables.
 */
@Composable
private fun ControllerTab(vm: ObjectDetailViewModel, navController: NavController) {
    val document = vm.objectBody.value
    val owners = remember(document) { vm.owners() }
    val controllerRows = remember(document) { vm.controllerRows() }
    val createdBy = remember(document) { vm.createdBy() }

    if (owners.isEmpty() && controllerRows.isEmpty() && createdBy == null) {
        EmptyState(
            title = stringResource(R.string.label_controller),
            body = stringResource(R.string.detail_no_controller),
        )
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
        if (controllerRows.isNotEmpty()) {
            item {
                SectionCard(title = stringResource(R.string.label_controller_spec)) {
                    controllerRows.forEach { (labelText, value) ->
                        KeyValueRow(labelText, value, copyable = true)
                    }
                }
            }
        }
        if (owners.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.label_owner)) }
            items(owners) { (ownerKind, ownerName, controller) ->
                val ownerResource = vm.ownerResource(ownerKind)
                ListItem(
                    headlineContent = { Text(ownerName) },
                    supportingContent = {
                        SecondaryText(
                            listOfNotNull(
                                ownerKind,
                                if (controller) stringResource(R.string.detail_owner_controller) else null,
                            ).joinToString(" · "),
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Filled.AccountTree, contentDescription = null)
                    },
                    modifier = if (ownerResource != null) {
                        Modifier.clickable {
                            navController.navigate(
                                Routes.objectDetail(
                                    ownerResource.routeKey(),
                                    vm.ownerNamespace() ?: NO_NAMESPACE,
                                    ownerName,
                                ),
                            )
                        }
                    } else {
                        Modifier
                    },
                )
            }
        }
        createdBy?.let { annotation ->
            item { SectionHeader(stringResource(R.string.label_created_by)) }
            item {
                KeyValueRow(
                    stringResource(R.string.label_created_by),
                    annotation,
                    copyable = true,
                    monospace = true,
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Dialogs                                                                                       */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun DrainDialog(
    nodeName: String,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var includeDaemonSets by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drain_title, nodeName)) },
        text = {
            Column {
                Text(stringResource(R.string.drain_body))
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.drain_include_daemonsets),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = includeDaemonSets, onCheckedChange = { includeDaemonSets = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(includeDaemonSets) }) {
                Text(
                    text = stringResource(R.string.action_drain),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RevisionPickerDialog(
    revisions: List<RevisionOption>,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_undo_revision)) },
        text = {
            if (revisions.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.state_loading))
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.action_rollout_undo)) },
                            supportingContent = { SecondaryText(stringResource(R.string.detail_previous_revision)) },
                            modifier = Modifier.clickable { onPick(null) },
                        )
                    }
                    items(revisions) { option ->
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.label_history_revision, option.revision.toInt()))
                            },
                            supportingContent = {
                                SecondaryText(
                                    listOfNotNull(option.replicaSet, option.age).joinToString(" · "),
                                )
                            },
                            modifier = Modifier.clickable { onPick(option.revision) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ArgoSyncDialog(
    name: String,
    onDismiss: () -> Unit,
    onSync: (String?, Boolean, Boolean) -> Unit,
) {
    var revision by remember { mutableStateOf("") }
    var prune by remember { mutableStateOf(false) }
    var dryRun by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_argo_sync_title, name)) },
        text = {
            Column {
                OutlinedTextField(
                    value = revision,
                    onValueChange = { revision = it },
                    label = { Text(stringResource(R.string.detail_argo_revision_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.detail_argo_prune), Modifier.weight(1f))
                    Switch(checked = prune, onCheckedChange = { prune = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.detail_argo_dry_run), Modifier.weight(1f))
                    Switch(checked = dryRun, onCheckedChange = { dryRun = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSync(revision.takeIf { it.isNotBlank() }, prune, dryRun) }) {
                Text(stringResource(R.string.action_sync))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ScaleDialog(
    name: String,
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(current.coerceAtLeast(0).toString()) }
    val parsed = value.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_scale_title, name)) },
        text = {
            Column {
                Text(stringResource(R.string.detail_scale_current, current))
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.list_scale_body)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
