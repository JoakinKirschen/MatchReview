package be.matchreview.app.recording

import be.matchreview.app.i18n.tr

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import be.matchreview.app.domain.HighlightClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

data class HighlightReel(val uri: Uri, val clips: Int, val skipped: Int, val durationMs: Long)

/**
 * Joins parts of the recorded clips into one MP4 without re-encoding, so it is quick and
 * keeps the original quality. Cuts start at the key frame before each window, so a clip
 * can begin up to a second or two early.
 */
object HighlightReelMaker {
    private const val GAP_US = 40_000L

    suspend fun make(
        context: Context,
        clips: List<HighlightClip>,
        fileName: String,
        onProgress: (Float) -> Unit = {}
    ): HighlightReel = withContext(Dispatchers.IO) {
        require(clips.isNotEmpty()) { tr("There is nothing recorded to put in this video.") }
        val reference = trackFormats(context, Uri.parse(clips.first().uri))
            ?: throw IllegalStateException(tr("The first clip could not be read."))
        // Clips from a recording with other video settings cannot be joined without re-encoding.
        val usable = clips.filter { clip ->
            trackFormats(context, Uri.parse(clip.uri))?.let { compatible(reference.video, it.video) } == true
        }
        if (usable.isEmpty()) throw IllegalStateException(tr("The clips could not be read."))
        val withAudio = reference.audio != null && usable.all { clip ->
            trackFormats(context, Uri.parse(clip.uri))?.audio?.let { compatible(reference.audio, it) } == true
        }

        val output = Output.create(context, fileName)
        var durationUs = 0L
        try {
            output.open(context).use { descriptor ->
                val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    if (reference.video.containsKey(MediaFormat.KEY_ROTATION)) {
                        muxer.setOrientationHint(reference.video.getInteger(MediaFormat.KEY_ROTATION))
                    }
                    val videoTrack = muxer.addTrack(reference.video)
                    val audioTrack = if (withAudio) muxer.addTrack(reference.audio!!) else -1
                    muxer.start()
                    val buffer = ByteBuffer.allocate(bufferSize(reference))
                    val info = MediaCodec.BufferInfo()
                    usable.forEachIndexed { index, clip ->
                        ensureActive()
                        durationUs += copyClip(context, clip, muxer, videoTrack, audioTrack, durationUs, buffer, info) + GAP_US
                        onProgress((index + 1f) / usable.size)
                    }
                    muxer.stop()
                } finally {
                    muxer.release()
                }
            }
            output.publish(context)
        } catch (error: Throwable) {
            output.discard(context)
            throw error
        }
        HighlightReel(output.uri, usable.size, clips.size - usable.size, durationUs / 1_000L)
    }

    /** Copies one window; returns how long it is in microseconds. */
    private fun copyClip(
        context: Context,
        clip: HighlightClip,
        muxer: MediaMuxer,
        videoTrack: Int,
        audioTrack: Int,
        offsetUs: Long,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ): Long {
        val uri = Uri.parse(clip.uri)
        val video = extractor(context, uri, "video/") ?: return 0L
        val audio = if (audioTrack >= 0) extractor(context, uri, "audio/") else null
        try {
            val endUs = clip.endMs * 1_000L
            video.seekTo(clip.startMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val firstUs = video.sampleTime.takeIf { it >= 0L } ?: return 0L
            audio?.seekTo(firstUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var lastUs = firstUs
            val sources = listOfNotNull(video to videoTrack, audio?.let { it to audioTrack })
            while (true) {
                // Write the earliest pending sample of either track, so the file stays interleaved.
                val (source, track) = sources
                    .filter { (extractor, _) -> extractor.sampleTime in 0..endUs }
                    .minByOrNull { (extractor, _) -> extractor.sampleTime }
                    ?: break
                val time = source.sampleTime
                val size = source.readSampleData(buffer, 0)
                if (size < 0) break
                if (time >= firstUs) {
                    info.set(0, size, time - firstUs + offsetUs, sampleFlags(source.sampleFlags))
                    muxer.writeSampleData(track, buffer, info)
                    lastUs = maxOf(lastUs, time)
                }
                source.advance()
            }
            return lastUs - firstUs
        } finally {
            video.release()
            audio?.release()
        }
    }

    private class Formats(val video: MediaFormat, val audio: MediaFormat?)

    private fun trackFormats(context: Context, uri: Uri): Formats? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.firstOrNull { it.mime().startsWith("video/") } ?: return@runCatching null
            Formats(video, formats.firstOrNull { it.mime().startsWith("audio/") })
        } finally {
            extractor.release()
        }
    }.getOrNull()

    private fun extractor(context: Context, uri: Uri, mimePrefix: String): MediaExtractor? {
        val extractor = MediaExtractor()
        return runCatching {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount)
                .firstOrNull { extractor.getTrackFormat(it).mime().startsWith(mimePrefix) }
                ?: error(tr("no {mimePrefix} track", "mimePrefix" to mimePrefix))
            extractor.selectTrack(track)
            extractor
        }.getOrElse {
            extractor.release()
            null
        }
    }

    private fun compatible(a: MediaFormat, b: MediaFormat): Boolean {
        fun int(format: MediaFormat, key: String) = if (format.containsKey(key)) format.getInteger(key) else null
        fun bytes(format: MediaFormat, key: String) = format.getByteBuffer(key)?.let { buffer ->
            ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        }
        return a.mime() == b.mime() &&
            int(a, MediaFormat.KEY_WIDTH) == int(b, MediaFormat.KEY_WIDTH) &&
            int(a, MediaFormat.KEY_HEIGHT) == int(b, MediaFormat.KEY_HEIGHT) &&
            int(a, MediaFormat.KEY_SAMPLE_RATE) == int(b, MediaFormat.KEY_SAMPLE_RATE) &&
            int(a, MediaFormat.KEY_CHANNEL_COUNT) == int(b, MediaFormat.KEY_CHANNEL_COUNT) &&
            bytes(a, "csd-0").contentEquals(bytes(b, "csd-0")) &&
            bytes(a, "csd-1").contentEquals(bytes(b, "csd-1"))
    }

    private fun bufferSize(formats: Formats): Int = listOfNotNull(formats.video, formats.audio)
        .mapNotNull { if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else null }
        .maxOrNull()
        ?.coerceAtLeast(1 shl 20)
        ?: (8 shl 20)

    @Suppress("WrongConstant")
    private fun sampleFlags(extractorFlags: Int): Int =
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

    private fun MediaFormat.mime(): String = getString(MediaFormat.KEY_MIME).orEmpty()

    /** Movies/MatchReview in the gallery on Android 10+, otherwise the app's share folder. */
    private class Output(val uri: Uri, private val file: File?) {
        fun open(context: Context): ParcelFileDescriptor = if (file != null) {
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            )
        } else {
            context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException(tr("The highlights video could not be created."))
        }

        fun publish(context: Context) {
            if (file == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(
                    uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null
                )
            }
        }

        fun discard(context: Context) {
            if (file != null) file.delete() else runCatching { context.contentResolver.delete(uri, null, null) }
        }

        companion object {
            fun create(context: Context, fileName: String): Output {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MatchReview")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
                    ) ?: throw IllegalStateException(tr("The highlights video could not be saved."))
                    return Output(uri, null)
                }
                val directory = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(directory, fileName).apply { delete(); createNewFile() }
                return Output(FileProvider.getUriForFile(context, "${context.packageName}.files", file), file)
            }
        }
    }
}
