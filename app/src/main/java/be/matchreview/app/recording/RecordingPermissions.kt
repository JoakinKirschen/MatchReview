package be.matchreview.app.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Permissions needed to save match video, besides the camera itself. */
object RecordingPermissions {
    /**
     * Android 9 and older only let an app write to the shared Movies folder with the
     * storage permission; newer versions save through MediaStore without it.
     */
    val storage: List<String>
        get() = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else emptyList()

    fun canSaveVideo(context: Context): Boolean = storage.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
