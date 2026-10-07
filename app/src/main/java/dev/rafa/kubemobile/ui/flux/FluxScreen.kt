package dev.rafa.kubemobile.ui.flux

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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import dev.rafa.kubemobile.ui.navigateToTop
import dev.rafa.kubemobile.ui.routeKey
import dev.rafa.kubemobile.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FluxScreen(
    app: AppViewModel,
    navController: NavController,
    header: @Composable () -> Unit = {},
) {
    val vm = screenViewModel(app) { a, _ -> FluxViewModel(a) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sessionState by app.sessionState.collectAsStateWithLifecycle()
    var sheetTarget by remember { mutableStateOf<FluxRow?>(null) }

    LaunchedEffect(sessionState) {
        if (sessionState is SessionState.Ready) vm.start()
    }

    BottomBarScreen(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.flux_title)) },
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

                state.loading && state.sections.isEmpty() -> LoadingState(label = stringResource(R.string.state_loading))

                !state.installed -> FluxMissingPanel()

                else -> PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = vm::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
                        val present = state.sections.filter { it.resource != null }
                        item(key = "summary") {
                            FluxSummary(present)
                        }
                        present.forEach { section ->
                            item(key = "hdr-${section.section.name}") {
                                SectionHeader(
                                    sectionTitle(section.section, present),
                                )
                            }
                            // A denied or timed-out request must say so; only a genuinely empty
                            // list may claim there is nothing here.
                            section.error?.let { error ->
                                item(key = "err-${section.section.name}") {
                                    SectionErrorCard(
                                        error = error,
                                        onRetry = { vm.retrySection() },
                                        modifier = Modifier.padding(horizontal = Spacing.ScreenPadding),
                                    )
                                }
                            }
                            if (section.partial) {
                                item(key = "partial-${section.section.name}") {
                                    SecondaryText(stringResource(R.string.list_partial), modifier = Modifier.cardGutter())
                                }
                            }
                            if (section.rows.isEmpty() && section.error == null) {
                                item(key = "empty-${section.section.name}") {
                                    SecondaryText(
                                        stringResource(R.string.state_empty),
                                        modifier = Modifier.cardGutter(),
                                    )
                                }
                            }
                            items(section.rows, key = { "${section.section.name}-${it.namespace}-${it.name}" }) { row ->
                                FluxRowView(
                                    row = row,
                                    onClick = {
                                        val resource = section.resource ?: return@FluxRowView
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
                    listOfNotNull(row.kind, row.namespace).joinToString(" · "),
                    modifier = Modifier.padding(horizontal = Spacing.SheetPadding),
                )
                Spacer(Modifier.size(Spacing.ItemGap))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_reconcile)) },
                    leadingContent = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        val target = row
                        sheetTarget = null
                        vm.reconcile(target, withSource = false) { }
                    },
                )
                if (row.kind == "GitRepository" || row.kind == "OCIRepository" || row.kind == "HelmRepository") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.flux_reconcile_with_source)) },
                        supportingContent = { SecondaryText(stringResource(R.string.flux_sources)) },
                        leadingContent = { Icon(Icons.Filled.AccountTree, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            val target = row
                            sheetTarget = null
                            vm.reconcile(target, withSource = true) { }
                        },
                    )
                }
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(
                                if (row.suspended) R.string.action_resume else R.string.action_suspend,
                            ),
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (row.suspended) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        val target = row
                        sheetTarget = null
                        vm.setSuspended(target, !target.suspended) { }
                    },
                )
            }
        }
    }
}

@Composable
private fun sectionTitle(section: FluxSection, present: List<FluxSectionState>): String {
    val sources = present.filter { it.section.titleRes == R.string.flux_sources }
    return if (section.titleRes == R.string.flux_sources && sources.size > 1) {
        "${stringResource(R.string.flux_sources_short)} · ${section.kind}"
    } else {
        stringResource(section.titleRes)
    }
}

@Composable
private fun FluxSummary(sections: List<FluxSectionState>) {
    val ready = sections.sumOf { section -> section.rows.count { it.health.tone == ResourceHealth.Tone.OK } }
    val total = sections.sumOf { it.rows.size }
    val failing = sections.sumOf { section ->
        section.rows.count {
            it.health.tone == ResourceHealth.Tone.BAD ||
                it.health.tone == ResourceHealth.Tone.WARN
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.ItemGap),
        horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
    ) {
        StatCard(stringResource(R.string.flux_ready_summary, ready, total), Modifier.weight(1f))
        StatCard(stringResource(R.string.flux_not_ready_summary, failing), Modifier.weight(1f))
        StatCard(stringResource(R.string.label_count, total), Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = KubeShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.ChipPadding)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
    }
}

/** The tile glyph for a Flux kind, so a source, a Kustomization and a HelmRelease read apart. */
private fun fluxKindIcon(kind: String): ImageVector = when (kind) {
    "Kustomization" -> Icons.Outlined.AccountTree
    "HelmRelease", "HelmRepository" -> Icons.Outlined.Inventory2
    "GitRepository" -> Icons.Outlined.Source
    "OCIRepository" -> Icons.Outlined.Layers
    "Bucket" -> Icons.Outlined.Storage
    else -> Icons.Outlined.AccountTree
}

/**
 * One Flux object as a standalone card, after the site's SyncCard: a tinted tile carrying the
 * health tone, the name over its kind and namespace, the status pills underneath, then the
 * revision, interval and URL. The overflow stays a separate tap target from the card itself.
 */
@Composable
private fun FluxRowView(row: FluxRow, onClick: () -> Unit, onMore: () -> Unit) {
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
                IconTile(icon = fluxKindIcon(row.kind), tone = row.health.tone, size = 36.dp)
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
                if (row.suspended) {
                    InfoChip(stringResource(R.string.flux_suspended), tone = ResourceHealth.Tone.WARN)
                }
            }
            val meta = listOfNotNull(
                row.revision?.let { "rev $it" },
                row.interval,
                row.url,
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
private fun FluxMissingPanel() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(Spacing.EmptyStatePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = Icons.Outlined.AccountTree, tone = ResourceHealth.Tone.NEUTRAL, size = 48.dp)
        Spacer(Modifier.size(Spacing.ContentInset))
        Text(
            text = stringResource(R.string.flux_missing_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(Spacing.ItemGap))
        Text(
            text = stringResource(R.string.flux_missing_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(Spacing.ContentInset))
        Text(
            text = stringResource(R.string.flux_missing_body),
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
                FLUX_CRD_NAMES.forEach { crd ->
                    MonoText(crd, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp))
                }
            }
        }
    }
}
