package dev.rafa.kubemobile.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.filled.WrapText
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import dev.rafa.kubemobile.ui.components.SearchField
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import dev.rafa.kubemobile.ui.KubeShapes
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.InlineBanner
import dev.rafa.kubemobile.ui.components.ListDivider
import dev.rafa.kubemobile.ui.components.ListGroup
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
    var searchOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // Search and the level filter only change what is drawn, never the stream itself.
    val visibleLines = remember(state.lines, state.query, state.minSeverity) {
        val query = state.query.trim()
        if (query.isEmpty() && state.minSeverity == 0) {
            state.lines
        } else {
            state.lines.filter { line ->
                (query.isEmpty() || line.contains(query, ignoreCase = true)) &&
                    (state.minSeverity == 0 || logSeverity(line) >= state.minSeverity)
            }
        }
    }

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
    LaunchedEffect(visibleLines.size, state.followed) {
        if (state.followed && visibleLines.isNotEmpty()) {
            listState.scrollToItem(visibleLines.lastIndex)
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
                                LOG_SINCE_OPTIONS.firstOrNull { it.second != null && it.second == state.sinceSeconds }
                                    ?.let { stringResource(R.string.logs_since_label, it.first) },
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
                    IconButton(
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) {
                                vm.setQuery("")
                                vm.setMinSeverity(0)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (searchOpen) Icons.Filled.SearchOff else Icons.Filled.Search,
                            contentDescription = stringResource(R.string.logs_search),
                            tint = if (searchOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    // Everything else (wrap, stream window, refresh, clear, copy, share) lives in
                    // one options sheet so the bar keeps room for the title and status line.
                    IconButton(onClick = { optionsOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.logs_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.followed) {
                // A solid, raised pill: a transparent chip disappears into the log text under it.
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = {
                        vm.setFollowed(true)
                        scope.launch { listState.scrollToItem(visibleLines.lastIndex.coerceAtLeast(0)) }
                    },
                    shape = KubeShapes.Pill,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null, Modifier.size(18.dp)) },
                    text = { Text(stringResource(R.string.action_jump_latest)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.isWorkload) {
                PodSelectorRow(state = state, vm = vm)
            }
            if (searchOpen) {
                LogSearchBar(
                    query = state.query,
                    onQuery = vm::setQuery,
                    minSeverity = state.minSeverity,
                    onMinSeverity = vm::setMinSeverity,
                    matches = visibleLines.size,
                    total = state.lines.size,
                )
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

                    else -> BoxWithConstraints(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    ) {
                        // Every row is at least the viewport wide, so severity washes span the
                        // screen even when wrapping is off and the list scrolls sideways.
                        val viewport = maxWidth
                        val horizontal = rememberScrollState()
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (state.wrap) Modifier else Modifier.horizontalScroll(horizontal)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                vertical = Spacing.ItemGap,
                            ),
                        ) {
                            itemsIndexed(visibleLines) { _, line ->
                                LogLine(
                                    line = line,
                                    tagged = state.tagged,
                                    wrap = state.wrap,
                                    minWidth = viewport,
                                    highlight = state.query.trim(),
                                )
                            }
                        }
                        if (visibleLines.isEmpty()) {
                            SecondaryText(
                                text = stringResource(R.string.logs_no_matches),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(Spacing.SectionGap),
                            )
                        }
                    }
                }
                // A stream that dies after producing output keeps the transcript readable and
                // floats a rounded banner over the bottom instead of replacing the lines.
                if (state.error != null && state.lines.isNotEmpty()) {
                    InlineBanner(
                        title = stringResource(state.error!!.titleRes),
                        message = state.error!!.message,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = vm::restart,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.ItemGap),
                    )
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
            onWrap = vm::setWrap,
            onSince = vm::setSince,
            onFollow = vm::setFollow,
            onPrevious = vm::setPrevious,
            onStartStop = { if (state.streaming) vm.stop() else vm.restart() },
            onRefresh = {
                optionsOpen = false
                vm.restart()
            },
            onClear = vm::clear,
            onCopy = {
                context.copyToClipboard("logs", vm.fullText())
                optionsOpen = false
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.action_copied)) }
            },
            onShare = {
                optionsOpen = false
                shareText(context, "logs-$pod.txt", vm.fullText())
            },
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
            FilterChip(
                shape = KubeShapes.Pill,
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
                FilterChip(
                    shape = KubeShapes.Pill,
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

/** One parsed log row: an optional pod tag, an optional timestamp and the message itself. */
private data class ParsedLogLine(
    val pod: String?,
    val time: String?,
    val message: String,
    val severity: Int,
)

private val LOG_TIMESTAMP = Regex("""^(\d{4}-\d{2}-\d{2}T(\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(?:Z|[+-]\d{2}:\d{2}))\s?""")

/**
 * Splits the `pod | ` prefix a merged stream adds and the RFC 3339 stamp `--timestamps` adds, so
 * both can be drawn as quiet metadata instead of repeating a 25-character pod name on every row.
 */
private fun parseLogLine(line: String, tagged: Boolean): ParsedLogLine {
    var rest = line
    var pod: String? = null
    if (tagged) {
        val split = rest.indexOf(" | ")
        if (split > 0) {
            pod = rest.substring(0, split)
            rest = rest.substring(split + 3)
        } else if (rest.endsWith(" |")) {
            pod = rest.removeSuffix(" |")
            rest = ""
        }
    }
    var time: String? = null
    LOG_TIMESTAMP.find(rest)?.let { match ->
        val millis = match.groupValues[3].take(3)
        time = if (millis.isEmpty()) match.groupValues[2] else "${match.groupValues[2]}.$millis"
        rest = rest.substring(match.range.last + 1)
    }
    return ParsedLogLine(pod = pod, time = time, message = rest, severity = logSeverity(rest))
}

/**
 * The short, recognisable part of a pod name: the random suffix a ReplicaSet or StatefulSet adds
 * (`web-86c8bd46c-b6x9w` → `b6x9w`). The full name is in the pod selector above the log.
 */
private fun shortPodName(pod: String): String = pod.substringAfterLast('-').ifBlank { pod }

/** Calm, distinguishable tag colours for merged pods; a pod keeps its colour for the session. */
private val POD_TAG_COLORS_DARK = listOf(
    Color(0xFF8FD8AF), Color(0xFF8EC5F2), Color(0xFFE5B45C),
    Color(0xFFC9A7F0), Color(0xFFF0A08C), Color(0xFF7FD6D0),
)
private val POD_TAG_COLORS_LIGHT = listOf(
    Color(0xFF2F6B4F), Color(0xFF2D5F8A), Color(0xFF7A5200),
    Color(0xFF6A43A0), Color(0xFF9A3E2A), Color(0xFF1F6E69),
)

@Composable
private fun podTagColor(pod: String): Color {
    val palette = if (androidx.compose.foundation.isSystemInDarkTheme()) POD_TAG_COLORS_DARK else POD_TAG_COLORS_LIGHT
    return palette[Math.floorMod(pod.hashCode(), palette.size)]
}

private val LogTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

/**
 * One log row. Severity reads from a thin accent bar and a faint wash rather than a whole line of
 * red text, so a burst of errors stays legible. Metadata (pod tag, time) is muted and sits in front
 * of the message; with wrapping on, continuation lines hang under the message, not under the tag.
 */
@Composable
private fun LogLine(
    line: String,
    tagged: Boolean,
    wrap: Boolean,
    minWidth: androidx.compose.ui.unit.Dp,
    highlight: String = "",
) {
    val parsed = remember(line, tagged) { parseLogLine(line, tagged) }
    val accent = when (parsed.severity) {
        2 -> MaterialTheme.colorScheme.error
        1 -> dev.rafa.kubemobile.ui.toneColors(dev.rafa.kubemobile.ops.ResourceHealth.Tone.WARN).content
        else -> Color.Transparent
    }
    val messageColor = when (parsed.severity) {
        2 -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val matchColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val message = remember(parsed.message, highlight, matchColor) {
        highlightMatches(parsed.message, highlight, matchColor)
    }
    Row(
        modifier = Modifier
            .widthIn(min = minWidth)
            .background(if (parsed.severity == 0) Color.Transparent else accent.copy(alpha = 0.07f))
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Row(
            modifier = Modifier.padding(start = Spacing.ChipPadding - 2.dp, end = Spacing.ScreenPadding, top = 1.dp, bottom = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
        ) {
            parsed.pod?.let { pod ->
                Text(
                    text = shortPodName(pod),
                    style = LogTextStyle,
                    color = podTagColor(pod),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            parsed.time?.let { time ->
                Text(
                    text = time,
                    style = LogTextStyle,
                    color = muted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            SelectionContainer(if (wrap) Modifier.weight(1f) else Modifier) {
                Text(
                    text = message,
                    style = LogTextStyle,
                    color = messageColor,
                    softWrap = wrap,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LogOptionsSheet(
    state: LogsUiState,
    onContainer: (String) -> Unit,
    onTail: (Int) -> Unit,
    onTimestamps: (Boolean) -> Unit,
    onWrap: (Boolean) -> Unit,
    onSince: (Long?) -> Unit,
    onFollow: (Boolean) -> Unit,
    onPrevious: (Boolean) -> Unit,
    onStartStop: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.SectionGap),
        ) {
            Text(
                text = stringResource(R.string.logs_options),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.ItemGap),
            )

            // Everything that acts on the transcript, one tap each, so the app bar can stay quiet.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.SheetPadding, vertical = Spacing.ItemGap),
                horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
            ) {
                SheetAction(
                    icon = if (state.streaming) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = stringResource(if (state.streaming) R.string.action_stop else R.string.action_start),
                    onClick = onStartStop,
                    modifier = Modifier.weight(1f),
                )
                SheetAction(Icons.Filled.Refresh, stringResource(R.string.action_refresh), onRefresh, Modifier.weight(1f))
                SheetAction(
                    icon = Icons.Filled.ClearAll,
                    label = stringResource(R.string.logs_clear),
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    enabled = state.lines.isNotEmpty(),
                )
                SheetAction(
                    icon = Icons.Filled.ContentCopy,
                    label = stringResource(R.string.logs_action_copy),
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    enabled = state.lines.isNotEmpty(),
                )
                SheetAction(
                    icon = Icons.Filled.Share,
                    label = stringResource(R.string.logs_action_share),
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    enabled = state.lines.isNotEmpty(),
                )
            }

            LogsSheetLabel(stringResource(R.string.logs_section_display))
            ListGroup {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.logs_wrap)) },
                    trailingContent = { Switch(checked = state.wrap, onCheckedChange = onWrap) },
                    colors = rowColors,
                    modifier = Modifier.clickable { onWrap(!state.wrap) },
                )
                ListDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.label_timestamps)) },
                    trailingContent = { Switch(checked = state.timestamps, onCheckedChange = onTimestamps) },
                    colors = rowColors,
                    modifier = Modifier.clickable { onTimestamps(!state.timestamps) },
                )
            }

            LogsSheetLabel(stringResource(R.string.logs_section_stream))
            ListGroup {
                Column(Modifier.padding(vertical = Spacing.ItemGap)) {
                    SheetChipRow(stringResource(R.string.logs_tail_size)) {
                        LOG_TAIL_OPTIONS.forEach { tail ->
                            FilterChip(
                                shape = KubeShapes.Pill,
                                selected = state.tailLines == tail,
                                onClick = { onTail(tail) },
                                label = { Text(tail.toString()) },
                            )
                        }
                    }
                    SheetChipRow(stringResource(R.string.logs_since)) {
                        LOG_SINCE_OPTIONS.forEach { (label, seconds) ->
                            FilterChip(
                                shape = KubeShapes.Pill,
                                selected = state.sinceSeconds == seconds,
                                onClick = { onSince(seconds) },
                                label = { Text(label) },
                            )
                        }
                    }
                    if (!state.tagged && state.containers.size > 1) {
                        SheetChipRow(stringResource(R.string.logs_container)) {
                            state.containers.forEach { container ->
                                FilterChip(
                                    shape = KubeShapes.Pill,
                                    selected = state.container == container,
                                    onClick = { onContainer(container) },
                                    label = { Text(container, maxLines = 1) },
                                )
                            }
                        }
                    }
                }
                ListDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.label_follow)) },
                    supportingContent = { SecondaryText(stringResource(R.string.logs_follow_hint)) },
                    trailingContent = { Switch(checked = state.follow, onCheckedChange = onFollow) },
                    colors = rowColors,
                    modifier = Modifier.clickable { onFollow(!state.follow) },
                )
                ListDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.label_previous)) },
                    supportingContent = { SecondaryText(stringResource(R.string.logs_previous_hint)) },
                    trailingContent = { Switch(checked = state.previous, onCheckedChange = onPrevious) },
                    colors = rowColors,
                    modifier = Modifier.clickable { onPrevious(!state.previous) },
                )
            }
            // In a merged view every pod's containers are read, so a container picker would be a
            // lie; the sheet says why instead of hiding the control without explanation.
            if (state.tagged) {
                SecondaryText(
                    text = stringResource(R.string.logs_options_merged),
                    modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.ItemGap),
                )
            }
        }
    }
}

/** A labelled, horizontally scrolling chip row inside a sheet card. */
@Composable
private fun SheetChipRow(label: String, chips: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = Spacing.TightGap)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.CardPadding),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.CardPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
        ) { chips() }
    }
}

/** An icon-over-label action tile for the sheet's quick action row. */
@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        modifier = modifier
            .clip(KubeShapes.Field)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = Spacing.ItemGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha), KubeShapes.Tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.size(Spacing.TightGap))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Sentence-case, muted label above a group of controls in an options sheet. */
@Composable
private fun LogsSheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = Spacing.SheetPadding,
            end = Spacing.SheetPadding,
            top = Spacing.ContentInset,
            bottom = Spacing.ItemGap,
        ),
    )
}

/** Marks every case-insensitive occurrence of [query] with a soft mint wash. */
private fun highlightMatches(
    text: String,
    query: String,
    color: Color,
): androidx.compose.ui.text.AnnotatedString {
    if (query.isEmpty()) return androidx.compose.ui.text.AnnotatedString(text)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        var from = text.indexOf(query, ignoreCase = true)
        while (from >= 0) {
            addStyle(androidx.compose.ui.text.SpanStyle(background = color), from, from + query.length)
            from = text.indexOf(query, from + query.length, ignoreCase = true)
        }
    }
}

/**
 * Search over the transcript plus a level filter. Both only narrow what is drawn, so the stream
 * keeps running underneath and clearing the query brings every line straight back.
 */
@Composable
private fun LogSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    minSeverity: Int,
    onMinSeverity: (Int) -> Unit,
    matches: Int,
    total: Int,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.TightGap),
    ) {
        SearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = stringResource(R.string.logs_search),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.ItemGap),
            horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                0 to stringResource(R.string.logs_level_all),
                1 to stringResource(R.string.logs_level_warn),
                2 to stringResource(R.string.logs_level_error),
            ).forEach { (level, label) ->
                FilterChip(
                    shape = KubeShapes.Pill,
                    selected = minSeverity == level,
                    onClick = { onMinSeverity(level) },
                    label = { Text(label, maxLines = 1) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (query.isNotBlank() || minSeverity > 0) {
                Text(
                    text = stringResource(R.string.logs_matches, matches, total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
