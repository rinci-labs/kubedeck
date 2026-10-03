package dev.rafa.kubemobile.ui.flux

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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
fun FluxScreen(
    app: AppViewModel,
    navController: NavController,
    header: @Composable () -> Unit = {},
) {
    val vm = screenViewModel(app) { a, handle -> FluxViewModel(a, handle) }
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
                                    SectionErrorCard(error = error, onRetry = { vm.retrySection() })
                                }
                            }
                            if (section.partial) {
                                item(key = "partial-${section.section.name}") {
                                    SecondaryText(stringResource(R.string.list_partial), modifier = Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical))
                                }
                            }
                            if (section.rows.isEmpty() && section.error == null) {
                                item(key = "empty-${section.section.name}") {
                                    SecondaryText(
                                        stringResource(R.string.state_empty),
                                        modifier = Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
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
                    listOfNotNull(row.kind, row.namespace).joinToString(" · "),
                    modifier = Modifier.padding(horizontal = Spacing.SheetPadding),
                )
                Spacer(Modifier.size(8.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_reconcile)) },
                    leadingContent = { Icon(Icons.Filled.Refresh, contentDescription = null) },
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
    val ready = sections.sumOf { section -> section.rows.count { it.health.tone == dev.rafa.kubemobile.ops.ResourceHealth.Tone.OK } }
    val total = sections.sumOf { it.rows.size }
    val failing = sections.sumOf { section ->
        section.rows.count {
            it.health.tone == dev.rafa.kubemobile.ops.ResourceHealth.Tone.BAD ||
                it.health.tone == dev.rafa.kubemobile.ops.ResourceHealth.Tone.WARN
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(Spacing.CardPadding)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
    }
}

@Composable
private fun FluxRowView(row: FluxRow, onClick: () -> Unit, onMore: () -> Unit) {
    ListItem(
        headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                SecondaryText(
                    listOfNotNull(
                        row.namespace,
                        row.revision?.let { "rev $it" },
                        row.interval,
                        row.url,
                        if (row.suspended) stringResource(R.string.flux_suspended) else null,
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
        modifier = Modifier.clickable(onClick = onClick),
    )
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
        Icon(
            imageVector = Icons.Filled.AccountTree,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.flux_missing_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.flux_missing_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.flux_missing_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        FLUX_CRD_NAMES.forEach { crd ->
            MonoText(crd, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp))
        }
    }
}
