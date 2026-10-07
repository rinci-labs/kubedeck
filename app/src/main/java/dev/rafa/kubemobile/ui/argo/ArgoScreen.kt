package dev.rafa.kubemobile.ui.argo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.ops.ResourceHealth
import dev.rafa.kubemobile.ui.KubeShapes
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.cardGutter
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.components.BottomBarScreen
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.SessionState
import dev.rafa.kubemobile.ui.components.ConnectionGate
import dev.rafa.kubemobile.ui.components.HealthChip
import dev.rafa.kubemobile.ui.components.IconTile
import dev.rafa.kubemobile.ui.components.InfoChip
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.MonoText
import dev.rafa.kubemobile.ui.components.RowCard
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.components.SectionErrorCard
import dev.rafa.kubemobile.ui.components.SectionHeader
import dev.rafa.kubemobile.ui.components.softFieldColors
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
                            item(key = "err-apps") {
                                SectionErrorCard(
                                    error = error,
                                    onRetry = { vm.retry() },
                                    modifier = Modifier.padding(horizontal = Spacing.ScreenPadding),
                                )
                            }
                        }
                        if (state.applicationsPartial) {
                            item(key = "partial-apps") { SecondaryText(stringResource(R.string.list_partial), modifier = Modifier.cardGutter()) }
                        }
                        if (state.applications.isEmpty() && state.applicationsError == null) {
                            item(key = "empty-apps") {
                                SecondaryText(
                                    stringResource(R.string.argo_empty),
                                    modifier = Modifier.cardGutter(),
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
                        }
                        // Shown whenever the section has content *or* a failure: an empty list
                        // with a denied request must not vanish silently.
                        if (state.applicationSets.isNotEmpty() || state.applicationSetsError != null || state.applicationSetsPartial) {
                            item(key = "hdr-sets") { SectionHeader(stringResource(R.string.argo_applicationsets)) }
                            state.applicationSetsError?.let { error ->
                                item(key = "err-sets") {
                                    SectionErrorCard(
                                        error = error,
                                        onRetry = { vm.retry() },
                                        modifier = Modifier.padding(horizontal = Spacing.ScreenPadding),
                                    )
                                }
                            }
                            if (state.applicationSetsPartial) {
                                item(key = "partial-sets") { SecondaryText(stringResource(R.string.list_partial), modifier = Modifier.cardGutter()) }
                            }
                            if (state.applicationSets.isEmpty() && state.applicationSetsError == null) {
                                item(key = "empty-sets") {
                                    SecondaryText(
                                        stringResource(R.string.argo_sets_empty),
                                        modifier = Modifier.cardGutter(),
                                    )
                                }
                            }
                            items(state.applicationSets, key = { "set-${it.namespace}-${it.name}" }) { row ->
                                // ApplicationSets have no detail screen of their own, so the row is
                                // deliberately not clickable rather than a dead tap target; the
                                // overflow still offers the actions that do exist.
                                ArgoRowView(row = row, onClick = null, onMore = { sheetTarget = row })
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
                Spacer(Modifier.size(Spacing.ItemGap))
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
                color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        // Sit on the sheet's own surface instead of painting a mismatched band per row.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Argo CD's own sync vocabulary mapped onto the shared tones, for the sync pill on each card. */
private fun syncTone(sync: String): ResourceHealth.Tone = when (sync.lowercase()) {
    "synced" -> ResourceHealth.Tone.OK
    "outofsync" -> ResourceHealth.Tone.WARN
    else -> ResourceHealth.Tone.NEUTRAL
}

/**
 * One Application or ApplicationSet as a standalone card, after the site's SyncCard: a tinted tile
 * carrying the health tone, the name over its kind and namespace, then the health, sync and
 * auto-sync pills, then the target revision, destination and last operation.
 */
@Composable
private fun ArgoRowView(row: ArgoRow, onClick: (() -> Unit)?, onMore: () -> Unit) {
    // A null onClick leaves the card inert: no ripple, no tap target, no dead end.
    RowCard(onClick = onClick) {
        Column(
            Modifier.padding(
                start = Spacing.CardPadding,
                top = Spacing.ChipPadding,
                end = Spacing.TightGap,
                bottom = Spacing.CardPadding,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    icon = if (row.kind == "ApplicationSet") Icons.Outlined.Layers else Icons.Outlined.Apps,
                    tone = row.health.tone,
                    size = 36.dp,
                )
                Spacer(Modifier.width(Spacing.ChipPadding))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SecondaryText(
                        listOfNotNull(row.kind, row.namespace).joinToString(" · "),
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onMore) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.action_more),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.size(Spacing.ItemGap))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HealthChip(row.health)
                row.sync?.takeIf { it.isNotBlank() }?.let { sync ->
                    InfoChip(sync, tone = syncTone(sync))
                }
                if (row.autoSync) {
                    InfoChip(stringResource(R.string.argo_autosync))
                }
            }
            val meta = listOfNotNull(
                row.targetRevision?.let { stringResource(R.string.label_target_revision) + " " + it },
                row.destination,
                row.lastOperation,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.size(Spacing.ItemGap))
                SecondaryText(
                    meta,
                    maxLines = 2,
                    modifier = Modifier.padding(end = Spacing.ChipPadding),
                )
            }
        }
    }
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
        IconTile(icon = Icons.Outlined.Sync, tone = ResourceHealth.Tone.NEUTRAL, size = 48.dp)
        Spacer(Modifier.size(Spacing.ContentInset))
        Text(
            text = stringResource(R.string.argo_missing_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(Spacing.ItemGap))
        Text(
            text = stringResource(R.string.argo_missing_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(Spacing.ContentInset))
        Text(
            text = stringResource(R.string.argo_missing_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(Spacing.ChipPadding))
        // The probed CRD names as a boxed code block, so they read as literal identifiers.
        Surface(
            shape = KubeShapes.Field,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(Spacing.ChipPadding)) {
                ARGO_CRD_NAMES.forEach { crd -> MonoText(crd, fontSize = 10.sp) }
            }
        }
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
                    Spacer(Modifier.size(Spacing.ItemGap))
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
                    shape = KubeShapes.Field,
                    colors = softFieldColors(),
                )
                Spacer(Modifier.size(Spacing.ItemGap))
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
            // Sync is the dialog's one forward action, so it gets the filled pill.
            Button(onClick = { onSync(revision.takeIf { it.isNotBlank() }, prune, dryRun) }) {
                Text(stringResource(R.string.action_sync))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
