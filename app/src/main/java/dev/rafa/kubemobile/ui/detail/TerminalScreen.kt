package dev.rafa.kubemobile.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
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
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    app: AppViewModel,
    navController: NavController,
    namespace: String,
    pod: String,
    initialContainer: String? = null,
) {
    val vm = screenViewModel(app) { a, handle -> TerminalViewModel(a, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var optionsOpen by remember { mutableStateOf(false) }

    // Without this the view model's namespace/pod stay empty and `Exec.start` builds
    // `/api/v1/namespaces//pods//exec`, which collapses to a 404 route on the API server.
    LaunchedEffect(namespace, pod) { vm.start(namespace, pod, initialContainer) }

    // Full-screen TUIs (top, vim, htop) get a phone-sized grid instead of the server's 80x24
    // default, derived from the transcript viewport and the monospace glyph box.
    var terminalPixels by remember { mutableStateOf(0 to 0) }
    val density = LocalDensity.current
    val cellWidthPx = with(density) { TERMINAL_CELL_WIDTH_DP.dp.toPx() }
    val cellHeightPx = with(density) { TERMINAL_CELL_HEIGHT_DP.dp.toPx() }
    LaunchedEffect(terminalPixels, state.status) {
        val (width, height) = terminalPixels
        if (width > 0 && height > 0 && state.status == TerminalStatus.OPEN) {
            vm.onResize(
                columns = (width / cellWidthPx).toInt().coerceAtLeast(20),
                rows = (height / cellHeightPx).toInt().coerceAtLeast(5),
            )
        }
    }

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.terminal_title, pod),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(
                                namespace.takeIf { it.isNotBlank() },
                                state.container,
                                state.shell,
                                when (state.status) {
                                    TerminalStatus.OPEN -> "connected"
                                    TerminalStatus.CONNECTING -> stringResource(R.string.terminal_connecting)
                                    TerminalStatus.CLOSED -> stringResource(R.string.state_session_closed)
                                    TerminalStatus.FAILED -> stringResource(R.string.state_error)
                                    TerminalStatus.IDLE -> null
                                },
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
                            vm.disconnect()
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
                            contentDescription = stringResource(R.string.terminal_options),
                        )
                    }
                    IconButton(onClick = vm::connect) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_reconnect),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.status == TerminalStatus.CONNECTING && state.lines.isEmpty() ->
                        LoadingState(label = stringResource(R.string.terminal_connecting))

                    // A live session always gets the transcript view, even before the first byte
                    // arrives, so the banner and the cursor area are visible.
                    state.status == TerminalStatus.OPEN -> Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { size -> terminalPixels = size.width to size.height },
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // The shared screen gutter, so the transcript's first column lines up
                            // with every other screen's content.
                            contentPadding = PaddingValues(
                                horizontal = Spacing.ScreenPadding,
                                vertical = Spacing.TightGap,
                            ),
                        ) {
                            item(key = "banner") {
                                SelectionContainer {
                                    Column {
                                        Text(
                                            text = state.banner.ifBlank {
                                                stringResource(R.string.terminal_banner)
                                            },
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                            ),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        if (state.lines.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.terminal_waiting),
                                                style = TextStyle(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    lineHeight = 15.sp,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            items(state.lines.size) { index ->
                                SelectionContainer {
                                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                                        Text(
                                            text = state.lines[index],
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    state.status == TerminalStatus.FAILED && state.lines.isEmpty() -> ErrorState(
                        error = state.error ?: dev.rafa.kubemobile.ui.UiError(
                            R.string.state_error,
                            stringResource(R.string.terminal_closed_body),
                            null,
                        ),
                        onRetry = vm::connect,
                    )

                    // Closed with nothing produced: almost always a container without that shell
                    // (distroless/scratch images) rather than a transport problem, so say so and
                    // surface whatever the server did send.
                    else -> EmptyState(
                        title = stringResource(R.string.action_shell),
                        body = buildString {
                            append(stringResource(R.string.terminal_closed_hint))
                            state.error?.message?.takeIf { it.isNotBlank() }?.let {
                                append(' ')
                                append(it)
                            }
                        },
                        actionLabel = stringResource(R.string.action_reconnect),
                        onAction = vm::connect,
                    )
                }
            }

            if (state.status == TerminalStatus.CLOSED || state.status == TerminalStatus.FAILED) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.ItemGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.terminal_closed_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Button(shape = RectangleShape, onClick = vm::connect) { Text(stringResource(R.string.action_reconnect)) }
                    }
                }
            }

            ControlBar(
                pending = state.pendingInput,
                enabled = state.status == TerminalStatus.OPEN,
                onInput = vm::setInput,
                onSend = vm::submit,
                onCtrlC = { vm.send("\u0003") },
                onEscape = { vm.send("\u001b") },
                onTab = { vm.send("\t") },
            )
        }
    }

    if (optionsOpen) {
        TerminalOptionsSheet(
            state = state,
            onContainer = vm::setContainer,
            onShell = vm::setShell,
            onTty = vm::setTty,
            onDismiss = { optionsOpen = false },
        )
    }
}

/**
 * Glyph advance and line height of the 11sp monospace transcript, in pixels at the device density.
 * The values mirror the `TextStyle` used by the transcript (`fontSize = 11.sp`,
 * `lineHeight = 15.sp`, monospace advance ≈ 0.6em) so `pods/exec` resize reports the same grid the
 * user actually sees.
 */
private val TERMINAL_CELL_WIDTH_DP = 6.6f
private val TERMINAL_CELL_HEIGHT_DP = 15f

@Composable
private fun ControlBar(
    pending: String,
    enabled: Boolean,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onCtrlC: () -> Unit,
    onEscape: () -> Unit,
    onTab: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.ItemGap)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(shape = RectangleShape, onClick = onCtrlC, label = { Text("^C") }, enabled = enabled)
                AssistChip(shape = RectangleShape, onClick = onEscape, label = { Text("Esc") }, enabled = enabled)
                AssistChip(shape = RectangleShape, onClick = onTab, label = { Text("Tab") }, enabled = enabled)
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = pending,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = enabled,
                    placeholder = { Text(stringResource(R.string.terminal_input_hint)) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    // Enter sends, which is what a shell prompt trains people to expect; the
                    // button stays for touch-only use.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                IconButton(onClick = onSend, enabled = enabled) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.terminal_send),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalOptionsSheet(
    state: TerminalUiState,
    onContainer: (String) -> Unit,
    onShell: (String) -> Unit,
    onTty: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.SheetPadding)) {
            Text(
                text = stringResource(R.string.label_shell),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.ItemGap),
            )
            Row(
                Modifier.padding(horizontal = Spacing.SheetPadding),
                horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
            ) {
                SHELL_OPTIONS.forEach { shell ->
                    FilterChip(shape = RectangleShape, 
                        selected = state.shell == shell,
                        onClick = { onShell(shell) },
                        label = { Text(shell, maxLines = 1) },
                    )
                }
            }
            if (state.containers.size > 1) {
                Spacer(Modifier.size(8.dp))
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
            ListItem(
                headlineContent = { Text(stringResource(R.string.terminal_tty)) },
                supportingContent = { SecondaryText("Interactive TTY is required for most shells") },
                trailingContent = { Switch(checked = state.tty, onCheckedChange = onTty) },
            )
            Spacer(Modifier.size(8.dp))
            Row(Modifier.padding(horizontal = Spacing.SheetPadding)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}
