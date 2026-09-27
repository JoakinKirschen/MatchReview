package be.matchreview.app

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deletes recorded clips from shared storage.
 *
 * Clips the app still owns are deleted directly. Clips it no longer owns (for
 * example after a reinstall) need the user's consent, which is requested with the
 * system dialog on Android 11+. [ClipDeleter.delete] reports how many clips remain
 * on the device so callers never claim a deletion that did not happen.
 */
class ClipDeleter internal constructor(
    private val start: (uris: List<String>, onFinished: (remaining: Int) -> Unit) -> Unit
) {
    fun delete(uris: List<String>, onFinished: (remaining: Int) -> Unit) = start(uris, onFinished)
}

@Composable
fun rememberClipDeleter(): ClipDeleter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<Int, (Int) -> Unit>?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val (failedCount, onFinished) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        onFinished(if (result.resultCode == Activity.RESULT_OK) 0 else failedCount)
    }
    return remember(context, scope, consentLauncher) {
        ClipDeleter { uris, onFinished ->
            scope.launch {
                val failed = withContext(Dispatchers.IO) {
                    deleteOwnedClips(context.contentResolver, uris)
                }
                val consent = if (failed.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { MediaStore.createDeleteRequest(context.contentResolver, failed) }.getOrNull()
                } else null
                if (consent == null) {
                    onFinished(failed.size)
                } else {
                    pending = failed.size to onFinished
                    consentLauncher.launch(IntentSenderRequest.Builder(consent.intentSender).build())
                }
            }
        }
    }
}

/** Returns the clips that could not be deleted because the app lacks permission. */
private fun deleteOwnedClips(resolver: ContentResolver, uris: List<String>): List<Uri> =
    uris.distinct().mapNotNull { value ->
        val uri = Uri.parse(value)
        try {
            resolver.delete(uri, null, null)
            null
        } catch (denied: SecurityException) {
            uri
        } catch (gone: Exception) {
            // Unknown or already removed media: nothing left to delete.
            null
        }
    }

/** Drops the long-lived read grant for an imported video; the video itself is kept. */
fun ContentResolver.releaseImportedVideo(uri: String) {
    runCatching {
        releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
