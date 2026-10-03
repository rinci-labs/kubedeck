package dev.rafa.kubemobile.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.k8s.ApiResource
import dev.rafa.kubemobile.k8s.ResourceUsage
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.ops.Status
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.ALL_NAMESPACES
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.components.ListDivider
import dev.rafa.kubemobile.ui.components.ConfirmDeleteDialog
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.HealthChip
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.SearchField
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.humanAge
import dev.rafa.kubemobile.ui.usageLabel
import dev.rafa.kubemobile.ui.parseResourceKey
import dev.rafa.kubemobile.ui.screenViewModel

/** Kinds whose rows offer a scale action, and the confirm dialog that goes with it. */
private val SCALABLE = setOf("Deployment", "StatefulSet", "ReplicaSet", "ReplicationController")

/** Kinds that support an in-place rollout restart. */
private val RESTARTABLE = setOf("Deployment", "StatefulSet", "DaemonSet")

/** Deleting these requires typing the object name. */
private val TYPED_CONFIRM = setOf("Secret", "PersistentVolumeClaim", "Namespace", "PersistentVolume")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceListScreen(
    app: AppViewModel,
    navController: NavController,
    resourceKey: String,
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

    val vm = screenViewModel(app) { a, handle -> ResourceListViewModel(a, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val namespace by vm.namespace.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val selector by vm.selector.collectAsStateWithLifecycle()
    val namespaces by app.namespaces.collectAsStateWithLifecycle()
    val sessionState by app.sessionState.collectAsStateWithLifecycle()

    var namespaceSheet by remember { mutableStateOf(false) }
    var selectorOpen by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ResourceRow?>(null) }
    var pendingScale by remember { mutableStateOf<ResourceRow?>(null) }
    var restartTarget by remember { mutableStateOf<ResourceRow?>(null) }
    var overflowTarget by remember { mutableStateOf<ResourceRow?>(null) }

    val resource = state.resource ?: ref.resolve(app.catalog)
    val resourceLabel = resource?.kind ?: ref.kind
    val namespaced = resource?.namespaced == true

    LaunchedEffect(ref.key, sessionState) {
        if (app.session != null) vm.start(ref)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // Single line: the bar's height is fixed, so a stacked title would be clamped and
                // would not scale with the system font setting. Scope and count live in the content
                // header below, where they have room.
                title = {
                    Text(text = resourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    if (namespaced) {
                        // One obvious namespace control, anchored to the bar, with a filter inside.
                        TextButton(onClick = { namespaceSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.UnfoldMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = namespace ?: stringResource(R.string.list_all),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { selectorOpen = !selectorOpen }) {
                        Icon(
                            imageVector = Icons.Filled.FilterList,
                            contentDescription = stringResource(R.string.label_selector),
                            tint = if (selector.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (resource?.supports("create") == true) {
                ExtendedFloatingActionButton(
                    onClick = {
                        vm.createTemplate()
                        createOpen = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_create)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Scope and count, spelled out where they can breathe: the reader always knows which
            // namespace the rows belong to and how many there are.
            SecondaryText(
                text = listOfNotNull(
                    if (namespaced) {
                        when {
                            state.partial -> stringResource(R.string.list_scope_all)
                            namespace == null || namespace == ALL_NAMESPACES ->
                                stringResource(R.string.label_all_namespaces)

                            else -> namespace
                        }
                    } else {
                        stringResource(R.string.label_cluster_scoped)
                    },
                    // When a name filter is active the header reports the visible subset, so the
                    // count always describes what is actually on screen.
                    when {
                        search.isNotBlank() && state.rows.size != state.total ->
                            stringResource(R.string.list_scope_count, state.rows.size, state.total)

                        state.total > 0 -> stringResource(R.string.label_count, state.total)
                        else -> null
                    },
                    if (state.truncated) stringResource(R.string.state_loading_more) else null,
                ).joinToString(" · "),
                modifier = Modifier.padding(start = Spacing.RowPadding, end = Spacing.RowPadding, top = Spacing.RowVertical),
                maxLines = 1,
            )
            SearchField(
                value = search,
                onValueChange = vm::setSearch,
                placeholder = stringResource(R.string.list_search_hint),
                modifier = Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
            )

            if (selectorOpen) {
                OutlinedTextField(
                    value = selector,
                    onValueChange = vm::setSelector,
                    label = { Text(stringResource(R.string.label_selector)) },
                    placeholder = { Text(stringResource(R.string.list_selector_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.ScreenPadding),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    trailingIcon = {
                        IconButton(onClick = vm::refresh) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_apply),
                            )
                        }
                    },
                )
            }

            if (state.partial) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
                ) {
                    Text(
                        text = stringResource(R.string.list_partial),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.ChipPadding, vertical = Spacing.TightGap),
                    )
                }
            }
            if (resource?.kind == "Pod" && state.metricsError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
                ) {
                    Text(
                        text = stringResource(R.string.metrics_request_failed, state.metricsError!!.message),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = Spacing.ChipPadding, vertical = Spacing.TightGap),
                    )
                }
            }

            when {
                state.loading -> LoadingState(label = stringResource(R.string.state_loading))

                state.error != null && state.rows.isEmpty() -> ErrorState(
                    error = state.error!!,
                    onRetry = vm::retry,
                )

                state.rows.isEmpty() -> EmptyState(
                    // Says which of the two reasons applies, and offers the matching escape hatch.
                    title = if (search.isNotBlank()) {
                        stringResource(R.string.state_no_matches)
                    } else {
                        stringResource(R.string.list_empty_title)
                    },
                    body = if (search.isNotBlank()) {
                        stringResource(R.string.list_no_matches_body, search)
                    } else {
                        stringResource(R.string.list_empty_body)
                    },
                    actionLabel = if (search.isNotBlank()) {
                        stringResource(R.string.action_clear)
                    } else if (namespaced && !vm.isAllNamespaces()) {
                        stringResource(R.string.list_scope_all)
                    } else {
                        null
                    },
                    onAction = if (search.isNotBlank()) {
                        { vm.setSearch("") }
                    } else if (namespaced && !vm.isAllNamespaces()) {
                        { vm.setNamespace(ALL_NAMESPACES) }
                    } else {
                        null
                    },
                )

                else -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = ListBottomPadding),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.rows, key = { it.uid }) { row ->
                            ResourceListRow(
                                row = row,
                                kind = resourceLabel,
                                showNamespace = vm.isAllNamespaces() || !namespaced,
                                usage = state.metrics["${row.namespace}/${row.name}"],
                                onClick = {
                                    navController.navigate(
                                        Routes.objectDetail(
                                            ref.key,
                                            row.namespace ?: dev.rafa.kubemobile.ui.NO_NAMESPACE,
                                            row.name,
                                        ),
                                    )
                                },
                                onLongPress = { overflowTarget = row },
                            )
                            ListDivider()
                        }
                    }
                }
            }
        }
    }

    if (namespaceSheet) {
        NamespaceSheet(
            namespaces = namespaces,
            selected = namespace,
            clusterNamespace = app.activeProfile?.namespace,
            onSelect = {
                vm.setNamespace(it)
                namespaceSheet = false
            },
            onDismiss = { namespaceSheet = false },
        )
    }

    pendingDelete?.let { row ->
        ConfirmDeleteDialog(
            name = row.name,
            requireTyping = resourceLabel in TYPED_CONFIRM,
            title = stringResource(R.string.list_delete_title, row.name),
            body = stringResource(R.string.list_delete_body),
            onConfirm = {
                pendingDelete = null
                vm.delete(row) { name ->
                    app.notify(app.getApplication<android.app.Application>().getString(R.string.list_deleted, name))
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingScale?.let { row ->
        ScaleDialog(
            name = row.name,
            current = row.object_["spec"]?.let { spec ->
                (spec as? kotlinx.serialization.json.JsonObject)?.get("replicas")?.toString()?.toIntOrNull()
            } ?: 1,
            onDismiss = { pendingScale = null },
            onConfirm = { replicas ->
                pendingScale = null
                vm.scale(row, replicas) {
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(R.string.list_scaled, row.name, replicas),
                    )
                }
            },
        )
    }

    restartTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { restartTarget = null },
            title = { Text(stringResource(R.string.action_restart_rollout)) },
            text = { Text(stringResource(R.string.list_restart_body, row.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = row
                    restartTarget = null
                    vm.rolloutRestart(target) { name ->
                        app.notify(
                            app.getApplication<android.app.Application>()
                                .getString(R.string.list_restart_done, name),
                        )
                    }
                }) { Text(stringResource(R.string.action_restart_rollout)) }
            },
            dismissButton = {
                TextButton(onClick = { restartTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    overflowTarget?.let { row ->
        ListRowSheet(
            row = row,
            kind = resourceLabel,
            resource = resource,
            onDismiss = { overflowTarget = null },
            onOpen = {
                overflowTarget = null
                navController.navigate(
                    Routes.objectDetail(ref.key, row.namespace ?: dev.rafa.kubemobile.ui.NO_NAMESPACE, row.name),
                )
            },
            onDelete = {
                overflowTarget = null
                pendingDelete = row
            },
            onScale = {
                overflowTarget = null
                pendingScale = row
            },
            onRestart = {
                overflowTarget = null
                restartTarget = row
            },
        )
    }

    if (createOpen) {
        CreateDialog(
            kind = resourceLabel,
            yaml = vm.createYaml.value,
            onYamlChange = vm::setCreateYaml,
            onDismiss = {
                createOpen = false
                vm.setCreateYaml("")
            },
            onSubmit = {
                vm.submitCreate(
                    onCreated = { name ->
                        createOpen = false
                        app.notify(
                            app.getApplication<android.app.Application>()
                                .getString(R.string.list_created, name),
                        )
                    },
                    onError = { error ->
                        app.notify(error.message ?: "Create failed")
                    },
                )
            },
        )
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Namespace chips                                                                               */
/* -------------------------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NamespaceSheet(
    namespaces: List<String>,
    selected: String?,
    clusterNamespace: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible = remember(query, namespaces) {
        if (query.isBlank()) namespaces else namespaces.filter { it.contains(query, true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.SheetPadding),
        ) {
            Text(
                text = stringResource(R.string.label_namespace),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.RowVertical),
            )
            if (namespaces.size > 6) {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.RowVertical),
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item(key = "__all__") {
                    NamespaceOption(
                        label = stringResource(R.string.list_all),
                        supporting = stringResource(R.string.label_all_namespaces),
                        selected = selected == null || selected == ALL_NAMESPACES,
                        onClick = { onSelect(ALL_NAMESPACES) },
                    )
                }
                items(visible, key = { it }) { ns ->
                    NamespaceOption(
                        label = ns,
                        supporting = if (ns == clusterNamespace) {
                            stringResource(R.string.clusters_namespace_title)
                        } else {
                            null
                        },
                        selected = selected == ns,
                        onClick = { onSelect(ns) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NamespaceOption(
    label: String,
    supporting: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { text -> { SecondaryText(text, maxLines = 1) } },
        leadingContent = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/* -------------------------------------------------------------------------------------------- */
/* Rows                                                                                          */
/* -------------------------------------------------------------------------------------------- */

/**
 * The workload row. One line for the name, one line for `scope · readiness · age [· restarts]`, and
 * a trailing health chip carrying the rollout state. `ListItem` supplies the >=48 dp touch target
 * and grows with the system font scale, so nothing clips at the largest setting.
 */
@Composable
private fun ResourceListRow(
    row: ResourceRow,
    kind: String,
    showNamespace: Boolean,
    usage: ResourceUsage?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val health = remember(row.uid, kind) { Status.health(row.object_, kind) }
    val usageText = remember(usage) { usage?.let { usageLabel(it.cpuMillis, it.memoryBytes) } }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(end = Spacing.TightGap),
        headlineContent = {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            SecondaryText(
                text = buildList {
                    if (showNamespace) row.namespace?.let { add(it) }
                    // `Status.health` already folds readiness and restarts into its detail line;
                    // appending them again here produced `2 restarts · ... · 2 restarts`.
                    health.detail?.let { add(it) }
                    humanAge(row.object_.str("metadata/creationTimestamp"))?.let { add(it) }
                    // Usage last, so a narrow screen truncates the metric rather than readiness.
                    usageText?.let { add(it) }
                }.joinToString(" · "),
                // Two lines only when there is a usage segment to fit, so plain rows stay compact
                // and the metric is never the part that gets ellipsised away.
                maxLines = if (usageText != null) 2 else 1,
            )
        },
        leadingContent = if (health.progress != null && health.progress < 1f) {
            {
                CircularProgressIndicator(
                    progress = { health.progress },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else {
            null
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthChip(health)
                IconButton(onClick = onLongPress) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListRowSheet(
    row: ResourceRow,
    kind: String,
    resource: ApiResource?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onScale: () -> Unit,
    onRestart: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(bottom = Spacing.SheetPadding)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.ItemGap),
            )
            SecondaryText(
                listOfNotNull(kind, row.namespace).joinToString(" · "),
                modifier = Modifier.padding(horizontal = Spacing.SheetPadding),
            )
            Spacer(Modifier.size(8.dp))
            if (resource?.supports("get") == true) {
                SheetAction(stringResource(R.string.label_summary), Icons.Filled.Tune, onClick = onOpen)
            }
            if (kind in SCALABLE && resource?.supports("update") == true) {
                SheetAction(stringResource(R.string.action_scale), Icons.Filled.Tune, onClick = onScale)
            }
            if (kind in RESTARTABLE && resource?.supports("patch") == true) {
                SheetAction(stringResource(R.string.action_restart_rollout), Icons.Filled.Refresh, onClick = onRestart)
            }
            if (resource?.supports("delete") == true) {
                SheetAction(
                    label = stringResource(R.string.action_delete),
                    icon = Icons.Filled.Delete,
                    destructive = true,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = label,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    androidx.compose.ui.graphics.Color.Unspecified
                },
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/* -------------------------------------------------------------------------------------------- */
/* Dialogs                                                                                       */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun ScaleDialog(
    name: String,
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(current.coerceAtLeast(1).toString()) }
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
                        keyboardType = KeyboardType.Number,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null && parsed >= 0) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateDialog(
    kind: String,
    yaml: String,
    onYamlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.list_create_title, kind)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_cancel),
                                )
                            }
                        },
                        actions = {
                            TextButton(onClick = onSubmit, enabled = yaml.isNotBlank()) {
                                Text(stringResource(R.string.action_create))
                            }
                        },
                    )
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    OutlinedTextField(
                        value = yaml,
                        onValueChange = onYamlChange,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.CardPadding),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        label = { Text(stringResource(R.string.label_yaml)) },
                    )
                }
            }
        }
    }
}
