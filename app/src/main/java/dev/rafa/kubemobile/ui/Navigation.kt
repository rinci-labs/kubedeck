package dev.rafa.kubemobile.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.ui.components.TabStrip
import dev.rafa.kubemobile.ui.argo.ArgoScreen
import dev.rafa.kubemobile.ui.browse.BrowseScreen
import dev.rafa.kubemobile.ui.browse.ResourceListScreen
import dev.rafa.kubemobile.ui.clusters.ClusterSummaryScreen
import dev.rafa.kubemobile.ui.clusters.ClustersScreen
import dev.rafa.kubemobile.ui.detail.LogsScreen
import dev.rafa.kubemobile.ui.detail.ObjectDetailScreen
import dev.rafa.kubemobile.ui.detail.PortForwardScreen
import dev.rafa.kubemobile.ui.detail.TerminalScreen
import dev.rafa.kubemobile.ui.events.EventsScreen
import dev.rafa.kubemobile.ui.flux.FluxScreen
import dev.rafa.kubemobile.ui.helm.HelmDetailScreen
import dev.rafa.kubemobile.ui.helm.HelmScreen
import dev.rafa.kubemobile.ui.settings.SettingsScreen

/* -------------------------------------------------------------------------------------------- */
/* Routes                                                                                        */
/* -------------------------------------------------------------------------------------------- */

object Routes {
    const val CLUSTERS = "clusters"
    const val CATALOG = "catalog"
    const val CLUSTER_SUMMARY = "cluster/{profileId}"
    const val RESOURCE_LIST = "browse/{resourceKey}"
    const val OBJECT = "object/{resourceKey}/{namespace}/{name}"
    const val LOGS = "logs/{namespace}/{pod}"
    const val TERMINAL = "terminal/{namespace}/{pod}"
    const val PORT_FORWARD = "forward/{namespace}/{pod}"
    const val GITOPS_TAB_ARG = "tab"
    const val HELM_RELEASE = "helm/{namespace}/{name}"
    const val EVENTS = "events"
    const val GITOPS = "gitops?tab={tab}"
    const val SETTINGS = "settings"

    fun resourceList(key: String): String = "browse/${Uri.encode(key)}"

    fun clusterSummary(profileId: String): String = "cluster/${Uri.encode(profileId)}"

    fun objectDetail(key: String, namespace: String, name: String): String =
        "object/${Uri.encode(key)}/${Uri.encode(namespace)}/${Uri.encode(name)}"

    fun logs(namespace: String, pod: String, container: String? = null, kind: String? = null): String {
        val base = "logs/${Uri.encode(namespace)}/${Uri.encode(pod)}"
        val query = buildList {
            container?.let { add("container=${Uri.encode(it)}") }
            kind?.let { add("kind=${Uri.encode(it)}") }
        }
        return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
    }

    fun terminal(namespace: String, pod: String, container: String? = null): String {
        val base = "terminal/${Uri.encode(namespace)}/${Uri.encode(pod)}"
        return if (container == null) base else "$base?container=${Uri.encode(container)}"
    }

    fun portForward(namespace: String, pod: String): String = "forward/${Uri.encode(namespace)}/${Uri.encode(pod)}"

    fun helmRelease(namespace: String, name: String): String = "helm/${Uri.encode(namespace)}/${Uri.encode(name)}"

    /** The GitOps destination, optionally opened on a specific tab. */
    fun gitops(tab: GitOpsTab = GitOpsTab.HELM): String = "gitops?tab=${tab.slug}"
}

/**
 * The five bottom-bar destinations. Each owns a route: tapping a destination navigates to its
 * concrete route rather than a pattern, since navigating with an unfilled `{arg}` pattern throws.
 *
 * Every entry carries both halves of the Material icon pair — outlined while idle, filled while
 * selected. The shape change is the fastest read of "where am I", and it does not depend on colour
 * contrast alone.
 */
private enum class TopDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Clusters(Routes.CLUSTERS, R.string.nav_clusters, Icons.Outlined.Dns, Icons.Filled.Dns),
    Browse(
        Routes.CATALOG,
        R.string.nav_browse,
        Icons.AutoMirrored.Outlined.List,
        Icons.AutoMirrored.Filled.List,
    ),
    GitOps(Routes.gitops(), R.string.nav_gitops, Icons.Outlined.AccountTree, Icons.Filled.AccountTree),
    Events(Routes.EVENTS, R.string.nav_events, Icons.Outlined.Event, Icons.Filled.Event),
    Settings(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/**
 * Routes that keep the bottom bar. Push-style screens (object detail, logs, terminal, port forward)
 * are deliberately absent: they are full-window and own all four edges. The cluster summary, the
 * resource list and the Helm release are each *reached from* a bottom-bar destination and their tab
 * stays lit, so they keep the bar.
 */
private val BOTTOM_BAR_ROUTES = setOf(
    Routes.CLUSTERS,
    Routes.CLUSTER_SUMMARY,
    Routes.CATALOG,
    Routes.RESOURCE_LIST,
    Routes.GITOPS,
    Routes.HELM_RELEASE,
    Routes.EVENTS,
    Routes.SETTINGS,
)

/** The destination whose tab lights up for a given route, or null for a bar-less pushed screen. */
private fun destinationFor(route: String?): TopDestination? = when (route) {
    Routes.CLUSTERS, Routes.CLUSTER_SUMMARY -> TopDestination.Clusters
    Routes.CATALOG, Routes.RESOURCE_LIST -> TopDestination.Browse
    Routes.GITOPS, Routes.HELM_RELEASE -> TopDestination.GitOps
    Routes.EVENTS -> TopDestination.Events
    Routes.SETTINGS -> TopDestination.Settings
    else -> null
}

/** The three dashboards behind the GitOps destination. */
enum class GitOpsTab(val slug: String, val labelRes: Int) {
    HELM("helm", R.string.browse_helm),
    FLUX("flux", R.string.flux_title),
    ARGO("argo", R.string.argo_title);

    companion object {
        fun fromSlug(slug: String?): GitOpsTab = entries.firstOrNull { it.slug == slug } ?: HELM
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Root                                                                                          */
/* -------------------------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KubeApp(app: AppViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(app) {
        app.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in BOTTOM_BAR_ROUTES
    val selectedTab = destinationFor(currentRoute)

    Scaffold(
        // The root owns no system-bar insets: every screen's own TopAppBar consumes the status bar,
        // and NavigationBar consumes the gesture inset itself. Applying them here as well is what
        // pushed every header down by a second status bar. The root reserves only the measured
        // height of its own bottom bar, and forwards that alone.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // A plain conditional, not AnimatedVisibility: an animating bar makes the Scaffold
            // measure a moving height, which both animates the content padding and leaves the
            // bar's labels inside the gesture area on the first frames.
            if (showBottomBar) {
                Column {
                    // Rinci rule: a hairline separates the bar from content, the selected item is
                    // the mint signal, and the Material pill indicator is removed so the bar reads
                    // as a flat ruled strip rather than five floating lozenges.
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        TopDestination.entries.forEach { destination ->
                            val selected = selectedTab == destination
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTop(destination.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                                        contentDescription = null,
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(destination.labelRes),
                                        maxLines = 2,
                                        softWrap = true,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // Only the bottom bar height is forwarded. Pushed screens are full-window, so they also
        // get the default insets from their own Scaffold; bottom-bar destinations add
        // BottomBarScreenInsets to cover the status bar once.
        val bottomPad = if (showBottomBar) padding.calculateBottomPadding() else 0.dp
        NavHost(
            navController = navController,
            startDestination = Routes.CLUSTERS,
            modifier = Modifier.padding(bottom = bottomPad),
        ) {
            composable(Routes.CLUSTERS) {
                ClustersScreen(app, navController)
            }

            composable(Routes.CATALOG) {
                BrowseScreen(app, navController)
            }

            composable(
                route = Routes.CLUSTER_SUMMARY,
                arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
            ) { entry ->
                ClusterSummaryScreen(
                    app = app,
                    navController = navController,
                    profileId = entry.stringArg("profileId"),
                )
            }

            composable(
                route = Routes.RESOURCE_LIST,
                arguments = listOf(navArgument("resourceKey") { type = NavType.StringType }),
            ) { entry ->
                ResourceListScreen(
                    app = app,
                    navController = navController,
                    resourceKey = entry.stringArg("resourceKey"),
                )
            }

            composable(
                route = Routes.OBJECT,
                arguments = listOf(
                    navArgument("resourceKey") { type = NavType.StringType },
                    navArgument("namespace") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType },
                ),
            ) { entry ->
                ObjectDetailScreen(
                    app = app,
                    navController = navController,
                    resourceKey = entry.stringArg("resourceKey"),
                    namespace = entry.stringArg("namespace"),
                    name = entry.stringArg("name"),
                )
            }

            composable(
                route = Routes.LOGS + "?container={container}&kind={kind}",
                arguments = listOf(
                    navArgument("namespace") { type = NavType.StringType },
                    navArgument("pod") { type = NavType.StringType },
                    navArgument("container") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("kind") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                LogsScreen(
                    app = app,
                    navController = navController,
                    namespace = entry.stringArg("namespace"),
                    pod = entry.stringArg("pod"),
                    initialContainer = entry.arguments?.getString("container"),
                    workloadKind = entry.arguments?.getString("kind"),
                )
            }

            composable(
                route = Routes.TERMINAL + "?container={container}",
                arguments = listOf(
                    navArgument("namespace") { type = NavType.StringType },
                    navArgument("pod") { type = NavType.StringType },
                    navArgument("container") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                TerminalScreen(
                    app = app,
                    navController = navController,
                    namespace = entry.stringArg("namespace"),
                    pod = entry.stringArg("pod"),
                    initialContainer = entry.arguments?.getString("container"),
                )
            }

            composable(
                route = Routes.PORT_FORWARD,
                arguments = listOf(
                    navArgument("namespace") { type = NavType.StringType },
                    navArgument("pod") { type = NavType.StringType },
                ),
            ) { entry ->
                PortForwardScreen(
                    app = app,
                    navController = navController,
                    namespace = entry.stringArg("namespace"),
                    pod = entry.stringArg("pod"),
                )
            }

            composable(
                route = Routes.GITOPS,
                arguments = listOf(
                    navArgument(Routes.GITOPS_TAB_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                GitOpsScreen(
                    app = app,
                    navController = navController,
                    initialTab = GitOpsTab.fromSlug(entry.arguments?.getString(Routes.GITOPS_TAB_ARG)),
                )
            }

            composable(
                route = Routes.HELM_RELEASE,
                arguments = listOf(
                    navArgument("namespace") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType },
                ),
            ) { entry ->
                HelmDetailScreen(
                    app = app,
                    navController = navController,
                    namespace = entry.stringArg("namespace"),
                    name = entry.stringArg("name"),
                )
            }

            composable(Routes.EVENTS) {
                EventsScreen(app, navController)
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(app, navController)
            }
        }
    }

}

/**
 * The GitOps destination: one app bar, the shared tab strip, and the three dashboards as bodies.
 * Tabs live in the bar so the current dashboard is always legible and switching is one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitOpsScreen(
    app: AppViewModel,
    navController: NavController,
    initialTab: GitOpsTab,
) {
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    val tabs: @Composable () -> Unit = {
        TabStrip(
            tabs = GitOpsTab.entries.map { stringResource(it.labelRes) },
            selectedIndex = GitOpsTab.entries.indexOf(tab),
            onSelect = { tab = GitOpsTab.entries[it] },
            modifier = Modifier.padding(horizontal = Spacing.ScreenPadding),
        )
    }
    when (tab) {
        GitOpsTab.HELM -> HelmScreen(app, navController, header = tabs)
        GitOpsTab.FLUX -> FluxScreen(app, navController, header = tabs)
        GitOpsTab.ARGO -> ArgoScreen(app, navController, header = tabs)
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Navigation helpers                                                                            */
/* -------------------------------------------------------------------------------------------- */

/** Switches the bottom-bar destination without stacking duplicates. */
fun NavController.navigateToTop(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

fun NavBackStackEntry.stringArg(name: String): String =
    arguments?.getString(name).orEmpty()
