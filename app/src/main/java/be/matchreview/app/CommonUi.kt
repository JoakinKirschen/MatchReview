package be.matchreview.app

import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppCard
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import be.matchreview.app.data.*
import java.util.*

@Composable
internal fun FormColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
internal fun Field(value: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth())
}

@Composable
internal fun EmptyCard(message: String) {
    AppCard(Modifier.fillMaxWidth()) { Text(message, Modifier.padding(16.dp)) }
}

internal fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

internal fun showClipsRemainingToast(context: android.content.Context, remaining: Int) {
    Toast.makeText(
        context,
        tr("{remaining} recorded clip(s) could not be deleted. Remove them from Movies/MatchReview in your gallery.", "remaining" to remaining),
        Toast.LENGTH_LONG
    ).show()
}
