package be.matchreview.app

import be.matchreview.app.i18n.tr

import be.matchreview.app.ui.AppCard
import be.matchreview.app.ui.AppButton
import be.matchreview.app.ui.AppOutlinedButton
import be.matchreview.app.ui.AppTonalButton
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import be.matchreview.app.data.*
import be.matchreview.app.domain.BackupEstimate
import be.matchreview.app.backup.AutoBackupRules
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*

@Composable
internal fun BackupRestoreScreen(vm: MainViewModel) {
    val operation by vm.backupOperation.collectAsStateWithLifecycle()
    val previewState by vm.restorePreview.collectAsStateWithLifecycle()
    val lastBackupEpochMs by vm.lastBackupEpochMs.collectAsStateWithLifecycle()
    val lastBackupIncludedMedia by vm.lastBackupIncludedMedia.collectAsStateWithLifecycle()
    val recordedVideoBytes by vm.totalRecordingBytes.collectAsStateWithLifecycle()
    val recordingCount by vm.recordingCount.collectAsStateWithLifecycle()

    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordAgain by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var includeMedia by remember { mutableStateOf(false) }
    var selectedRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showExportPassword by remember { mutableStateOf(false) }
    var showRestorePassword by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportBackup(uri, exportPassword, includeMedia)
    }
    val autoBackup by vm.autoBackupState.collectAsStateWithLifecycle()
    var autoBackupMessage by remember { mutableStateOf<String?>(null) }
    val autoBackupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { folder ->
        if (folder != null) {
            autoBackupMessage = vm.enableAutoBackup(folder, exportPassword, includeMedia)
            if (autoBackupMessage == null) {
                exportPassword = ""
                exportPasswordAgain = ""
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedRestoreUri = uri
            vm.inspectBackup(uri, restorePassword)
        }
    }

    LaunchedEffect(operation) {
        if (operation is BackupOperationState.Success) {
            exportPassword = ""
            exportPasswordAgain = ""
            restorePassword = ""
        }
    }

    val working = operation is BackupOperationState.Working ||
        previewState is RestorePreviewState.Working
    val passwordValid = exportPassword.length >= 6 && exportPassword == exportPasswordAgain
    val lastBackupLabel = lastBackupEpochMs?.let {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it))
    } ?: tr("Never backed up")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(tr("Encrypted backups to this phone, Google Drive or another storage app."))
        Text(
            tr("App {app} • database {database} • backup format {format}", "app" to BuildConfig.VERSION_NAME, "database" to AppDatabase.DATABASE_VERSION, "format" to be.matchreview.app.backup.MatchBackupManager.BACKUP_FORMAT_VERSION),
            style = MaterialTheme.typography.bodySmall
        )

        AppCard(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("Backup status"), style = MaterialTheme.typography.titleMedium)
                Text(lastBackupLabel)
                if (lastBackupEpochMs != null) {
                    Text(
                        if (lastBackupIncludedMedia) tr("Last backup included videos") else tr("Last backup excluded videos"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(tr("Create backup"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = exportPassword,
                    onValueChange = { exportPassword = it },
                    label = { Text(tr("Password (6+ characters)")) },
                    visualTransformation = if (showExportPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showExportPassword = !showExportPassword }) {
                            Text(if (showExportPassword) tr("Hide") else tr("Show"))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = exportPasswordAgain,
                    onValueChange = { exportPasswordAgain = it },
                    label = { Text(tr("Repeat password")) },
                    visualTransformation = if (showExportPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    singleLine = true,
                    isError = exportPasswordAgain.isNotEmpty() &&
                        exportPassword != exportPasswordAgain,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    tr("The password cannot be recovered by MatchReview."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeMedia,
                        onCheckedChange = { includeMedia = it }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(tr("Include video files"))
                        Text(
                            tr("Much larger; only with the club's consent."),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    if (includeMedia) {
                        tr("Estimated backup: {size} ", "size" to (BackupEstimate.humanReadable(BackupEstimate.estimatedBytes(recordedVideoBytes, true)))) +
                            tr("including {recordingCount} recorded clip(s).", "recordingCount" to recordingCount)
                    } else {
                        tr("Estimated backup: {size}; videos excluded.", "size" to (BackupEstimate.humanReadable(BackupEstimate.estimatedBytes(recordedVideoBytes, false))))
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                AppButton(
                    onClick = {
                        exportLauncher.launch(
                            "MatchReview-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())}.mrbak"
                        )
                    },
                    enabled = passwordValid && !working,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(tr("Choose backup location")) }
                if (!passwordValid && (exportPassword.isNotEmpty() || exportPasswordAgain.isNotEmpty())) {
                    Text(
                        tr("Passwords must match and contain at least 6 characters."),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(tr("Automatic backup"), style = MaterialTheme.typography.titleMedium)
                if (!autoBackup.enabled) {
                    Text(
                        tr("After every finished match, into a folder you pick once: on the phone, an SD card, or a ") +
                            tr("cloud app that offers folders there. Uses the password above; the newest ") +
                            tr("{count} are kept. Google Drive usually does not offer folders: use ", "count" to AutoBackupRules.KEEP) +
                            tr("\"Share latest backup\" afterwards to save a copy there."),
                        style = MaterialTheme.typography.bodySmall
                    )
                    AppButton(
                        onClick = { autoBackupFolderLauncher.launch(null) },
                        enabled = passwordValid && !working,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(tr("Choose folder and turn on")) }
                    if (!passwordValid) {
                        Text(tr("Enter and repeat a password above first."), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(tr("Folder: {folderName}", "folderName" to (autoBackup.folderName ?: tr("chosen folder"))))
                    Text(
                        when {
                            autoBackup.running -> tr("Backing up…")
                            autoBackup.lastError != null -> tr("Last automatic backup failed: {lastError}", "lastError" to autoBackup.lastError)
                            autoBackup.lastSuccessEpochMs != null -> tr("Last automatic backup ") +
                                SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(autoBackup.lastSuccessEpochMs!!))
                            else -> tr("Runs after the next finished match.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (autoBackup.lastError != null && !autoBackup.running) {
                            MaterialTheme.colorScheme.error
                        } else LocalContentColor.current
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = autoBackup.includeMedia,
                            onCheckedChange = vm::setAutoBackupIncludesMedia
                        )
                        Text(tr("Include video files"), Modifier.weight(1f))
                    }
                    if (autoBackup.includeMedia && recordedVideoBytes > 0L) {
                        val oneBackup = BackupEstimate.estimatedBytes(recordedVideoBytes, true)
                        Text(
                            tr("Each automatic backup copies all recorded video (about {oneBackup}). ", "oneBackup" to (BackupEstimate.humanReadable(oneBackup))) +
                                tr("Keeping {count} can take up to ", "count" to AutoBackupRules.KEEP) +
                                tr("{oneBackup} in the folder.", "oneBackup" to (BackupEstimate.humanReadable(AutoBackupRules.keptBytes(oneBackup)))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTonalButton(
                            onClick = vm::runAutoBackupNow,
                            enabled = !autoBackup.running,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text(tr("Back up now")) }
                        AppOutlinedButton(
                            onClick = vm::disableAutoBackup,
                            enabled = !autoBackup.running,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(tr("Turn off")) }
                    }
                    if (autoBackup.lastSuccessEpochMs != null) {
                        AppOutlinedButton(
                            onClick = {
                                scope.launch {
                                    val latest = vm.latestAutoBackup()
                                    if (latest == null) autoBackupMessage = tr("No automatic backup was found in the folder.")
                                    else ExternalApps.share(context, latest, "application/octet-stream", tr("MatchReview backup"))
                                }
                            },
                            enabled = !autoBackup.running,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text(tr("Share latest backup (Drive, e-mail…)")) }
                    }
                }
                autoBackupMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(tr("Restore backup"), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr("The selected backup is decrypted and inspected before you are asked to replace current data.")
                )
                OutlinedTextField(
                    value = restorePassword,
                    onValueChange = { restorePassword = it },
                    label = { Text(tr("Backup password")) },
                    visualTransformation = if (showRestorePassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showRestorePassword = !showRestorePassword }) {
                            Text(if (showRestorePassword) tr("Hide") else tr("Show"))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                AppButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    enabled = restorePassword.length >= 6 && !working,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(tr("Select and inspect backup")) }
                Text(
                    tr("Restore is blocked while a match or recording is active."),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (previewState is RestorePreviewState.Working) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(tr("Decrypting and validating backup…"))
        }
        if (previewState is RestorePreviewState.Error) {
            val error = previewState as RestorePreviewState.Error
            AppCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("Backup could not be inspected"), style = MaterialTheme.typography.titleMedium)
                    Text(error.message)
                    TextButton(
                        onClick = {
                            selectedRestoreUri = null
                            vm.clearRestorePreview()
                        }
                    ) { Text(tr("Dismiss")) }
                }
            }
        }

        when (val state = operation) {
            BackupOperationState.Idle -> Unit
            is BackupOperationState.Working -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.message)
            }
            is BackupOperationState.Success -> {
                AppCard(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(state.message, style = MaterialTheme.typography.titleMedium)
                        Text(
                            tr("{teams} teams, {players} players, ", "teams" to state.result.teams, "players" to state.result.players) +
                                tr("{matches} matches, {mediaFiles} media files", "matches" to state.result.matches, "mediaFiles" to state.result.mediaFiles)
                        )
                        state.result.warnings.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = vm::clearBackupOperation) { Text(tr("Dismiss")) }
                    }
                }
            }
            is BackupOperationState.Error -> {
                AppCard(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(tr("Backup operation failed"), style = MaterialTheme.typography.titleMedium)
                        Text(state.message)
                        TextButton(onClick = vm::clearBackupOperation) { Text(tr("Dismiss")) }
                    }
                }
            }
        }
    }

    val readyPreview = (previewState as? RestorePreviewState.Ready)?.preview
    if (readyPreview != null) {
        AlertDialog(
            onDismissRequest = {
                selectedRestoreUri = null
                vm.clearRestorePreview()
            },
            title = { Text(tr("Review backup before restore")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Created: {createdAtUtc}", "createdAtUtc" to readyPreview.createdAtUtc))
                    Text(tr("Source app: {appVersionName} ({appVersionCode})", "appVersionName" to readyPreview.appVersionName, "appVersionCode" to readyPreview.appVersionCode))
                    Text(
                        tr("Versions: database {databaseVersion}, ", "databaseVersion" to readyPreview.databaseVersion) +
                            tr("backup {backupFormatVersion}", "backupFormatVersion" to readyPreview.backupFormatVersion)
                    )
                    Text(
                        tr("{teams} teams • {players} players • ", "teams" to readyPreview.teams, "players" to readyPreview.players) +
                            tr("{matches} matches", "matches" to readyPreview.matches)
                    )
                    Text(
                        if (readyPreview.includesMedia) {
                            tr("{mediaFiles} video files included", "mediaFiles" to readyPreview.mediaFiles)
                        } else {
                            tr("Videos are not included")
                        }
                    )
                    HorizontalDivider()
                    Text(
                        tr("Restoring replaces all current teams and match data. Cancel now and create ") +
                            tr("a safety backup first if needed."),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                AppButton(
                    onClick = {
                        val uri = selectedRestoreUri
                        selectedRestoreUri = null
                        if (uri != null) vm.restoreBackup(uri, restorePassword)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(tr("Restore and replace")) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        selectedRestoreUri = null
                        vm.clearRestorePreview()
                    }
                ) { Text(tr("Cancel")) }
            }
        )
    }
}
