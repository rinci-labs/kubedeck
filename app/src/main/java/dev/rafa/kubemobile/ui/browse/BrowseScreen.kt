package dev.rafa.kubemobile.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.k8s.ApiResource
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.ALL_NAMESPACES
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.GitOpsTab
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.SessionState
import dev.rafa.kubemobile.ui.components.BottomBarScreen
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.ConnectionGate
import dev.rafa.kubemobile.ui.components.ListDivider
import dev.rafa.kubemobile.ui.components.SearchField
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.ui.navigateToTop
import dev.rafa.kubemobile.ui.routeKey

/** The kinds a Kubernetes engineer reaches for first, resolved against the live catalog. */
private val COMMON_PLURALS = listOf(
    "pods",
    "deployments",
    "statefulsets",
    "daemonsets",
    "replicasets",
    "jobs",
    "cronjobs",
    "services",
    "endpoints",
    "ingresses",
    "configmaps",
    "secrets",
    "persistentvolumeclaims",
    "persistentvolumes",
    "storageclasses",
    "serviceaccounts",
    "roles",
    "rolebindings",
    "clusterroles",
    "clusterrolebindings",
    "nodes",
    "namespaces",
    "events",
)

/** Which half of a resource's scope the user wants to see. */
private enum class ScopeFilter { ALL, NAMESPACED, CLUSTER }

/** One API group as rendered by the collapsed section list. */
private data class GroupSection(
    val name: String,
    val label: String,
    val resources: List<ApiResource>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(app: AppViewModel, navController: NavController) {
    val sessionState by app.sessionState.collectAsStateWithLifecycle()
    val session = (sessionState as? SessionState.Ready)?.session
    val catalog = (sessionState as? SessionState.Ready)?.catalog

    // Filters are remembered across configuration changes; the recent list is derived from the
    // app-scoped memory so it reflects what was actually opened.
    var filter by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(ScopeFilter.ALL) }
    var groupFilter by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var expandedGroups by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var filterSheet by remember { mutableStateOf(false) }

    // A reload of the catalog (discovery refresh) must recompute the sections, so key on it; the
    // recent list also changes when another screen records an opened kind, which is why Browse
    // observes recentVersion rather than reading the map once.
    val catalogVersion = catalog?.resources?.size ?: 0
    val recentVersion by app.recentVersion.collectAsStateWithLifecycle()
    val recentKeys = remember(catalogVersion, recentVersion, session?.profile?.id) {
        app.recentResourceKeys(session?.profile?.id)
    }

    val namespaces by app.namespaces.collectAsStateWithLifecycle()
    var namespace by remember { mutableStateOf<String?>(null) }
    var namespaceSheet by remember { mutableStateOf(false) }
    val clusterId = session?.profile?.id
    LaunchedEffect(clusterId) { namespace = app.browseNamespace(clusterId) }

    BottomBarScreen(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.browse_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                actions = {
                    Text(
                        text = session?.profile?.name.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = Spacing.ScreenPadding),
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                session == null || catalog == null -> ConnectionGate(
                    state = sessionState,
                    onRetry = { app.connectInBackground(it) },
                    onGoToClusters = { navController.navigateToTop(Routes.CLUSTERS) },
                )

                else -> {
                    val query = filter.trim().lowercase()

                    // Resolve the curated and grouped views once, then filter each list in one pass.
                    val common = COMMON_PLURALS.mapNotNull { catalog.forResource(it) }
                    val byGroup = catalog.resources
                        .filter { !it.isCore }
                        .groupBy { it.group }
                    val groupSections = catalog.groups
                        .sortedBy { it.name }
                        .mapNotNull { group ->
                            val resources = byGroup[group.name]
                                .orEmpty()
                                .filter { it.version == group.preferredVersion }
                                .sortedBy { it.kind }
                            if (resources.isEmpty()) null
                            else GroupSection(group.name, group.name, resources)
                        }
                    // A grouped kind whose group document was missing still has to be reachable.
                    val loose = catalog.resources
                        .filterNot { it.isCore }
                        .filterNot { resource -> groupSections.any { it.resources.contains(resource) } }
                        .sortedBy { it.kind }
                    val core = catalog.resources.filter { it.isCore }.sortedBy { it.kind }

                    fun keep(resource: ApiResource): Boolean {
                        if (!matchesScope(resource, scope)) return false
                        if (groupFilter.isNotEmpty() && resource.group !in groupFilter) return false
                        return matches(resource, query)
                    }

                    val recent = recentKeys.mapNotNull { catalog.forResource(keyPlural(it), keyGroup(it)) }
                        .filter { keep(it) }
                    val visibleCommon = common.filter { keep(it) }
                    val visibleLoose = loose.filter { keep(it) }
                    val visibleCore = core.filter { keep(it) }
                    val visibleGroups = groupSections
                        .map { section -> section to section.resources.filter { keep(it) } }
                        .filter { it.second.isNotEmpty() }

                    val anyMatch = recent.isNotEmpty() || visibleCommon.isNotEmpty() ||
                        visibleLoose.isNotEmpty() || visibleCore.isNotEmpty() ||
                        visibleGroups.isNotEmpty()
                    val filtering = query.isNotEmpty() || scope != ScopeFilter.ALL || groupFilter.isNotEmpty()

                    Column {
                        BrowseNamespaceRow(
                            namespace = namespace,
                            namespaces = namespaces,
                            onOpen = { namespaceSheet = true },
                        )
                        SearchField(
                            value = filter,
                            onValueChange = { filter = it },
                            placeholder = stringResource(R.string.browse_filter_hint),
                            modifier = Modifier.padding(
                                horizontal = Spacing.ScreenPadding,
                                vertical = Spacing.ItemGap,
                            ),
                        )
                        BrowseFilterRow(
                            scope = scope,
                            onScope = { scope = it },
                            groupCount = groupFilter.size,
                            onOpenGroups = { filterSheet = true },
                        )

                        if (!anyMatch) {
                            EmptyState(
                                title = stringResource(R.string.state_no_matches),
                                body = stringResource(R.string.browse_no_matches_body),
                                actionLabel = stringResource(R.string.action_clear_filters),
                                onAction = {
                                    filter = ""
                                    scope = ScopeFilter.ALL
                                    groupFilter = emptySet()
                                },
                            )
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
                                // Only shown when nothing is filtered: the dashboard tiles are the
                                // landing content of this tab.
                                if (!filtering) {
                                    item(key = "dash") { DashboardGrid(app, navController) }
                                }

                                if (recent.isNotEmpty() && query.isEmpty()) {
                                    item(key = "hdr-recent") {
                                        CatalogHeader(stringResource(R.string.label_recent))
                                    }
                                    items(recent, key = { "recent-${it.qualified}" }) { resource ->
                                        CatalogRow(resource) { open(app, navController, resource) }
                                    }
                                }

                                if (visibleCommon.isNotEmpty()) {
                                    item(key = "hdr-common") {
                                        CatalogHeader(stringResource(R.string.label_common))
                                    }
                                    items(visibleCommon, key = { "common-${it.qualified}" }) { resource ->
                                        CatalogRow(resource) { open(app, navController, resource) }
                                    }
                                }

                                if (visibleGroups.isNotEmpty()) {
                                    item(key = "hdr-groups") {
                                        CatalogHeader(stringResource(R.string.label_api_groups))
                                    }
                                    visibleGroups.forEach { (section, resources) ->
                                        item(key = "group-${section.name}") {
                                            GroupRow(
                                                section = section,
                                                expanded = section.name in expandedGroups,
                                                onToggle = {
                                                    expandedGroups = if (section.name in expandedGroups) {
                                                        expandedGroups - section.name
                                                    } else {
                                                        expandedGroups + section.name
                                                    }
                                                },
                                            )
                                        }
                                        if (section.name in expandedGroups) {
                                            items(
                                                resources,
                                                key = { "g-${section.name}-${it.qualified}" },
                                            ) { resource ->
                                                CatalogRow(resource) { open(app, navController, resource) }
                                            }
                                        }
                                    }
                                }

                                if (visibleLoose.isNotEmpty()) {
                                    item(key = "hdr-crd") {
                                        CatalogHeader(stringResource(R.string.label_custom_resources))
                                    }
                                    items(visibleLoose, key = { "crd-${it.qualified}" }) { resource ->
                                        CatalogRow(resource) { open(app, navController, resource) }
                                    }
                                }

                                if (visibleCore.isNotEmpty()) {
                                    item(key = "hdr-core") {
                                        CatalogHeader(stringResource(R.string.label_core))
                                    }
                                    items(visibleCore, key = { "core-${it.qualified}" }) { resource ->
                                        CatalogRow(resource) { open(app, navController, resource) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (namespaceSheet) {
        val clusterId = session?.profile?.id
        BrowseNamespaceSheet(
            namespaces = namespaces,
            selected = namespace,
            clusterNamespace = app.activeProfile?.namespace,
            onSelect = {
                namespace = it
                app.rememberBrowseNamespace(clusterId, it)
                namespaceSheet = false
            },
            onDismiss = { namespaceSheet = false },
        )
    }

    if (filterSheet && catalog != null) {
        val groups = remember(catalog) { catalog.groups.map { it.name }.sorted() }
        GroupFilterSheet(
            groups = groups,
            selected = groupFilter,
            onToggle = { name ->
                groupFilter = if (name in groupFilter) groupFilter - name else groupFilter + name
            },
            onClear = { groupFilter = emptySet() },
            onDismiss = { filterSheet = false },
        )
    }
}

private fun open(app: AppViewModel, navController: NavController, resource: ApiResource) {
    // The cluster-scoped choice rides along, so the list opens on the namespace Browse is scoped to
    // without the user having to reselect it.
    if (resource.namespaced) {
        app.rememberNamespace(resource.routeKey(), app.session?.profile?.id, app.browseNamespace(app.session?.profile?.id))
    }
    app.rememberOpenedKind(app.session?.profile?.id, resource.routeKey())
    navController.navigate(Routes.resourceList(resource.routeKey()))
}

/** `resourceKey` is `group|version|plural|kind`; Recent stores it, the catalog is keyed by parts. */
private fun keyPlural(key: String): String = key.split("|").getOrNull(2).orEmpty()

private fun keyGroup(key: String): String = key.split("|").getOrNull(0).orEmpty()

private fun matches(resource: ApiResource, query: String): Boolean {
    if (query.isEmpty()) return true
    return resource.kind.lowercase().contains(query) ||
        resource.name.lowercase().contains(query) ||
        resource.group.lowercase().contains(query) ||
        resource.shortNames.any { it.lowercase().contains(query) } ||
        resource.categories.any { it.lowercase().contains(query) }
}

private fun matchesScope(resource: ApiResource, scope: ScopeFilter): Boolean = when (scope) {
    ScopeFilter.ALL -> true
    ScopeFilter.NAMESPACED -> resource.namespaced
    ScopeFilter.CLUSTER -> !resource.namespaced
}

/* -------------------------------------------------------------------------------------------- */
/* Header blocks                                                                                 */
/* -------------------------------------------------------------------------------------------- */

@Composable
private fun BrowseNamespaceRow(
    namespace: String?,
    namespaces: List<String>,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.ScreenPadding,
                end = Spacing.ScreenPadding,
                top = Spacing.ContentInset,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onOpen,
            label = {
                Text(
                    text = namespace ?: stringResource(R.string.label_all_namespaces),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        Spacer(Modifier.size(Spacing.ItemGap))
        SecondaryText(
            text = stringResource(R.string.browse_namespace_hint, namespaces.size),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BrowseFilterRow(
    scope: ScopeFilter,
    onScope: (ScopeFilter) -> Unit,
    groupCount: Int,
    onOpenGroups: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScopeFilter.entries.forEach { entry ->
            FilterChip(
                selected = scope == entry,
                onClick = { onScope(entry) },
                label = {
                    Text(
                        text = stringResource(
                            when (entry) {
                                ScopeFilter.ALL -> R.string.browse_scope_all
                                ScopeFilter.NAMESPACED -> R.string.browse_namespaced
                                ScopeFilter.CLUSTER -> R.string.browse_cluster_scoped
                            },
                        ),
                        maxLines = 1,
                    )
                },
            )
        }
        Spacer(Modifier.weight(1f))
        FilterChip(
            selected = groupCount > 0,
            onClick = onOpenGroups,
            label = {
                Text(
                    text = if (groupCount > 0) {
                        stringResource(R.string.browse_groups_count, groupCount)
                    } else {
                        stringResource(R.string.browse_groups)
                    },
                    maxLines = 1,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.FilterList,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

@Composable
private fun CatalogHeader(text: String) {
    Column {
        ListDivider()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start = Spacing.ScreenPadding,
                end = Spacing.ScreenPadding,
                top = Spacing.ContentInset,
                bottom = Spacing.ItemGap,
            ),
        )
    }
}

@Composable
private fun GroupRow(
    section: GroupSection,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(section.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        },
        supportingContent = {
            SecondaryText(stringResource(R.string.browse_group_kinds, section.resources.size), maxLines = 1)
        },
        trailingContent = {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onToggle),
    )
}

@Composable
private fun CatalogRow(resource: ApiResource, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(resource.kind, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        },
        supportingContent = {
            SecondaryText(resource.qualified, maxLines = 1)
        },
        trailingContent = {
            if (!resource.namespaced) {
                // A small badge, not a word: cluster-scoped is the exception, so it is marked and
                // everything else stays quiet.
                Text(
                    text = stringResource(R.string.browse_cluster_scoped),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun DashboardGrid(app: AppViewModel, navController: NavController) {
    val entries = listOf(
        DashEntry(Routes.gitops(GitOpsTab.HELM), R.string.browse_helm, R.string.browse_helm_body, Icons.Filled.Hub),
        DashEntry(Routes.gitops(GitOpsTab.FLUX), R.string.flux_title, R.string.browse_gitops_flux_body, Icons.Filled.AccountTree),
        DashEntry(Routes.gitops(GitOpsTab.ARGO), R.string.argo_title, R.string.browse_gitops_argo_body, Icons.Filled.Apps),
        DashEntry(Routes.EVENTS, R.string.browse_events, R.string.browse_events_body, Icons.Filled.Event),
        DashEntry(Routes.SETTINGS, R.string.browse_settings, R.string.browse_settings_body, Icons.Filled.Settings),
    )
    Column(
        Modifier.padding(
            horizontal = Spacing.ScreenPadding,
            vertical = Spacing.ContentInset,
        ),
    ) {
        Text(
            text = stringResource(R.string.label_dashboards),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = Spacing.ItemGap),
        )
        entries.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
            ) {
                row.forEach { entry ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { navController.navigateToTop(entry.route) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(Modifier.padding(Spacing.CardPadding)) {
                            Icon(
                                imageVector = entry.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(Spacing.ItemGap))
                            Text(
                                text = stringResource(entry.titleRes),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            SecondaryText(stringResource(entry.bodyRes), maxLines = 2)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.size(Spacing.ItemGap))
        }
    }
}

private data class DashEntry(
    val route: String,
    val titleRes: Int,
    val bodyRes: Int,
    val icon: ImageVector,
)

/* -------------------------------------------------------------------------------------------- */
/* Sheets                                                                                        */
/* -------------------------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseNamespaceSheet(
    namespaces: List<String>,
    selected: String?,
    clusterNamespace: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible = remember(query, namespaces) {
        if (query.isBlank()) namespaces else namespaces.filter { it.contains(query, true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.SheetPadding),
        ) {
            Text(
                text = stringResource(R.string.label_namespace),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = Spacing.SheetPadding,
                    vertical = Spacing.ItemGap,
                ),
            )
            SecondaryText(
                stringResource(R.string.browse_namespace_body),
                modifier = Modifier.padding(horizontal = Spacing.SheetPadding),
            )
            if (namespaces.size > 6) {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(
                        horizontal = Spacing.SheetPadding,
                        vertical = Spacing.ItemGap,
                    ),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item(key = "__all__") {
                    SheetOption(
                        label = stringResource(R.string.label_all_namespaces),
                        supporting = null,
                        selected = selected == null || selected == ALL_NAMESPACES,
                        onClick = { onSelect(ALL_NAMESPACES) },
                    )
                }
                items(visible, key = { it }) { ns ->
                    SheetOption(
                        label = ns,
                        supporting = if (ns == clusterNamespace) {
                            stringResource(R.string.clusters_namespace_title)
                        } else {
                            null
                        },
                        selected = selected == ns,
                        onClick = { onSelect(ns) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupFilterSheet(
    groups: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible = remember(query, groups) {
        if (query.isBlank()) groups else groups.filter { it.contains(query, true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.SheetPadding),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Spacing.SheetPadding,
                        vertical = Spacing.ItemGap,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.browse_groups),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear, enabled = selected.isNotEmpty()) {
                    Text(stringResource(R.string.action_clear))
                }
            }
            SearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.padding(
                    horizontal = Spacing.SheetPadding,
                    vertical = Spacing.ItemGap,
                ),
            )
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(visible, key = { it }) { group ->
                    ListItem(
                        headlineContent = { Text(group, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Checkbox(
                                checked = group in selected,
                                onCheckedChange = { onToggle(group) },
                            )
                        },
                        modifier = Modifier.clickable { onToggle(group) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetOption(
    label: String,
    supporting: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { text -> { SecondaryText(text, maxLines = 1) } },
        leadingContent = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
