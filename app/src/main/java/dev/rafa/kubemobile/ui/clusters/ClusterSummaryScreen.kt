package dev.rafa.kubemobile.ui.clusters

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.ops.ResourceHealth
import dev.rafa.kubemobile.ui.KubeShapes
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.GitOpsTab
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.SessionState
import dev.rafa.kubemobile.ui.components.BottomBarScreen
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.HealthChip
import dev.rafa.kubemobile.ui.components.IconTile
import dev.rafa.kubemobile.ui.components.InfoChip
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.SectionCard
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.components.ToneDot
import dev.rafa.kubemobile.ui.formatCpu
import dev.rafa.kubemobile.ui.hostPort
import dev.rafa.kubemobile.ui.humanBytes
import dev.rafa.kubemobile.ui.navigateToTop
import dev.rafa.kubemobile.ui.screenViewModel
import dev.rafa.kubemobile.ui.toneColors
import dev.rafa.kubemobile.ui.toUiError

/**
 * The landing screen for a connected cluster: identity, live counts, resource usage and the
 * handful of things that are actually wrong. Reached by tapping a cluster; the bottom-bar Browse
 * destination still opens the full catalog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterSummaryScreen(
    app: AppViewModel,
    navController: NavController,
    profileId: String,
) {
    val vm = screenViewModel(app) { a, _ -> ClusterSummaryViewModel(a) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sessionState by app.sessionState.collectAsStateWithLifecycle()
    val profile = app.profileById(profileId)

    LaunchedEffect(profileId, sessionState) {
        if (app.session != null && app.session?.profile?.id == profileId) vm.start()
    }

    if (app.session == null || app.session?.profile?.id != profileId) {
        // A direct deep link (process death, back-stack restore) must not show a stale cluster's
        // numbers: reconnect, then the LaunchedEffect above starts the load.
        LaunchedEffect(profileId) {
            profile?.let { app.connectInBackground(it) }
        }
    }

    BottomBarScreen(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = profile?.name ?: stringResource(R.string.clusters_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.nodesTotal == null -> LoadingState(
                    label = stringResource(R.string.state_loading),
                )

                state.error != null && state.nodesTotal == null -> ErrorState(
                    error = state.error!!,
                    onRetry = vm::retry,
                )

                else -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
                        item(key = "header") {
                            ClusterHeaderCard(
                                name = profile?.name.orEmpty(),
                                server = profile?.hostPort.orEmpty(),
                                state = sessionState,
                                version = state.serverVersion,
                            )
                        }
                        item(key = "tiles") { StatGrid(state) }
                        item(key = "usage") { UsageCard(state) }
                        item(key = "attention") {
                            AttentionCard(
                                state = state,
                                onOpenEvents = { navController.navigateToTop(Routes.EVENTS) },
                            )
                        }
                        item(key = "links") {
                            QuickLinks(
                                onBrowse = { navController.navigateToTop(Routes.CATALOG) },
                                onEvents = { navController.navigateToTop(Routes.EVENTS) },
                                onHelm = { navController.navigateToTop(Routes.gitops(GitOpsTab.HELM)) },
                                onFlux = { navController.navigateToTop(Routes.gitops(GitOpsTab.FLUX)) },
                                onArgo = { navController.navigateToTop(Routes.gitops(GitOpsTab.ARGO)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Header                                                                                        */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun ClusterHeaderCard(
    name: String,
    server: String,
    state: SessionState,
    version: String?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.ScreenPadding,
                end = Spacing.ScreenPadding,
                top = Spacing.ContentInset,
            ),
        shape = KubeShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // Name and connection pill on one line, then the endpoint and version underneath, the
        // same order the landing site's summary card reads in.
        Column(Modifier.padding(Spacing.CardPadding)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                HealthChip(sessionHealth(state))
            }
            Spacer(Modifier.size(Spacing.TightGap))
            SecondaryText(server, maxLines = 1)
            Text(
                text = if (!version.isNullOrBlank()) {
                    stringResource(R.string.summary_k8s_version, version)
                } else {
                    stringResource(R.string.summary_version_unknown)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun sessionHealth(state: SessionState): ResourceHealth = when (state) {
    is SessionState.Ready -> ResourceHealth("Connected", tone = ResourceHealth.Tone.OK)
    is SessionState.Connecting -> ResourceHealth("Connecting", tone = ResourceHealth.Tone.PROGRESS)
    is SessionState.Failed -> ResourceHealth("Connection failed", tone = ResourceHealth.Tone.BAD)
    SessionState.Idle -> ResourceHealth("Not connected", tone = ResourceHealth.Tone.NEUTRAL)
}

/* -------------------------------------------------------------------------------------------- */
/* Stat tiles                                                                                    */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun StatGrid(state: ClusterSummaryState) {
    val tiles = listOf(
        StatTile(state.nodesReady, state.nodesTotal, R.string.summary_nodes),
        StatTile(state.namespaces, null, R.string.summary_namespaces),
        StatTile(state.podsRunning, state.podsTotal, R.string.summary_pods),
        StatTile(state.deploymentsAvailable, state.deploymentsTotal, R.string.summary_deployments),
    )
    Column(
        Modifier.padding(
            start = Spacing.ScreenPadding,
            end = Spacing.ScreenPadding,
            top = Spacing.ContentInset,
        ),
    ) {
        tiles.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
            ) {
                row.forEach { tile ->
                    StatTileView(tile, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.size(Spacing.ItemGap))
        }
    }
}

/** A compact count tile on the muted surface, like the site's `Card variant="muted"`. */
@Composable
private fun StatTileView(tile: StatTile, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.ChipPadding)) {
            Text(
                text = statValue(tile, MaterialTheme.colorScheme.onSurfaceVariant),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(Spacing.TightGap))
            Text(
                text = stringResource(tile.labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * `3/3`, `12`, or `—` when the count is genuinely not computable. The `/total` part is muted so the
 * live number is what the eye lands on.
 */
private fun statValue(tile: StatTile, muted: androidx.compose.ui.graphics.Color): AnnotatedString {
    val value = tile.value ?: return AnnotatedString("—")
    val total = tile.total ?: return AnnotatedString(value.toString())
    return buildAnnotatedString {
        append(value.toString())
        withStyle(SpanStyle(color = muted)) { append("/$total") }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Usage                                                                                         */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun UsageCard(state: ClusterSummaryState) {
    Column(
        Modifier.padding(
            start = Spacing.ScreenPadding,
            end = Spacing.ScreenPadding,
            top = Spacing.ContentInset,
        ),
    ) {
        SectionCard(title = stringResource(R.string.summary_usage)) {
            Column(Modifier.padding(horizontal = Spacing.CardPadding)) {
                if (!state.metricsAvailable) {
                    SecondaryText(stringResource(R.string.summary_metrics_unavailable))
                    Spacer(Modifier.size(Spacing.TightGap))
                    SecondaryText(
                        stringResource(R.string.summary_metrics_unavailable_hint),
                        maxLines = 3,
                    )
                } else if (state.metricsError) {
                    SecondaryText(stringResource(R.string.summary_metrics_error))
                    Spacer(Modifier.size(Spacing.TightGap))
                    SecondaryText(
                        stringResource(R.string.summary_metrics_error_hint),
                        maxLines = 3,
                    )
                } else {
                    UsageRow(
                        label = stringResource(R.string.label_cpu),
                        used = state.usedCpuMillis?.let { formatCpu(it) } ?: "—",
                        total = state.allocatableCpuMillis?.let { formatCpu(it) } ?: "—",
                        fraction = state.cpuFraction,
                    )
                    Spacer(Modifier.size(Spacing.ContentInset))
                    UsageRow(
                        label = stringResource(R.string.label_memory),
                        used = state.usedMemoryBytes?.let { humanBytes(it) } ?: "—",
                        total = state.allocatableMemoryBytes?.let { humanBytes(it) } ?: "—",
                        fraction = state.memoryFraction,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, used: String, total: String, fraction: Float?) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$used / $total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.size(Spacing.ItemGap))
            Text(
                text = fraction?.let { "${(it * 100).toInt()}%" } ?: "—",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(Spacing.TightGap))
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(KubeShapes.Pill),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        } else {
            Spacer(Modifier.size(Spacing.ItemGap))
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Attention                                                                                     */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun AttentionCard(state: ClusterSummaryState, onOpenEvents: () -> Unit) {
    Column(
        Modifier.padding(
            start = Spacing.ScreenPadding,
            end = Spacing.ScreenPadding,
            top = Spacing.ContentInset,
        ),
    ) {
        SectionCard(title = stringResource(R.string.summary_attention)) {
            Column(Modifier.padding(horizontal = Spacing.CardPadding)) {
                if (state.allHealthy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToneDot(ResourceHealth.Tone.OK)
                        Spacer(Modifier.size(Spacing.ItemGap))
                        SecondaryText(stringResource(R.string.summary_all_healthy), maxLines = 2)
                    }
                } else {
                    if (!state.healthResolved) {
                        // A source that could not be read is not a source that is fine. Say which.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToneDot(ResourceHealth.Tone.NEUTRAL)
                            Spacer(Modifier.size(Spacing.ItemGap))
                            SecondaryText(stringResource(R.string.summary_sources_unavailable), maxLines = 2)
                        }
                        state.unavailableSources.forEach { label ->
                            SecondaryText(
                                "• " + stringResource(label),
                                modifier = Modifier.padding(start = Spacing.CardPadding),
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.size(Spacing.TightGap))
                    }
                    AttentionRow(
                        count = state.notReadyPods,
                        label = stringResource(R.string.summary_not_ready_pods),
                    )
                    AttentionRow(
                        count = state.unavailableDeployments,
                        label = stringResource(R.string.summary_unavailable_deployments),
                    )
                    AttentionRow(
                        count = state.failingKustomizations,
                        label = stringResource(R.string.summary_failing_kustomizations),
                    )
                    AttentionRow(
                        count = state.failingHelmReleases,
                        label = stringResource(R.string.summary_failing_helmreleases),
                    )
                }
            }
            if (state.warningEvents.isNotEmpty()) {
                Spacer(Modifier.size(Spacing.ItemGap))
                SecondaryText(
                    stringResource(R.string.summary_recent_warnings),
                    modifier = Modifier.padding(horizontal = Spacing.CardPadding),
                )
                state.warningEvents.forEach { event ->
                    WarningEventRow(event, onOpenEvents)
                }
            }
        }
    }
}

@Composable
private fun AttentionRow(count: Long?, label: String) {
    val resolved = count != null
    val positive = (count ?: 0L) > 0L
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.TightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToneDot(
            when {
                !resolved -> ResourceHealth.Tone.NEUTRAL
                positive -> ResourceHealth.Tone.WARN
                else -> ResourceHealth.Tone.OK
            },
        )
        Spacer(Modifier.size(Spacing.ItemGap))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        // The count rides on a soft pill: amber when something is wrong, mint when clear.
        InfoChip(
            label = count?.toString() ?: "—",
            tone = when {
                !resolved -> ResourceHealth.Tone.NEUTRAL
                positive -> ResourceHealth.Tone.WARN
                else -> ResourceHealth.Tone.OK
            },
        )
    }
}

@Composable
private fun WarningEventRow(event: AttentionEvent, onClick: () -> Unit) {
    // The card clips to its rounded shape, so this row's ripple stays inside the corners.
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = Spacing.CardPadding,
                vertical = Spacing.ItemGap,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = toneColors(ResourceHealth.Tone.WARN).content,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(Spacing.ItemGap))
            Text(
                text = event.reason,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SecondaryText(
            listOfNotNull(
                event.involvedKind.takeIf { it.isNotBlank() }?.let {
                    if (event.involvedName.isNotBlank()) "$it/${event.involvedName}" else it
                },
                event.namespace,
            ).joinToString(" · "),
            maxLines = 1,
        )
        if (event.message.isNotBlank()) {
            SecondaryText(event.message, maxLines = 2)
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Quick links                                                                                   */
/* -------------------------------------------------------------------------------------------- */

private data class LinkEntry(val labelRes: Int, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun QuickLinks(
    onBrowse: () -> Unit,
    onEvents: () -> Unit,
    onHelm: () -> Unit,
    onFlux: () -> Unit,
    onArgo: () -> Unit,
) {
    val links = listOf(
        LinkEntry(R.string.nav_browse, Icons.AutoMirrored.Filled.List, onBrowse),
        LinkEntry(R.string.nav_events, Icons.Filled.Event, onEvents),
        LinkEntry(R.string.browse_helm, Icons.Filled.Hub, onHelm),
        LinkEntry(R.string.flux_title, Icons.Filled.AccountTree, onFlux),
        LinkEntry(R.string.argo_title, Icons.Filled.AccountTree, onArgo),
    )
    Column(
        Modifier.padding(
            start = Spacing.ScreenPadding,
            end = Spacing.ScreenPadding,
            top = Spacing.ContentInset,
        ),
    ) {
        // A quiet section label, like every other section title on the screen.
        Text(
            text = stringResource(R.string.summary_quick_links),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.TightGap, bottom = Spacing.ItemGap),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
        ) {
            links.take(3).forEach { entry ->
                QuickLink(entry, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.size(Spacing.ItemGap))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
        ) {
            links.drop(3).forEach { entry ->
                QuickLink(entry, Modifier.weight(1f))
            }
            if (links.size - 3 < 3) {
                repeat(3 - (links.size - 3)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QuickLink(entry: LinkEntry, modifier: Modifier = Modifier) {
    // Card's own onClick clips the ripple to the rounded corners; a clickable modifier would not.
    Card(
        onClick = entry.onClick,
        modifier = modifier,
        shape = KubeShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.ChipPadding, horizontal = Spacing.ItemGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconTile(icon = entry.icon, size = 36.dp)
            Spacer(Modifier.size(Spacing.ItemGap))
            Text(
                text = stringResource(entry.labelRes),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
