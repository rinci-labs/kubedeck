package dev.rafa.kubemobile.ui.detail

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.components.InfoChip
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.MonoText
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.components.SectionHeader
import dev.rafa.kubemobile.ui.copyToClipboard
import dev.rafa.kubemobile.ui.humanBytes
import dev.rafa.kubemobile.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortForwardScreen(
    app: AppViewModel,
    navController: NavController,
    namespace: String,
    pod: String,
) {
    val vm = screenViewModel(app) { a, handle -> PortForwardViewModel(a, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var customOpen by remember { mutableStateOf(false) }

    LaunchedEffect(namespace, pod) { vm.start(namespace, pod) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.pf_title, pod),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(
                                namespace.takeIf { it.isNotBlank() },
                                if (state.forwards.isNotEmpty()) {
                                    stringResource(R.string.label_count, state.forwards.size)
                                } else {
                                    null
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
                            vm.stopAll()
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
                    IconButton(onClick = { customOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.Numbers,
                            contentDescription = stringResource(R.string.pf_custom_port),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.ports.isEmpty() -> LoadingState(
                    label = stringResource(R.string.state_loading),
                )

                state.error != null && state.ports.isEmpty() -> ErrorState(
                    error = state.error!!,
                    onRetry = vm::loadPorts,
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = ListBottomPadding),
                ) {
                    // Section headers and their bodies both take the screen gutter: the empty
                    // "no active forwards" body previously sat at 0 dp and touched the edge.
                    item { SectionHeader(stringResource(R.string.pf_active)) }
                    if (state.forwards.isEmpty()) {
                        item {
                            SecondaryText(
                                stringResource(R.string.pf_none_active),
                                modifier = Modifier.padding(horizontal = Spacing.ScreenPadding),
                            )
                        }
                    } else {
                        items(state.forwards, key = { it.id }) { card ->
                            ForwardCardView(
                                card = card,
                                onCopy = {
                                    context.copyToClipboard("127.0.0.1:${card.localPort}", "127.0.0.1:${card.localPort}")
                                },
                                onStop = { vm.stop(card.id) },
                            )
                        }
                    }

                    if (state.ports.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.label_ports)) }
                        items(state.ports) { port ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        listOfNotNull(
                                            port.name,
                                            "${port.port}/${port.protocol}",
                                        ).joinToString(" · "),
                                    )
                                },
                                supportingContent = { SecondaryText(port.containerName) },
                                trailingContent = {
                                    TextButton(onClick = { vm.forward(port.port, port.containerName, port.protocol) }) {
                                        Text(stringResource(R.string.detail_forward))
                                    }
                                },
                            )
                        }
                    } else {
                        item {
                            SecondaryText(
                                stringResource(R.string.pf_no_ports),
                                modifier = Modifier.padding(horizontal = Spacing.ScreenPadding),
                            )
                        }
                    }
                }
            }
        }
    }

    if (customOpen) {
        CustomPortDialog(
            onDismiss = { customOpen = false },
            onConfirm = { port ->
                customOpen = false
                vm.forward(port)
            },
        )
    }
}

@Composable
private fun ForwardCardView(
    card: ForwardCard,
    onCopy: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
        colors = CardDefaults.cardColors(
            containerColor = if (card.error != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.padding(Spacing.CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    text = stringResource(R.string.pf_hint, card.localPort),
                    fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy_named, "127.0.0.1:${card.localPort}"),
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.action_stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap)) {
                InfoChip(stringResource(R.string.label_bytes_up, humanBytes(card.bytesUp)))
                InfoChip(stringResource(R.string.label_bytes_down, humanBytes(card.bytesDown)))
                InfoChip("→ ${card.remotePort}/${card.protocol}")
            }
            card.error?.let {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.pf_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun CustomPortDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableStateOf("") }
    val parsed = value.toIntOrNull()?.takeIf { it in 1..65535 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pf_custom_port)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.label_remote_port)) },
                placeholder = { Text(stringResource(R.string.pf_custom_hint)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text(stringResource(R.string.action_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
