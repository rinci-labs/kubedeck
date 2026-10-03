package dev.rafa.kubemobile.ui.helm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.ops.HelmRelease
import dev.rafa.kubemobile.ops.ResourceHealth
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.components.ListDivider
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.HealthChip
import dev.rafa.kubemobile.ui.components.KeyValueRow
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.MenuAction
import dev.rafa.kubemobile.ui.components.MonoText
import dev.rafa.kubemobile.ui.components.OverflowMenu
import dev.rafa.kubemobile.ui.components.SearchField
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.components.SectionCard
import dev.rafa.kubemobile.ui.components.SectionHeader
import dev.rafa.kubemobile.ui.components.TabStrip
import dev.rafa.kubemobile.ui.copyToClipboard
import dev.rafa.kubemobile.ui.fullTimestamp
import dev.rafa.kubemobile.ui.humanAge
import dev.rafa.kubemobile.ui.screenViewModel
import dev.rafa.kubemobile.ui.shareText

private enum class HelmTab { OVERVIEW, NOTES, MANIFEST, HISTORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelmDetailScreen(
    app: AppViewModel,
    navController: NavController,
    namespace: String,
    name: String,
) {
    val vm = screenViewModel(app) { a, handle -> HelmDetailViewModel(a, handle) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(HelmTab.OVERVIEW) }
    var uninstallOpen by remember { mutableStateOf(false) }
    var revisionPicker by remember { mutableStateOf(false) }
    var manifestQuery by remember { mutableStateOf("") }

    LaunchedEffect(namespace, name) { vm.start(namespace, name, null) }

    val release = state.release

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = listOfNotNull(
                                namespace.takeIf { it.isNotBlank() },
                                release?.let { "${it.chartName}-${it.chartVersion}".trim('-') },
                                release?.let { stringResource(R.string.label_release, it.revision) },
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
                    IconButton(
                        onClick = { revisionPicker = true },
                        enabled = state.history.size > 1,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = stringResource(R.string.label_history),
                        )
                    }
                    OverflowMenu(
                        actions = buildList {
                            add(
                                MenuAction(
                                    label = stringResource(R.string.detail_copy_yaml),
                                    onClick = { release?.let { context.copyToClipboard("manifest", it.manifest) } },
                                ),
                            )
                            add(
                                MenuAction(
                                    label = stringResource(R.string.action_share),
                                    onClick = {
                                        release?.let { shareText(context, "${it.name}-manifest.yaml", it.manifest) }
                                    },
                                ),
                            )
                            add(
                                MenuAction(
                                    label = stringResource(R.string.action_uninstall),
                                    onClick = { uninstallOpen = true },
                                    destructive = true,
                                    enabled = release != null,
                                ),
                            )
                        },
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && release == null -> LoadingState(label = stringResource(R.string.state_loading))

                state.error != null && release == null -> ErrorState(
                    error = state.error!!,
                    onRetry = { vm.load() },
                )

                release == null -> EmptyState(
                    title = stringResource(R.string.helm_not_found),
                    body = stringResource(R.string.helm_empty_body),
                )

                else -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.CardPadding, vertical = Spacing.ItemGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HealthChip(release.health())
                        Spacer(Modifier.size(8.dp))
                        SecondaryText(
                            listOfNotNull(
                                release.appVersion.takeIf { it.isNotBlank() }?.let { "app $it" },
                                fullTimestamp(release.updated),
                                release.description.takeIf { it.isNotBlank() }?.take(60),
                            ).joinToString(" · "),
                            maxLines = 2,
                        )
                    }
                    TabStrip(
                        tabs = listOf(
                            stringResource(R.string.label_overview),
                            stringResource(R.string.label_notes),
                            stringResource(R.string.label_manifest),
                            stringResource(R.string.label_history),
                        ),
                        selectedIndex = tab.ordinal,
                        onSelect = { tab = HelmTab.entries[it] },
                        modifier = Modifier.padding(horizontal = Spacing.ScreenPadding),
                    )
                    when (tab) {
                        HelmTab.OVERVIEW -> OverviewTab(vm = vm, release = release)

                        HelmTab.NOTES -> YamlBlock(
                            text = release.notes.ifBlank { stringResource(R.string.helm_notes_empty) },
                            horizontal = false,
                            scrollVertically = true,
                        )

                        HelmTab.MANIFEST -> ManifestTab(
                            vm = vm,
                            release = release,
                            query = manifestQuery,
                            onQuery = { manifestQuery = it },
                        )

                        HelmTab.HISTORY -> HistoryTab(
                            history = state.history,
                            onPick = { revision ->
                                vm.selectRevision(revision.revision)
                                tab = HelmTab.OVERVIEW
                            },
                        )
                    }
                }
            }
        }
    }

    if (state.uninstalled) {
        UninstallResultDialog(problems = state.problems, onDismiss = { })
    }

    if (uninstallOpen) {
        UninstallDialog(
            releaseName = name,
            onDismiss = { uninstallOpen = false },
            onConfirm = { deleteResources ->
                uninstallOpen = false
                vm.uninstall(deleteResources) { problems ->
                    app.notify(
                        if (problems.isEmpty()) {
                            app.getApplication<android.app.Application>()
                                .getString(R.string.helm_uninstalled, name)
                        } else {
                            app.getApplication<android.app.Application>()
                                .getString(R.string.helm_uninstall_problems, problems.size)
                        },
                    )
                }
            },
        )
    }

    if (revisionPicker) {
        HistoryPickerDialog(
            history = state.history,
            onDismiss = { revisionPicker = false },
            onPick = { revision ->
                revisionPicker = false
                vm.selectRevision(revision.revision)
            },
        )
    }
}

private fun HelmRelease.health(): ResourceHealth {
    val tone = when (status.lowercase()) {
        "deployed" -> ResourceHealth.Tone.OK
        "failed" -> ResourceHealth.Tone.BAD
        "pending-install", "pending-upgrade", "pending-rollback", "uninstalling" ->
            ResourceHealth.Tone.PROGRESS

        else -> ResourceHealth.Tone.NEUTRAL
    }
    return ResourceHealth(status.ifBlank { "unknown" }, null, tone)
}

/* -------------------------------------------------------------------------------------------- */

@Composable
private fun OverviewTab(vm: HelmDetailViewModel, release: HelmRelease) {
    val context = LocalContext.current
    val diff = remember(release.revision, release.secretName) { vm.valueDiff() }
    val userValues = remember(release.revision) { vm.userValues() }
    val chartValues = remember(release.revision) { vm.chartValues() }

    LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
        item {
            SectionCard(title = stringResource(R.string.label_chart)) {
                KeyValueRow(stringResource(R.string.label_chart), release.chartName, copyable = true)
                KeyValueRow(stringResource(R.string.label_version), release.chartVersion, copyable = true)
                KeyValueRow(stringResource(R.string.label_app_version), release.appVersion, copyable = true)
                KeyValueRow(stringResource(R.string.label_revision), release.revision.toString())
                KeyValueRow(stringResource(R.string.label_namespace), release.namespace)
                KeyValueRow(
                    stringResource(R.string.label_updated),
                    fullTimestamp(release.updated) ?: release.updated,
                )
                KeyValueRow(
                    stringResource(R.string.label_source),
                    if (release.isV2) "Helm 2 config map" else "Helm 3 secret",
                )
                KeyValueRow(
                    stringResource(R.string.label_name),
                    release.secretName,
                    copyable = true,
                    monospace = true,
                )
            }
        }
        if (diff.isNotEmpty()) {
            item {
                SectionHeader(
                    stringResource(R.string.label_values),
                    modifier = Modifier.clickable {
                        context.copyToClipboard(
                            "values",
                            diff.joinToString("\n") { (key, defaults, user) ->
                                "$key: default=${defaults ?: "-"} user=${user ?: "-"}"
                            },
                        )
                    },
                )
            }
            items(diff) { (key, defaults, user) ->
                val overridden = user != null && user != defaults
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardPadding, vertical = Spacing.RowVertical),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        MonoText(key, fontSize = 11.sp)
                        SecondaryText("default ${defaults ?: "—"}", maxLines = 1)
                    }
                    MonoText(
                        text = user ?: "—",
                        fontSize = 11.sp,
                        color = if (overridden) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                ListDivider()
            }
        }
        item {
            SectionHeader(stringResource(R.string.label_user_values))
            YamlBlock(
                text = userValues.ifBlank { stringResource(R.string.helm_values_empty) },
                horizontal = true,
            )
        }
        if (chartValues.isNotBlank()) {
            item {
                SectionHeader(stringResource(R.string.label_values))
                YamlBlock(text = chartValues, horizontal = true)
            }
        }
    }
}

@Composable
private fun ManifestTab(
    vm: HelmDetailViewModel,
    release: HelmRelease,
    query: String,
    onQuery: (String) -> Unit,
) {
    val entries = remember(release.revision) { vm.entries() }
    val filtered = remember(query, entries) {
        if (query.isBlank()) {
            entries
        } else {
            entries.filter {
                it.name.contains(query, true) || it.kind.contains(query, true)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        SearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = stringResource(R.string.label_search_manifest),
            modifier = Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
        )
        if (filtered.isNotEmpty()) {
            SectionHeader(stringResource(R.string.helm_entries))
            LazyColumn(Modifier.weight(1f)) {
                items(filtered) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            SecondaryText(
                                listOfNotNull(entry.kind, entry.namespace, entry.apiVersion).joinToString(" · "),
                            )
                        },
                    )
                }
            }
        }
        if (release.manifest.isBlank()) {
            EmptyState(
                title = stringResource(R.string.label_manifest),
                body = stringResource(R.string.helm_manifest_empty),
            )
        } else {
            Text(
                text = stringResource(R.string.label_manifest),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.ScreenPadding, top = Spacing.ItemGap, bottom = Spacing.RowVertical),
            )
            Box(Modifier.weight(1f)) {
                YamlBlock(text = release.manifest, horizontal = true, scrollVertically = true)
            }
        }
    }
}

@Composable
private fun HistoryTab(
    history: List<HelmRelease>,
    onPick: (HelmRelease) -> Unit,
) {
    if (history.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.label_history),
            body = stringResource(R.string.helm_empty_body),
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
        items(history, key = { it.secretName }) { revision ->
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.label_history_revision, revision.revision))
                },
                supportingContent = {
                    Column {
                        SecondaryText(
                            listOfNotNull(
                                revision.status.takeIf { it.isNotBlank() },
                                revision.chartVersion.takeIf { it.isNotBlank() }?.let { "chart $it" },
                                humanAge(revision.updated),
                            ).joinToString(" · "),
                        )
                        if (revision.description.isNotBlank()) {
                            SecondaryText(revision.description, maxLines = 1)
                        }
                    }
                },
                leadingContent = {
                    Icon(Icons.Filled.History, contentDescription = null)
                },
                modifier = Modifier.clickable { onPick(revision) },
            )
            ListDivider()
        }
    }
}

/**
 * A monospace YAML/text panel.
 *
 * [scrollVertically] must stay false inside a `LazyColumn` item: the item's height constraint is
 * infinite, and nesting `verticalScroll` under it throws
 * `IllegalStateException: Vertically scrollable component was measured with an infinity maximum
 * height constraints`. Only pass true when the parent imposes a bounded height (for example a
 * `Box(Modifier.weight(1f))`).
 */
@Composable
private fun YamlBlock(
    text: String,
    horizontal: Boolean,
    scrollVertically: Boolean = false,
) {
    // Full-bleed surface with the screen gutter applied once, inside: the code text then lines up
    // with the sibling section text at 16 dp instead of sitting at a nested 8 dp.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.RowVertical),
    ) {
        val vertical = rememberScrollState()
        val horizontalState = rememberScrollState()
        SelectionContainer {
            Box(
                Modifier
                    .then(if (horizontal) Modifier.horizontalScroll(horizontalState) else Modifier)
                    .then(if (scrollVertically) Modifier.verticalScroll(vertical) else Modifier)
                    .padding(Spacing.CardPadding),
            ) {
                MonoText(text, fontSize = 11.sp)
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Dialogs                                                                                       */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun UninstallDialog(
    releaseName: String,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var deleteResources by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.helm_uninstall_title, releaseName)) },
        text = {
            Column {
                Text(stringResource(R.string.helm_uninstall_warning))
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.helm_uninstall_delete_resources),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = deleteResources, onCheckedChange = { deleteResources = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(deleteResources) },
            ) {
                Text(
                    text = stringResource(R.string.action_uninstall),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun UninstallResultDialog(problems: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (problems.isEmpty()) {
                    stringResource(R.string.action_uninstall)
                } else {
                    stringResource(R.string.helm_uninstall_problems, problems.size)
                },
            )
        },
        text = {
            if (problems.isEmpty()) {
                Text(stringResource(R.string.action_done))
            } else {
                LazyColumn(Modifier.padding(top = Spacing.RowVertical)) {
                    items(problems) { problem ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("• ", color = MaterialTheme.colorScheme.error)
                            Text(problem, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
private fun HistoryPickerDialog(
    history: List<HelmRelease>,
    onDismiss: () -> Unit,
    onPick: (HelmRelease) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.label_history)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(history, key = { it.secretName }) { revision ->
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.label_history_revision, revision.revision))
                        },
                        supportingContent = { SecondaryText(revision.status) },
                        modifier = Modifier.clickable { onPick(revision) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
