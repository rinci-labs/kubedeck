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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import dev.rafa.kubemobile.ui.KubeShapes
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
import dev.rafa.kubemobile.ui.cardGutter
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
        shape = KubeShapes.Pill,
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.TightGap),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
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
            .background(toneColors(tone).content, CircleShape),
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
        if (icon != null) Spacer(Modifier.size(Spacing.ContentInset))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(Spacing.ItemGap))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EmptyStateMaxWidth),
        )
        if ((actionLabel != null && onAction != null) ||
            (secondaryActionLabel != null && onSecondaryAction != null)
        ) {
            Spacer(Modifier.size(Spacing.SectionGap))
            // Stacked, full width, primary first: two labels side by side wrap mid-word on a
            // phone, and a stack keeps both targets large and the main path obvious.
            Column(
                modifier = Modifier.widthIn(max = EmptyStateMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
            ) {
                if (actionLabel != null && onAction != null) {
                    Button(
                        onClick = onAction,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(actionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(secondaryActionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** Keeps empty-state copy and buttons at a readable measure on wide screens and tablets. */
private val EmptyStateMaxWidth = 360.dp

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
        IconTile(
            icon = Icons.Outlined.ErrorOutline,
            tone = ResourceHealth.Tone.BAD,
            size = 48.dp,
        )
        Spacer(Modifier.size(Spacing.ContentInset))
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
            FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
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
    // Like SectionCard it owns no horizontal gutter: callers place it inside their own 16 dp inset
    // so it lines up with the cards around it.
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.RowVertical),
        shape = KubeShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
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
        shape = KubeShapes.Pill,
        color = colors.container,
        contentColor = colors.content,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = Spacing.TightGap),
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
        shape = KubeShapes.Card,
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
 * and GitOps all render through here, so their tab rows are identical by construction.
 *
 * Tabs are pills, matching the landing site's segmented nav: the selected tab sits on the soft mint
 * wash, the rest are quiet outlined text. The row scrolls horizontally, so eight object-detail tabs
 * or a large accessibility font never clip a label.
 */
@Composable
fun TabStrip(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val selected = selectedIndex.coerceIn(0, tabs.lastIndex)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = Spacing.ItemGap),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, text ->
            val isSelected = index == selected
            Surface(
                shape = KubeShapes.Pill,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .clip(KubeShapes.Pill)
                    .clickable(role = Role.Tab) { onSelect(index) },
            ) {
                Text(
                    text = text,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Surfaces                                                                                      */
/* -------------------------------------------------------------------------------------------- */

/**
 * A rounded container for a run of list rows, like the site's `Card` with `divide-y`. Rows inside
 * keep their own 16 dp inset; the group itself sits one screen gutter in from the edges.
 */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenPadding),
        shape = KubeShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column { content() }
    }
}

/**
 * A single tappable rounded card for list rows that stand on their own (clusters, events, releases).
 * Keeps the screen gutter and an 8 dp rhythm between stacked cards.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val border = BorderStroke(
        1.dp,
        if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
    )
    val clickable = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            enabled = enabled,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else {
        Modifier
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .cardGutter()
            .clip(KubeShapes.Card)
            .then(clickable),
        shape = KubeShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = border,
    ) {
        content()
    }
}

/** A tinted rounded-square icon holder, the site's `size-9 rounded-sm bg-success-soft` tile. */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: ResourceHealth.Tone = ResourceHealth.Tone.PROGRESS,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val colors = toneColors(tone)
    Box(
        modifier = modifier
            .size(size)
            .background(colors.container, KubeShapes.Tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * An inline banner for a problem attached to one item: title, the server's own message, and an
 * optional action. Rounded and soft so it reads as part of the row, not a slab across the screen.
 */
@Composable
fun InlineBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: ResourceHealth.Tone = ResourceHealth.Tone.BAD,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = toneColors(tone)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = KubeShapes.Field,
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            modifier = Modifier.padding(
                start = Spacing.ChipPadding,
                top = Spacing.ChipPadding,
                bottom = Spacing.ChipPadding,
                end = Spacing.TightGap,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(2.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = colors.content) }
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
        shape = KubeShapes.Pill,
        colors = softFieldColors(),
    )
}

/** Quiet field chrome: a filled raised surface with a hairline, mint only while focused. */
@Composable
fun softFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
)

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
        shape = KubeShapes.Field,
        colors = softFieldColors(),
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
        text = text,
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
