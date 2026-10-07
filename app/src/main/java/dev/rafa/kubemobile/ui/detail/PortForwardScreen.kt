package dev.rafa.kubemobile.ui.detail

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.ops.ResourceHealth
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.cardGutter
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.KubeShapes
import dev.rafa.kubemobile.ui.components.InfoChip
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.IconTile
import dev.rafa.kubemobile.ui.components.ListDivider
import dev.rafa.kubemobile.ui.components.ListGroup
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.MonoText
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.components.SectionHeader
import dev.rafa.kubemobile.ui.components.softFieldColors
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
    val vm = screenViewModel(app) { a, _ -> PortForwardViewModel(a) }
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
                    // Every section body sits in a rounded card one screen gutter in, so the empty
                    // hints line up with the forward cards and the grouped port list.
                    item { SectionHeader(stringResource(R.string.pf_active)) }
                    if (state.forwards.isEmpty()) {
                        item { PortForwardHintCard(stringResource(R.string.pf_none_active)) }
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
                        // Declared ports are one short run of rows, so they share a single rounded
                        // group with inset dividers rather than floating as full-bleed list items.
                        item {
                            ListGroup {
                                state.ports.forEachIndexed { index, port ->
                                    if (index > 0) ListDivider()
                                    ListItem(
                                        leadingContent = {
                                            IconTile(
                                                icon = Icons.Filled.Numbers,
                                                tone = ResourceHealth.Tone.NEUTRAL,
                                                size = 36.dp,
                                            )
                                        },
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
                                            FilledTonalButton(
                                                onClick = { vm.forward(port.port, port.containerName, port.protocol) },
                                            ) {
                                                Text(stringResource(R.string.detail_forward))
                                            }
                                        },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    )
                                }
                            }
                        }
                    } else {
                        item { PortForwardHintCard(stringResource(R.string.pf_no_ports)) }
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
    val failed = card.error != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .cardGutter(),
        shape = KubeShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = if (failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            1.dp,
            if (failed) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(Modifier.padding(Spacing.CardPadding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.ChipPadding),
            ) {
                IconTile(
                    icon = Icons.Filled.SwapHoriz,
                    tone = if (failed) ResourceHealth.Tone.BAD else ResourceHealth.Tone.OK,
                    size = 36.dp,
                )
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
            Spacer(Modifier.size(Spacing.ItemGap))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap)) {
                InfoChip(stringResource(R.string.label_bytes_up, humanBytes(card.bytesUp)))
                InfoChip(stringResource(R.string.label_bytes_down, humanBytes(card.bytesDown)))
                InfoChip("→ ${card.remotePort}/${card.protocol}")
            }
            card.error?.let {
                Spacer(Modifier.size(Spacing.ItemGap))
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
                shape = KubeShapes.Field,
                colors = softFieldColors(),
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

/** Quiet one-line hint for an empty section, in the same rounded card as the section's rows. */
@Composable
private fun PortForwardHintCard(text: String) {
    ListGroup(modifier = Modifier.padding(vertical = Spacing.RowVertical)) {
        SecondaryText(
            text = text,
            modifier = Modifier.padding(Spacing.CardPadding),
        )
    }
}
