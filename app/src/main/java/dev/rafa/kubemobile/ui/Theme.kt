package dev.rafa.kubemobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* -------------------------------------------------------------------------------------------- */
/* Rinci design tokens                                                                           */
/* -------------------------------------------------------------------------------------------- */

/**
 * The palette is the Rinci landing system, held in one place: an off-black canvas, a single raised
 * surface, warm paper text, a two-step muted pair and one restrained mint signal. The app does not
 * opt into Android's dynamic colour: the brand cannot be overridden by the device wallpaper on
 * API 31+, which is exactly what the previous blue scheme allowed.
 */
private val Ink = Color(0xFF0A0B0A)
private val Ink2 = Color(0xFF101210)
private val Ink3 = Color(0xFF161916)
private val Paper = Color(0xFFF4F5F3)
private val Dim = Color(0xFF9AA09A)
private val Line = Color(0xFF1E211E)
private val Signal = Color(0xFF6FBF95)
private val SignalDim = Color(0xFF15241C)
private val SignalText = Color(0xFF9FD9B8)

/* Warm-paper companion so system light mode stays readable rather than flipping the brand off. */
private val PaperBg = Color(0xFFF4F5F3)
private val PaperRaised = Color(0xFFFBFAF6)
private val PaperLine = Color(0xFFD4D8D1)
private val InkText = Color(0xFF171B19)
private val DimText = Color(0xFF59625D)
private val SignalDeep = Color(0xFF2F6B4F)

private val RinciDark = darkColorScheme(
    primary = Signal,
    onPrimary = Ink,
    primaryContainer = SignalDim,
    onPrimaryContainer = SignalText,
    inversePrimary = SignalDeep,
    secondary = SignalText,
    onSecondary = Ink,
    secondaryContainer = SignalDim,
    onSecondaryContainer = SignalText,
    tertiary = Dim,
    onTertiary = Ink,
    tertiaryContainer = Line,
    onTertiaryContainer = Paper,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = Line,
    onSurfaceVariant = Dim,
    surfaceTint = Signal,
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    outline = Line,
    outlineVariant = Line,
    scrim = Color(0xCC000000),
    surfaceBright = Ink3,
    surfaceDim = Ink,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Ink2,
    surfaceContainer = Ink2,
    surfaceContainerHigh = Ink3,
    surfaceContainerHighest = Color(0xFF1B1E1A),
    error = Color(0xFFE5695F),
    onError = Ink,
    errorContainer = Color(0xFF2A1413),
    onErrorContainer = Color(0xFFF3B0AA),
)

private val RinciLight = lightColorScheme(
    primary = SignalDeep,
    onPrimary = Paper,
    primaryContainer = Color(0xFFD8EDE0),
    onPrimaryContainer = Color(0xFF10281B),
    secondary = Color(0xFF3F5750),
    onSecondary = Paper,
    secondaryContainer = Color(0xFFE2EFE7),
    onSecondaryContainer = SignalDeep,
    tertiary = Color(0xFF4A5350),
    onTertiary = Paper,
    tertiaryContainer = Color(0xFFE6E9E3),
    onTertiaryContainer = InkText,
    background = PaperBg,
    onBackground = InkText,
    surface = PaperRaised,
    onSurface = InkText,
    surfaceVariant = Color(0xFFE6E9E3),
    onSurfaceVariant = DimText,
    surfaceTint = SignalDeep,
    outline = PaperLine,
    outlineVariant = PaperLine,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = PaperRaised,
    surfaceContainer = Color(0xFFF0F2EE),
    surfaceContainerHigh = Color(0xFFEAEDE8),
    surfaceContainerHighest = Color(0xFFE4E7E2),
    error = Color(0xFFB42318),
    onError = Paper,
    errorContainer = Color(0xFFFDE7E4),
    onErrorContainer = Color(0xFF601410),
)

/**
 * Soft geometry, mirrored from the landing site's radius scale (`--radius-sm` 10px through
 * `--radius-2xl` 36px). Every Material surface that draws from `MaterialTheme.shapes` — cards,
 * dialogs, menus, text fields, sheets — picks its corner here rather than in twenty call sites.
 */
private val SoftShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.Small),
    small = RoundedCornerShape(Radius.Medium),
    medium = RoundedCornerShape(Radius.Large),
    large = RoundedCornerShape(Radius.ExtraLarge),
    extraLarge = RoundedCornerShape(Radius.Sheet),
)

/** Corner radii by role. Screens reach for these instead of literal dp values. */
object Radius {
    /** Chips inside dense rows, icon tiles, code blocks. */
    val Small = 10.dp

    /** Text fields, menus, small cards. */
    val Medium = 14.dp

    /** Cards, grouped lists, banners. */
    val Large = 20.dp

    /** Dialogs and hero cards. */
    val ExtraLarge = 28.dp

    /** Bottom sheets. */
    val Sheet = 32.dp
}

/** Shared shapes, so a card on one screen is the same card on every other screen. */
object KubeShapes {
    val Pill = RoundedCornerShape(percent = 50)
    val Tile = RoundedCornerShape(Radius.Small)
    val Field = RoundedCornerShape(Radius.Medium)
    val Card = RoundedCornerShape(Radius.Large)
    val Dialog = RoundedCornerShape(Radius.ExtraLarge)
}

/**
 * System sans and system monospace with explicit weight and tracking instead of a bundled face.
 * Geist ships as ~400 KB of variable WOFF2 on the web; re-shipping it as an Android TTF would add
 * roughly a fifth to a 2.2 MB APK for a difference that only shows at display sizes, so the type
 * echoes Geist through weight and letter-spacing and keeps the size budget intact. Monospace stays
 * the platform monospace, which is also what the terminal and YAML panes already assume.
 */
private val Sans = FontFamily.SansSerif

private val RinciTypography: Typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        titleSmall = base.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = Sans),
        bodyMedium = base.bodyMedium.copy(fontFamily = Sans),
        bodySmall = base.bodySmall.copy(fontFamily = Sans),
        labelLarge = base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
        labelMedium = base.labelMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
        labelSmall = base.labelSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    )
}

@Composable
fun KubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) RinciDark else RinciLight,
        shapes = SoftShapes,
        typography = RinciTypography,
        content = content,
    )
}
