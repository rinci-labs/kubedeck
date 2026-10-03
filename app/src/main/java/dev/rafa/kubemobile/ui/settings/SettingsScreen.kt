package dev.rafa.kubemobile.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.config.ClusterProfile
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.SessionState
import dev.rafa.kubemobile.ui.components.BottomBarScreen
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.KeyValueRow
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.SectionCard
import dev.rafa.kubemobile.ui.navigateToTop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppViewModel, navController: NavController) {
    val sessionState by app.sessionState.collectAsStateWithLifecycle()
    val profiles by app.profiles.collectAsStateWithLifecycle()
    val namespaces by app.namespaces.collectAsStateWithLifecycle()
    var forgetOpen by remember { mutableStateOf(false) }

    val profile: ClusterProfile? = (sessionState as? SessionState.Ready)?.session?.profile
        ?: profiles.firstOrNull()
    val session = (sessionState as? SessionState.Ready)?.session

    BottomBarScreen(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                profile == null -> EmptyState(
                    title = stringResource(R.string.state_no_cluster_title),
                    body = stringResource(R.string.settings_no_cluster),
                    actionLabel = stringResource(R.string.state_go_to_clusters),
                    onAction = { navController.navigateToTop(Routes.CLUSTERS) },
                )

                sessionState is SessionState.Connecting -> LoadingState(
                    label = stringResource(R.string.state_connecting),
                )

                sessionState is SessionState.Failed && session == null -> ErrorState(
                    error = (sessionState as SessionState.Failed).error,
                    onRetry = { app.connectInBackground(profile) },
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = ListBottomPadding)) {
                    item {
                        SectionCard(title = stringResource(R.string.label_cluster_info)) {
                            KeyValueRow(stringResource(R.string.label_name), profile.name, copyable = true)
                            KeyValueRow(stringResource(R.string.label_server), profile.baseUrl, copyable = true)
                            KeyValueRow(stringResource(R.string.label_namespace), profile.displayNamespace)
                            KeyValueRow(stringResource(R.string.label_auth), profile.authKind.label)
                            KeyValueRow(
                                stringResource(R.string.label_context_name),
                                profile.contextName.orEmpty().ifBlank { "—" },
                            )
                            KeyValueRow(
                                stringResource(R.string.label_source),
                                if (profile.source == ClusterProfile.SOURCE_MANUAL) {
                                    stringResource(R.string.label_manual)
                                } else {
                                    ClusterProfile.SOURCE_IMPORT
                                },
                            )
                            KeyValueRow(
                                stringResource(R.string.label_tls),
                                if (profile.insecureSkipTlsVerify) {
                                    stringResource(R.string.settings_tls_insecure)
                                } else {
                                    stringResource(R.string.settings_tls_verified)
                                },
                            )
                            if (profile.tlsServerName != null) {
                                KeyValueRow("tls-server-name", profile.tlsServerName)
                            }
                            if (profile.proxyUrl != null) {
                                KeyValueRow("proxy-url", profile.proxyUrl)
                            }
                        }
                    }

                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.label_insecure_tls)) },
                            supportingContent = {
                                dev.rafa.kubemobile.ui.components.SecondaryText(
                                    stringResource(R.string.settings_persist_hint),
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = profile.insecureSkipTlsVerify,
                                    onCheckedChange = { value ->
                                        app.updateTlsVerification(profile, value)
                                    },
                                )
                            },
                        )
                    }

                    profile.execCommand?.let { command ->
                        item {
                            WarningCard(
                                text = stringResource(R.string.settings_exec_warning, command),
                            )
                        }
                    }
                    profile.authProvider?.let { provider ->
                        item {
                            WarningCard(
                                text = stringResource(R.string.settings_provider_warning, provider),
                            )
                        }
                    }

                    item {
                        SectionCard(title = stringResource(R.string.settings_diagnostics)) {
                            KeyValueRow(
                                stringResource(R.string.label_catalog),
                                stringResource(
                                    R.string.settings_api_groups,
                                    app.catalog.groups.size,
                                    app.catalog.resources.size,
                                ),
                            )
                            KeyValueRow(
                                stringResource(R.string.label_namespace),
                                stringResource(R.string.settings_namespaces, namespaces.size),
                            )
                        }
                    }

                    item {
                        Column(Modifier.padding(horizontal = Spacing.RowPadding, vertical = Spacing.ItemGap)) {
                            FilledTonalButton(
                                onClick = { app.reloadDiscovery() },
                                enabled = session != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Dns, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.action_reload_discovery))
                            }
                            Spacer(Modifier.size(8.dp))
                            OutlinedButton(
                                onClick = { forgetOpen = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.action_forget_cluster),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    item {
                        SectionCard(title = stringResource(R.string.label_about)) {
                            Row(Modifier.padding(horizontal = Spacing.CardPadding, vertical = Spacing.ItemGap)) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.size(12.dp))
                                Text(
                                    text = stringResource(R.string.settings_about_body),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (forgetOpen && profile != null) {
        AlertDialog(
            onDismissRequest = { forgetOpen = false },
            title = { Text(stringResource(R.string.settings_forget_title, profile.name)) },
            text = { Text(stringResource(R.string.settings_forget_body)) },
            confirmButton = {
                TextButton(onClick = {
                    forgetOpen = false
                    app.forget(profile) { name ->
                        app.notify(
                            app.getApplication<android.app.Application>()
                                .getString(R.string.settings_forgotten, name),
                        )
                    }
                }) {
                    Text(
                        text = stringResource(R.string.action_forget_cluster),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetOpen = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.RowVertical),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.padding(Spacing.CardPadding),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
