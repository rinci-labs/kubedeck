package dev.rafa.kubemobile.ui.helm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.ops.HelmRelease
import dev.rafa.kubemobile.ops.ResourceHealth
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.cardGutter
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.KubeShapes
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.SessionState
import dev.rafa.kubemobile.ui.components.BottomBarScreen
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.ConnectionGate
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.HealthChip
import dev.rafa.kubemobile.ui.components.IconTile
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.RowCard
import dev.rafa.kubemobile.ui.components.SearchField
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.components.ToneDot
import dev.rafa.kubemobile.ui.navigateToTop
import dev.rafa.kubemobile.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelmScreen(
    app: AppViewModel,
    navController: NavController,
    header: @Composable () -> Unit = {},
) {
    val vm = screenViewModel(app) { a, handle -> HelmViewModel(a, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope by vm.scope.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val namespaces by app.namespaces.collectAsStateWithLifecycle()
    val sessionState by app.sessionState.collectAsStateWithLifecycle()

    var scopeSheet by remember { mutableStateOf(false) }

    LaunchedEffect(sessionState) {
        if (sessionState is SessionState.Ready) vm.start()
    }

    BottomBarScreen(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.helm_title)) },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                actions = {
                    IconButton(onClick = { scopeSheet = true }) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = stringResource(R.string.label_namespace),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Always on screen: without it a disconnected GitOps screen would have no way to
            // reach the Flux or Argo tabs, which is exactly the dead end the user hit.
            header()
            Box(Modifier.fillMaxSize()) {
            val session = (sessionState as? SessionState.Ready)?.session
            when {
                session == null -> ConnectionGate(
                    state = sessionState,
                    onRetry = { app.connectInBackground(it) },
                    onGoToClusters = { navController.navigateToTop(Routes.CLUSTERS) },
                )

                state.loading && state.releases.isEmpty() -> LoadingState(label = stringResource(R.string.state_loading))

                state.error != null && state.releases.isEmpty() -> ErrorState(
                    error = state.error!!,
                    onRetry = vm::retry,
                )

                else -> Column {
                    SearchField(
                        value = search,
                        onValueChange = vm::setSearch,
                        placeholder = stringResource(R.string.list_search_hint),
                        modifier = Modifier.cardGutter(),
                    )
                    ScopeStrip(
                        selected = scope,
                        onOpen = { scopeSheet = true },
                    )
                    val releases = vm.visible()
                    if (releases.isEmpty() && search.isNotBlank()) {
                        // A filter that matches nothing is not an empty cluster: say so, and offer
                        // the one action that gets the list back.
                        EmptyState(
                            title = stringResource(R.string.helm_empty_title),
                            body = stringResource(R.string.list_no_matches_body, search),
                            actionLabel = stringResource(R.string.action_clear),
                            onAction = { vm.setSearch("") },
                        )
                    } else if (releases.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.helm_empty_title),
                            body = stringResource(R.string.helm_empty_body),
                        )
                    } else {
                        PullToRefreshBox(
                            isRefreshing = state.refreshing,
                            onRefresh = vm::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(
                                contentPadding = PaddingValues(
                                    top = Spacing.RowVertical,
                                    bottom = ListBottomPadding,
                                ),
                            ) {
                                items(releases, key = { "${it.namespace}/${it.name}" }) { release ->
                                    ReleaseRow(release) {
                                        navController.navigate(Routes.helmRelease(release.namespace, release.name))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (scopeSheet) {
        ScopeSheet(
            namespaces = namespaces,
            selected = scope,
            onToggle = vm::toggleNamespace,
            onAll = { vm.setScope(emptyList()) },
            onDismiss = { scopeSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeStrip(selected: List<String>, onOpen: () -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .cardGutter(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
    ) {
        FilterChip(
            shape = KubeShapes.Pill,
            selected = selected.isEmpty(),
            onClick = onOpen,
            label = { Text(stringResource(R.string.helm_all_namespaces)) },
        )
        selected.take(4).forEach { ns ->
            FilterChip(shape = KubeShapes.Pill, selected = true, onClick = onOpen, label = { Text(ns, maxLines = 1) })
        }
        if (selected.size > 4) {
            AssistChip(shape = KubeShapes.Pill, onClick = onOpen, label = { Text("+${selected.size - 4}") })
        }
    }
}

@Composable
private fun ReleaseRow(release: HelmRelease, onClick: () -> Unit) {
    val health = remember(release.id, release.revision, release.status) {
        val tone = when (release.status.lowercase()) {
            "deployed" -> ResourceHealth.Tone.OK
            "failed" -> ResourceHealth.Tone.BAD
            "pending-install", "pending-upgrade", "pending-rollback", "uninstalling" ->
                ResourceHealth.Tone.PROGRESS

            "superseded", "uninstalled" -> ResourceHealth.Tone.NEUTRAL
            else -> ResourceHealth.Tone.WARN
        }
        ResourceHealth(release.status.ifBlank { "unknown" }, null, tone)
    }
    // A standalone card per release, like the site's SyncCard: a tinted tile carrying the status
    // tone, the name over its namespace and chart, the status pill trailing, and the remaining
    // facts underneath so the headline line never has to truncate them away.
    RowCard(onClick = onClick) {
        Column(Modifier.padding(Spacing.CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon = Icons.Outlined.Inventory2, tone = health.tone, size = 36.dp)
                Spacer(Modifier.width(Spacing.ChipPadding))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = release.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SecondaryText(
                        listOfNotNull(
                            release.namespace.takeIf { it.isNotBlank() },
                            "${release.chartName}-${release.chartVersion}".trim('-'),
                        ).joinToString(" · "),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(Spacing.ItemGap))
                HealthChip(health)
            }
            Spacer(Modifier.size(Spacing.ItemGap))
            SecondaryText(
                listOfNotNull(
                    release.appVersion.takeIf { it.isNotBlank() }?.let { "app $it" },
                    stringResource(R.string.label_release, release.revision),
                ).joinToString(" · "),
                maxLines = 1,
            )
            if (release.description.isNotBlank()) {
                SecondaryText(release.description, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeSheet(
    namespaces: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.SheetPadding)) {
            Text(
                text = stringResource(R.string.label_namespace),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.ItemGap),
            )
            SecondaryText(
                stringResource(R.string.helm_empty_body),
                modifier = Modifier.padding(horizontal = Spacing.SheetPadding),
            )
            Spacer(Modifier.size(Spacing.ItemGap))
            ListItem(
                headlineContent = { Text(stringResource(R.string.helm_all_namespaces)) },
                leadingContent = {
                    ToneDot(if (selected.isEmpty()) ResourceHealth.Tone.OK else ResourceHealth.Tone.NEUTRAL)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onAll() },
            )
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(namespaces, key = { it }) { ns ->
                    ListItem(
                        headlineContent = { Text(ns) },
                        leadingContent = {
                            androidx.compose.material3.Checkbox(
                                checked = ns in selected,
                                onCheckedChange = { onToggle(ns) },
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onToggle(ns) },
                    )
                }
            }
            Spacer(Modifier.size(Spacing.ItemGap))
            // The sheet's one primary action, full width so it is never a small target in a corner.
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.SheetPadding),
            ) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}
