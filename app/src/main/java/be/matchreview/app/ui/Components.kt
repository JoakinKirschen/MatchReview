package be.matchreview.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One size and shape for every button in the app. */
object AppButtons {
    val Height = 48.dp
    /** Match-day actions that are tapped in a hurry on the sideline. */
    val LargeHeight = 56.dp
    val Shape = RoundedCornerShape(12.dp)
    val Padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
}

/** The main action of a screen or row. */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = AppButtons.Padding,
    content: @Composable RowScope.() -> Unit
) = Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = AppButtons.Height),
    enabled = enabled,
    shape = AppButtons.Shape,
    colors = colors,
    contentPadding = contentPadding,
    content = content
)

/** A secondary action next to a main one. */
@Composable
fun AppTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    contentPadding: PaddingValues = AppButtons.Padding,
    content: @Composable RowScope.() -> Unit
) = FilledTonalButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = AppButtons.Height),
    enabled = enabled,
    shape = AppButtons.Shape,
    colors = colors,
    contentPadding = contentPadding,
    content = content
)

/** A less important or reversible action. */
@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    contentPadding: PaddingValues = AppButtons.Padding,
    content: @Composable RowScope.() -> Unit
) = OutlinedButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = AppButtons.Height),
    enabled = enabled,
    shape = AppButtons.Shape,
    colors = colors,
    contentPadding = contentPadding,
    content = content
)

/** Cards are white (the lightest surface) on the light grey background, in every theme. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    ),
    content: @Composable ColumnScope.() -> Unit
) = Card(
    modifier = modifier,
    shape = MaterialTheme.shapes.medium,
    colors = colors,
    content = content
)

/**
 * True when the screen is wider than it is tall, such as a phone held sideways. Screens
 * then place their parts side by side instead of stacking them into thin strips.
 */
@Composable
fun isLandscape(): Boolean {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return configuration.screenWidthDp > configuration.screenHeightDp
}
