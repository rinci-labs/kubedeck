package dev.rafa.kubemobile.ui.argo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.components.ListDivider
import dev.rafa.kubemobile.ui.components.BottomBarScreen
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.SessionState
import dev.rafa.kubemobile.ui.components.ConnectionGate
import dev.rafa.kubemobile.ui.components.HealthChip
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.MonoText
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.components.SectionErrorCard
import dev.rafa.kubemobile.ui.components.SectionHeader
import dev.rafa.kubemobile.ui.navigateToTop
import dev.rafa.kubemobile.ui.routeKey
import dev.rafa.kubemobile.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArgoScreen(
    app: AppViewModel,
    navController: NavController,
    header: @Composable () -> Unit = {},
) {
    val vm = screenViewModel(app) { a, _ -> ArgoViewModel(a) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sessionState by app.sessionState.collectAsStateWithLifecycle()
    var sheetTarget by remember { mutableStateOf<ArgoRow?>(null) }
    var syncTarget by remember { mutableStateOf<ArgoRow?>(null) }

    LaunchedEffect(sessionState) {
        if (sessionState is SessionState.Ready) vm.start()
    }

    BottomBarScreen(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.argo_title)) },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // A Column (not a Box): the tab strip stacks above the body instead of being overlapped
        // by it, and every branch below fills only the space that is left.
        Column(Modifier.padding(padding).fillMaxSize()) {
            header()
            val session = (sessionState as? SessionState.Ready)?.session
            when {
                session == null -> ConnectionGate(
                    state = sessionState,
                    onRetry = { app.connectInBackground(it) },
                    onGoToClusters = { navController.navigateToTop(Routes.CLUSTERS) },
                )

                state.loading && state.applications.isEmpty() ->
                    LoadingState(label = stringResource(R.string.state_loading))

                !state.installed -> ArgoMissingPanel()

                else -> PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = vm::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
                        item(key = "hdr-apps") { SectionHeader(stringResource(R.string.argo_applications)) }
                        state.applicationsError?.let { error ->
                            item(key = "err-apps") { SectionErrorCard(error = error, onRetry = { vm.retry() }) }
                        }
                        if (state.applicationsPartial) {
                            item(key = "partial-apps") { SecondaryText(stringResource(R.string.list_partial), modifier = Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical)) }
                        }
                        if (state.applications.isEmpty() && state.applicationsError == null) {
                            item(key = "empty-apps") {
                                SecondaryText(
                                    stringResource(R.string.argo_empty),
                                    modifier = Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
                                )
                            }
                        }
                        items(state.applications, key = { "app-${it.namespace}-${it.name}" }) { row ->
                            ArgoRowView(
                                row = row,
                                onClick = {
                                    val resource = app.catalog.forKind("Application", "argoproj.io")
                                        ?: return@ArgoRowView
                                    navController.navigate(
                                        Routes.objectDetail(
                                            resource.routeKey(),
                                            row.namespace ?: dev.rafa.kubemobile.ui.NO_NAMESPACE,
                                            row.name,
                                        ),
                                    )
                                },
                                onMore = { sheetTarget = row },
                            )
                            ListDivider()
                        }
                        // Shown whenever the section has content *or* a failure: an empty list
                        // with a denied request must not vanish silently.
                        if (state.applicationSets.isNotEmpty() || state.applicationSetsError != null || state.applicationSetsPartial) {
                            item(key = "hdr-sets") { SectionHeader(stringResource(R.string.argo_applicationsets)) }
                            state.applicationSetsError?.let { error ->
                                item(key = "err-sets") { SectionErrorCard(error = error, onRetry = { vm.retry() }) }
                            }
                            if (state.applicationSetsPartial) {
                                item(key = "partial-sets") { SecondaryText(stringResource(R.string.list_partial), modifier = Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical)) }
                            }
                            if (state.applicationSets.isEmpty() && state.applicationSetsError == null) {
                                item(key = "empty-sets") {
                                    SecondaryText(
                                        stringResource(R.string.argo_sets_empty),
                                        modifier = Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
                                    )
                                }
                            }
                            items(state.applicationSets, key = { "set-${it.namespace}-${it.name}" }) { row ->
                                // ApplicationSets have no detail screen of their own, so the row is
                                // deliberately not clickable rather than a dead tap target; the
                                // overflow still offers the actions that do exist.
                                ArgoRowView(row = row, onClick = null, onMore = { sheetTarget = row })
                                ListDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    sheetTarget?.let { row ->
        ModalBottomSheet(
            onDismissRequest = { sheetTarget = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.padding(bottom = Spacing.SheetPadding)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.ItemGap),
                )
                SecondaryText(
                    listOfNotNull(row.kind, row.namespace, row.sync).joinToString(" · "),
                    modifier = Modifier.padding(horizontal = Spacing.SheetPadding),
                )
                Spacer(Modifier.size(8.dp))
                SheetRow(stringResource(R.string.detail_argo_refresh), Icons.Filled.Refresh) {
                    val target = row
                    sheetTarget = null
                    vm.refreshApplication(target, hard = false) { }
                }
                SheetRow(stringResource(R.string.detail_argo_hard_refresh), Icons.Filled.Refresh) {
                    val target = row
                    sheetTarget = null
                    vm.refreshApplication(target, hard = true) { }
                }
                SheetRow(stringResource(R.string.detail_argo_sync), Icons.Filled.Sync) {
                    sheetTarget = null
                    syncTarget = row
                }
                SheetRow(stringResource(R.string.action_terminate), Icons.Filled.Stop, destructive = true) {
                    val target = row
                    sheetTarget = null
                    vm.terminate(target) { }
                }
            }
        }
    }

    syncTarget?.let { row ->
        ArgoSyncDialog(
            name = row.name,
            defaultRevision = row.targetRevision.orEmpty(),
            onDismiss = { syncTarget = null },
            onSync = { revision, prune, dryRun ->
                syncTarget = null
                vm.syncApplication(row, revision, prune, dryRun) { }
            },
        )
    }
}

@Composable
private fun SheetRow(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = label,
                color = if (destructive) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ArgoRowView(row: ArgoRow, onClick: (() -> Unit)?, onMore: () -> Unit) {
    ListItem(
        headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                SecondaryText(
                    listOfNotNull(
                        row.namespace,
                        row.targetRevision?.let { stringResource(R.string.label_target_revision) + " " + it },
                        row.destination,
                        row.lastOperation,
                        if (row.autoSync) stringResource(R.string.argo_autosync) else null,
                    ).joinToString(" · "),
                    maxLines = 2,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthChip(row.health)
                IconButton(onClick = onMore) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.action_more),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        // A null onClick leaves the row inert: no ripple, no tap target, no dead end.
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun ArgoMissingPanel() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(Spacing.EmptyStatePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.argo_missing_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.argo_missing_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.argo_missing_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        ARGO_CRD_NAMES.forEach { crd -> MonoText(crd, fontSize = 10.sp) }
    }
}

@Composable
private fun ArgoSyncDialog(
    name: String,
    defaultRevision: String,
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
                if (defaultRevision.isNotBlank()) {
                    SecondaryText(defaultRevision)
                    Spacer(Modifier.size(8.dp))
                }
                OutlinedTextField(
                    value = revision,
                    onValueChange = { revision = it },
                    label = { Text(stringResource(R.string.detail_argo_revision_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
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
