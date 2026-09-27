package be.matchreview.app.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.room.withTransaction
import be.matchreview.app.BuildConfig
import be.matchreview.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupResult(
    val teams: Int,
    val players: Int,
    val matches: Int,
    val mediaFiles: Int,
    val warnings: List<String> = emptyList()
)

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Creates a password-encrypted .mrbak package. The encrypted payload is a ZIP
 * containing versioned JSON plus optional media. No network service is used:
 * Android's document picker decides whether the destination is local, Drive,
 * OneDrive, or another installed document provider.
 */
class MatchBackupManager(
    private val context: Context,
    private val database: AppDatabase
) {
    suspend fun exportBackup(
        destination: Uri,
        password: CharArray,
        includeMedia: Boolean
    ): BackupResult = withContext(Dispatchers.IO) {
        validatePassword(password)
        val tempZip = File.createTempFile("matchreview-export-", ".zip", context.cacheDir)
        try {
            val databaseJson = database.withTransaction { exportDatabaseJson() }
            val warnings = mutableListOf<String>()
            var mediaCount = 0

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zip ->
                putText(zip, METADATA_ENTRY, JSONObject().apply {
                    put("backupFormatVersion", BACKUP_FORMAT_VERSION)
                    put("databaseVersion", AppDatabase.DATABASE_VERSION)
                    put("appVersionName", BuildConfig.VERSION_NAME)
                    put("appVersionCode", BuildConfig.VERSION_CODE)
                    put("createdAtUtc", Instant.now().toString())
                    put("includesMedia", includeMedia)
                }.toString(2))
                putText(zip, DATABASE_ENTRY, databaseJson.toString())

                val mediaManifest = JSONArray()
                if (includeMedia) {
                    referencedMedia(databaseJson).forEachIndexed { index, originalUri ->
                        val archivePath = "media/${index.toString().padStart(5, '0')}.bin"
                        try {
                            context.contentResolver.openInputStream(Uri.parse(originalUri))?.use { input ->
                                zip.putNextEntry(ZipEntry(archivePath))
                                input.copyTo(zip)
                                zip.closeEntry()
                                mediaManifest.put(JSONObject().apply {
                                    put("originalUri", originalUri)
                                    put("archivePath", archivePath)
                                })
                                mediaCount++
                            } ?: warnings.add("Could not open media: $originalUri")
                        } catch (error: Exception) {
                            runCatching { zip.closeEntry() }
                            warnings.add("Skipped unavailable media: $originalUri")
                        }
                    }
                }
                putText(zip, MEDIA_MANIFEST_ENTRY, mediaManifest.toString())
            }

            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                BackupCrypto.encrypt(tempZip, output, password)
            } ?: throw BackupException("The selected backup destination could not be opened.")

            counts(databaseJson, mediaCount, warnings)
        } finally {
            password.fill('\u0000')
            tempZip.delete()
        }
    }

    suspend fun restoreBackup(
        source: Uri,
        password: CharArray
    ): BackupResult = withContext(Dispatchers.IO) {
        validatePassword(password)
        ensureRestoreIsSafe()
        val tempZip = File.createTempFile("matchreview-restore-", ".zip", context.cacheDir)
        val restoredMediaDir = File(context.filesDir, "restored_media/${System.currentTimeMillis()}")
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                BackupCrypto.decrypt(input, tempZip, password)
            } ?: throw BackupException("The selected backup could not be opened.")

            ZipFile(tempZip).use { zip ->
                val metadata = zip.readJsonObject(METADATA_ENTRY)
                validateMetadata(metadata)
                val databaseJson = zip.readJsonObject(DATABASE_ENTRY)
                validateDatabaseJson(databaseJson)

                val remappedUris = restoreMedia(zip, restoredMediaDir)
                applyMediaUriRemapping(databaseJson, remappedUris)

                database.withTransaction {
                    replaceDatabase(databaseJson)
                }
                database.invalidationTracker.refreshVersionsAsync()

                counts(databaseJson, remappedUris.size, emptyList())
            }
        } catch (error: BackupException) {
            restoredMediaDir.deleteRecursively()
            throw error
        } catch (error: Exception) {
            restoredMediaDir.deleteRecursively()
            val message = when {
                error.causeChainContains("AEADBadTagException") ->
                    "The password is incorrect or the backup has been damaged."
                else -> "Restore failed: ${error.message ?: "invalid backup"}"
            }
            throw BackupException(message, error)
        } finally {
            password.fill('\u0000')
            tempZip.delete()
        }
    }

    private fun exportDatabaseJson(): JSONObject {
        val sqlite = database.openHelper.writableDatabase
        val tables = JSONObject()
        TABLES_IN_INSERT_ORDER.forEach { table ->
            val rows = JSONArray()
            sqlite.query("SELECT * FROM $table").use { cursor ->
                while (cursor.moveToNext()) rows.put(cursorRow(cursor))
            }
            tables.put(table, rows)
        }
        return JSONObject().apply {
            put("schemaVersion", AppDatabase.DATABASE_VERSION)
            put("tables", tables)
        }
    }

    private fun cursorRow(cursor: Cursor): JSONObject = JSONObject().apply {
        for (index in 0 until cursor.columnCount) {
            val value: Any = when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                Cursor.FIELD_TYPE_BLOB -> throw BackupException("Binary database columns are not supported.")
                else -> JSONObject.NULL
            }
            put(cursor.getColumnName(index), value)
        }
    }

    private fun replaceDatabase(databaseJson: JSONObject) {
        val sqlite = database.openHelper.writableDatabase
        TABLES_IN_DELETE_ORDER.forEach { sqlite.execSQL("DELETE FROM $it") }

        val tables = databaseJson.getJSONObject("tables")
        TABLES_IN_INSERT_ORDER.forEach { table ->
            val rows = tables.getJSONArray(table)
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val values = ContentValues()
                row.keys().forEach { column ->
                    when (val value = row.get(column)) {
                        JSONObject.NULL -> values.putNull(column)
                        is Boolean -> values.put(column, value)
                        is Int -> values.put(column, value)
                        is Long -> values.put(column, value)
                        is Double -> values.put(column, value)
                        is String -> values.put(column, value)
                        else -> throw BackupException("Unsupported value in $table.$column")
                    }
                }
                val inserted = sqlite.insert(table, 0, values)
                if (inserted == -1L) throw BackupException("Could not restore table $table.")
            }
        }
    }

    private fun validateMetadata(metadata: JSONObject) {
        val format = metadata.optInt("backupFormatVersion", -1)
        if (format != BACKUP_FORMAT_VERSION) {
            throw BackupException("Unsupported backup format version: $format.")
        }
        val databaseVersion = metadata.optInt("databaseVersion", -1)
        if (databaseVersion < 1 || databaseVersion > AppDatabase.DATABASE_VERSION) {
            throw BackupException(
                "This backup uses database version $databaseVersion; this app supports up to ${AppDatabase.DATABASE_VERSION}."
            )
        }
    }

    private fun validateDatabaseJson(databaseJson: JSONObject) {
        val tables = databaseJson.optJSONObject("tables")
            ?: throw BackupException("The backup has no database tables.")
        TABLES_IN_INSERT_ORDER.forEach { table ->
            if (!tables.has(table) || tables.optJSONArray(table) == null) {
                throw BackupException("The backup is missing table: $table.")
            }
        }
        val unknown = tables.keys().asSequence().filterNot { it in TABLES_IN_INSERT_ORDER }.toList()
        if (unknown.isNotEmpty()) throw BackupException("The backup contains unsupported tables.")
    }

    private fun ensureRestoreIsSafe() {
        val sqlite = database.openHelper.writableDatabase
        sqlite.query(
            "SELECT COUNT(*) FROM matches WHERE status IN ('LIVE','PAUSED','PERIOD_ENDED')"
        ).use {
            if (it.moveToFirst() && it.getLong(0) > 0) {
                throw BackupException("Finish or abandon the active match before restoring a backup.")
            }
        }
        sqlite.query(
            "SELECT COUNT(*) FROM recording_segments WHERE status IN ('PREPARING','RECORDING')"
        ).use {
            if (it.moveToFirst() && it.getLong(0) > 0) {
                throw BackupException("Stop the active recording before restoring a backup.")
            }
        }
    }

    private fun referencedMedia(databaseJson: JSONObject): List<String> {
        val result = linkedSetOf<String>()
        val tables = databaseJson.getJSONObject("tables")
        tables.getJSONArray("matches").forEachObject {
            if (!it.isNull("videoUri")) {
                it.optString("videoUri").takeIf(String::isNotBlank)?.let(result::add)
            }
        }
        tables.getJSONArray("recording_segments").forEachObject {
            if (!it.isNull("uri")) {
                it.optString("uri").takeIf(String::isNotBlank)?.let(result::add)
            }
        }
        return result.toList()
    }

    private fun restoreMedia(zip: ZipFile, outputDir: File): Map<String, String> {
        val manifestEntry = zip.getEntry(MEDIA_MANIFEST_ENTRY) ?: return emptyMap()
        val manifest = zip.getInputStream(manifestEntry).bufferedReader().use {
            JSONArray(it.readText())
        }
        if (manifest.length() == 0) return emptyMap()
        outputDir.mkdirs()
        val result = mutableMapOf<String, String>()
        for (index in 0 until manifest.length()) {
            val item = manifest.getJSONObject(index)
            val original = item.getString("originalUri")
            val archivePath = item.getString("archivePath")
            if (!archivePath.startsWith("media/") || ".." in archivePath) {
                throw BackupException("Unsafe media path in backup.")
            }
            val entry = zip.getEntry(archivePath)
                ?: throw BackupException("A media file listed in the backup is missing.")
            val target = File(outputDir, "${index.toString().padStart(5, '0')}.bin")
            zip.getInputStream(entry).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            result[original] = Uri.fromFile(target).toString()
        }
        return result
    }

    private fun applyMediaUriRemapping(databaseJson: JSONObject, mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        val tables = databaseJson.getJSONObject("tables")
        remapColumn(tables.getJSONArray("matches"), "videoUri", mapping)
        remapColumn(tables.getJSONArray("recording_segments"), "uri", mapping)
    }

    private fun remapColumn(rows: JSONArray, column: String, mapping: Map<String, String>) {
        rows.forEachObject { row ->
            if (!row.isNull(column)) {
                val original = row.optString(column)
                mapping[original]?.let { row.put(column, it) }
            }
        }
    }

    private fun counts(json: JSONObject, media: Int, warnings: List<String>): BackupResult {
        val tables = json.getJSONObject("tables")
        return BackupResult(
            teams = tables.getJSONArray("teams").length(),
            players = tables.getJSONArray("players").length(),
            matches = tables.getJSONArray("matches").length(),
            mediaFiles = media,
            warnings = warnings
        )
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun validatePassword(password: CharArray) {
        if (password.size < MIN_PASSWORD_LENGTH) {
            throw BackupException("Use a backup password of at least $MIN_PASSWORD_LENGTH characters.")
        }
    }

    companion object {
        const val BACKUP_FORMAT_VERSION = 1
        const val MIN_PASSWORD_LENGTH = 6
        private const val METADATA_ENTRY = "metadata.json"
        private const val DATABASE_ENTRY = "database.json"
        private const val MEDIA_MANIFEST_ENTRY = "media_manifest.json"

        val TABLES_IN_INSERT_ORDER = listOf(
            "teams",
            "players",
            "matches",
            "match_squad_players",
            "match_lineup_placements",
            "match_periods",
            "match_clock_segments",
            "player_participations",
            "recording_segments",
            "events"
        )
        val TABLES_IN_DELETE_ORDER = TABLES_IN_INSERT_ORDER.asReversed()
    }
}

internal object BackupCrypto {
    private val MAGIC = "MRBACKUP".toByteArray(Charsets.US_ASCII)
    private const val ENVELOPE_VERSION = 1
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val KEY_BITS = 256
    private const val ITERATIONS = 150_000

    fun encrypt(zipFile: File, output: java.io.OutputStream, password: CharArray) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv)

        val data = DataOutputStream(BufferedOutputStream(output))
        data.write(MAGIC)
        data.writeInt(ENVELOPE_VERSION)
        data.writeInt(salt.size)
        data.writeInt(iv.size)
        data.write(salt)
        data.write(iv)
        CipherOutputStream(data, cipher).use { encrypted ->
            FileInputStream(zipFile).use { it.copyTo(encrypted) }
        }
    }

    fun decrypt(input: java.io.InputStream, destination: File, password: CharArray) {
        val data = DataInputStream(BufferedInputStream(input))
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw BackupException("This is not a MatchReview backup.")
        val version = data.readInt()
        if (version != ENVELOPE_VERSION) throw BackupException("Unsupported encrypted backup version.")
        val saltSize = data.readInt()
        val ivSize = data.readInt()
        if (saltSize !in 8..64 || ivSize !in 12..32) throw BackupException("Invalid backup header.")
        val salt = ByteArray(saltSize).also { data.readFully(it) }
        val iv = ByteArray(ivSize).also { data.readFully(it) }
        val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, iv)
        CipherInputStream(data, cipher).use { decrypted ->
            FileOutputStream(destination).use { decrypted.copyTo(it) }
        }
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray): Cipher {
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, ITERATIONS, KEY_BITS))
            .encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        keyBytes.fill(0)
        return cipher
    }
}

private fun ZipFile.readJsonObject(name: String): JSONObject {
    val entry = getEntry(name) ?: throw BackupException("The backup is missing $name.")
    return getInputStream(entry).bufferedReader().use { JSONObject(it.readText()) }
}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (index in 0 until length()) block(getJSONObject(index))
}


private fun Throwable.causeChainContains(simpleName: String): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.javaClass.simpleName == simpleName) return true
        current = current.cause
    }
    return false
}
