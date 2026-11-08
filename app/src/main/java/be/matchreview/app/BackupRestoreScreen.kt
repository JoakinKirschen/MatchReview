package be.matchreview.app

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
    } ?: "Never backed up"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Encrypted backups to this phone, Google Drive or another storage app.")
        Text(
            "App ${BuildConfig.VERSION_NAME} • database ${AppDatabase.DATABASE_VERSION} • backup format ${be.matchreview.app.backup.MatchBackupManager.BACKUP_FORMAT_VERSION}",
            style = MaterialTheme.typography.bodySmall
        )

        AppCard(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Backup status", style = MaterialTheme.typography.titleMedium)
                Text(lastBackupLabel)
                if (lastBackupEpochMs != null) {
                    Text(
                        if (lastBackupIncludedMedia) "Last backup included videos" else "Last backup excluded videos",
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
                Text("Create backup", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = exportPassword,
                    onValueChange = { exportPassword = it },
                    label = { Text("Password (6+ characters)") },
                    visualTransformation = if (showExportPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showExportPassword = !showExportPassword }) {
                            Text(if (showExportPassword) "Hide" else "Show")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = exportPasswordAgain,
                    onValueChange = { exportPasswordAgain = it },
                    label = { Text("Repeat password") },
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
                    "The password cannot be recovered by MatchReview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeMedia,
                        onCheckedChange = { includeMedia = it }
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Include video files")
                        Text(
                            "Much larger; only with the club's consent.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    if (includeMedia) {
                        "Estimated backup: ${BackupEstimate.humanReadable(BackupEstimate.estimatedBytes(recordedVideoBytes, true))} " +
                            "including $recordingCount recorded clip(s)."
                    } else {
                        "Estimated backup: ${BackupEstimate.humanReadable(BackupEstimate.estimatedBytes(recordedVideoBytes, false))}; videos excluded."
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
                ) { Text("Choose backup location") }
                if (!passwordValid && (exportPassword.isNotEmpty() || exportPasswordAgain.isNotEmpty())) {
                    Text(
                        "Passwords must match and contain at least 6 characters.",
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
                Text("Automatic backup", style = MaterialTheme.typography.titleMedium)
                if (!autoBackup.enabled) {
                    Text(
                        "After every finished match, into a folder you pick once (for example on Google Drive). " +
                            "Uses the password above; the newest ${AutoBackupRules.KEEP} are kept.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    AppButton(
                        onClick = { autoBackupFolderLauncher.launch(null) },
                        enabled = passwordValid && !working,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Choose folder and turn on") }
                    if (!passwordValid) {
                        Text("Enter and repeat a password above first.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Folder: ${autoBackup.folderName ?: "chosen folder"}")
                    Text(
                        when {
                            autoBackup.running -> "Backing up…"
                            autoBackup.lastError != null -> "Last automatic backup failed: ${autoBackup.lastError}"
                            autoBackup.lastSuccessEpochMs != null -> "Last automatic backup " +
                                SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(autoBackup.lastSuccessEpochMs!!))
                            else -> "Runs after the next finished match."
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
                        Text("Include video files", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTonalButton(
                            onClick = vm::runAutoBackupNow,
                            enabled = !autoBackup.running,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text("Back up now") }
                        AppOutlinedButton(
                            onClick = vm::disableAutoBackup,
                            enabled = !autoBackup.running,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Turn off") }
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
                Text("Restore backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The selected backup is decrypted and inspected before you are asked to replace current data."
                )
                OutlinedTextField(
                    value = restorePassword,
                    onValueChange = { restorePassword = it },
                    label = { Text("Backup password") },
                    visualTransformation = if (showRestorePassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showRestorePassword = !showRestorePassword }) {
                            Text(if (showRestorePassword) "Hide" else "Show")
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
                ) { Text("Select and inspect backup") }
                Text(
                    "Restore is blocked while a match or recording is active.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (previewState is RestorePreviewState.Working) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Decrypting and validating backup…")
        }
        if (previewState is RestorePreviewState.Error) {
            val error = previewState as RestorePreviewState.Error
            AppCard(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Backup could not be inspected", style = MaterialTheme.typography.titleMedium)
                    Text(error.message)
                    TextButton(
                        onClick = {
                            selectedRestoreUri = null
                            vm.clearRestorePreview()
                        }
                    ) { Text("Dismiss") }
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
                            "${state.result.teams} teams, ${state.result.players} players, " +
                                "${state.result.matches} matches, ${state.result.mediaFiles} media files"
                        )
                        state.result.warnings.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = vm::clearBackupOperation) { Text("Dismiss") }
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
                        Text("Backup operation failed", style = MaterialTheme.typography.titleMedium)
                        Text(state.message)
                        TextButton(onClick = vm::clearBackupOperation) { Text("Dismiss") }
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
            title = { Text("Review backup before restore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Created: ${readyPreview.createdAtUtc}")
                    Text("Source app: ${readyPreview.appVersionName} (${readyPreview.appVersionCode})")
                    Text(
                        "Versions: database ${readyPreview.databaseVersion}, " +
                            "backup ${readyPreview.backupFormatVersion}"
                    )
                    Text(
                        "${readyPreview.teams} teams • ${readyPreview.players} players • " +
                            "${readyPreview.matches} matches"
                    )
                    Text(
                        if (readyPreview.includesMedia) {
                            "${readyPreview.mediaFiles} video files included"
                        } else {
                            "Videos are not included"
                        }
                    )
                    HorizontalDivider()
                    Text(
                        "Restoring replaces all current teams and match data. Cancel now and create " +
                            "a safety backup first if needed.",
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
                ) { Text("Restore and replace") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        selectedRestoreUri = null
                        vm.clearRestorePreview()
                    }
                ) { Text("Cancel") }
            }
        )
    }
}
