package dev.rafa.kubemobile.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.rafa.kubemobile.BuildConfig
import dev.rafa.kubemobile.ui.components.SecondaryText
import dev.rafa.kubemobile.update.GithubRelease
import dev.rafa.kubemobile.update.InstallResult
import dev.rafa.kubemobile.update.ReleaseAsset
import dev.rafa.kubemobile.update.UpdateResult
import kotlinx.coroutines.launch
import java.io.File
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
import dev.rafa.kubemobile.ui.components.ListDivider
import dev.rafa.kubemobile.ui.components.InfoChip
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.config.ClusterProfile
import dev.rafa.kubemobile.ops.ResourceHealth
import dev.rafa.kubemobile.ui.KubeShapes
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.cardGutter
import dev.rafa.kubemobile.ui.ListBottomPadding
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.Routes
import dev.rafa.kubemobile.ui.SessionState
import dev.rafa.kubemobile.ui.components.BottomBarScreen
import dev.rafa.kubemobile.ui.components.EmptyState
import dev.rafa.kubemobile.ui.components.ErrorState
import dev.rafa.kubemobile.ui.components.IconTile
import dev.rafa.kubemobile.ui.components.KeyValueRow
import dev.rafa.kubemobile.ui.components.ListGroup
import dev.rafa.kubemobile.ui.components.LoadingState
import dev.rafa.kubemobile.ui.components.SectionCard
import dev.rafa.kubemobile.ui.navigateToTop

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(
        val release: GithubRelease,
        val apkAsset: ReleaseAsset,
        val sumsAsset: ReleaseAsset?,
    ) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class ReadyToInstall(val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppViewModel, navController: NavController) {
    val sessionState by app.sessionState.collectAsStateWithLifecycle()
    val profiles by app.profiles.collectAsStateWithLifecycle()
    val namespaces by app.namespaces.collectAsStateWithLifecycle()
    var forgetOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateState: UpdateState by remember { mutableStateOf(UpdateState.Idle) }
    var currentAvailable by remember { mutableStateOf<UpdateResult.Available?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val backgroundRelease by app.updateAvailable.collectAsStateWithLifecycle()
    LaunchedEffect(backgroundRelease) {
        val rel = backgroundRelease
        if (rel != null && updateState is UpdateState.Idle) {
            val apk = rel.assets.firstOrNull { it.name.endsWith(".apk") }
            if (apk != null) {
                val sums = rel.assets.firstOrNull { it.name.equals("SHA256SUMS.txt", ignoreCase = true) }
                val avail = UpdateResult.Available(rel, apk, sums)
                currentAvailable = avail
                updateState = UpdateState.Available(rel, apk, sums)
            }
        }
    }

    val checkUpdates: () -> Unit = {
        if (updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading) {
            updateState = UpdateState.Checking
            scope.launch {
                when (val result = app.updateManager.checkForUpdate()) {
                    is UpdateResult.UpToDate -> updateState = UpdateState.UpToDate
                    is UpdateResult.Available -> {
                        currentAvailable = result
                        updateState = UpdateState.Available(result.release, result.apkAsset, result.sumsAsset)
                        showUpdateDialog = true
                        app.setUpdateAvailable(result.release)
                    }
                    is UpdateResult.NetworkError -> updateState = UpdateState.Error(result.msg)
                    is UpdateResult.ParseError -> updateState = UpdateState.Error(result.msg)
                }
            }
        }
    }

    val profile: ClusterProfile? = (sessionState as? SessionState.Ready)?.session?.profile
        ?: profiles.firstOrNull()
    val session = (sessionState as? SessionState.Ready)?.session

    // List rows inside section cards sit on the card's own surface
    // instead of painting a different-coloured band across it.
    val transparentRow = ListItemDefaults.colors(containerColor = Color.Transparent)

    BottomBarScreen(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
            )
        },
    ) { padding ->
        // App-level sections (updates, about) never depend on a cluster, so the list always
        // renders; only the cluster block at the top changes with the session. Every section is a
        // rounded card one gutter in from the edges, stacked on an 8 dp rhythm.
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(top = Spacing.TopBarToContent, bottom = ListBottomPadding),
        ) {
            when {
                profile == null -> item {
                    ClusterStatusCard(
                        title = stringResource(R.string.state_no_cluster_title),
                        body = stringResource(R.string.settings_no_cluster),
                        tone = ResourceHealth.Tone.NEUTRAL,
                        actionLabel = stringResource(R.string.state_go_to_clusters),
                        onAction = { navController.navigateToTop(Routes.CLUSTERS) },
                    )
                }

                sessionState is SessionState.Connecting -> item {
                    ClusterStatusCard(
                        title = profile.name,
                        body = stringResource(R.string.state_connecting),
                        tone = ResourceHealth.Tone.PROGRESS,
                        loading = true,
                    )
                }

                sessionState is SessionState.Failed && session == null -> item {
                    val error = (sessionState as SessionState.Failed).error
                    ClusterStatusCard(
                        title = stringResource(error.titleRes),
                        body = error.message,
                        tone = ResourceHealth.Tone.BAD,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { app.connectInBackground(profile) },
                    )
                }

                else -> {
                    item {
                        SectionCard(
                            title = stringResource(R.string.label_cluster_info),
                            modifier = Modifier.cardGutter(),
                        ) {
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
                        ListGroup(modifier = Modifier.padding(vertical = Spacing.RowVertical)) {
                            ListItem(
                                colors = transparentRow,
                                leadingContent = {
                                    // The tile mirrors the switch: amber and unlocked while
                                    // certificate verification is off.
                                    if (profile.insecureSkipTlsVerify) {
                                        IconTile(Icons.Filled.LockOpen, tone = ResourceHealth.Tone.WARN)
                                    } else {
                                        IconTile(Icons.Filled.Lock, tone = ResourceHealth.Tone.OK)
                                    }
                                },
                                headlineContent = { Text(stringResource(R.string.label_insecure_tls)) },
                                supportingContent = {
                                    SecondaryText(stringResource(R.string.settings_persist_hint))
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
                        SectionCard(
                            title = stringResource(R.string.settings_diagnostics),
                            modifier = Modifier.cardGutter(),
                        ) {
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
                        // Primary action first; the destructive one is quieter and sits below it.
                        Column(Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.ItemGap)) {
                            FilledTonalButton(
                                onClick = { app.reloadDiscovery() },
                                enabled = session != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Dns, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.size(Spacing.ItemGap))
                                Text(stringResource(R.string.action_reload_discovery))
                            }
                            Spacer(Modifier.size(Spacing.ItemGap))
                            OutlinedButton(
                                onClick = { forgetOpen = true },
                                modifier = Modifier.fillMaxWidth(),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                                ),
                            ) {
                                Text(
                                    text = stringResource(R.string.action_forget_cluster),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            // One "App" card: identity and version on top with the update action beside them, then
            // the credential note as quiet fine print instead of a second card with a large icon.
            item {
                SectionCard(
                    title = stringResource(R.string.settings_app),
                    modifier = Modifier.cardGutter(),
                ) {
                    Row(
                        Modifier.padding(horizontal = Spacing.CardPadding, vertical = Spacing.ItemGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(Icons.Filled.Hexagon)
                        Spacer(Modifier.size(Spacing.ChipPadding))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.size(Spacing.ItemGap))
                                InfoChip("v${BuildConfig.VERSION_NAME}")
                            }
                            Spacer(Modifier.size(2.dp))
                            when (val s = updateState) {
                                is UpdateState.Idle -> SecondaryText(stringResource(R.string.settings_update_idle), maxLines = 1)
                                is UpdateState.Checking -> SecondaryText(stringResource(R.string.settings_update_checking), maxLines = 1)
                                is UpdateState.UpToDate -> SecondaryText(stringResource(R.string.settings_update_current), maxLines = 1)
                                is UpdateState.Available -> Text(
                                    text = stringResource(R.string.settings_update_available, s.release.tag_name),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                )
                                is UpdateState.Downloading -> SecondaryText(
                                    stringResource(R.string.settings_update_downloading, (s.progress * 100).toInt()),
                                    maxLines = 1,
                                )
                                is UpdateState.ReadyToInstall -> Text(
                                    text = stringResource(R.string.settings_update_ready),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                )
                                is UpdateState.Error -> Text(
                                    text = s.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.size(Spacing.ItemGap))
                        when (updateState) {
                            is UpdateState.Checking -> CircularProgressIndicator(
                                Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                            is UpdateState.Downloading -> CircularProgressIndicator(
                                progress = { (updateState as UpdateState.Downloading).progress },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                            is UpdateState.Available -> Button(onClick = { showUpdateDialog = true }) {
                                Text(stringResource(R.string.settings_update_action_view))
                            }
                            is UpdateState.ReadyToInstall -> Button(onClick = { showUpdateDialog = true }) {
                                Text(stringResource(R.string.settings_update_action_install))
                            }
                            else -> FilledTonalButton(onClick = checkUpdates) {
                                Text(stringResource(R.string.settings_update_action_check))
                            }
                        }
                    }
                    ListDivider(Modifier.padding(vertical = Spacing.ItemGap))
                    Row(
                        Modifier.padding(horizontal = Spacing.CardPadding, vertical = Spacing.TightGap),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(14.dp),
                        )
                        Spacer(Modifier.size(Spacing.ItemGap))
                        Text(
                            text = stringResource(R.string.settings_about_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

    if (showUpdateDialog && currentAvailable != null) {
        val avail = currentAvailable!!
        val apkAsset = avail.apkAsset
        val sumsAsset = avail.sumsAsset
        val release = avail.release
        AlertDialog(
            onDismissRequest = {
                if (updateState !is UpdateState.Downloading) showUpdateDialog = false
            },
            title = {
                Text(release.name?.takeIf { it.isNotBlank() } ?: release.tag_name)
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("Version: ${release.tag_name}", style = MaterialTheme.typography.titleSmall)
                    if (apkAsset.size > 0) {
                        Text(
                            "Size: ${"%.1f MB".format(apkAsset.size / (1024f * 1024f))}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (!release.body.isNullOrBlank()) {
                        Spacer(Modifier.size(Spacing.ItemGap))
                        Text("Release Notes:", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.size(Spacing.TightGap))
                        Text(release.body, style = MaterialTheme.typography.bodySmall)
                    }
                    when (val s = updateState) {
                        is UpdateState.Downloading -> {
                            Spacer(Modifier.size(Spacing.ItemGap))
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(KubeShapes.Pill),
                            )
                            Spacer(Modifier.size(Spacing.TightGap))
                            Text("Downloading: ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        is UpdateState.ReadyToInstall -> {
                            Spacer(Modifier.size(Spacing.ItemGap))
                            Text(
                                "Download complete and verified.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        is UpdateState.Error -> {
                            Spacer(Modifier.size(Spacing.ItemGap))
                            Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        else -> {}
                    }
                }
            },
            confirmButton = {
                when (val s = updateState) {
                    is UpdateState.ReadyToInstall -> FilledTonalButton(onClick = {
                        scope.launch {
                            val activity = context.findActivity()
                            if (activity != null) {
                                when (val res = app.updateManager.verifyAndInstall(s.file, sumsAsset, activity)) {
                                    is InstallResult.Launched -> showUpdateDialog = false
                                    is InstallResult.NeedsPermission -> {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse("package:${context.packageName}"),
                                                ),
                                            )
                                        }
                                    }
                                    is InstallResult.HashMismatch -> updateState = UpdateState.Error("Hash mismatch")
                                    is InstallResult.PackageMismatch -> updateState = UpdateState.Error("Package name mismatch")
                                    is InstallResult.CertMismatch -> updateState = UpdateState.Error("Signing certificate mismatch")
                                    is InstallResult.Error -> updateState = UpdateState.Error(res.msg)
                                }
                            } else {
                                updateState = UpdateState.Error("Activity context unavailable")
                            }
                        }
                    }) { Text("Install") }
                    is UpdateState.Downloading -> FilledTonalButton(onClick = {}, enabled = false) {
                        Text("Downloading…")
                    }
                    else -> FilledTonalButton(onClick = {
                        updateState = UpdateState.Downloading(0f)
                        scope.launch {
                            val file = app.updateManager.downloadUpdate(apkAsset) { p ->
                                updateState = UpdateState.Downloading(p)
                            }
                            if (file != null) {
                                val verifyError = app.updateManager.verifyApk(file, sumsAsset)
                                updateState = if (verifyError == null) {
                                    UpdateState.ReadyToInstall(file)
                                } else when (verifyError) {
                                    is InstallResult.HashMismatch -> UpdateState.Error("Hash mismatch: expected ${verifyError.expected.take(8)}…")
                                    is InstallResult.PackageMismatch -> UpdateState.Error("Package name mismatch")
                                    is InstallResult.CertMismatch -> UpdateState.Error("Signing certificate mismatch")
                                    is InstallResult.Error -> UpdateState.Error(verifyError.msg)
                                    else -> UpdateState.Error("Verification failed")
                                }
                            } else {
                                updateState = UpdateState.Error("Download failed")
                            }
                        }
                    }) { Text("Download") }
                }
            },
            dismissButton = {
                if (updateState !is UpdateState.Downloading) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .cardGutter(),
        shape = KubeShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
        ),
    ) {
        Row(
            Modifier.padding(Spacing.CardPadding),
            verticalAlignment = Alignment.Top,
        ) {
            IconTile(Icons.Filled.Warning, tone = ResourceHealth.Tone.BAD, size = 32.dp)
            Spacer(Modifier.size(Spacing.ChipPadding))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * The cluster block when there is no live session: what is going on, plus the one action that
 * moves it forward. Kept card-sized so the app sections below it stay reachable.
 */
@Composable
private fun ClusterStatusCard(
    title: String,
    body: String,
    tone: ResourceHealth.Tone,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    SectionCard(
        title = stringResource(R.string.label_cluster_info),
        modifier = Modifier.cardGutter(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.CardPadding, vertical = Spacing.ItemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                IconTile(Icons.Filled.Dns, tone = tone)
                if (loading) {
                    CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.size(Spacing.ChipPadding))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SecondaryText(body, maxLines = 3)
            }
        }
        if (actionLabel != null && onAction != null) {
            FilledTonalButton(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.CardPadding, vertical = Spacing.ItemGap),
            ) { Text(actionLabel) }
        }
    }
}
