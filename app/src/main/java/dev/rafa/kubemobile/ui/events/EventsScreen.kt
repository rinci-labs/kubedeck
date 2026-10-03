package dev.rafa.kubemobile.ui.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.SessionState
import dev.rafa.kubemobile.ui.components.ListDivider
import dev.rafa.kubemobile.ui.components.BottomBarScreen
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.ConnectionGate
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.SectionHeader
import dev.rafa.kubemobile.ui.copyToClipboard
import dev.rafa.kubemobile.ui.detail.EventCard
import dev.rafa.kubemobile.ui.navigateToTop
import dev.rafa.kubemobile.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(app: AppViewModel, navController: NavController) {
    val vm = screenViewModel(app) { a, handle -> EventsViewModel(a, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sessionState by app.sessionState.collectAsStateWithLifecycle()
    val namespaces by app.namespaces.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scopeSheet by remember { mutableStateOf(false) }

    LaunchedEffect(sessionState) {
        if (sessionState is SessionState.Ready) vm.start()
    }

    val visible = remember(state.rows, state.scope, state.warningsOnly) { vm.visible() }
    val grouped = remember(visible) { vm.grouped() }

    BottomBarScreen(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.events_title))
                        Text(
                            text = listOfNotNull(
                                state.scope ?: stringResource(R.string.label_all_namespaces),
                                stringResource(R.string.label_count, visible.size),
                                if (state.warningsOnly) stringResource(R.string.events_warning) else null,
                            ).joinToString(" · "),
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
                    IconButton(onClick = { context.copyToClipboard("events", vm.copyText()) }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                        )
                    }
                    IconButton(onClick = { scopeSheet = true }) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = stringResource(R.string.label_namespace),
                        )
                    }
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
            val session = (sessionState as? SessionState.Ready)?.session
            when {
                session == null -> ConnectionGate(
                    state = sessionState,
                    onRetry = { app.connectInBackground(it) },
                    onGoToClusters = { navController.navigateToTop(Routes.CLUSTERS) },
                )

                state.loading && state.rows.isEmpty() -> LoadingState(label = stringResource(R.string.state_loading))

                state.error != null && state.rows.isEmpty() -> ErrorState(
                    error = state.error!!,
                    onRetry = vm::retry,
                )

                else -> Column {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
                    ) {
                        FilterChip(
                            selected = !state.warningsOnly,
                            onClick = { vm.setWarningsOnly(false) },
                            label = { Text(stringResource(R.string.events_normal)) },
                        )
                        FilterChip(
                            selected = state.warningsOnly,
                            onClick = { vm.setWarningsOnly(true) },
                            label = { Text(stringResource(R.string.events_warning)) },
                        )
                        AssistChip(
                            onClick = { vm.setGrouped(!state.grouped) },
                            label = {
                                Text(
                                    if (state.grouped) {
                                        stringResource(R.string.label_involved_object)
                                    } else {
                                        stringResource(R.string.label_time)
                                    },
                                )
                            },
                        )
                    }
                    if (visible.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.events_empty_title),
                            body = stringResource(R.string.events_empty_body),
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.Event,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    } else {
                        PullToRefreshBox(
                            isRefreshing = state.refreshing,
                            onRefresh = vm::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
                                if (state.grouped) {
                                    grouped.forEach { (key, list) ->
                                        item(key = "g-$key") {
                                            SectionHeader("${list.size}× $key")
                                        }
                                        items(list, key = { it.uid }) { row ->
                                            EventCard(row.object_)
                                            ListDivider()
                                        }
                                    }
                                } else {
                                    items(visible, key = { it.uid }) { row ->
                                        EventCard(row.object_)
                                        ListDivider()
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
        ModalBottomSheet(
            onDismissRequest = { scopeSheet = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.padding(bottom = Spacing.SheetPadding)) {
                Text(
                    text = stringResource(R.string.label_namespace),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.ItemGap),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.helm_all_namespaces)) },
                    modifier = Modifier.clickable {
                        scopeSheet = false
                        vm.setScope(null)
                    },
                )
                (namespaces.ifEmpty { vm.namespacesInFeed() }).forEach { ns ->
                    ListItem(
                        headlineContent = { Text(ns) },
                        modifier = Modifier.clickable {
                            scopeSheet = false
                            vm.setScope(ns)
                        },
                    )
                }
            }
        }
    }
}
