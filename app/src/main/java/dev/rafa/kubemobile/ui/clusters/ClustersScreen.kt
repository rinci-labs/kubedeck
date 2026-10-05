package dev.rafa.kubemobile.ui.clusters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.config.AuthKind
import dev.rafa.kubemobile.config.ClusterProfile
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.SessionState
import dev.rafa.kubemobile.ui.UiError
import dev.rafa.kubemobile.ui.shortLabel
import dev.rafa.kubemobile.ui.components.ListDivider
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.BottomBarScreen
import dev.rafa.kubemobile.ui.components.InfoChip
import dev.rafa.kubemobile.ui.components.ToneDot
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.MenuAction
import dev.rafa.kubemobile.ui.components.OverflowMenu
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.components.SectionHeader
import dev.rafa.kubemobile.ui.components.TextFieldRow
import dev.rafa.kubemobile.ui.hostPort
import dev.rafa.kubemobile.ui.screenViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ClustersScreen(app: AppViewModel, navController: NavController) {
    val vm = screenViewModel(app) { a, handle -> ClustersViewModel(a, handle) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val connectState by vm.connect.collectAsStateWithLifecycle()
    val importState by vm.import.collectAsStateWithLifecycle()
    val sessionState by app.sessionState.collectAsStateWithLifecycle()

    val liveSession = sessionState as? SessionState.Ready
    val connectingSession = sessionState as? SessionState.Connecting
    val failedSession = sessionState as? SessionState.Failed

    var manualOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ClusterProfile?>(null) }
    var namespaceTarget by remember { mutableStateOf<ClusterProfile?>(null) }
    var actionSheetTarget by remember { mutableStateOf<ClusterProfile?>(null) }

    // A cluster tap lands on its own summary — identity, live counts, usage and what is wrong —
    // not the full catalog, which the bottom-bar Browse destination owns.
    val openSummary = { id: String -> navController.navigate(Routes.clusterSummary(id)) }
    // Two kubeconfigs for the same cluster routinely share a context name. Where that happens the
    // headline carries `host:port` so the rows are never ambiguous, even for profiles that were
    // stored before import-time disambiguation existed.
    val duplicatedNames = remember(profiles) {
        profiles.groupBy { it.name.lowercase() }
            .filterValues { it.size > 1 }
            .keys
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.rememberPendingImport(uri)
            importOpen = true
            vm.importUri(uri)
        }
    }

    BottomBarScreen(
        topBar = {
            TopAppBar(
                // Single line only: a stacked title would be clamped by the bar's fixed height and
                // stop scaling with the system font setting. The active cluster is already marked
                // by the dot and check on its own row.
                title = {
                    Text(
                        text = stringResource(R.string.clusters_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                actions = {
                    IconButton(onClick = { importOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.Upload,
                            contentDescription = stringResource(R.string.action_import_kubeconfig),
                        )
                    }
                    IconButton(onClick = { manualOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.clusters_add_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (profiles.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.clusters_empty_title),
                    body = stringResource(R.string.clusters_empty_body),
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    actionLabel = stringResource(R.string.action_import_kubeconfig),
                    onAction = { importOpen = true },
                    secondaryActionLabel = stringResource(R.string.clusters_add_title),
                    onSecondaryAction = { manualOpen = true },
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = ListBottomPadding),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        val error = (connectState as? ConnectState.Failed)
                            ?.takeIf { it.profileId == profile.id }?.error
                            ?: failedSession?.takeIf { it.profile?.id == profile.id }?.error
                        ClusterRow(
                            profile = profile,
                            nameSuffix = if (profile.name.lowercase() in duplicatedNames) {
                                profile.hostPort
                            } else {
                                null
                            },
                            isActive = liveSession?.session?.profile?.id == profile.id,
                            isConnecting = (connectState as? ConnectState.Connecting)?.profileId == profile.id ||
                                connectingSession?.profile?.id == profile.id,
                            error = error,
                            onConnect = {
                                if (liveSession?.session?.profile?.id == profile.id) {
                                    openSummary(profile.id)
                                } else {
                                    vm.connect(
                                        profile = profile,
                                        onConnected = {
                                            // Belt and braces: never leave this screen unless a
                                            // session for this profile actually exists, so a tap
                                            // can never look like a silent no-op.
                                            if (app.session?.profile?.id == profile.id) {
                                                openSummary(profile.id)
                                            }
                                        },
                                    )
                                }
                            },
                            onLongPress = { actionSheetTarget = profile },
                            onSetActive = { vm.setActive(profile) },
                            onEditNamespace = { namespaceTarget = profile },
                            onDuplicate = { vm.duplicate(profile) },
                            onDelete = { pendingDelete = profile },
                        )
                        ListDivider()
                    }
                    item {
                        TextButton(
                            onClick = { importOpen = true },
                            modifier = Modifier.padding(horizontal = Spacing.ItemGap, vertical = Spacing.ItemGap),
                        ) {
                            Icon(Icons.Filled.FileOpen, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.action_import_kubeconfig))
                        }
                    }
                }
            }
        }
    }

    if (importOpen) {
        ImportSheet(
            state = importState,
            onPaste = { text -> vm.importText(text, null) },
            onPickFile = {
                filePicker.launch(
                    arrayOf(
                        "text/yaml",
                        "application/x-yaml",
                        "application/yaml",
                        "text/plain",
                        "*/*",
                    ),
                )
            },
            onToggle = vm::toggleCandidate,
            onSelectAll = { vm.setAllCandidates(true) },
            onSelectNone = { vm.setAllCandidates(false) },
            onConfirm = {
                vm.confirmImport { count ->
                    app.notify(app.getApplication<android.app.Application>().getString(R.string.clusters_saved, count))
                    importOpen = false
                }
            },
            onDismiss = {
                importOpen = false
                vm.resetImport()
            },
        )
    }

    if (manualOpen) {
        ManualClusterDialog(
            onDismiss = { manualOpen = false },
            onSave = { profile ->
                vm.addManual(profile) {
                    manualOpen = false
                    app.notify(app.getApplication<android.app.Application>().getString(R.string.clusters_updated))
                }
            },
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.clusters_delete_title, profile.name)) },
            text = { Text(stringResource(R.string.clusters_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(profile)
                        pendingDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    namespaceTarget?.let { profile ->
        NamespaceDialog(
            profile = profile,
            onDismiss = { namespaceTarget = null },
            onSave = { namespace ->
                vm.updateNamespace(profile, namespace)
                namespaceTarget = null
            },
        )
    }

    actionSheetTarget?.let { profile ->
        ModalBottomSheet(
            onDismissRequest = { actionSheetTarget = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.padding(bottom = Spacing.SheetPadding)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.ItemGap),
                )
                SheetRow(stringResource(R.string.action_connect), Icons.Filled.Dns) {
                    actionSheetTarget = null
                    vm.connect(profile) { openSummary(profile.id) }
                }
                SheetRow(stringResource(R.string.action_set_active), Icons.Filled.Star) {
                    actionSheetTarget = null
                    vm.setActive(profile)
                }
                SheetRow(
                    label = stringResource(R.string.action_edit_namespace),
                    icon = Icons.Filled.Edit,
                    subtitle = profile.displayNamespace,
                ) {
                    actionSheetTarget = null
                    namespaceTarget = profile
                }
                SheetRow(stringResource(R.string.action_duplicate), Icons.Filled.ContentCopy) {
                    actionSheetTarget = null
                    vm.duplicate(profile)
                }
                SheetRow(
                    label = stringResource(R.string.action_delete),
                    icon = Icons.Filled.Delete,
                    destructive = true,
                    onClick = {
                        actionSheetTarget = null
                        pendingDelete = profile
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = label,
                color = if (destructive) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
            )
        },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/* -------------------------------------------------------------------------------------------- */
/* Rows                                                                                          */
/* -------------------------------------------------------------------------------------------- */

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ClusterRow(
    profile: ClusterProfile,
    nameSuffix: String?,
    isActive: Boolean,
    isConnecting: Boolean,
    error: UiError?,
    onConnect: () -> Unit,
    onLongPress: () -> Unit,
    onSetActive: () -> Unit,
    onEditNamespace: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = !isConnecting, onClick = onConnect, onLongClick = onLongPress)
            .padding(end = Spacing.TightGap),
    ) {
        ListItem(
            // Two lines only: name, then `host:port · namespace`. Everything else is a trailing
            // fact, so the list reads as a scannable column of clusters instead of a chip wall.
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (nameSuffix != null) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = nameSuffix,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            },
            supportingContent = {
                Column {
                    SecondaryText(text = profile.endpointLabel(), maxLines = 1)
                    Spacer(Modifier.size(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The connection state is spelled out, not implied by a dot colour, so
                        // "did my tap do anything?" is always answerable from the row itself.
                        InfoChip(
                            label = when {
                                isConnecting -> stringResource(R.string.clusters_state_connecting)
                                isActive -> stringResource(R.string.clusters_state_connected)
                                error != null -> stringResource(R.string.clusters_state_failed)
                                else -> stringResource(R.string.clusters_state_idle)
                            },
                            tone = when {
                                isConnecting -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.PROGRESS
                                isActive -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.OK
                                error != null -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.BAD
                                else -> dev.rafa.kubemobile.ops.ResourceHealth.Tone.NEUTRAL
                            },
                        )
                        InfoChip(profile.authKind.shortLabel)
                        if (profile.insecureSkipTlsVerify) {
                            InfoChip(
                                label = stringResource(R.string.settings_tls_insecure),
                                tone = dev.rafa.kubemobile.ops.ResourceHealth.Tone.WARN,
                            )
                        }
                        if (profile.execCommand != null || profile.authProvider != null) {
                            InfoChip(
                                label = stringResource(R.string.label_warnings),
                                tone = dev.rafa.kubemobile.ops.ResourceHealth.Tone.WARN,
                            )
                        }
                    }
                }
            },
            leadingContent = {
                when {
                    isConnecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    isActive -> ToneDot(
                        tone = dev.rafa.kubemobile.ops.ResourceHealth.Tone.OK,
                        size = 10.dp,
                    )

                    error != null -> ToneDot(
                        tone = dev.rafa.kubemobile.ops.ResourceHealth.Tone.BAD,
                        size = 10.dp,
                    )

                    else -> ToneDot(
                        tone = dev.rafa.kubemobile.ops.ResourceHealth.Tone.NEUTRAL,
                        size = 10.dp,
                    )
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OverflowMenu(
                        actions = listOf(
                            MenuAction(stringResource(R.string.action_connect), onConnect),
                            MenuAction(stringResource(R.string.action_set_active), onSetActive),
                            MenuAction(stringResource(R.string.action_edit_namespace), onEditNamespace),
                            MenuAction(stringResource(R.string.action_duplicate), onDuplicate),
                            MenuAction(
                                label = stringResource(R.string.action_delete),
                                onClick = onDelete,
                                destructive = true,
                            ),
                        ),
                    )
                }
            },
        )
        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.RowVertical),
            ) {
                Row(
                    Modifier.padding(Spacing.CardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(error.titleRes),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.size(Spacing.TightGap))
                        Text(
                            text = error.message,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onConnect) {
                        Text(stringResource(R.string.action_retry_connect))
                    }
                }
            }
        }
    }
}

/**
 * `host:port · namespace` — the two facts that actually distinguish one cluster from another.
 * The scheme is dropped because every kubeconfig endpoint is https in practice, and the port is
 * kept because 6443 vs 443 (or a NodePort) is often the difference between two entries.
 */
private fun ClusterProfile.endpointLabel(): String = "$hostPort · $displayNamespace"


/* -------------------------------------------------------------------------------------------- */
/* Import                                                                                        */
/* -------------------------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(
    state: ImportState,
    onPaste: (String) -> Unit,
    onPickFile: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.SheetPadding)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.clusters_import_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.size(4.dp))
            SecondaryText(stringResource(R.string.clusters_import_body))
            Spacer(Modifier.size(12.dp))
            OutlinedButton(shape = RectangleShape, onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.clusters_import_file))
            }
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.clusters_import_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            )
            Spacer(Modifier.size(8.dp))
            Button(
                shape = RectangleShape,
                onClick = { onPaste(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.clusters_import_parse))
            }

            when (state) {
                ImportState.Parsing -> {
                    Spacer(Modifier.size(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.state_loading))
                    }
                }

                is ImportState.Error -> {
                    Spacer(Modifier.size(16.dp))
                    ErrorState(error = state.error)
                }

                is ImportState.Ready -> {
                    Spacer(Modifier.size(16.dp))
                    if (state.candidates.isEmpty()) {
                        Text(
                            text = stringResource(R.string.clusters_import_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.size(8.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.clusters_import_select),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.action_select_all)) }
                            TextButton(onClick = onSelectNone) { Text(stringResource(R.string.action_none)) }
                        }
                        SecondaryText(stringResource(R.string.clusters_select_contexts))
                        state.candidates.forEach { candidate ->
                            ListItem(
                                headlineContent = { Text(candidate.profile.name) },
                                supportingContent = {
                                    SecondaryText(
                                        listOf(
                                            candidate.profile.server,
                                            candidate.profile.displayNamespace,
                                            candidate.profile.authKind.label,
                                        ).joinToString(" · "),
                                    )
                                },
                                leadingContent = {
                                    Checkbox(
                                        checked = candidate.selected,
                                        onCheckedChange = { onToggle(candidate.profile.id) },
                                    )
                                },
                                modifier = Modifier.clickable { onToggle(candidate.profile.id) },
                            )
                        }
                    }

                    if (state.warnings.isNotEmpty()) {
                        Spacer(Modifier.size(12.dp))
                        SectionHeader(stringResource(R.string.label_warnings))
                        state.warnings.forEach { warning ->
                            Row(
                                Modifier.padding(horizontal = Spacing.SheetPadding, vertical = Spacing.RowVertical),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text("• ", color = MaterialTheme.colorScheme.error)
                                Text(
                                    text = warning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (state.candidates.isNotEmpty()) {
                        Spacer(Modifier.size(16.dp))
                        val selectedCount = state.candidates.count { it.selected }
                        Button(
                            shape = RectangleShape,
                            onClick = onConfirm,
                            enabled = selectedCount > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.clusters_import_confirm, selectedCount))
                        }
                    }
                }

                ImportState.Idle -> Unit
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Manual entry                                                                                  */
/* -------------------------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualClusterDialog(
    onDismiss: () -> Unit,
    onSave: (ClusterProfile) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var namespace by remember { mutableStateOf("default") }
    var authKind by remember { mutableStateOf(AuthKind.TOKEN) }
    var token by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var cert by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var ca by remember { mutableStateOf("") }
    var insecure by remember { mutableStateOf(false) }

    val valid = name.isNotBlank() && server.isNotBlank()

    fun build(): ClusterProfile = ClusterProfile(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        server = server.trim(),
        namespace = namespace.trim().takeIf { it.isNotBlank() },
        token = token.trim().takeIf { it.isNotBlank() },
        username = username.trim().takeIf { it.isNotBlank() },
        password = password.takeIf { it.isNotBlank() },
        clientCertPem = cert.trim().takeIf { it.isNotBlank() },
        clientKeyPem = key.trim().takeIf { it.isNotBlank() },
        caCertPem = ca.trim().takeIf { it.isNotBlank() },
        insecureSkipTlsVerify = insecure,
        source = ClusterProfile.SOURCE_MANUAL,
        createdAt = System.currentTimeMillis(),
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.clusters_add_title)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_cancel),
                                )
                            }
                        },
                        actions = {
                            TextButton(onClick = { onSave(build()) }, enabled = valid) {
                                Text(stringResource(R.string.action_save))
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.SheetPadding)
                        .padding(bottom = 48.dp),
                ) {
                    Spacer(Modifier.size(8.dp))
                    TextFieldRow(name, { name = it }, stringResource(R.string.clusters_field_name))
                    Spacer(Modifier.size(8.dp))
                    TextFieldRow(
                        value = server,
                        onValueChange = { server = it },
                        label = stringResource(R.string.clusters_field_server),
                        keyboardType = KeyboardType.Uri,
                        placeholder = "https://10.0.0.1:6443",
                    )
                    Spacer(Modifier.size(8.dp))
                    TextFieldRow(
                        value = namespace,
                        onValueChange = { namespace = it },
                        label = stringResource(R.string.clusters_field_namespace),
                    )

                    SectionHeader(stringResource(R.string.clusters_section_auth))
                    Column(Modifier.padding(horizontal = Spacing.ItemGap)) {
                        AuthKind.entries.forEach { kind ->
                            ListItem(
                                headlineContent = { Text(authKindLabel(kind)) },
                                leadingContent = {
                                    RadioButton(selected = authKind == kind, onClick = { authKind = kind })
                                },
                                modifier = Modifier.clickable { authKind = kind },
                            )
                        }
                    }

                    Spacer(Modifier.size(8.dp))
                    when (authKind) {
                        AuthKind.TOKEN -> OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text(stringResource(R.string.clusters_field_token)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        )

                        AuthKind.BASIC -> {
                            TextFieldRow(username, { username = it }, stringResource(R.string.clusters_field_username))
                            Spacer(Modifier.size(8.dp))
                            TextFieldRow(
                                value = password,
                                onValueChange = { password = it },
                                label = stringResource(R.string.clusters_field_password),
                            )
                        }

                        AuthKind.CLIENT_CERT -> {
                            PemField(cert, { cert = it }, stringResource(R.string.clusters_field_cert))
                            Spacer(Modifier.size(8.dp))
                            PemField(key, { key = it }, stringResource(R.string.clusters_field_key))
                        }

                        AuthKind.NONE -> Unit
                    }

                    Spacer(Modifier.size(12.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.clusters_insecure)) },
                        trailingContent = {
                            Switch(checked = insecure, onCheckedChange = { insecure = it })
                        },
                    )

                    Spacer(Modifier.size(8.dp))
                    PemField(ca, { ca = it }, stringResource(R.string.clusters_field_ca))

                    if (!valid) {
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.clusters_invalid),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.size(24.dp))
                    FilledTonalButton(shape = RectangleShape, 
                        onClick = { onSave(build()) },
                        enabled = valid,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.clusters_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun authKindLabel(kind: AuthKind): String = when (kind) {
    AuthKind.CLIENT_CERT -> stringResource(R.string.clusters_auth_client_cert)
    AuthKind.TOKEN -> stringResource(R.string.clusters_auth_token)
    AuthKind.BASIC -> stringResource(R.string.clusters_auth_basic)
    AuthKind.NONE -> kind.label
}

@Composable
private fun PemField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
    )
}

/* -------------------------------------------------------------------------------------------- */
/* Namespace edit                                                                                */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun NamespaceDialog(
    profile: ClusterProfile,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var value by remember { mutableStateOf(profile.namespace.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clusters_namespace_title)) },
        text = {
            Column {
                Text(stringResource(R.string.clusters_namespace_body, profile.name))
                Spacer(Modifier.size(12.dp))
                TextFieldRow(value, { value = it }, stringResource(R.string.label_namespace))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.trim().takeIf { it.isNotBlank() }) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
