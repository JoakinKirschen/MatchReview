package be.matchreview.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Naming and clean-up of the automatic backups in the chosen folder. */
object AutoBackupRules {
    const val PREFIX = "matchreview-auto-"
    const val EXTENSION = ".mrbak"
    const val KEEP = 5

    fun fileName(epochMs: Long): String =
        PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(epochMs)) + EXTENSION

    /**
     * Automatic backups beyond the newest [keep]; the timestamp in the name sorts in time order.
     * Files the coach saved by hand are never touched.
     */
    /** The newest automatic backup among [names], if any. */
    fun newest(names: List<String>): String? = names
        .filter { it.startsWith(PREFIX) && it.endsWith(EXTENSION) }
        .maxOrNull()

    /** Space the kept automatic backups can take when each one copies all recorded video. */
    fun keptBytes(oneBackupBytes: Long, keep: Int = KEEP): Long = oneBackupBytes.coerceAtLeast(0L) * keep

    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> = names
        .filter { it.startsWith(PREFIX) && it.endsWith(EXTENSION) }
        .sortedDescending()
        .drop(keep.coerceAtLeast(1))
}

data class AutoBackupState(
    val folderUri: String? = null,
    val folderName: String? = null,
    val includeMedia: Boolean = false,
    val lastSuccessEpochMs: Long? = null,
    val lastError: String? = null,
    val running: Boolean = false
) {
    val enabled: Boolean get() = folderUri != null
}

/**
 * Backs up into a folder the coach picked once (local storage, Drive or another provider)
 * after every finished match. The backup password is kept encrypted with a key that never
 * leaves the device's keystore.
 */
class AutoBackupController(
    private val context: Context,
    private val backupManager: MatchBackupManager,
    private val scope: CoroutineScope
) {
    private val prefs = context.getSharedPreferences("matchreview_auto_backup", 0)
    private val statusPrefs = context.getSharedPreferences("matchreview_backup_status", 0)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AutoBackupState> = _state.asStateFlow()

    private fun load() = AutoBackupState(
        folderUri = prefs.getString(KEY_FOLDER, null),
        folderName = prefs.getString(KEY_FOLDER_NAME, null),
        includeMedia = prefs.getBoolean(KEY_MEDIA, false),
        lastSuccessEpochMs = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
        lastError = prefs.getString(KEY_LAST_ERROR, null)
    )

    /** Remembers the folder and password; [folder] comes from the folder picker. */
    fun enable(folder: Uri, password: String, includeMedia: Boolean): String? {
        if (password.length < MatchBackupManager.MIN_PASSWORD_LENGTH) {
            return "Use a backup password of at least ${MatchBackupManager.MIN_PASSWORD_LENGTH} characters."
        }
        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                folder,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit()
                .putString(KEY_FOLDER, folder.toString())
                .putString(KEY_FOLDER_NAME, folderName(folder))
                .putString(KEY_PASSWORD, encrypt(password))
                .putBoolean(KEY_MEDIA, includeMedia)
                .remove(KEY_LAST_ERROR)
                .apply()
            _state.value = load()
            null
        }.getOrElse { "Automatic backup could not be turned on: ${it.message ?: "unknown error"}" }
    }

    fun setIncludeMedia(includeMedia: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA, includeMedia).apply()
        _state.value = _state.value.copy(includeMedia = includeMedia)
    }

    fun disable() {
        prefs.getString(KEY_FOLDER, null)?.let { folder ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(folder),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit().clear().apply()
        _state.value = AutoBackupState()
    }

    /** Starts a backup in the background when automatic backups are on. */
    fun runInBackground() {
        if (!_state.value.enabled) return
        scope.launch { runNow() }
    }

    suspend fun runNow(): Boolean = mutex.withLock {
        val folder = prefs.getString(KEY_FOLDER, null) ?: return false
        _state.value = _state.value.copy(running = true)
        val includeMedia = prefs.getBoolean(KEY_MEDIA, false)
        val outcome = runCatching {
            val password = prefs.getString(KEY_PASSWORD, null)?.let(::decrypt)
                ?: throw BackupException("The saved backup password is missing. Turn automatic backup on again.")
            val now = System.currentTimeMillis()
            val target = withContext(Dispatchers.IO) {
                createDocument(Uri.parse(folder), AutoBackupRules.fileName(now))
            }
            try {
                backupManager.exportBackup(target, password, includeMedia)
            } catch (error: Throwable) {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                }
                throw error
            }
            withContext(Dispatchers.IO) { runCatching { deleteOldBackups(Uri.parse(folder)) } }
            now
        }
        outcome.fold(
            onSuccess = { completedAt ->
                prefs.edit().putLong(KEY_LAST_SUCCESS, completedAt).remove(KEY_LAST_ERROR).apply()
                // Counts as a backup for the reminder on the dashboard.
                statusPrefs.edit()
                    .putLong("last_success_epoch_ms", completedAt)
                    .putBoolean("last_success_included_media", includeMedia)
                    .apply()
            },
            onFailure = { error ->
                prefs.edit().putString(KEY_LAST_ERROR, error.message ?: "Automatic backup failed.").apply()
            }
        )
        _state.value = load()
        outcome.isSuccess
    }

    private fun createDocument(tree: Uri, name: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return try {
            DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)
        } catch (error: Exception) {
            null
        } ?: throw BackupException("The backup folder can no longer be written. Choose the folder again.")
    }

    /** The newest automatic backup in the chosen folder, to share it to another app. */
    suspend fun latestBackup(): Uri? = withContext(Dispatchers.IO) {
        val tree = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse) ?: return@withContext null
        runCatching {
            val documents = listDocuments(tree)
            AutoBackupRules.newest(documents.keys.toList())?.let { name ->
                DocumentsContract.buildDocumentUriUsingTree(tree, documents.getValue(name))
            }
        }.getOrNull()
    }

    private fun listDocuments(tree: Uri): Map<String, String> {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val documents = mutableMapOf<String, String>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                documents[name] = id
            }
        }
        return documents
    }

    private fun deleteOldBackups(tree: Uri) {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val documents = mutableMapOf<String, String>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                documents[name] = id
            }
        }
        AutoBackupRules.toDelete(documents.keys.toList()).forEach { name ->
            DocumentsContract.deleteDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, documents.getValue(name))
            )
        }
    }

    private fun folderName(tree: Uri): String? = runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(
            document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(password: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Null when the key is gone, for example after restoring the app on a new phone. */
    private fun decrypt(value: String): CharArray? = runCatching {
        val sealed = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, IV_BYTES)))
        }
        String(cipher.doFinal(sealed.copyOfRange(IV_BYTES, sealed.size)), Charsets.UTF_8).toCharArray()
    }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "matchreview_auto_backup"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_FOLDER = "folder_uri"
        const val KEY_FOLDER_NAME = "folder_name"
        const val KEY_PASSWORD = "password"
        const val KEY_MEDIA = "include_media"
        const val KEY_LAST_SUCCESS = "last_success_epoch_ms"
        const val KEY_LAST_ERROR = "last_error"
    }
}
