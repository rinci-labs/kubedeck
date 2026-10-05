package dev.rafa.kubemobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.ops.ResourceHealth
import dev.rafa.kubemobile.ui.Spacing
import dev.rafa.kubemobile.ui.UiError
import dev.rafa.kubemobile.ui.BottomBarScreenInsets
import dev.rafa.kubemobile.ui.copyToClipboard
import dev.rafa.kubemobile.ui.toneColors

/* -------------------------------------------------------------------------------------------- */
/* Screen shell                                                                                  */
/* -------------------------------------------------------------------------------------------- */

/**
 * Shell for the four bottom-bar destinations. The root `Scaffold` deliberately contributes no
 * system-bar insets (see `KubeApp`), so this is the single place the status bar and the horizontal
 * cutout are consumed for those screens. Owns no bottom edge: the root already reserved the bar.
 */
@Composable
fun BottomBarScreen(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        contentWindowInsets = BottomBarScreenInsets,
        content = content,
    )
}

/* -------------------------------------------------------------------------------------------- */
/* Health chip                                                                                   */
/* -------------------------------------------------------------------------------------------- */

/**
 * The one status affordance used by every list row and detail header. A determinate ring is shown
 * for `PROGRESS` so a rolling update reads as "in flight" rather than "broken".
 */
@Composable
fun HealthChip(
    health: ResourceHealth,
    modifier: Modifier = Modifier,
    overallProgress: Float? = null,
) {
    val colors = toneColors(health.tone)
    val progress = health.progress ?: overallProgress
    Surface(
        modifier = modifier,
        shape = RectangleShape,
        color = colors.container,
        contentColor = colors.content,
        border = BorderStroke(1.dp, colors.content.copy(alpha = 0.35f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.TightGap),
            modifier = Modifier.padding(horizontal = Spacing.ChipPadding, vertical = Spacing.TightGap),
        ) {
            when {
                // A measurable ratio is the most informative: it shows how much is left to roll.
                progress != null && progress < 1f -> CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = colors.content,
                    trackColor = colors.content.copy(alpha = 0.25f),
                )

                // In flight but unmeasurable (a Pending pod, a reconciling CR): always animate, so
                // a partially rolled-out workload can never be mistaken for a settled one.
                health.tone == ResourceHealth.Tone.PROGRESS -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = colors.content,
                )

                else -> ToneDot(health.tone)
            }
            Text(
                text = health.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ToneDot(tone: ResourceHealth.Tone, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(toneColors(tone).content, RectangleShape),
    )
}

/** Inline rollout progress bar, drawn only when there is something to show. */

/* -------------------------------------------------------------------------------------------- */
/* States                                                                                        */
/* -------------------------------------------------------------------------------------------- */

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    // Deliberately not scrollable: callers place these inside LazyColumn items, where a nested
    // verticalScroll would be measured against an infinite height and throw. Centre and wrap
    // instead, so any parent (list item, Box, weighted column) gets a sane height.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.EmptyStatePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.invoke()
        if (icon != null) Spacer(Modifier.size(Spacing.ItemGap))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(Spacing.ItemGap))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if ((actionLabel != null && onAction != null) ||
            (secondaryActionLabel != null && onSecondaryAction != null)
        ) {
            Spacer(Modifier.size(Spacing.ContentInset))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.ItemGap)) {
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(shape = RectangleShape, onClick = onSecondaryAction) { Text(secondaryActionLabel) }
                }
                if (actionLabel != null && onAction != null) {
                    Button(shape = RectangleShape, onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

/** Error surface that always shows the API server's own message plus a retry affordance. */
@Composable
fun ErrorState(
    error: UiError,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.EmptyStatePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(error.titleRes),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(Spacing.ItemGap))
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.size(Spacing.ContentInset))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/**
 * Inline failure for one section of a dashboard: the API server's own message plus a retry, sized
 * for a list row rather than a whole screen. Used where a failure must not be mistaken for "empty".
 */
@Composable
fun SectionErrorCard(
    error: UiError,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Full-bleed like SectionCard, so the inner gutter is the only inset and the text lines up
    // with every other row at 16 dp rather than at 16 + a nested card inset.
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.RowVertical),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(Spacing.CardPadding)) {
            Text(
                text = stringResource(error.titleRes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(Spacing.TightGap))
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (onRetry != null) {
                Spacer(Modifier.size(Spacing.TightGap))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.EmptyStatePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        if (label != null) {
            Spacer(Modifier.size(Spacing.ContentInset))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A non-interactive pill for read-only facts (auth kind, TLS state, protocol). Chips would add a
 * click target that does nothing, which is worse for screen readers than static text.
 */
@Composable
fun InfoChip(
    label: String,
    modifier: Modifier = Modifier,
    tone: ResourceHealth.Tone = ResourceHealth.Tone.NEUTRAL,
) {
    val colors = toneColors(tone)
    Surface(
        modifier = modifier,
        shape = RectangleShape,
        color = colors.container,
        contentColor = colors.content,
        border = BorderStroke(1.dp, colors.content.copy(alpha = 0.35f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.ItemGap, vertical = Spacing.TightGap),
        )
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Structure                                                                                     */
/* -------------------------------------------------------------------------------------------- */

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(vertical = Spacing.ItemGap)) {
            if (title != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardPadding, vertical = Spacing.RowVertical),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    copyable: Boolean = false,
    monospace: Boolean = false,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardPadding, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(124.dp),
        )
        Text(
            text = value,
            style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodySmall
            },
            modifier = Modifier.weight(1f),
        )
        if (copyable) {
            IconButton(
                onClick = { context.copyToClipboard(label, value) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy_named, label),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The one tab strip: every multi-section surface in the app uses it — object detail, Helm release
 * and GitOps all render through here, so their tab rows are identical by construction rather than
 * by two implementations happening to agree.
 *
 * The strip chooses its own layout from the space available:
 *
 * - When every label fits, tabs are laid out by [PrimaryTabRow], which divides the width evenly and
 *   gives a wide, flat strip.
 * - When they do not — object detail can carry eight tabs — [PrimaryScrollableTabRow] takes over and
 *   scrolls the selection back into view.
 *
 * Both halves are the same Material 3 primitive, so the tab height, indicator, label typography and
 * content colours are the same in either mode. Fit is decided by measuring the real labels at the
 * current font scale, so a large accessibility font switches to the scrolling layout instead of
 * clipping text.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TabStrip(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val selected = selectedIndex.coerceIn(0, tabs.lastIndex)
    val textStyle = MaterialTheme.typography.titleSmall

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val availablePx = with(density) { maxWidth.toPx() }
        // Material's own tab places 16 dp of padding either side of the label; the row keeps a
        // chip-sized gutter at each end. Both come from the shared scale.
        val labelPx = with(density) { (Spacing.RowPadding * 2).toPx() }
        val fitsEvenly = remember(tabs, availablePx, textStyle, labelPx) {
            var needed = 0f
            for (text in tabs) {
                needed += measurer.measure(AnnotatedString(text), textStyle).size.width + labelPx
            }
            needed <= availablePx
        }

        val label: @Composable (String) -> Unit = { text ->
            Text(
                text = text,
                maxLines = 1,
                softWrap = false,
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (fitsEvenly) {
            PrimaryTabRow(
                selectedTabIndex = selected,
                modifier = Modifier.fillMaxWidth(),
                containerColor = Color.Transparent,
                divider = {},
            ) {
                tabs.forEachIndexed { index, text ->
                    Tab(
                        selected = index == selected,
                        onClick = { onSelect(index) },
                        text = { label(text) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            PrimaryScrollableTabRow(
                selectedTabIndex = selected,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = Spacing.ChipPadding,
                containerColor = Color.Transparent,
                divider = {},
            ) {
                tabs.forEachIndexed { index, text ->
                    Tab(
                        selected = index == selected,
                        onClick = { onSelect(index) },
                        text = { label(text) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Inputs                                                                                        */
/* -------------------------------------------------------------------------------------------- */

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(placeholder ?: stringResource(R.string.action_search), maxLines = 1)
        },
        leadingIcon = leadingIcon ?: {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
            )
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_clear),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
fun TextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    minLines: Int = 1,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        placeholder = placeholder?.let { { Text(it) } },
        minLines = if (singleLine) 1 else minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}


/* -------------------------------------------------------------------------------------------- */
/* Menus and dialogs                                                                             */
/* -------------------------------------------------------------------------------------------- */

data class MenuAction(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
)

/** The standard three-dot overflow used by app bars and list rows. */
@Composable
fun OverflowMenu(
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.MoreVert,
) {
    if (actions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription ?: stringResource(R.string.action_more),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.label,
                            color = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color.Unspecified
                            },
                        )
                    },
                    enabled = action.enabled,
                    leadingIcon = action.leadingIcon?.let { vector ->
                        {
                            Icon(
                                imageVector = vector,
                                contentDescription = null,
                                tint = if (action.destructive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

/**
 * Destructive confirmation. High-risk kinds require the user to type the object's name, matching
 * the `kubectl delete` muscle memory for Secrets, PVCs and Namespaces.
 */
@Composable
fun ConfirmDeleteDialog(
    name: String,
    requireTyping: Boolean,
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember(name) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body)
                if (requireTyping) {
                    Spacer(Modifier.size(Spacing.ChipPadding))
                    Text(
                        text = stringResource(R.string.list_delete_body_confirm, name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(Spacing.ItemGap))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text(name) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !requireTyping || typed.trim() == name,
            ) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}


/* -------------------------------------------------------------------------------------------- */
/* Text primitives                                                                               */
/* -------------------------------------------------------------------------------------------- */

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            lineHeight = fontSize * 1.35f,
        ),
        color = color,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // Clamps line count rather than height, so the row grows with the system font scale
        // instead of slicing glyphs.
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(
            start = Spacing.ScreenPadding,
            end = Spacing.ScreenPadding,
            top = Spacing.ContentInset,
            bottom = Spacing.ItemGap,
        ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The one divider between list rows. It keeps the screen gutter on **both** sides, so the rule
 * starts at the row text and stops at the matching inset rather than running to the screen edge.
 * Every list uses this instead of a bare `HorizontalDivider`, which is what previously left the
 * right edge bleeding (a bare divider padded only on the start side).
 */
@Composable
fun ListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(
            start = Spacing.DividerPadding,
            end = Spacing.DividerPadding,
        ),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}


/** Monospace, non-selectable-by-accident row used for byte counters and short values. */


/* -------------------------------------------------------------------------------------------- */
/* Connection gate                                                                               */
/* -------------------------------------------------------------------------------------------- */

/**
 * The one place a screen explains why it has no data: connecting, a failed handshake (with retry),
 * or no cluster configured at all. Every dashboard uses it so the wording never drifts.
 */
@Composable
fun ConnectionGate(
    state: dev.rafa.kubemobile.ui.SessionState,
    onRetry: (dev.rafa.kubemobile.config.ClusterProfile) -> Unit,
    onGoToClusters: () -> Unit,
) {
    when (state) {
        is dev.rafa.kubemobile.ui.SessionState.Connecting -> LoadingState(
            label = stringResource(R.string.state_connecting),
        )

        is dev.rafa.kubemobile.ui.SessionState.Failed -> ErrorState(
            error = state.error,
            onRetry = state.profile?.let { profile -> { onRetry(profile) } },
        )

        else -> EmptyState(
            title = stringResource(R.string.state_no_cluster_title),
            body = stringResource(R.string.state_no_cluster_body),
            actionLabel = stringResource(R.string.state_go_to_clusters),
            onAction = onGoToClusters,
        )
    }
}
