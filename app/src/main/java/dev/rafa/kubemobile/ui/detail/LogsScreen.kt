package dev.rafa.kubemobile.ui.detail

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.MenuAction
import dev.rafa.kubemobile.ui.components.OverflowMenu
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.components.ToneDot
import dev.rafa.kubemobile.ui.copyToClipboard
import dev.rafa.kubemobile.ui.screenViewModel
import dev.rafa.kubemobile.ui.shareText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    app: AppViewModel,
    navController: NavController,
    namespace: String,
    pod: String,
    initialContainer: String? = null,
    workloadKind: String? = null,
) {
    val vm = screenViewModel(app) { a, handle -> LogsViewModel(a, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var optionsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(namespace, pod, workloadKind) { vm.start(namespace, pod, workloadKind, initialContainer) }

    // Auto-scroll pauses only when the reader deliberately scrolls up, and resumes the moment
    // they come back to the bottom. An empty or short list always counts as "at the bottom".
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount <= 2) return@derivedStateOf true
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(atBottom) { vm.setFollowed(atBottom) }
    LaunchedEffect(state.lines.size, state.followed) {
        if (state.followed && state.lines.isNotEmpty()) {
            listState.scrollToItem(state.lines.lastIndex)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.logs_title, pod),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(
                                namespace.takeIf { it.isNotBlank() },
                                state.container.takeIf { !state.tagged },
                                state.cappedFrom?.let {
                                    stringResource(R.string.logs_capped, state.trackedPods, it)
                                },
                                if (state.streaming) "streaming" else stringResource(R.string.logs_stopped),
                                if (!state.followed) stringResource(R.string.logs_paused) else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            vm.stop()
                            navController.popBackStack()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { optionsOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.logs_options),
                        )
                    }
                    IconButton(onClick = { vm.restart() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                    OverflowMenu(
                        actions = listOf(
                            MenuAction(
                                label = stringResource(R.string.logs_copy_all),
                                onClick = {
                                    context.copyToClipboard("logs", vm.fullText())
                                    scope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.action_copied))
                                    }
                                },
                            ),
                            MenuAction(
                                label = stringResource(R.string.logs_share),
                                onClick = { shareText(context, "logs-$pod.txt", vm.fullText()) },
                            ),
                            if (state.streaming) {
                                MenuAction(stringResource(R.string.action_stop), vm::stop)
                            } else {
                                MenuAction(stringResource(R.string.action_start), vm::restart)
                            },
                        ),
                    )
                },
            )
        },
        floatingActionButton = {
            if (!state.followed) {
                AssistChip(shape = RectangleShape, 
                    onClick = {
                        vm.setFollowed(true)
                        scope.launch { listState.scrollToItem(state.lines.lastIndex.coerceAtLeast(0)) }
                    },
                    label = { Text(stringResource(R.string.action_jump_latest)) },
                    leadingIcon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null, Modifier.size(16.dp)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.isWorkload) {
                PodSelectorRow(state = state, vm = vm)
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    state.isWorkload && state.podsLoading -> LoadingState(label = stringResource(R.string.state_loading))

                    state.podsError != null -> ErrorState(error = state.podsError!!, onRetry = vm::restart)

                    state.isWorkload && state.pods.isEmpty() && !state.podsLoading -> EmptyState(
                        title = stringResource(R.string.logs_no_pods),
                        body = stringResource(R.string.logs_no_pods_body, state.workloadKind ?: pod),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = vm::restart,
                    )

                    state.error != null && state.lines.isEmpty() -> ErrorState(
                        error = state.error!!,
                        onRetry = vm::restart,
                    )

                    state.lines.isEmpty() && state.streaming -> LoadingState(label = stringResource(R.string.logs_empty))

                    state.lines.isEmpty() -> EmptyState(
                        title = stringResource(R.string.logs_stopped),
                        body = stringResource(R.string.logs_empty),
                        actionLabel = stringResource(R.string.action_start),
                        onAction = vm::restart,
                    )

                    else -> Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // Same gutter as every other screen, so a log line starts on the same
                            // vertical line as the rest of the app's content.
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = Spacing.ScreenPadding,
                                vertical = Spacing.TightGap,
                            ),
                        ) {
                            itemsIndexed(state.lines) { _, line ->
                                LogLine(line)
                            }
                        }
                    }
                }
                if (state.error != null && state.lines.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.ItemGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.error!!.message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = vm::restart) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                }
            }
        }
    }

    if (optionsOpen) {
        LogOptionsSheet(
            state = state,
            onContainer = vm::setContainer,
            onTail = vm::setTail,
            onTimestamps = vm::setTimestamps,
            onFollow = vm::setFollow,
            onPrevious = vm::setPrevious,
            onDismiss = { optionsOpen = false },
        )
    }
}

@Composable
private fun PodSelectorRow(state: LogsUiState, vm: LogsViewModel) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.ItemGap),
            horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(shape = RectangleShape, 
                selected = state.allPods,
                onClick = { vm.setAllPods(true) },
                label = {
                    Text(
                        text = stringResource(R.string.logs_all_pods),
                        maxLines = 1,
                    )
                },
            )
            state.pods.forEach { pod ->
                FilterChip(shape = RectangleShape, 
                    selected = !state.allPods && state.selectedPod == pod.name,
                    onClick = { vm.setSelectedPod(pod.name) },
                    label = {
                        Text(
                            text = pod.name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    },
                    leadingIcon = {
                        ToneDot(
                            tone = when {
                                pod.phase == "Running" && pod.ready -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.OK
                                pod.phase == "Running" -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.WARN
                                else -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.BAD
                            },
                            size = 8.dp,
                        )
                    },
                )
            }
        }
        // Never truncate silently: a workload with more pods than the cap says so.
        state.cappedFrom?.let { total ->
            SecondaryText(
                text = stringResource(R.string.logs_capped, state.trackedPods, total),
                modifier = Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = 2.dp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LogLine(line: String) {
    val severity = remember(line) { logSeverity(line) }
    val color = when (severity) {
        2 -> MaterialTheme.colorScheme.error
        1 -> dev.rafa.kubemobile.ui.toneColors(dev.rafa.kubemobile.ops.ResourceHealth.Tone.WARN).content
        else -> MaterialTheme.colorScheme.onSurface
    }
    SelectionContainer {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    if (severity == 0) Color.Transparent else color.copy(alpha = 0.08f),
                ),
        ) {
            Text(
                text = line,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                ),
                color = color,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogOptionsSheet(
    state: LogsUiState,
    onContainer: (String) -> Unit,
    onTail: (Int) -> Unit,
    onTimestamps: (Boolean) -> Unit,
    onFollow: (Boolean) -> Unit,
    onPrevious: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.SheetPadding)) {
            Text(
                text = stringResource(R.string.logs_options),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.ItemGap),
            )
            // In a merged view every pod's containers are read, so a container picker would be a
            // lie; the sheet says why instead of hiding the control without explanation.
            if (state.tagged) {
                SecondaryText(
                    text = stringResource(R.string.logs_options_merged),
                    modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.RowVertical),
                )
            } else if (state.containers.size > 1) {
                Text(
                    text = stringResource(R.string.logs_container),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.RowVertical),
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.SheetPadding),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
                ) {
                    state.containers.forEach { container ->
                        FilterChip(shape = RectangleShape, 
                            selected = state.container == container,
                            onClick = { onContainer(container) },
                            label = { Text(container, maxLines = 1) },
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.logs_tail_size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.RowVertical),
            )
            Row(
                Modifier.padding(horizontal = Spacing.SheetPadding),
                horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
            ) {
                LOG_TAIL_OPTIONS.forEach { tail ->
                    FilterChip(shape = RectangleShape, 
                        selected = state.tailLines == tail,
                        onClick = { onTail(tail) },
                        label = { Text(tail.toString()) },
                    )
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.label_timestamps)) },
                trailingContent = { Switch(checked = state.timestamps, onCheckedChange = onTimestamps) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.label_follow)) },
                supportingContent = { SecondaryText(stringResource(R.string.logs_follow_hint)) },
                trailingContent = { Switch(checked = state.follow, onCheckedChange = onFollow) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.label_previous)) },
                supportingContent = { SecondaryText(stringResource(R.string.logs_previous_hint)) },
                trailingContent = { Switch(checked = state.previous, onCheckedChange = onPrevious) },
            )
        }
    }
}
